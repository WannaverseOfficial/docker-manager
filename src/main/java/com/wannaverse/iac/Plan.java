package com.wannaverse.iac;

import java.util.List;

/**
 * What applying a file would do.
 *
 * @param problems errors in the file; a plan with problems is never applied
 * @param warnings things worth knowing that don't stop the apply (a host that didn't answer...)
 */
public record Plan(List<Item> items, List<String> problems, List<String> warnings) {

    public enum Action {
        /** Not there yet: it will be created. */
        CREATE,
        /** Made by hand: the file takes it over and brings it in line. */
        ADOPT,
        /** Managed, and the entry changed since it was last applied. */
        UPDATE,
        /** Managed, but it was removed by other means: it will be created again. */
        RECREATE,
        UNCHANGED,
        /** Removed from the file: it will be deleted. */
        DELETE,
        /** Removed from the file, but kept (it holds data): Docker Manager stops managing it. */
        RELEASE
    }

    /**
     * @param fields for UPDATE, the settings that changed
     */
    public record Item(
            String section,
            String key,
            Action action,
            List<String> fields,
            String label,
            String hostId) {}

    public boolean hasChanges() {
        return items.stream().anyMatch(i -> i.action() != Action.UNCHANGED);
    }
}
