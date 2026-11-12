package com.wannaverse.iac;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed configuration file: every section's entries in file order, already typed, plus the
 * problems found while reading it. A document with problems is never applied.
 */
public record ConfigDocument(
        Map<String, LinkedHashMap<String, ResourceHandler.Entry<?>>> sections,
        List<String> problems,
        String hash) {

    public Map<String, ResourceHandler.Entry<?>> entries(String section) {
        return sections.getOrDefault(section, new LinkedHashMap<>());
    }

    public boolean declared(String section, String key) {
        return entries(section).containsKey(key);
    }
}
