package com.wannaverse;

import static org.junit.jupiter.api.Assertions.*;

import com.wannaverse.service.GitService;
import com.wannaverse.service.StackService;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

class SecurityHelpersTest {

    @Test
    void gitUrlsMustBeRemote() {
        GitService.validateUrl("https://github.com/owner/repo.git");
        GitService.validateUrl("ssh://git@example.com/owner/repo.git");
        GitService.validateUrl("git@github.com:owner/repo.git");
        for (String bad :
                new String[] {
                    "file:///etc/passwd",
                    "/etc",
                    "../repo",
                    "ext::sh -c id",
                    "ssh://-oProxyCommand=x/r"
                }) {
            assertThrows(ResponseStatusException.class, () -> GitService.validateUrl(bad), bad);
        }
    }

    @Test
    void repositoryPathsCannotEscape() {
        Path repo = Path.of("/tmp/repo");
        assertEquals(
                repo.resolve("sub/Dockerfile").toAbsolutePath().normalize(),
                GitService.resolveInside(repo, "sub/Dockerfile", "Dockerfile"));
        assertThrows(
                IllegalArgumentException.class, () -> GitService.resolveInside(repo, "../x", "D"));
        assertThrows(
                IllegalArgumentException.class,
                () -> GitService.resolveInside(repo, "/etc/passwd", "D"));
    }

    @Test
    void projectNamesAreSafe() {
        assertEquals("my-app", StackService.projectNameFor("My App!"));
        assertEquals("repo", StackService.projectNameFor("!!!"));
        assertFalse(StackService.projectNameFor("../../etc").contains("/"));
    }
}
