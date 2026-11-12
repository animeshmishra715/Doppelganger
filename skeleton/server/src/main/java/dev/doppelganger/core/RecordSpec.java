package dev.doppelganger.core;

import java.util.List;
import java.util.Map;

/** One record type of a domain spec: its fields with their merge policies, and its invariants. */
public record RecordSpec(String recordType, Map<String, MergePolicy> fields, List<Invariant> invariants) {

    public RecordSpec {
        fields = Map.copyOf(fields);
        invariants = List.copyOf(invariants);
    }

    /** @return the field's policy, or {@code null} if the field is not declared */
    public MergePolicy policy(String field) {
        return field == null ? null : fields.get(field);
    }
}
