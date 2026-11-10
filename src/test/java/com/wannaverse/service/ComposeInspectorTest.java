package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ComposeInspectorTest {
    private final ComposeInspector inspector = new ComposeInspector();

    private void denied(String compose) {
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> inspector.checkAllowedForNonAdmin(compose),
                        compose);
        assertEquals(403, e.getStatusCode().value());
    }

    @Test
    void summarisesServicesAndImages() {
        var s =
                inspector.summarize(
                        "services:\n  web:\n    image: nginx\n  db:\n    image: postgres:17\n");
        assertEquals(java.util.List.of("web", "db"), s.services());
        assertEquals(java.util.List.of("nginx", "postgres:17"), s.images());
    }

    @Test
    void rejectsMalformedFiles() {
        assertThrows(ResponseStatusException.class, () -> inspector.parse(""));
        assertThrows(ResponseStatusException.class, () -> inspector.parse("services: [a"));
        assertThrows(ResponseStatusException.class, () -> inspector.parse("version: '3'\n"));
    }

    @Test
    void ordinaryStacksAreAllowed() {
        inspector.checkAllowedForNonAdmin(
                "services:\n  web:\n    image: nginx\n    volumes:\n      - data:/data\n"
                        + "volumes:\n  data:\n");
    }

    @Test
    void hostAccessIsDenied() {
        denied("services:\n  a:\n    image: x\n    privileged: true\n");
        denied("services:\n  a:\n    image: x\n    network_mode: host\n");
        denied("services:\n  a:\n    image: x\n    pid: host\n");
        denied("services:\n  a:\n    image: x\n    cap_add: [SYS_ADMIN]\n");
        denied("services:\n  a:\n    image: x\n    devices: ['/dev/sda:/dev/sda']\n");
        denied("services:\n  a:\n    image: x\n    volumes:\n      - /:/host\n");
        denied("services:\n  a:\n    image: x\n    volumes:\n      - /var/run/docker.sock:/s\n");
        denied("services:\n  a:\n    image: x\n    volumes:\n      - ./data:/data\n");
        denied("services:\n  a:\n    image: x\n    volumes:\n      - ${HOME}:/h\n");
        denied(
                "services:\n  a:\n    image: x\n    volumes:\n      - type: bind\n"
                        + "        source: /\n        target: /h\n");
        denied(
                "services:\n  a:\n    image: x\nvolumes:\n  v:\n    driver_opts:\n"
                        + "      type: none\n      o: bind\n      device: /\n");
    }
}
