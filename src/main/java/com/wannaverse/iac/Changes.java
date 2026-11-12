package com.wannaverse.iac;

import java.util.Set;

/**
 * Which top-level fields of an entry differ from what was last applied.
 *
 * @param takeover true when taking over something made by hand, or re-creating something that
 *     disappeared: every field then counts as changed
 */
public record Changes(Set<String> fields, boolean takeover) {

    public static Changes all() {
        return new Changes(Set.of(), true);
    }

    /** Whether any of the fields changed. */
    public boolean has(String... names) {
        if (takeover) return true;
        for (String n : names) {
            if (fields.contains(n)) return true;
        }
        return false;
    }
}
