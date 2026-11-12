package dev.doppelganger.core;

import dev.doppelganger.core.FieldState.Candidate;

import java.util.List;

/**
 * Something waiting for a human. {@code field} is {@code null} for a delete-versus-edit item.
 * Derived from the current state, so it can never disagree with what devices show as disputed.
 */
public record ReviewItem(
        String recordType,
        String recordId,
        String field,
        Object currentValue,
        String currentDeviceId,
        List<Candidate> candidates,
        String summary) {
}
