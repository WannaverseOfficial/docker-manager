package com.wannaverse.iac;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads the YAML text into a {@link ConfigDocument}. */
public class ConfigParser {

    public static final int VERSION = 1;

    private final Collection<ResourceHandler<?>> handlers;

    public ConfigParser(Collection<ResourceHandler<?>> handlers) {
        this.handlers = handlers;
    }

    public ConfigDocument parse(String yaml, Interpolator interpolator) {
        List<String> problems = new ArrayList<>();
        Map<String, LinkedHashMap<String, ResourceHandler.Entry<?>>> sections =
                new LinkedHashMap<>();
        String hash = sha256(yaml == null ? "" : yaml);

        JsonNode root;
        try {
            root = yaml == null || yaml.isBlank() ? null : YamlReader.read(yaml);
        } catch (IacException e) {
            problems.add(e.getMessage());
            return new ConfigDocument(sections, problems, hash);
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            root = IacJson.YAML.createObjectNode();
        }
        if (!(root instanceof ObjectNode doc)) {
            problems.add("The file must be a map of sections (version:, hosts:, users: ...)");
            return new ConfigDocument(sections, problems, hash);
        }

        JsonNode version = doc.get("version");
        if (version == null) {
            problems.add("Add \"version: " + VERSION + "\" at the top of the file");
        } else if (!version.canConvertToInt() || version.asInt() != VERSION) {
            problems.add(
                    "version: "
                            + version.asText()
                            + " is not supported; this Docker Manager reads version "
                            + VERSION);
        }

        interpolator.apply(doc, "", problems);

        Map<String, ResourceHandler<?>> topLevel = new LinkedHashMap<>();
        for (ResourceHandler<?> h : handlers) {
            if (h.parentSection() == null) topLevel.put(h.section(), h);
        }
        var it = doc.fieldNames();
        List<String> names = new ArrayList<>();
        it.forEachRemaining(names::add);
        for (String name : names) {
            if (name.equals("version") || name.startsWith("x-")) continue;
            if (!topLevel.containsKey(name)) {
                problems.add(
                        "Unknown section \""
                                + name
                                + "\". Sections: "
                                + String.join(", ", topLevel.keySet())
                                + " (keys starting with x- are ignored, for YAML anchors)");
            }
        }

        // Nested sections first: they are taken out of their parents' entries, so the parent
        // types never see them.
        for (ResourceHandler<?> h : handlers) {
            if (h.parentSection() == null) continue;
            JsonNode parent = doc.get(h.parentSection());
            LinkedHashMap<String, ResourceHandler.Entry<?>> entries = new LinkedHashMap<>();
            sections.put(h.section(), entries);
            if (parent == null || !parent.isObject()) continue;
            for (var p : iterable(parent)) {
                if (!(p.getValue() instanceof ObjectNode parentEntry)) continue;
                JsonNode children = parentEntry.remove(h.parentField());
                if (children == null || children.isNull()) continue;
                String where = h.parentSection() + "." + p.getKey() + "." + h.parentField();
                if (!children.isObject()) {
                    problems.add(where + ": must be a map of named entries");
                    continue;
                }
                for (var c : iterable(children)) {
                    String key = p.getKey() + "/" + c.getKey();
                    read(h, key, p.getKey(), c.getKey(), c.getValue(), where, entries, problems);
                }
            }
        }

        for (ResourceHandler<?> h : topLevel.values()) {
            JsonNode node = doc.get(h.section());
            LinkedHashMap<String, ResourceHandler.Entry<?>> entries = new LinkedHashMap<>();
            sections.put(h.section(), entries);
            if (node == null || node.isNull()) continue;
            if (h.singleton()) {
                read(h, "default", null, "default", node, h.section(), entries, problems);
                continue;
            }
            if (!node.isObject()) {
                problems.add(
                        h.section()
                                + ": must be a map of named entries, such as\n  "
                                + h.section()
                                + ":\n    my-name:\n      ...");
                continue;
            }
            for (var e : iterable(node)) {
                read(h, e.getKey(), null, e.getKey(), e.getValue(), h.section(), entries, problems);
            }
        }
        return new ConfigDocument(sections, problems, hash);
    }

