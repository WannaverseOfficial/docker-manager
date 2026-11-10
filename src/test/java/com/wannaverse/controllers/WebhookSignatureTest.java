package com.wannaverse.controllers;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

class WebhookSignatureTest {
    private static final String SECRET = "s3cret-from-url";
    private static final String BODY = "{\"ref\":\"refs/heads/main\"}";

    private static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void githubSignatureIsVerified() throws Exception {
        assertTrue(
                GitController.signatureValid(
                        SECRET,
                        BODY,
                        Map.of("X-Hub-Signature-256", "sha256=" + hmac(SECRET, BODY))));
        assertFalse(
                GitController.signatureValid(
                        SECRET,
                        BODY,
                        Map.of("X-Hub-Signature-256", "sha256=" + hmac("other", BODY))));
        assertFalse(
                GitController.signatureValid(
                        SECRET,
                        BODY + " ",
                        Map.of("x-hub-signature-256", "sha256=" + hmac(SECRET, BODY))));
    }

    @Test
    void giteaAndGitlabAreVerified() throws Exception {
        assertTrue(
                GitController.signatureValid(
                        SECRET, BODY, Map.of("X-Gitea-Signature", hmac(SECRET, BODY))));
        assertTrue(GitController.signatureValid(SECRET, BODY, Map.of("X-Gitlab-Token", SECRET)));
        assertFalse(GitController.signatureValid(SECRET, BODY, Map.of("X-Gitlab-Token", "nope")));
    }

    @Test
    void unsignedRequestsRelyOnTheSecretUrl() {
        assertTrue(GitController.signatureValid(SECRET, BODY, Map.of()));
    }
}
