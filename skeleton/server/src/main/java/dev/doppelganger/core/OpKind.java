package dev.doppelganger.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum OpKind {
    CREATE,
    SET,
    DELETE,
    /** A human reviewer cleared a disputed field by selecting a value. */
    REVIEW_DECISION,
    /** A human reviewer resolved a delete-vs-edit dispute (value: "delete" or "keep"). */
    DELETE_DISPUTE_DECISION,
    /** Two records confirmed as the same entity; absorbs one into the other. */
    DUPLICATE_MERGE,
    /** Undoes a previous duplicate-merge, restoring both original records. */
    DUPLICATE_UNDO;

    @JsonValue
    public String json() {
        return switch (this) {
            case CREATE -> "create";
            case SET -> "set";
            case DELETE -> "delete";
            case REVIEW_DECISION -> "review-decision";
            case DELETE_DISPUTE_DECISION -> "delete-dispute-decision";
            case DUPLICATE_MERGE -> "duplicate-merge";
            case DUPLICATE_UNDO -> "duplicate-undo";
        };
    }

    @JsonCreator
    public static OpKind fromJson(String s) {
        return switch (s.trim()) {
            case "create" -> CREATE;
            case "set" -> SET;
            case "delete" -> DELETE;
            case "review-decision" -> REVIEW_DECISION;
            case "delete-dispute-decision" -> DELETE_DISPUTE_DECISION;
            case "duplicate-merge" -> DUPLICATE_MERGE;
            case "duplicate-undo" -> DUPLICATE_UNDO;
            default -> throw new IllegalArgumentException("Unknown OpKind: " + s);
        };
    }
}