    private static <S> void read(
            ResourceHandler<S> h,
            String key,
            String parentKey,
            String localKey,
            JsonNode node,
            String where,
            LinkedHashMap<String, ResourceHandler.Entry<?>> into,
            List<String> problems) {
        String path = h.singleton() ? where : where + "." + localKey;
        if (localKey.isBlank() || localKey.contains("/")) {
            problems.add(path + ": names can't be empty or contain \"/\"");
            return;
        }
        if (node == null || node instanceof NullNode) node = IacJson.YAML.createObjectNode();
        if (!node.isObject()) {
            problems.add(path + ": must be a map of settings");
            return;
        }
        try {
            S spec = IacJson.SPEC.treeToValue(node, h.specType());
            List<String> missing = missingRequired(spec);
            if (!missing.isEmpty()) {
                problems.add(path + ": missing " + String.join(", ", missing));
                return;
            }
            into.put(key, new ResourceHandler.Entry<>(key, parentKey, localKey, spec));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            problems.add(path + describeField(e));
        }
    }

    /** Fields marked {@code @Doc(required = true)} that are not set, at the entry's top level. */
    static List<String> missingRequired(Object spec) {
        List<String> missing = new ArrayList<>();
        for (Class<?> c = spec.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                Doc doc = f.getAnnotation(Doc.class);
                if (doc == null || !doc.required()) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(spec);
                    if (v == null
                            || (v instanceof String s && s.isBlank())
                            || (v instanceof Secret s && s.isBlank())) {
                        missing.add(SchemaGenerator.jsonName(f));
                    }
                } catch (IllegalAccessException ignored) {
                    // Not reachable for our own types.
                }
            }
        }
        return missing;
    }

    private static String describeField(Exception e) {
        if (e instanceof UnrecognizedPropertyException u) {
            String known =
                    u.getKnownPropertyIds() == null
                            ? ""
                            : u.getKnownPropertyIds().stream()
                                    .map(Object::toString)
                                    .sorted()
                                    .collect(Collectors.joining(", "));
            return pathOf(u)
                    + ": unknown setting \""
                    + u.getPropertyName()
                    + "\""
                    + (known.isEmpty() ? "" : ". Settings: " + known);
        }
        if (e instanceof InvalidFormatException f) {
            String msg = f.getOriginalMessage();
            int hint = msg == null ? -1 : msg.indexOf("use one of");
            // The value itself is never repeated: it may have come from ${file:...} or ${env:...}.
            return pathOf(f)
                    + ": not a valid value"
                    + (hint >= 0 ? "; " + msg.substring(hint) : "");
        }
        if (e instanceof MismatchedInputException m) {
            String type =
                    m.getTargetType() == null
                            ? ""
                            : SchemaGenerator.friendlyType(m.getTargetType());
            return pathOf(m) + ": expected " + (type.isEmpty() ? "another kind of value" : type);
        }
        // Jackson's own messages quote the value, which may be a secret: say less.
        return (e instanceof com.fasterxml.jackson.databind.JsonMappingException j ? pathOf(j) : "")
                + ": not a valid value here";
    }

    private static String pathOf(com.fasterxml.jackson.databind.JsonMappingException e) {
        StringBuilder sb = new StringBuilder();
        for (var ref : e.getPath()) {
            if (ref.getFieldName() != null) sb.append('.').append(ref.getFieldName());
            else if (ref.getIndex() >= 0) sb.append('[').append(ref.getIndex()).append(']');
        }
        return sb.toString();
    }

    private static Iterable<Map.Entry<String, JsonNode>> iterable(JsonNode node) {
        List<Map.Entry<String, JsonNode>> list = new ArrayList<>();
        node.fields().forEachRemaining(list::add);
        return list;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
