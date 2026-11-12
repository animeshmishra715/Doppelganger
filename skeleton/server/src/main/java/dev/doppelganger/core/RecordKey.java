package dev.doppelganger.core;

public record RecordKey(String recordType, String recordId) implements Comparable<RecordKey> {
    @Override
    public int compareTo(RecordKey o) {
        int c = recordType.compareTo(o.recordType);
        return c != 0 ? c : recordId.compareTo(o.recordId);
    }
}
