package com.wannaverse.iac.handlers;

import com.github.dockerjava.api.exception.NotFoundException;
import com.wannaverse.iac.IacException;
import com.wannaverse.iac.Secret;
import com.wannaverse.service.DockerAPI;
import com.wannaverse.service.DockerHostService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Helpers shared by the handlers of Docker objects (containers, networks, volumes, stacks). */
final class DockerEntries {

    private DockerEntries() {}

    /** Anonymous volumes are named with 64 hex digits. */
    static final Pattern ANONYMOUS = Pattern.compile("^[0-9a-f]{64}$");

    /** Labels Docker Manager and compose put on what they create themselves. */
    static boolean createdByTool(Map<String, String> labels) {
        if (labels == null) return false;
        for (String k : labels.keySet()) {
            if (k.startsWith("com.wannaverse.") || k.startsWith("com.docker.compose.")) {
                return true;
            }
        }
        return false;
    }

    /** Names Docker Manager gives its own containers, networks and volumes. */
    static boolean internalName(String name) {
        return name != null
                && (name.startsWith("dm-")
                        || name.startsWith("managed-ingress-")
                        || ANONYMOUS.matcher(name).matches());
    }

    static DockerAPI api(DockerHostService hosts, String hostId) {
        if (hostId == null) throw new IacException("the host has not been set up");
        try {
            return hosts.getDockerAPI(hostId);
        } catch (IllegalArgumentException e) {
            throw new IacException("the host no longer exists");
        }
    }

    /**
     * For a database's or observability's containers: list: "containers" when a listed container
     * exists but isn't connected. Listed containers that don't exist are reported (an exception the
     * engine turns into a warning) rather than counted as drift, so a typo doesn't make every apply
     * "change" something forever.
     */
    static Set<String> memberDrift(DockerAPI api, List<String> wanted, Set<String> members) {
        List<String> missing = new java.util.ArrayList<>();
        boolean disconnected = false;
        for (String c : wanted) {
            if (members.contains(c)) continue;
            if (orNull(() -> api.inspectContainer(c)) == null) missing.add(c);
            else disconnected = true;
        }
        if (disconnected) return Set.of("containers");
        if (!missing.isEmpty()) {
            throw new IacException(
                    "containers: there is no container called " + String.join(", ", missing));
        }
        return Set.of();
    }

    /** The value, or null when Docker says it doesn't exist. */
    static <T> T orNull(Supplier<T> call) {
        try {
            return call.get();
        } catch (NotFoundException e) {
            return null;
        }
    }

    /** "KEY=value" lines as a map of secrets (values may hold passwords). */
    static Map<String, Secret> envMap(Iterable<String> lines) {
        Map<String, Secret> out = new LinkedHashMap<>();
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            out.put(line.substring(0, eq), Secret.placeholder(escape(line.substring(eq + 1))));
        }
        return out;
    }

    /**
     * A .env file's variables. Comments and blank lines are dropped; values are kept as written.
     */
    /**
     * Refuses an entry that would move what it manages to another host (or give it another name,
     * unless renaming is safe): that would quietly make a new one and delete the old one with its
     * data. The file has to say so instead, with a new entry and the old one removed.
     */
    static void refuseMove(
            com.wannaverse.iac.ApplyContext ctx,
            String section,
            String key,
            String hostRef,
            String name,
            boolean renameOk,
            String what) {
        var record = ctx.record(section, key).orElse(null);
        if (record == null) return;
        String hostId = ctx.hostId(hostRef);
        if (record.getHostId() != null && !record.getHostId().equals(hostId)) {
            throw new IacException(
                    "host: a "
                            + what
                            + " can't move to another host (its data would be left behind)."
                            + " Add a new entry for the new host and remove this one.");
        }
        if (!renameOk && !record.getTargetId().equals(name)) {
            throw new IacException(
                    "name: a "
                            + what
                            + " can't be renamed in place (it would be made again from scratch)."
                            + " Add a new entry with the new name and remove this one.");
        }
    }

    /** Variable names that usually hold secrets. */
    private static final Pattern SECRET_NAME =
            Pattern.compile(
                    "(?i).*(pass|secret|token|key|credential|auth|private|cert|salt|dsn|"
                            + "database_url|db_url|connection).*");

    /**
     * For exports: variables whose names suggest a secret become ${env:...} placeholders (listed at
     * the top of the export), the rest stay as they are.
     */
    static Map<String, Secret> hideSecrets(
            Map<String, Secret> env,
            com.wannaverse.iac.ExportContext ctx,
            String section,
            String key,
            String what) {
        Map<String, Secret> out = new LinkedHashMap<>();
        env.forEach(
                (name, value) ->
                        out.put(
                                name,
                                SECRET_NAME.matcher(name).matches()
                                        ? ctx.secret(section, key, name, name + " of " + what)
                                        : value));
        return out;
    }

    static Map<String, Secret> parseEnvFile(String text) {
        Map<String, Secret> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (l.startsWith("export ")) l = l.substring(7).strip();
            int eq = l.indexOf('=');
            if (eq <= 0) continue;
            out.put(l.substring(0, eq).strip(), Secret.placeholder(escape(l.substring(eq + 1))));
        }
        return out;
    }

    /**
     * Writes text for an export so that applying the file reads it back unchanged: the file's own
     * {@code ${env:...}} and {@code ${file:...}} references are escaped as {@code $${...}}.
     */
    static String escape(String text) {
        if (text == null || !text.contains("${")) return text;
        return text.replace("$${", "$$${")
                .replace("${env:", "$${env:")
                .replace("${file:", "$${file:");
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static Map<String, String> nullIfEmpty(Map<String, String> m) {
        return m == null || m.isEmpty() ? null : new LinkedHashMap<>(m);
    }
}
