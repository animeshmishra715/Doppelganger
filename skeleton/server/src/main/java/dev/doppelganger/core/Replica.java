package dev.doppelganger.core;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** A full copy of the data: every record, built by applying log entries in order. */
public final class Replica {

    private final Map<RecordKey, RecordState> records = new TreeMap<>();

    public void apply(LogEntry entry, DomainSpec spec) {
        Operation op = entry.op();
        RecordSpec recordSpec = spec.type(op.recordType());
        if (recordSpec == null) return;
        RecordState record = records.get(op.key());
        if (record == null) {
            if (op.kind() != OpKind.CREATE || entry.disposition() != Disposition.APPLIED) return;
            record = new RecordState(op.key());
            records.put(op.key(), record);
        }
        record.apply(entry, recordSpec);
    }

    public RecordState get(RecordKey key) {
        return records.get(key);
    }

    public Map<RecordKey, RecordState> records() {
        return Collections.unmodifiableMap(records);
    }

    public Replica copy() {
        Replica c = new Replica();
        records.forEach((k, v) -> c.records.put(k, v.copy()));
        return c;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Replica r && records.equals(r.records);
    }

    @Override
    public int hashCode() {
        return records.hashCode();
    }

    @Override
    public String toString() {
        return records.values().toString();
    }
}
