package com.wannaverse.iac;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a JSON schema of the configuration file from the handlers' entry types. Editors that
 * understand YAML schemas (VS Code with the YAML extension, IntelliJ) use it for completion,
 * checking and hover help.
 */
public final class SchemaGenerator {

    private SchemaGenerator() {}

    public static Map<String, Object> generate(Collection<ResourceHandler<?>> handlers) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(
                "version",
                Map.of(
                        "description",
                        "The file format's version. Always " + ConfigParser.VERSION + ".",
                        "const",
                        ConfigParser.VERSION));

        Map<String, List<ResourceHandler<?>>> nested = new LinkedHashMap<>();
        for (ResourceHandler<?> h : handlers) {
            if (h.parentSection() != null) {
                nested.computeIfAbsent(h.parentSection(), k -> new ArrayList<>()).add(h);
            }
        }
        for (ResourceHandler<?> h : handlers) {
            if (h.parentSection() != null) continue;
            Map<String, Object> entry = objectSchema(h.specType());
            for (ResourceHandler<?> child : nested.getOrDefault(h.section(), List.of())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> childProps = (Map<String, Object>) entry.get("properties");
                childProps.put(child.parentField(), keyed(child, objectSchema(child.specType())));
            }
            props.put(h.section(), h.singleton() ? withDescription(entry, h) : keyed(h, entry));
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("title", "Docker Manager configuration");
        schema.put("type", "object");
        schema.put("required", List.of("version"));
        schema.put("properties", props);
        schema.put("patternProperties", Map.of("^x-", Map.of()));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> keyed(ResourceHandler<?> h, Map<String, Object> entry) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        m.put("description", h.description());
        m.put("additionalProperties", entry);
        return m;
    }

    private static Map<String, Object> withDescription(
            Map<String, Object> entry, ResourceHandler<?> h) {
        entry.put("description", h.description());
        return entry;
    }

    static Map<String, Object> objectSchema(Class<?> type) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Field f : fields(type)) {
            String name = jsonName(f);
            Map<String, Object> s = new LinkedHashMap<>(schemaOf(f.getGenericType()));
            Doc doc = f.getAnnotation(Doc.class);
            if (doc != null) {
                s.put("description", doc.value());
                if (doc.required()) required.add(name);
            }
            props.put(name, s);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        Doc typeDoc = type.getAnnotation(Doc.class);
        if (typeDoc != null) m.put("description", typeDoc.value());
        m.put("properties", props);
        if (!required.isEmpty()) m.put("required", required);
        m.put("additionalProperties", false);
        return m;
    }

    private static Map<String, Object> schemaOf(Type t) {
        if (t instanceof ParameterizedType p) {
            Class<?> raw = (Class<?>) p.getRawType();
            Type[] args = p.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                return Map.of("type", "array", "items", schemaOf(args[0]));
            }
            if (Map.class.isAssignableFrom(raw)) {
                return Map.of("type", "object", "additionalProperties", schemaOf(args[1]));
            }
            return Map.of();
        }
        if (!(t instanceof Class<?> c)) return Map.of();
        if (c == String.class) return Map.of("type", "string");
        if (c == Secret.class) {
            return Map.of(
                    "type",
                    "string",
                    "markdownDescription",
                    "A secret. Write ${env:NAME} or ${file:/run/secrets/name} to keep it out of"
                            + " the file.");
        }
        if (c == boolean.class || c == Boolean.class) return Map.of("type", "boolean");
        if (c == int.class
                || c == Integer.class
                || c == long.class
                || c == Long.class
                || c == short.class) {
            return Map.of("type", "integer");
        }
        if (c == double.class || c == Double.class || c == float.class || c == Float.class) {
            return Map.of("type", "number");
        }
        if (c.isEnum()) {
            return Map.of(
                    "type",
                    "string",
                    "enum",
                    Arrays.stream(c.getEnumConstants())
                            .map(e -> IacJson.enumName((Enum<?>) e))
                            .toList());
        }
        if (Collection.class.isAssignableFrom(c)) return Map.of("type", "array");
        if (Map.class.isAssignableFrom(c)) return Map.of("type", "object");
        if (c == Object.class) return Map.of();
        return objectSchema(c);
    }

    static List<Field> fields(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            chain.addFirst(c);
        }
        List<Field> out = new ArrayList<>();
        for (Class<?> c : chain) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic())
                    continue;
                if (f.isAnnotationPresent(JsonIgnore.class)) continue;
                out.add(f);
            }
        }
        return out;
    }

    static String jsonName(Field f) {
        JsonProperty p = f.getAnnotation(JsonProperty.class);
        return p != null && !p.value().isEmpty() ? p.value() : f.getName();
    }

    /** "a number", "a list", "yes or no"... for error messages. */
    static String friendlyType(Class<?> c) {
        if (c == String.class || c == Secret.class) return "text";
        if (c == boolean.class || c == Boolean.class) return "true or false";
        if (c == int.class || c == Integer.class || c == long.class || c == Long.class) {
            return "a whole number";
        }
        if (c == double.class || c == Double.class) return "a number";
        if (c.isEnum()) {
            return "one of "
                    + String.join(
                            ", ",
                            Arrays.stream(c.getEnumConstants())
                                    .map(e -> IacJson.enumName((Enum<?>) e))
                                    .toList());
        }
        if (Collection.class.isAssignableFrom(c)) return "a list";
        if (Map.class.isAssignableFrom(c) || !c.isPrimitive()) return "a map of settings";
        return c.getSimpleName();
    }
}
