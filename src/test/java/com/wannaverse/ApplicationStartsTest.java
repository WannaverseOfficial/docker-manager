package com.wannaverse;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Starts the whole application against an empty database, so wiring mistakes (a missing bean, a
 * misplaced @Scheduled) fail the build instead of a deployment. No Docker daemon is needed: hosts
 * that can't be reached are skipped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationStartsTest {
    private static final Path DIR = createDir();

    private static Path createDir() {
        try {
            return Files.createTempDirectory("dm-start-test");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:sqlite:" + DIR.resolve("data.db"));
        r.add("app.data-dir", DIR::toString);
        r.add("app.stacks-dir", () -> DIR.resolve("stacks").toString());
        r.add("app.git.clone-dir", () -> DIR.resolve("git").toString());
        r.add("app.encryption.key", () -> "test-encryption-key-that-is-long-enough-123");
        r.add("app.jwt.access-secret", () -> "test-access-secret-that-is-long-enough-1234567");
        r.add("app.jwt.refresh-secret", () -> "test-refresh-secret-that-is-long-enough-123456");
    }

    @Autowired ApplicationContext context;

    @Test
    void contextStarts() {
        assertNotNull(context.getBean(com.wannaverse.service.NginxApplier.class));
        assertNotNull(context.getBean(com.wannaverse.service.StackService.class));
    }
}
