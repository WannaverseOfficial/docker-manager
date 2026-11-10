package com.wannaverse.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SecretsBootstrapTest {

    @Test
    void weakOrPublishedSecretsAreRejected() {
        assertFalse(SecretsBootstrap.isStrong(null));
        assertFalse(SecretsBootstrap.isStrong("short"));
        assertFalse(SecretsBootstrap.isStrong("change-this-access-secret-32chars"));
        assertFalse(SecretsBootstrap.isStrong("change-this-in-production-32chars"));
        assertFalse(SecretsBootstrap.isStrong("bI4pEE+5oVq9gkWyy/plIsRvaZ8ghj54access"));
        assertFalse(SecretsBootstrap.isStrong("bI4pEE+5oVq9gkWyy/plIsRvaZ8ghj54"));
    }

    @Test
    void randomSecretsAreAccepted() {
        assertTrue(SecretsBootstrap.isStrong("q9Zr0mYvE1tXk3pL7aW2sB8nC5dF4gH6jK0uI"));
    }
}
