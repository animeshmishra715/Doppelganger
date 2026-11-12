package dev.doppelganger.core;

/**
 * One change to one field of one record (or the creation / deletion of a record).
 *
 * @param opId        unique id; a resent operation with the same id is applied only once
 * @param baseVersion the record version (log position of the last entry touching the record)
 *                    the device had pulled when it made the change; 0 for a record it just created
 * @param hlc         hybrid logical clock timestamp, see {@link Hlc}
 */
public record Operation(
        String opId,
        String deviceId,
        String recordType,
        String recordId,
        String field,
        OpKind kind,
        Object value,
        long baseVersion,
        String hlc) {

    public Operation withValue(Object newValue) {
        return new Operation(opId, deviceId, recordType, recordId, field, kind, newValue, baseVersion, hlc);
    }

    public RecordKey key() {
        return new RecordKey(recordType, recordId);
    }
}
