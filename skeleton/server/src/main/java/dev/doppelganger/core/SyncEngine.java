package dev.doppelganger.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The server-side merge engine. It owns the append-only operation log and decides, once, what
 * each pushed operation means (three-way compare, then the conflict judge). The decision is
 * written into the log, so replaying the log never needs the judge again.
 *
 * <p>Pure Java with no framework or database dependency: the Spring server and the test
 * simulator both run this same class.
 */
public final class SyncEngine {

    /** Device id used for operations the server writes itself (resolutions, restores). */
    public static final String SERVER = "server";

    private final DomainSpec spec;
    private final List<LogEntry> log = new ArrayList<>();
    private final Set<String> seenOpIds = new HashSet<>();
    private final Replica state = new Replica();
    private final Map<RecordKey, List<LogEntry>> entriesByRecord = new HashMap<>();
    private final List<Resolution> resolutions = new ArrayList<>();

    public SyncEngine(DomainSpec spec) {
        this.spec = spec;
    }

    /** Rebuilds an engine from a stored log without re-judging anything. */
    public static SyncEngine restore(DomainSpec spec, List<LogEntry> entries) {
        SyncEngine engine = new SyncEngine(spec);
        for (LogEntry entry : entries) {
            if (entry.position() != engine.log.size() + 1) {
                throw new IllegalStateException("Operation log has a gap at position " + entry.position());
            }
            engine.record(entry);
        }
        return engine;
    }

    // ---------------------------------------------------------------- public API

    /**
     * Accepts a batch of device operations in the order the device made them. Operations the
     * server has already seen are acknowledged again but not re-applied, so resending is safe.
     *
     * @throws IllegalArgumentException if any operation is malformed; nothing is applied then
     */
    public synchronized PushResult push(List<Operation> ops) {
        ops.forEach(SyncEngine::validate);
        int before = log.size();
        List<String> accepted = new ArrayList<>();
        List<String> duplicates = new ArrayList<>();
        for (Operation op : ops) {
            accepted.add(op.opId());
            if (seenOpIds.contains(op.opId())) {
                duplicates.add(op.opId());
            } else {
                process(op);
            }
        }
        return new PushResult(accepted, duplicates, List.copyOf(log.subList(before, log.size())), head());
    }

    /** All log entries after the given position. */
    public synchronized List<LogEntry> pull(long since) {
        int from = (int) Math.max(0, Math.min(since, log.size()));
        return List.copyOf(log.subList(from, log.size()));
    }

    public synchronized long head() {
        return log.size();
    }

    /** A copy of the current server state. */
    public synchronized Replica snapshot() {
        return state.copy();
    }

    /** Conflicts resolved since this engine was created (not restored from the log yet). */
    public synchronized List<Resolution> resolutions() {
        return List.copyOf(resolutions);
    }

    /** Everything currently waiting for a human: disputed fields and delete-versus-edit records. */
    public synchronized List<ReviewItem> openReviews() {
        List<ReviewItem> items = new ArrayList<>();
        for (RecordState record : state.records().values()) {
            RecordKey key = record.key();
            if (record.deleteDispute() != null && !record.deleted()) {
                items.add(new ReviewItem(key.recordType(), key.recordId(), null, null, null, List.of(),
                        "Deleted on " + record.deleteDispute().deviceId() + " but edited on another device."));
            }
            record.fields().forEach((name, field) -> {
                if (field.disputed()) {
                    items.add(new ReviewItem(key.recordType(), key.recordId(), name, field.value(), field.deviceId(),
                            field.candidates(), noteOf(key, field.candidates().get(0).opId())));
                }
            });
        }
        return items;
    }

    // ---------------------------------------------------------------- processing

