package dev.doppelganger.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** All record types the server knows about. Everything app-specific the engine needs is in here. */
public final class DomainSpec {

    private final Map<String, RecordSpec> types = new LinkedHashMap<>();

    public DomainSpec(RecordSpec... specs) {
        for (RecordSpec s : specs) {
            if (types.put(s.recordType(), s) != null) {
                throw new IllegalArgumentException("Duplicate record type: " + s.recordType());
            }
        }
    }

    /** @return the spec for the record type, or {@code null} if it is not declared */
    public RecordSpec type(String recordType) {
        return types.get(recordType);
    }
}
