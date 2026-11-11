package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;

import com.wannaverse.config.SecretsBootstrap;

import org.junit.jupiter.api.Test;

class EncryptionUpgradeTest {

    @Test
    void valuesFrom1xDefaultsStayReadableAfterTheKeyChanges() {
        String published =
                new EncryptionService(SecretsBootstrap.PUBLISHED_ENCRYPTION_KEY)
                        .encrypt("registry-pass");
        String legacy =
                new EncryptionService(SecretsBootstrap.LEGACY_DEFAULT_ENCRYPTION_KEY)
                        .encrypt("git-token");
        EncryptionService now =
                new EncryptionService("a-brand-new-generated-key-that-is-long-and-random");
        assertEquals("registry-pass", now.decrypt(published));
        assertEquals("git-token", now.decrypt(legacy));
        assertEquals("smtp", now.decrypt(now.encrypt("smtp")));
    }

    @Test
    void aCustomKeyThatIsLostCannotBeGuessed() {
        String custom = new EncryptionService("my-own-1x-key-that-was-in-my-compose").encrypt("x");
        EncryptionService now =
                new EncryptionService("a-brand-new-generated-key-that-is-long-and-random");
        RuntimeException e = assertThrows(RuntimeException.class, () -> now.decrypt(custom));
        assertTrue(e.getMessage().contains("different ENCRYPTION_KEY"));
    }
}
