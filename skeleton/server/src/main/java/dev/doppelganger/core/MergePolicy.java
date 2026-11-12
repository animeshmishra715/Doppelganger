package dev.doppelganger.core;

/** How a field resolves conflicts. Names in the domain spec YAML are the lower-case, hyphenated forms. */
public enum MergePolicy {
    COUNTER, SET_UNION, APPEND_ONLY, LATEST, AI_JUDGE, CRITICAL;

    public String specName() {
        return name().toLowerCase().replace('_', '-');
    }
}
