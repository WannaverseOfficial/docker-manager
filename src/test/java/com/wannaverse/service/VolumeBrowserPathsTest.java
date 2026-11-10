package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

class VolumeBrowserPathsTest {

    @Test
    void pathsStayInsideTheVolume() {
        assertEquals("/", VolumeBrowserService.normalize(null));
        assertEquals("/", VolumeBrowserService.normalize("/"));
        assertEquals("/a/b", VolumeBrowserService.normalize("a//b/./"));
        assertEquals("/a/b", VolumeBrowserService.normalize("/a/b"));
        assertEquals("/a/b", VolumeBrowserService.normalize("\\a\\b"));
        String nul = "x" + (char) 0 + "y";
        for (String bad : new String[] {"../etc", "/a/../../x", "a/..", nul, "a\nb"}) {
            assertThrows(
                    ResponseStatusException.class, () -> VolumeBrowserService.normalize(bad), bad);
        }
    }

    @Test
    void binaryFilesAreRecognised() {
        assertFalse(
                VolumeBrowserService.looksBinary(
                        "hello\nworld\t!".getBytes(StandardCharsets.UTF_8)));
        assertTrue(VolumeBrowserService.looksBinary(new byte[] {0x50, 0x4b, 0x03, 0x04, 0x00}));
        assertFalse(VolumeBrowserService.looksBinary(new byte[0]));
    }
}
