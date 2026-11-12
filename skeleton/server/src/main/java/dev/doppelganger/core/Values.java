package dev.doppelganger.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/** Brings field values into one canonical Java form so they can be compared with {@code equals}. */
public final class Values {

    private Values() {
    }

    /** Whole numbers become {@code Long}, other numbers {@code Double}, collections become lists. */
    public static Object normalize(Object v) {
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d) ? (Object) n.longValue() : (Object) d;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) out.add(normalize(o));
            return List.copyOf(out);
        }
        return v;
    }

    /** The canonical value for a field with the given policy; throws if the value has the wrong shape. */
    public static Object forPolicy(MergePolicy policy, Object raw) {
        return switch (policy) {
            case COUNTER -> {
                if (!(normalize(raw) instanceof Long l)) {
                    throw new IllegalArgumentException("counter field needs a whole number, got: " + raw);
                }
                yield l;
            }
            case SET_UNION -> {
                if (raw != null && !(raw instanceof Collection<?>)) {
                    throw new IllegalArgumentException("set-union field needs a list, got: " + raw);
                }
                yield asSet(raw);
            }
            case APPEND_ONLY -> {
                if (!(raw instanceof String s) || s.isBlank()) {
                    throw new IllegalArgumentException("append-only field needs one text entry, got: " + raw);
                }
                yield s;
            }
            default -> normalize(raw);
        };
    }

    public static long asLong(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }

    /** A set is stored as a sorted list of distinct strings. */
    public static List<String> asSet(Object v) {
        TreeSet<String> set = new TreeSet<>();
        if (v instanceof Collection<?> c) {
            for (Object o : c) set.add(String.valueOf(o));
        }
        return List.copyOf(set);
    }
}
