package com.wannaverse.iac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wannaverse.persistence.ManagedResource;

import lombok.Data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class ConfigParserTest {

    enum Mode {
        LETS_ENCRYPT,
        NONE
    }

    @Data
    static class ThingSpec {
        @Doc(value = "required", required = true)
        private String image;

        private Integer port;
        private Secret password;
        private Mode mode;
        private List<String> tags = new ArrayList<>();
        private Long memoryMb;
    }

    @Data
    static class ChildSpec {
        private String hostname;
    }

    /** A handler that only describes its section; parsing never calls the rest. */
    record Fake<S>(
            String section, Class<S> specType, String parent, String field, boolean singleton)
            implements ResourceHandler<S> {
        @Override
        public int order() {
            return 0;
        }

        @Override
        public String description() {
            return section;
        }

        @Override
        public String parentSection() {
            return parent;
        }

        @Override
        public String parentField() {
            return field;
        }

        @Override
        public Optional<Target> findExisting(Entry<S> e, ApplyContext c) {
            return Optional.empty();
        }

        @Override
        public boolean exists(ManagedResource r, ApplyContext c) {
            return true;
        }

        @Override
        public Target create(Entry<S> e, ApplyContext c) {
            return null;
        }

        @Override
        public Target update(Entry<S> e, Changes ch, ManagedResource r, ApplyContext c) {
            return null;
        }

        @Override
        public void delete(ManagedResource r, ApplyContext c) {}

        @Override
        public Map<String, S> export(ExportContext c) {
            return Map.of();
        }
    }

    private final ConfigParser parser =
            new ConfigParser(
                    List.of(
                            new Fake<>("things", ThingSpec.class, null, null, false),
                            new Fake<>(
                                    "things.children",
                                    ChildSpec.class,
                                    "things",
                                    "children",
                                    false),
                            new Fake<>("single", ChildSpec.class, null, null, true)));

    private ConfigDocument parse(String yaml, Map<String, String> env) {
        return parser.parse(yaml, new Interpolator(env::get, null, null));
    }

    @Test
    void readsEntriesNestedSectionsAndSingletons() {
        ConfigDocument doc =
                parse(
                        """
                        version: 1
                        x-common: &common
                          image: nginx
                        things:
                          web:
                            <<: *common
                            port: "8080"
                            mode: lets-encrypt
                            password: ${env:PW}
                            children:
                              a:
                                hostname: a.example.com
                          empty-ok:
                            image: redis
                            tags: []
                        single:
                          hostname: mail
                        """,
                        Map.of("PW", "hunter22"));
        assertEquals(List.of(), doc.problems());
        ThingSpec web = (ThingSpec) doc.entries("things").get("web").spec();
        assertEquals("nginx", web.getImage());
        assertEquals(8080, web.getPort());
        assertEquals(Mode.LETS_ENCRYPT, web.getMode());
        assertEquals("hunter22", web.getPassword().value());
        var child = doc.entries("things.children").get("web/a");
        assertNotNull(child);
        assertEquals("web", child.parentKey());
        assertEquals("a", child.localKey());
        assertEquals(
                "mail", ((ChildSpec) doc.entries("single").get("default").spec()).getHostname());
    }

    @Test
    void reportsProblemsInsteadOfGuessing() {
        ConfigDocument doc =
                parse(
                        """
                        version: 1
                        thingz: {}
                        things:
                          a:
                            image: x
                            imgae: typo
                          b:
                            port: 1
                          c:
                            image: x
                            mode: sometimes
                          d:
                            image: x
                            password: ${env:MISSING}
                          e:
                            image: x
                            tags: notalist
                        """,
                        Map.of());
        String all = String.join("\n", doc.problems());
        assertTrue(all.contains("Unknown section \"thingz\""), all);
        assertTrue(all.contains("things.a.imgae: unknown setting \"imgae\""), all);
        assertTrue(all.contains("things.b: missing image"), all);
        assertTrue(
                all.contains("things.c.mode: not a valid value; use one of lets-encrypt, none"),
                all);
        assertFalse(all.contains("sometimes"), "values are never repeated: " + all);
        assertTrue(
                all.contains("things.d.password: the environment variable MISSING is not set"),
                all);
        assertTrue(all.contains("things.e.tags: expected a list"), all);
    }

    @Test
    void readsPlainValuesTheYaml12Way() {
        var tree =
                YamlReader.read(
                        """
                        restart: no
                        ports: [8080:80, 0755]
                        when: 2026-01-02
                        on: yes
                        n: 12
                        f: 1.5
                        """);
        assertEquals("no", tree.get("restart").asText());
        assertTrue(tree.get("restart").isTextual());
        assertEquals("8080:80", tree.get("ports").get(0).asText());
        assertEquals("0755", tree.get("ports").get(1).asText());
        assertTrue(tree.get("when").isTextual());
        assertTrue(tree.get("on").isTextual());
        assertEquals(12, tree.get("n").asInt());
        assertEquals(1.5, tree.get("f").asDouble());
    }

    @Test
    void requiresAKnownVersion() {
        assertTrue(parse("things: {}", Map.of()).problems().get(0).contains("version: 1"));
        assertTrue(parse("version: 7", Map.of()).problems().get(0).contains("not supported"));
        assertTrue(parse("version: [", Map.of()).problems().get(0).startsWith("Line "));
        List<String> dup = parse("version: 1\nversion: 1", Map.of()).problems();
        assertTrue(dup.get(0).contains("duplicate"), dup.toString());
        // An empty file is an error, never "delete everything".
        assertFalse(parse("", Map.of()).problems().isEmpty());
    }

    @Test
    void interpolatesEnvFilesDefaultsAndEscapes(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("secret.txt"), "from-file\n");
        Path data = Files.createDirectories(dir.resolve("data"));
        Files.writeString(data.resolve("keys"), "nope");
        Interpolator in = new Interpolator(Map.of("A", "1")::get, dir, data);
        ObjectNode node = IacJson.YAML.createObjectNode();
        node.put("env", "x${env:A}y");
        node.put("fallback", "${env:B:-two}");
        node.put("file", "${file:secret.txt}");
        node.put("escaped", "$${HOME}");
        node.put("forbidden", "${env:ENCRYPTION_KEY}");
        node.put("datadir", "${file:data/keys}");
        List<String> problems = new ArrayList<>();
        in.apply(node, "", problems);
        assertEquals("x1y", node.get("env").asText());
        assertEquals("two", node.get("fallback").asText());
        assertEquals("from-file", node.get("file").asText());
        assertEquals("${HOME}", node.get("escaped").asText());
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("forbidden:"));
        assertTrue(problems.get(1).startsWith("datadir:"));
    }

    @Test
    void diffNamesChangedFieldsAndSecretsChangeTheirHash() {
        Secret.setHashKey("k");
        ThingSpec a = new ThingSpec();
        a.setImage("nginx");
        a.setPassword(Secret.of("one"));
        ObjectNode applied = IacJson.JSON.valueToTree(a);
        assertFalse(applied.toString().contains("one"), "secrets are never stored in clear");

        ThingSpec b = new ThingSpec();
        b.setImage("nginx");
        b.setPassword(Secret.of("one"));
        assertEquals(Set.of(), ConfigEngine.diff(applied.toString(), IacJson.JSON.valueToTree(b)));

        b.setPassword(Secret.of("two"));
        b.setPort(80);
        assertEquals(
                Set.of("password", "port"),
                ConfigEngine.diff(applied.toString(), IacJson.JSON.valueToTree(b)));
    }

    @Test
    void storedSpecsCompareEqualAcrossNumberTypes() {
        ThingSpec a = new ThingSpec();
        a.setImage("x");
        a.setMemoryMb(512L);
        String stored = IacJson.JSON.valueToTree(a).toString();
        // A Long becomes an IntNode when read back; it must still count as unchanged.
        assertEquals(Set.of(), ConfigEngine.diff(stored, IacJson.JSON.valueToTree(a)));
    }

    @Test
    void schemaDescribesSectionsNestedEntriesAndEnums() {
        Map<String, Object> schema =
                SchemaGenerator.generate(
                        List.of(
                                new Fake<>("things", ThingSpec.class, null, null, false),
                                new Fake<>(
                                        "things.children",
                                        ChildSpec.class,
                                        "things",
                                        "children",
                                        false)));
        String json = schema.toString();
        assertTrue(json.contains("children"), json);
        assertTrue(json.contains("lets-encrypt"), json);
        assertTrue(json.contains("required=[image]"), json);
    }
}
