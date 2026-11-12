package com.wannaverse.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class RolloutChecksTest {
    @Test
    void parsesGrowingCanarySteps() {
        assertEquals(List.of(10, 25, 50), RolloutChecks.parseSteps("10, 25,50"));
        assertEquals(List.of(5, 50), RolloutChecks.parseSteps("5% 50%"));
        assertTrue(RolloutChecks.parseSteps("").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RolloutChecks.parseSteps("50,25"));
        assertThrows(IllegalArgumentException.class, () -> RolloutChecks.parseSteps("10,100"));
        assertThrows(IllegalArgumentException.class, () -> RolloutChecks.parseSteps("0"));
        assertThrows(IllegalArgumentException.class, () -> RolloutChecks.parseSteps("ten"));
    }

    @Test
    void matchesExpectedStatuses() {
        assertTrue(RolloutChecks.statusMatches(200, null));
        assertTrue(RolloutChecks.statusMatches(302, ""));
        assertFalse(RolloutChecks.statusMatches(404, ""));
        assertTrue(RolloutChecks.statusMatches(204, "2xx"));
        assertTrue(RolloutChecks.statusMatches(404, "200, 404"));
        assertTrue(RolloutChecks.statusMatches(250, "200-299"));
        assertFalse(RolloutChecks.statusMatches(500, "2xx,3xx"));
        assertTrue(RolloutChecks.validStatusList("2xx, 404,200-299"));
        assertFalse(RolloutChecks.validStatusList("ok"));
        assertFalse(RolloutChecks.validStatusList("2xx;rm"));
    }

    @Test
    void readsTheLastStatusFromWget() {
        String out =
                "  HTTP/1.1 301 Moved Permanently\n"
                        + "  Location: /x\n"
                        + "  HTTP/1.1 200 OK\n"
                        + "  Server: x\n";
        assertEquals(200, RolloutChecks.lastStatus(out));
        assertEquals(
                404,
                RolloutChecks.lastStatus(
                        "  HTTP/1.1 404 Not Found\nwget: server returned error: HTTP/1.1 404 Not"
                                + " Found"));
        assertEquals(
                0,
                RolloutChecks.lastStatus(
                        "wget: can't connect to remote host (172.18.0.3): Connection refused"));
    }

    @Test
    void parsesTrafficAndJudgesIt() {
        Map<String, RolloutChecks.Traffic> t =
                RolloutChecks.parseTraffic("app-green 40 4 2.0000\napp-blue 160 1 8.0000\njunk\n");
        assertEquals(40, t.get("app-green").requests());
        assertEquals(10.0, t.get("app-green").errorRate(), 0.001);
        assertEquals(50, t.get("app-green").avgMillis());
        assertNotNull(RolloutChecks.judge(t.get("app-green"), 5, 0, 20));
        assertNull(RolloutChecks.judge(t.get("app-green"), 5, 0, 100), "too few requests yet");
        assertNull(RolloutChecks.judge(t.get("app-blue"), 5, 0, 20));
        assertNotNull(RolloutChecks.judge(t.get("app-blue"), 5, 40, 20), "slower than 40 ms");
    }

    @Test
    void safePathsOnly() {
        assertTrue(RolloutChecks.safePath("/health?full=1"));
        assertFalse(RolloutChecks.safePath("/a b"));
        assertFalse(RolloutChecks.safePath("/\"x"));
        assertFalse(RolloutChecks.safePath("/$host"));
        assertFalse(RolloutChecks.safePath("health"));
    }
}
