package dev.doppelganger.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Current state of one field: its value, who wrote it and when, and (while the field is disputed)
 * the values waiting for a human decision.
 */
public record FieldState(Object value, String hlc, String deviceId, List<Candidate> candidates) {

    public static final FieldState EMPTY = new FieldState(null, null, null, List.of());

    /** A value that was not applied because the field needs human review. */
    public record Candidate(String opId, String deviceId, String hlc, Object value) {
    }

    /** One entry of an append-only field. */
    public record Note(String hlc, String deviceId, String text) {
    }

    public boolean disputed() {
        return !candidates.isEmpty();
    }

    FieldState withCandidate(Candidate c) {
        List<Candidate> list = new ArrayList<>(candidates);
        list.add(c);
        return new FieldState(value, hlc, deviceId, List.copyOf(list));
    }
}
