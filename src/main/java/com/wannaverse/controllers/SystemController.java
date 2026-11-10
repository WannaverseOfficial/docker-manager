package com.wannaverse.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Unauthenticated liveness endpoint for container health checks. (The image's health check used to
 * POST-probe /api/auth/login with GET, which returns an error status, so the container was never
 * reported healthy.)
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        String version = SystemController.class.getPackage().getImplementationVersion();
        return ResponseEntity.ok(Map.of("ok", true, "version", version == null ? "dev" : version));
    }
}
