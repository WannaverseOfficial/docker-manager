package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;

import com.wannaverse.persistence.NotificationEventType;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;

class EmailTemplateTest {
    private final EmailTemplateService templates = new EmailTemplateService();

    {
        ReflectionTestUtils.setField(templates, "baseUrl", "https://dm.example.com/");
    }

    @Test
    void everyEventRendersHtmlAndText() {
        for (NotificationEventType type : NotificationEventType.values()) {
            var email =
                    templates.render(
                            type,
                            Map.of(
                                    "repoName", "shop",
                                    "repoId", "r1",
                                    "containerName", "web",
                                    "containerId", "abc",
                                    "hostName", "web-01",
                                    "errorMessage", "exit 1",
                                    "timestamp", Instant.now()));
            assertTrue(email.html().startsWith("<!DOCTYPE html>"), type.name());
            assertFalse(email.text().contains("<"), type.name());
            assertTrue(email.text().contains("https://dm.example.com/"), type.name());
            assertFalse(templates.getSubject(type, Map.of()).isBlank());
        }
    }

    @Test
    void valuesAreEscapedAndLinksPointAtTheNewPages() {
        var email =
                templates.render(
                        NotificationEventType.CONTAINER_STOPPED_UNEXPECTEDLY,
                        Map.of("containerName", "<script>x</script>", "containerId", "abc"));
        assertFalse(email.html().contains("<script>x"));
        assertTrue(email.html().contains("&lt;script&gt;"));
        assertTrue(email.html().contains("https://dm.example.com/containers/view/?id=abc"));
        // The plain-text part is not HTML, so it is not escaped.
        assertTrue(email.text().contains("<script>x</script>"));
    }
}