    private static void validate(Operation op) {
        if (op == null || blank(op.opId()) || blank(op.deviceId()) || blank(op.recordType())
                || blank(op.recordId()) || op.kind() == null || blank(op.hlc())) {
            throw new IllegalArgumentException("Operation needs opId, deviceId, recordType, recordId, kind and hlc: " + op);
        }
        if (op.kind() == OpKind.SET && blank(op.field())) {
            throw new IllegalArgumentException("A set operation needs a field: " + op.opId());
        }
        if (SERVER.equals(op.deviceId())) {
            throw new IllegalArgumentException("deviceId '" + SERVER + "' is reserved: " + op.opId());
        }
        if (op.baseVersion() < 0) {
            throw new IllegalArgumentException("baseVersion cannot be negative: " + op.opId());
        }
        Hlc.parse(op.hlc());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private void process(Operation op) {
        RecordSpec recordSpec = spec.type(op.recordType());
        if (recordSpec == null) {
            append(op, Disposition.REJECTED, "Unknown record type '" + op.recordType() + "'.");
            return;
        }
        RecordState record = state.get(op.key());
        switch (op.kind()) {
            case CREATE -> {
                if (record == null) append(op, Disposition.APPLIED, null);
                else append(op, Disposition.NO_CHANGE, "Record already exists.");
            }
            case DELETE -> processDelete(op, record);
            case SET -> processSet(op, recordSpec, record);
        }
    }

    private void processDelete(Operation op, RecordState record) {
        if (record == null) {
            append(op, Disposition.REJECTED, "Unknown record.");
        } else if (record.deleted()) {
            append(op, Disposition.NO_CHANGE, "Record was already deleted.");
        } else if (editedByOthersSince(op)) {
            append(op, Disposition.DISPUTED, "Delete versus edit: another device edited this record after "
                    + op.deviceId() + " last synced. Record kept and sent to review.");
        } else {
            append(op, Disposition.APPLIED, null);
        }
    }

    private void processSet(Operation raw, RecordSpec recordSpec, RecordState record) {
        MergePolicy policy = recordSpec.policy(raw.field());
        if (record == null) {
            append(raw, Disposition.REJECTED, "Unknown record.");
            return;
        }
        if (policy == null) {
            append(raw, Disposition.REJECTED, "Unknown field '" + raw.field() + "'.");
            return;
        }
        Operation op;
        try {
            op = raw.withValue(Values.forPolicy(policy, raw.value()));
        } catch (IllegalArgumentException e) {
            append(raw, Disposition.REJECTED, "Invalid value: " + e.getMessage());
            return;
        }

        if (record.deleted()) {
            RecordState.DeleteMark tombstone = record.tombstone();
            boolean editorHadSeenDelete = tombstone.position() <= op.baseVersion()
                    || tombstone.deviceId().equals(op.deviceId());
            if (editorHadSeenDelete) {
                append(op, Disposition.REJECTED, "Record is deleted.");
                return;
            }
            // Delete arrived first, the offline edit second: keep the record with the edit.
            Operation restore = new Operation(serverOpId("restore", op.opId()), SERVER, op.recordType(),
                    op.recordId(), null, OpKind.CREATE, null, record.version(), op.hlc());
            append(restore, Disposition.APPLIED, "Delete versus edit: deleted on " + tombstone.deviceId()
                    + " but edited offline on " + op.deviceId() + ". Record restored and sent to review.");
        }

        if (policy == MergePolicy.APPEND_ONLY) {
            processAppend(op);
            return;
        }

        FieldState field = record.field(op.field());
        if (field.disputed()) {
            append(op, Disposition.DISPUTED, "Field is already disputed; value added for the reviewer.");
            return;
        }

        Object base = baseValue(op);
        Object server = field.value();
        Object incoming = op.value();

        if (Objects.equals(server, base)) {
            append(op, Disposition.APPLIED, null);                       // only the device changed it
        } else if (Objects.equals(incoming, base)) {
            append(op, Disposition.NO_CHANGE, "Device did not change the value; server value kept.");
        } else if (Objects.equals(incoming, server) && policy != MergePolicy.COUNTER) {
            append(op, Disposition.NO_CHANGE, "Both sides made the same change.");
        } else {
            judge(op, recordSpec, record, policy, field, base);         // both changed it: conflict
        }
    }

    /** Appends commute, so they are always applied; entries are kept in HLC order by the fold. */
    private void processAppend(Operation op) {
        boolean concurrent = entriesOf(op.key()).stream().anyMatch(e -> e.position() > op.baseVersion()
                && e.op().kind() == OpKind.SET && op.field().equals(e.op().field())
                && e.disposition() == Disposition.APPLIED && !e.op().deviceId().equals(op.deviceId()));
        String note = concurrent ? "Tier 1 (append-only): entries from both devices kept, ordered by HLC." : null;
        append(op, Disposition.APPLIED, note);
        if (concurrent) {
            resolutions.add(new Resolution(op.recordType(), op.recordId(), op.field(), MergePolicy.APPEND_ONLY, 1,
                    null, null, op.value(), op.value(), note, op.opId(), op.opId()));
        }
    }

    // ---------------------------------------------------------------- conflict judge (tier 1 + escalation)

    private void judge(Operation op, RecordSpec recordSpec, RecordState record, MergePolicy policy,
                       FieldState field, Object base) {
        Object server = field.value();
        Object incoming = op.value();
        switch (policy) {
            case COUNTER -> {
                long b = Values.asLong(base), s = Values.asLong(server), i = Values.asLong(incoming);
                long merged = s + (i - b);
                merge(op, recordSpec, record, policy, field, base, merged, Hlc.max(field.hlc(), op.hlc()),
                        "Tier 1 (counter): both changes combined: " + s + " + (" + i + " - " + b + ") = " + merged + ".");
            }
            case SET_UNION -> {
                // Union of both sides: every addition survives, and an item that was in the base
                // disappears only if both sides removed it.
                TreeSet<String> merged = new TreeSet<>(Values.asSet(server));
                merged.addAll(Values.asSet(incoming));
                merge(op, recordSpec, record, policy, field, base, List.copyOf(merged), Hlc.max(field.hlc(), op.hlc()),
                        "Tier 1 (set-union): additions from both sides kept; only items removed by both are removed.");
            }
            case LATEST -> {
                boolean incomingWins = Hlc.compare(op.hlc(), field.hlc()) > 0;
                merge(op, recordSpec, record, policy, field, base, incomingWins ? incoming : server,
                        incomingWins ? op.hlc() : field.hlc(),
                        "Tier 1 (latest): " + (incomingWins ? "incoming value from " + op.deviceId() : "existing value")
                                + " has the later hybrid logical clock and wins.");
            }
            case CRITICAL -> append(op, Disposition.DISPUTED, "Tier 3 (critical): " + field.deviceId() + " recorded '"
                    + server + "', " + op.deviceId() + " recorded '" + incoming + "'. Never merged automatically.");
            case AI_JUDGE -> append(op, Disposition.DISPUTED,
                    "Tier 2 (ai-judge): no AI judge available, sent to review.");
            case APPEND_ONLY -> throw new IllegalStateException("append-only fields never reach the judge");
        }
    }

    private void merge(Operation op, RecordSpec recordSpec, RecordState record, MergePolicy policy,
                       FieldState field, Object base, Object merged, String hlc, String explanation) {
        Invariant broken = brokenInvariant(recordSpec, record, op.field(), merged);
        if (broken != null) {
            append(op, Disposition.DISPUTED, "Merged value " + merged + " would break the rule '" + broken
                    + "'. Sent to review instead.");
            return;
        }
        LogEntry conflicted = append(op, Disposition.MERGED, explanation);
        Operation resolution = new Operation(serverOpId("resolve", op.opId()), SERVER, op.recordType(),
                op.recordId(), op.field(), OpKind.SET, merged, conflicted.position(), hlc);
        append(resolution, Disposition.APPLIED, explanation);
        resolutions.add(new Resolution(op.recordType(), op.recordId(), op.field(), policy, 1, base, field.value(),
                op.value(), merged, explanation, op.opId(), resolution.opId()));
    }

    private static Invariant brokenInvariant(RecordSpec recordSpec, RecordState record, String field, Object merged) {
        Map<String, Object> values = new HashMap<>();
        record.fields().forEach((name, f) -> values.put(name, f.value()));
        values.put(field, merged);
        for (Invariant invariant : recordSpec.invariants()) {
            if (!invariant.holds(values)) return invariant;
        }
        return null;
    }

    // ---------------------------------------------------------------- three-way compare helpers

    /**
     * The value the device was looking at when it made this change: its own previous unsynced
     * edit of the field if there is one, otherwise the server value at the op's base version.
     */
    private Object baseValue(Operation op) {
        List<LogEntry> entries = entriesOf(op.key());
        for (int i = entries.size() - 1; i >= 0 && entries.get(i).position() > op.baseVersion(); i--) {
            LogEntry e = entries.get(i);
            if (e.op().kind() == OpKind.SET && op.field().equals(e.op().field())
                    && e.op().deviceId().equals(op.deviceId()) && e.disposition() != Disposition.REJECTED) {
                return e.op().value();
            }
        }
        Replica past = new Replica();
        for (LogEntry e : entries) {
            if (e.position() > op.baseVersion()) break;
            past.apply(e, spec);
        }
        RecordState record = past.get(op.key());
        return record == null ? null : record.value(op.field());
    }

    private boolean editedByOthersSince(Operation op) {
        return entriesOf(op.key()).stream().anyMatch(e -> e.position() > op.baseVersion()
                && e.op().kind() == OpKind.SET && !e.op().deviceId().equals(op.deviceId())
                && (e.disposition() == Disposition.APPLIED || e.disposition() == Disposition.MERGED
                || e.disposition() == Disposition.DISPUTED));
    }

    // ---------------------------------------------------------------- log

    private LogEntry append(Operation op, Disposition disposition, String note) {
        LogEntry entry = new LogEntry(log.size() + 1L, op, disposition, note);
        record(entry);
        return entry;
    }

    private void record(LogEntry entry) {
        log.add(entry);
        seenOpIds.add(entry.op().opId());
        state.apply(entry, spec);
        entriesByRecord.computeIfAbsent(entry.op().key(), k -> new ArrayList<>()).add(entry);
    }

    private List<LogEntry> entriesOf(RecordKey key) {
        return entriesByRecord.getOrDefault(key, List.of());
    }

    private String noteOf(RecordKey key, String opId) {
        return entriesOf(key).stream().filter(e -> e.op().opId().equals(opId)).map(LogEntry::note)
                .findFirst().orElse(null);
    }

    private static String serverOpId(String purpose, String opId) {
        return UUID.nameUUIDFromBytes((purpose + ":" + opId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
