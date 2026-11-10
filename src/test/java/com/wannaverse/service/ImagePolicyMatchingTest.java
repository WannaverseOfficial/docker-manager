package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ImagePolicyMatchingTest {

    @Test
    void dockerHubSpellingsNormaliseToOneName() {
        String canonical = "docker.io/library/nginx:latest";
        for (String ref :
                new String[] {
                    "nginx",
                    "nginx:latest",
                    "docker.io/nginx",
                    "library/nginx",
                    "index.docker.io/library/nginx",
                    "registry-1.docker.io/library/nginx:latest"
                }) {
            assertEquals(canonical, ImagePolicyService.normalizeImageName(ref), ref);
        }
    }

    @Test
    void privateRegistriesAreNotDockerHub() {
        assertEquals(
                "localhost/app:latest", ImagePolicyService.normalizeImageName("localhost/app"));
        assertEquals(
                "registry.example.com:5000/team/app:1.2",
                ImagePolicyService.normalizeImageName("registry.example.com:5000/team/app:1.2"));
        assertEquals(
                "ghcr.io/owner/app@sha256:abc",
                ImagePolicyService.normalizeImageName("ghcr.io/owner/app@sha256:abc"));
    }

    @Test
    void patternsAreWrittenLikeImages() {
        String nginx = ImagePolicyService.normalizeImageName("docker.io/nginx:1.29");
        assertTrue(ImagePolicyService.matchesPattern(nginx, "nginx"));
        assertTrue(ImagePolicyService.matchesPattern(nginx, "nginx*"));
        assertTrue(ImagePolicyService.matchesPattern(nginx, "docker.io/library/*"));
        assertTrue(ImagePolicyService.matchesPattern(nginx, "*"));
        assertFalse(ImagePolicyService.matchesPattern(nginx, "nginx:1.28"));
        assertFalse(ImagePolicyService.matchesPattern(nginx, "bitnami/*"));
        assertTrue(
                ImagePolicyService.matchesPattern(
                        ImagePolicyService.normalizeImageName("bitnami/redis"), "bitnami/*"));
    }

    @Test
    void regexCharactersInPatternsAreLiteral() {
        String img = ImagePolicyService.normalizeImageName("ghcr.io/a/b:1");
        assertFalse(ImagePolicyService.matchesPattern(img, "ghcr.io/(a|x)/b:1"));
        assertFalse(ImagePolicyService.matchesPattern(img, "ghcrXio/a/b:1"));
    }
}
