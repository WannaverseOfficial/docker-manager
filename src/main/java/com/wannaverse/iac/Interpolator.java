package com.wannaverse.iac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fills in references in string values, so secrets stay out of the file:
 *
 * <ul>
 *   <li>{@code ${env:NAME}}: an environment variable of Docker Manager. Fails when it is not set.
 *   <li>{@code ${env:NAME:-fallback}}: the same, with a value to use when it is not set.
 *   <li>{@code ${file:/run/secrets/name}}: the contents of a file (Docker and Kubernetes secrets),
 *       without the trailing newline. A relative path is read next to the configuration file.
 *   <li>{@code $${...}} is written out as {@code ${...}} unchanged, for values that need it (such
 *       as compose files that use their own variables).
 * </ul>
 */
public class Interpolator {

    private static final Pattern REF =
            Pattern.compile("\\$\\$\\{|\\$\\{(env|file):([^}:]+?)(?::-([^}]*))?}");

    /** Docker Manager's own keys. A configuration file has no business copying them anywhere. */
    private static final Set<String> FORBIDDEN_ENV =
            Set.of("ENCRYPTION_KEY", "JWT_ACCESS_SECRET", "JWT_REFRESH_SECRET");

    private final Function<String, String> env;
    private final Path baseDir;
    private final Path forbiddenDir;

    /**
     * @param env looks up environment variables
     * @param baseDir where relative {@code file:} paths start, usually the configuration file's
     *     folder (null: the working directory)
     * @param forbiddenDir Docker Manager's data folder, which holds its database and keys and is
     *     never read
     */
    public Interpolator(Function<String, String> env, Path baseDir, Path forbiddenDir) {
        this.env = env;
        this.baseDir = baseDir;
        this.forbiddenDir = forbiddenDir == null ? null : forbiddenDir.toAbsolutePath().normalize();
    }

    public static Interpolator system(Path baseDir, Path forbiddenDir) {
        Map<String, String> vars = System.getenv();
        return new Interpolator(vars::get, baseDir, forbiddenDir);
    }

    /** Replaces references throughout the tree, in place. Problems are added to the list. */
    public void apply(JsonNode node, String path, List<String> problems) {
        if (node instanceof ObjectNode obj) {
            Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
            it.forEachRemaining(entries::add);
            for (var e : entries) {
                String child = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                if (e.getValue().isTextual()) {
                    obj.set(e.getKey(), resolve(e.getValue().asText(), child, problems));
                } else {
                    apply(e.getValue(), child, problems);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                String child = path + "[" + i + "]";
                if (arr.get(i).isTextual()) {
                    arr.set(i, resolve(arr.get(i).asText(), child, problems));
                } else {
                    apply(arr.get(i), child, problems);
                }
            }
        }
    }

    private TextNode resolve(String text, String path, List<String> problems) {
        if (!text.contains("${")) return TextNode.valueOf(text);
        Matcher m = REF.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement;
            if (m.group().equals("$${")) {
                replacement = "${";
            } else {
                String kind = m.group(1);
                String name = m.group(2).trim();
                String fallback = m.group(3);
                try {
                    replacement =
                            kind.equals("env") ? fromEnv(name, fallback) : fromFile(name, fallback);
                } catch (IacException e) {
                    problems.add(path + ": " + e.getMessage());
                    replacement = "";
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return TextNode.valueOf(out.toString());
    }

    private String fromEnv(String name, String fallback) {
        if (FORBIDDEN_ENV.contains(name)) {
            throw new IacException(
                    "${env:" + name + "} is Docker Manager's own key; it can't be used");
        }
        String value = env.apply(name);
        if (value != null) return value;
        if (fallback != null) return fallback;
        throw new IacException(
                "the environment variable "
                        + name
                        + " is not set (write ${env:"
                        + name
                        + ":-value} to give a default)");
    }

    private static Path realOrSelf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    private String fromFile(String name, String fallback) {
        Path p = Path.of(name);
        if (!p.isAbsolute() && baseDir != null) p = baseDir.resolve(p);
        p = p.toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(p)) {
                if (fallback != null) return fallback;
                throw new IacException("the file " + name + " does not exist");
            }
            // Where it really is, after links: Docker Manager's data folder (its database and
            // keys) and the kernel's views of processes and devices are never read.
            Path real = p.toRealPath();
            Path data = forbiddenDir == null ? null : realOrSelf(forbiddenDir);
            if ((data != null && real.startsWith(data))
                    || real.startsWith("/proc")
                    || real.startsWith("/sys")
                    || real.startsWith("/dev")) {
                throw new IacException(name + " can't be read from a configuration file");
            }
            p = real;
            String s = Files.readString(p, StandardCharsets.UTF_8);
            return s.endsWith("\r\n")
                    ? s.substring(0, s.length() - 2)
                    : s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
        } catch (java.nio.file.AccessDeniedException e) {
            throw new IacException(
                    "Docker Manager is not allowed to read "
                            + name
                            + " (make it readable by the user Docker Manager runs as)");
        } catch (IOException e) {
            throw new IacException(
                    "could not read " + name + " (" + e.getClass().getSimpleName() + ")");
        }
    }
}
