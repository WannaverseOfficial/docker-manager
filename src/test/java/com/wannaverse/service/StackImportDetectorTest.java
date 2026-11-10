package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class StackImportDetectorTest {

    @Test
    void findsVariablesButNotEscapedDollars() {
        String compose =
                "services:\n"
                        + "  db:\n"
                        + "    image: postgres:${PG_TAG:-17}\n"
                        + "    environment:\n"
                        + "      POSTGRES_PASSWORD: ${DB_PASS}\n"
                        + "      X: $$NOT_A_VAR\n"
                        + "      Y: $PLAIN\n";
        assertEquals(
                List.of("PG_TAG", "DB_PASS", "PLAIN"),
                List.copyOf(StackImportDetector.variables(compose)));
        assertEquals(Set.of("PG_TAG"), StackImportDetector.optionalVariables(compose));
    }

    @Test
    void recoversValuesFromWhatTheContainerReallyGot() {
        Map<String, String> values = new LinkedHashMap<>();
        StackImportDetector.match("${DB_PASS}", "s3cret", values);
        StackImportDetector.match(
                "postgres://${USER}:${PASS}@db:5432/app",
                "postgres://app:p@ss@db:5432/app",
                values);
        StackImportDetector.match("nginx:${TAG}", "nginx:1.29-alpine", values);
        StackImportDetector.match("no variables", "x", values);
        StackImportDetector.match("fixed-${X}-end", "different", values);
        assertEquals("s3cret", values.get("DB_PASS"));
        assertEquals("app", values.get("USER"));
        assertEquals("p@ss", values.get("PASS"));
        assertEquals("1.29-alpine", values.get("TAG"));
        assertFalse(values.containsKey("X"));
    }

    @Test
    void splitsLinuxAndWindowsPaths() {
        assertArrayEquals(
                new String[] {"/srv/app", "compose.yaml"},
                StackImportDetector.splitPath("/srv/app/compose.yaml"));
        assertArrayEquals(
                new String[] {"C:\\Users\\me\\app", "docker-compose.yml"},
                StackImportDetector.splitPath("C:\\Users\\me\\app\\docker-compose.yml"));
        assertArrayEquals(
                new String[] {"C:\\", "x.yml"}, StackImportDetector.splitPath("C:\\x.yml"));
    }

    @Test
    void quotesValuesThatNeedIt() {
        assertEquals("plain-value_1", StackImportDetector.quote("plain-value_1"));
        assertEquals("'two words'", StackImportDetector.quote("two words"));
        assertEquals("'it'\"'\"'s'", StackImportDetector.quote("it's"));
        assertEquals("'a$b'", StackImportDetector.quote("a$b"));
    }
}
