package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;

import com.wannaverse.persistence.Stack;

import org.junit.jupiter.api.Test;

import java.util.List;

class StackDeploySafetyTest {

    private static Stack stack(Stack.Source source, String compose, String env) {
        Stack s = new Stack();
        s.setName("app");
        s.setSource(source);
        s.setComposeContent(compose);
        s.setEnvContent(env);
        return s;
    }

    @Test
    void importedStacksNeverRemoveOrphansOrRebuild() {
        assertEquals(
                List.of("up", "-d"), StackService.upArgs(stack(Stack.Source.IMPORTED, "", null)));
        assertTrue(
                StackService.upArgs(stack(Stack.Source.UI, "", null)).contains("--remove-orphans"));
    }

    @Test
    void deployingWithBlankVariablesIsRefused() {
        String compose =
                "services:\n"
                        + "  db:\n"
                        + "    # uses $NOT_INTERPOLATED in a comment\n"
                        + "    image: postgres:${TAG:-17}\n"
                        + "    environment:\n"
                        + "      POSTGRES_PASSWORD: ${DB_PASS}\n"
                        + "      POSTGRES_USER: ${DB_USER}\n";
        // The detector leaves variables it couldn't find commented out.
        String env = "DB_USER=app\n# DB_PASS=\n";
        assertEquals(
                List.of("DB_PASS"),
                StackService.unsetVariables(stack(Stack.Source.IMPORTED, compose, env)));
        // An explicit empty value is a choice, not a mistake.
        assertEquals(
                List.of(),
                StackService.unsetVariables(
                        stack(Stack.Source.UI, compose, "DB_USER=a\nDB_PASS=\n")));
        assertEquals(
                List.of("DB_PASS", "DB_USER"),
                StackService.unsetVariables(stack(Stack.Source.UI, compose, null)));
    }
}
