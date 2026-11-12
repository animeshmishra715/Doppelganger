package dev.doppelganger.core;

import java.util.Map;

/** A simple rule over one numeric field, for example {@code quantity >= 0}. */
public record Invariant(String field, Comparison comparison, long bound) {

    public enum Comparison {
        GE(">="), GT(">"), LE("<="), LT("<");

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }
    }

    /** A field that has no value yet cannot break the rule. */
    public boolean holds(Map<String, Object> values) {
        Object v = values.get(field);
        if (!(v instanceof Number n)) return true;
        long x = n.longValue();
        return switch (comparison) {
            case GE -> x >= bound;
            case GT -> x > bound;
            case LE -> x <= bound;
            case LT -> x < bound;
        };
    }

    @Override
    public String toString() {
        return field + " " + comparison.symbol + " " + bound;
    }
}
