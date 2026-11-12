package dev.doppelganger.core;

import dev.doppelganger.core.FieldState.Candidate;
import dev.doppelganger.core.FieldState.Note;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Materialised state of one record, built only by applying log entries in order.
 * This fold is the same on the server and on every device and never calls the judge.
 */
public final class RecordState {

    /** Who deleted (or tried to delete) the record. */
    public record DeleteMark(String opId, String deviceId, String hlc, long position) {
    }

    private final RecordKey key;
    private long version;
    private final Map<String, FieldState> fields = new TreeMap<>();
    private DeleteMark tombstone;
    private DeleteMark deleteDispute;

    RecordState(RecordKey key) {
        this.key = key;
    }

    void apply(LogEntry entry, RecordSpec spec) {
        Operation op = entry.op();
        version = entry.position();
        switch (op.kind()) {
            case CREATE -> {
                // A server "create" on a deleted record restores it: it was edited elsewhere.
                if (tombstone != null && SyncEngine.SERVER.equals(op.deviceId())) {
                    deleteDispute = tombstone;
                    tombstone = null;
                }
            }
            case DELETE -> {
                DeleteMark mark = new DeleteMark(op.opId(), op.deviceId(), op.hlc(), entry.position());
                if (entry.disposition() == Disposition.APPLIED && tombstone == null) {
                    tombstone = mark;
                } else if (entry.disposition() == Disposition.DISPUTED && deleteDispute == null) {
                    deleteDispute = mark;
                }
            }
            case SET -> applySet(entry, spec.policy(op.field()));
        }
    }

    private void applySet(LogEntry entry, MergePolicy policy) {
        Operation op = entry.op();
        FieldState old = field(op.field());
        if (entry.disposition() == Disposition.DISPUTED) {
            fields.put(op.field(), old.withCandidate(new Candidate(op.opId(), op.deviceId(), op.hlc(), op.value())));
        } else if (entry.disposition() == Disposition.APPLIED && policy == MergePolicy.APPEND_ONLY) {
            List<Note> notes = new ArrayList<>(notes(old.value()));
            notes.add(new Note(op.hlc(), op.deviceId(), (String) op.value()));
            notes.sort(Comparator.comparing(n -> Hlc.parse(n.hlc())));
            fields.put(op.field(), new FieldState(List.copyOf(notes), Hlc.max(op.hlc(), old.hlc()), op.deviceId(), old.candidates()));
        } else if (entry.disposition() == Disposition.APPLIED) {
            // An applied value (including a reviewer's decision) ends any dispute on the field.
            fields.put(op.field(), new FieldState(op.value(), op.hlc(), op.deviceId(), List.of()));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Note> notes(Object value) {
        return value == null ? List.of() : (List<Note>) value;
    }

    public RecordKey key() {
        return key;
    }

    /** Log position of the last entry that touched this record. Devices send it as {@code baseVersion}. */
    public long version() {
        return version;
    }

    public FieldState field(String name) {
        return fields.getOrDefault(name, FieldState.EMPTY);
    }

    public Object value(String name) {
        return field(name).value();
    }

    public Map<String, FieldState> fields() {
        return Map.copyOf(fields);
    }

    public boolean deleted() {
        return tombstone != null;
    }

    public DeleteMark tombstone() {
        return tombstone;
    }

    /** Set while the record is "deleted elsewhere" but kept because another device edited it. */
    public DeleteMark deleteDispute() {
        return deleteDispute;
    }

    RecordState copy() {
        RecordState c = new RecordState(key);
        c.version = version;
        c.fields.putAll(fields);
        c.tombstone = tombstone;
        c.deleteDispute = deleteDispute;
        return c;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RecordState r && key.equals(r.key) && version == r.version && fields.equals(r.fields)
                && Objects.equals(tombstone, r.tombstone) && Objects.equals(deleteDispute, r.deleteDispute);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, version, fields, tombstone, deleteDispute);
    }

    @Override
    public String toString() {
        return key + "@" + version + fields + (tombstone != null ? " DELETED" : "")
                + (deleteDispute != null ? " DELETED-ELSEWHERE" : "");
    }
}
