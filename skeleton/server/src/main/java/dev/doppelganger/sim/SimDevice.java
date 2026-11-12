package dev.doppelganger.sim;

import dev.doppelganger.core.Disposition;
import dev.doppelganger.core.DomainSpec;
import dev.doppelganger.core.Hlc;
import dev.doppelganger.core.HlcClock;
import dev.doppelganger.core.LogEntry;
import dev.doppelganger.core.MergePolicy;
import dev.doppelganger.core.OpKind;
import dev.doppelganger.core.Operation;
import dev.doppelganger.core.RecordKey;
import dev.doppelganger.core.RecordState;
import dev.doppelganger.core.Replica;
import dev.doppelganger.core.Values;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/**
 * An in-memory device: its own copy of the data, an outbox, and a clock that can be wrong.
 * It behaves the way the browser sync client must: edits are applied locally first, pushed
 * later, and the local copy is rebuilt from pulled log entries plus still-pending edits.
 */
public final class SimDevice {

    private final String id;
    private final DomainSpec spec;
    private final HlcClock clock;
    private long clockSkewMillis;

    private final Replica replica = new Replica();                     // state from pulled log entries
    private long pulledPosition;
    private final Map<String, Operation> pending = new LinkedHashMap<>(); // made here, not yet seen in the log
    private final Set<String> acked = new HashSet<>();
    private final List<Operation> history = new ArrayList<>();
    private int nextId;

    public SimDevice(String id, DomainSpec spec, LongSupplier trueTimeMillis) {
        this.id = id;
        this.spec = spec;
        this.clock = new HlcClock(id, () -> trueTimeMillis.getAsLong() + clockSkewMillis);
    }

    // ---------------------------------------------------------------- local edits (work offline)

    public RecordKey create(String recordType) {
        RecordKey key = new RecordKey(recordType, id + "-rec-" + (++nextId));
        emit(key, null, OpKind.CREATE, null);
        return key;
    }

    /** Sets a field to a new value. Returns the operation, or {@code null} if nothing changed. */
    public Operation set(RecordKey key, String field, Object value) {
        RecordState record = editable(key);
        MergePolicy policy = spec.type(key.recordType()).policy(field);
        if (record == null || policy == null || policy == MergePolicy.APPEND_ONLY) return null;
        Object normalized = Values.forPolicy(policy, value);
        if (Objects.equals(record.value(field), normalized)) return null;
        return emit(key, field, OpKind.SET, normalized);
    }

    /** Adds a delta to a counter field (negative for a sale). */
    public Operation add(RecordKey key, String field, long delta) {
        RecordState record = editable(key);
        return record == null ? null : set(key, field, Values.asLong(record.value(field)) + delta);
    }

    public Operation addToSet(RecordKey key, String field, String item) {
        RecordState record = editable(key);
        if (record == null) return null;
        Set<String> items = new TreeSet<>(Values.asSet(record.value(field)));
        items.add(item);
        return set(key, field, items);
    }

    public Operation removeFromSet(RecordKey key, String field, String item) {
        RecordState record = editable(key);
        if (record == null) return null;
        Set<String> items = new TreeSet<>(Values.asSet(record.value(field)));
        items.remove(item);
        return set(key, field, items);
    }

    /** Adds one entry to an append-only field. */
    public Operation append(RecordKey key, String field, String text) {
        if (editable(key) == null || spec.type(key.recordType()).policy(field) != MergePolicy.APPEND_ONLY) return null;
        return emit(key, field, OpKind.SET, text);
    }

    public Operation delete(RecordKey key) {
        return editable(key) == null ? null : emit(key, null, OpKind.DELETE, null);
    }

    private RecordState editable(RecordKey key) {
        RecordState record = view().get(key);
        return record == null || record.deleted() ? null : record;
    }

    private Operation emit(RecordKey key, String field, OpKind kind, Object value) {
        RecordState synced = replica.get(key);
        long baseVersion = synced == null ? 0 : synced.version();
        Operation op = new Operation(id + "-op-" + (++nextId), id, key.recordType(), key.recordId(), field, kind,
                value, baseVersion, clock.tick().format());
        pending.put(op.opId(), op);
        history.add(op);
        return op;
    }

    // ---------------------------------------------------------------- sync

    /** Operations to send: made here and not yet acknowledged, in the order they were made. */
    public List<Operation> outbox() {
        return pending.values().stream().filter(op -> !acked.contains(op.opId())).toList();
    }

    public void acknowledge(Collection<String> opIds) {
        acked.addAll(opIds);
    }

    /** Applies pulled log entries in order. An entry for one of our own ops ends its pending state. */
    public void applyPulled(List<LogEntry> entries) {
        for (LogEntry entry : entries) {
            clock.observe(Hlc.parse(entry.op().hlc()));
            replica.apply(entry, spec);
            pending.remove(entry.op().opId());
            pulledPosition = entry.position();
        }
    }

    // ---------------------------------------------------------------- reading

    /** What the user sees: the synced state with pending local edits applied on top. */
    public Replica view() {
        Replica view = replica.copy();
        for (Operation op : pending.values()) {
            view.apply(new LogEntry(pulledPosition, op, Disposition.APPLIED, null), spec);
        }
        return view;
    }

    /** State built only from pulled log entries. */
    public Replica replica() {
        return replica.copy();
    }

    public String id() {
        return id;
    }

    public long pulledPosition() {
        return pulledPosition;
    }

    public int pendingCount() {
        return pending.size();
    }

    /** Every operation this device ever made. */
    public List<Operation> history() {
        return List.copyOf(history);
    }

    /** Makes this device's wall clock wrong by the given amount. */
    public void setClockSkewMillis(long skew) {
        this.clockSkewMillis = skew;
    }
}
