package dev.doppelganger.core;

/** Audit entry: how one conflict was resolved, by which tier, and why. */
public record Resolution(
        String recordType,
        String recordId,
        String field,
        MergePolicy policy,
        int tier,
        Object baseValue,
        Object serverValue,
        Object incomingValue,
        Object finalValue,
        String explanation,
        String incomingOpId,
        String resolutionOpId) {
}
