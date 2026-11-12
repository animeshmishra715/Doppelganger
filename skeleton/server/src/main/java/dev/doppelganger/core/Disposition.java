package dev.doppelganger.core;

/** What the server decided to do with an operation when it was appended to the log. */
public enum Disposition {
    /** Apply the operation as written. */
    APPLIED,
    /** Conflict resolved by a rule; the result is the server operation that follows in the log. */
    MERGED,
    /** Not applied. The value is kept as a candidate until a human decides. */
    DISPUTED,
    /** Recorded, but it changes nothing (for example both sides made the same change). */
    NO_CHANGE,
    /** Recorded, but it could not be applied (unknown record, unknown field, bad value). */
    REJECTED,
    /** A human reviewer picked a value; the dispute is now cleared. */
    REVIEW_DECISION
}
