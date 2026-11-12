package com.wannaverse.iac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannaverse.persistence.DockerHost;
import com.wannaverse.persistence.DockerHostRepository;
import com.wannaverse.persistence.ManagedResource;
import com.wannaverse.persistence.ManagedResourceRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes today's setup as a configuration file, so that an existing installation can switch to
 * configuration as code: export, review, then apply (which takes everything over).
 */
@Component
public class ConfigExporter {

    private static final Logger log = LoggerFactory.getLogger(ConfigExporter.class);

    private final ConfigEngine engine;
    private final ManagedResourceRepository managed;
    private final DockerHostRepository hosts;

    public ConfigExporter(
            ConfigEngine engine, ManagedResourceRepository managed, DockerHostRepository hosts) {
        this.engine = engine;
        this.managed = managed;
        this.hosts = hosts;
    }

    public String export() {
        Ctx ctx = new Ctx();
        ObjectNode root = IacJson.YAML.createObjectNode();
        root.put("version", ConfigParser.VERSION);
        List<String> failures = new ArrayList<>();

        Map<String, ObjectNode> sectionNodes = new LinkedHashMap<>();
        List<ResourceHandler<?>> nested = new ArrayList<>();
        for (ResourceHandler<?> h : engine.handlers()) {
            if (h.parentSection() != null) {
                nested.add(h);
                continue;
            }
            try {
                Map<String, ?> entries = h.export(ctx);
                if (entries == null || entries.isEmpty()) continue;
                if (h.singleton()) {
                    Object only = entries.values().iterator().next();
                    root.set(h.section(), IacJson.JSON.valueToTree(only));
                } else {
                    ObjectNode section = IacJson.YAML.createObjectNode();
                    entries.forEach((k, v) -> section.set(k, IacJson.JSON.valueToTree(v)));
                    root.set(h.section(), section);
                    sectionNodes.put(h.section(), section);
                }
            } catch (RuntimeException e) {
                log.warn("Could not export {}", h.section(), e);
                failures.add(h.section() + ": " + ConfigEngine.message(e));
            }
        }
        for (ResourceHandler<?> h : nested) {
            try {
                Map<String, ?> entries = h.export(ctx);
                if (entries == null) continue;
                ObjectNode parents = sectionNodes.get(h.parentSection());
                for (var e : entries.entrySet()) {
                    int slash = e.getKey().indexOf('/');
                    if (slash < 0) continue;
                    String parentKey = e.getKey().substring(0, slash);
                    JsonNode parent = parents == null ? null : parents.get(parentKey);
                    if (!(parent instanceof ObjectNode p)) continue;
                    JsonNode field = p.get(h.parentField());
                    ObjectNode children =
                            field instanceof ObjectNode o ? o : p.putObject(h.parentField());
                    children.set(
                            e.getKey().substring(slash + 1),
                            IacJson.JSON.valueToTree(e.getValue()));
                }
            } catch (RuntimeException e) {
                log.warn("Could not export {}", h.section(), e);
                failures.add(h.section() + ": " + ConfigEngine.message(e));
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("# Docker Manager configuration, exported ")
                .append(Instant.now().toString(), 0, 19)
                .append("Z.\n")
                .append("# Review it, then apply it to put everything under the file's control.\n")
                .append(
                        "# Editor help: point your YAML editor at /api/config/schema"
                                + " (yaml-language-server).\n")
                .append(
                        "# Container and stack variables are exported as they are, except those\n"
                                + "# named like secrets (PASSWORD, TOKEN, KEY...): check the rest"
                                + " before\n"
                                + "# you commit this file anywhere.\n");
        if (!ctx.secrets.isEmpty()) {
            out.append(
                            "#\n"
                                    + "# Passwords and keys are not exported. Set these environment"
                                    + " variables\n")
                    .append(
                            "# (or replace the references with ${file:/run/secrets/...}) before"
                                    + " applying:\n");
            ctx.secrets.forEach(
                    (name, what) ->
                            out.append("#   ").append(name).append(": ").append(what).append('\n'));
        }
        if (!failures.isEmpty()) {
            out.append("#\n# Could not export (fix and export again):\n");
            failures.forEach(f -> out.append("#   ").append(f).append('\n'));
        }
        out.append('\n');
        try {
            out.append(IacJson.YAML.writeValueAsString(root));
        } catch (Exception e) {
            throw new IacException("Could not write the export: " + e.getMessage(), e);
        }
        return out.toString();
    }

    private class Ctx implements ExportContext {
        final Map<String, String> secrets = new LinkedHashMap<>();
        final Map<String, String> hostKeys = new LinkedHashMap<>();

        Ctx() {
            Set<String> used = new HashSet<>();
            Map<String, String> managedKeys = new LinkedHashMap<>();
            for (ManagedResource r : managed.findBySection("hosts")) {
                managedKeys.put(r.getTargetId(), r.getEntryKey());
                used.add(r.getEntryKey());
            }
            for (DockerHost h : hosts.findAll()) {
                String key = managedKeys.get(h.getId());
                if (key == null) {
                    String base = slug(h.getName() == null ? "host" : h.getName());
                    key = base;
                    for (int i = 2; used.contains(key); i++) key = base + "-" + i;
                    used.add(key);
                }
                hostKeys.put(h.getId(), key);
            }
        }

        @Override
        public String hostKey(String hostId) {
            return hostKeys.getOrDefault(hostId, hostId);
        }

        @Override
        public String key(String section, String targetId, String fallback) {
            for (ManagedResource r : managed.findBySectionAndTargetId(section, targetId)) {
                String k = r.getEntryKey();
                int slash = k.lastIndexOf('/');
                return slash >= 0 ? k.substring(slash + 1) : k;
            }
            return fallback;
        }

        @Override
        public Secret secret(String section, String key, String field, String what) {
            String name =
                    ("DM_" + section + "_" + key + "_" + field)
                            .toUpperCase(Locale.ROOT)
                            .replaceAll("[^A-Z0-9]+", "_")
                            .replaceAll("_+$", "");
            secrets.put(name, what);
            return Secret.placeholder("${env:" + name + "}");
        }
    }

    /** A readable key from a name: "Web 01" becomes "web-01". */
    public static String slug(String name) {
        String s =
                name.trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9._-]+", "-")
                        .replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "item" : s;
    }
}
