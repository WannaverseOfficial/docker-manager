package com.wannaverse.iac;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The Jackson setup for configuration files.
 *
 * <p>Enum values are written in lower case with dashes ({@code lets-encrypt}); reading accepts any
 * case, and dashes or underscores. Unknown fields are errors, so a misspelt key is reported instead
 * of silently ignored.
 */
public final class IacJson {

    private IacJson() {}

    /** Reads entries into their types. */
    public static final ObjectMapper SPEC = configure(JsonMapper.builder().build(), true);

    /** Writes entries for comparison and storage: secrets hashed, empty values left out. */
    public static final ObjectMapper JSON = configure(JsonMapper.builder().build(), false);

    /** Reads and writes the YAML text. */
    public static final YAMLMapper YAML =
            (YAMLMapper)
                    configure(
                            new YAMLMapper(
                                    YAMLFactory.builder()
                                            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                                            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                                            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                                            .enable(
                                                    YAMLGenerator.Feature
                                                            .INDENT_ARRAYS_WITH_INDICATOR)
                                            .build()),
                            false);

    private static ObjectMapper configure(ObjectMapper mapper, boolean strict) {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, strict);
        mapper.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        mapper.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, false);
        // Empty lists and maps are left out: shorter exports, and "empty" equals "not given".
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
        mapper.registerModule(enumModule());
        return mapper;
    }

    /** {@code LETS_ENCRYPT} is written as "lets-encrypt". */
    public static String enumName(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static SimpleModule enumModule() {
        SimpleModule module = new SimpleModule("iac-enums");
        module.addDeserializer(Secret.class, new SecretDeserializer());
        module.addSerializer(
                Enum.class,
                new StdSerializer<Enum>(Enum.class) {
                    @Override
                    public void serialize(Enum value, JsonGenerator gen, SerializerProvider p)
                            throws IOException {
                        gen.writeString(enumName(value));
                    }
                });
        module.setDeserializerModifier(
                new BeanDeserializerModifier() {
                    @Override
                    public JsonDeserializer<?> modifyEnumDeserializer(
                            DeserializationConfig config,
                            JavaType type,
                            BeanDescription beanDesc,
                            JsonDeserializer<?> deserializer) {
                        Class<? extends Enum> raw = (Class<? extends Enum>) type.getRawClass();
                        return new JsonDeserializer<Enum>() {
                            @Override
                            public Enum deserialize(JsonParser p, DeserializationContext ctxt)
                                    throws IOException {
                                String text = p.getValueAsString();
                                if (text == null) return null;
                                String norm =
                                        text.trim()
                                                .toUpperCase(Locale.ROOT)
                                                .replace('-', '_')
                                                .replace(' ', '_');
                                for (Enum c : raw.getEnumConstants()) {
                                    if (c.name().equals(norm)) return c;
                                }
                                throw ctxt.weirdStringException(
                                        text,
                                        raw,
                                        "use one of "
                                                + Arrays.stream(raw.getEnumConstants())
                                                        .map(c -> enumName((Enum<?>) c))
                                                        .collect(Collectors.joining(", ")));
                            }
                        };
                    }
                });
        return module;
    }
}
