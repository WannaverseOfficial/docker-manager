package com.wannaverse.security;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down password guessing. Failed sign-ins are counted per client address and username, per
 * client address, and per username, each with its own allowance. Past the allowance, attempts are
 * refused for a lock-out period that doubles with each further failure (capped at 15 minutes).
 *
 * <p>The per-username allowance is large so that someone else cannot lock an account out with a few
 * wrong passwords, while guessing one account's password from many addresses is still slowed down.
 * A successful sign-in clears that address's counters.
 */
@Component
public class LoginRateLimiter {
    private static final int PAIR_FREE_ATTEMPTS = 5;
    private static final int IP_FREE_ATTEMPTS = 20;
    private static final int USER_FREE_ATTEMPTS = 50;
    private static final long BASE_LOCK_MS = 30_000;
    private static final long MAX_LOCK_MS = 15 * 60_000;
    private static final long FORGET_AFTER_MS = 60 * 60_000;
    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private static final class Entry {
        int failures;
        long lockedUntil;
        long lastFailure;
    }

    /** Milliseconds the caller must wait before trying again, or 0 when allowed. */
    public long retryAfterMs(String ip, String username) {
        long now = System.currentTimeMillis();
        cleanup(now);
        return Math.max(
                wait(pair(ip, username), now),
                Math.max(wait(key("ip", ip), now), wait(key("user", username), now)));
    }

    public void recordFailure(String ip, String username) {
        long now = System.currentTimeMillis();
        fail(pair(ip, username), PAIR_FREE_ATTEMPTS, now);
        fail(key("ip", ip), IP_FREE_ATTEMPTS, now);
        fail(key("user", username), USER_FREE_ATTEMPTS, now);
    }

    public void recordSuccess(String ip, String username) {
        entries.remove(pair(ip, username));
        entries.remove(key("ip", ip));
    }

    private long wait(String key, long now) {
        Entry e = entries.get(key);
        return e == null ? 0 : Math.max(0, e.lockedUntil - now);
    }

    private void fail(String key, int freeAttempts, long now) {
        entries.compute(
                key,
                (k, e) -> {
                    if (e == null) e = new Entry();
                    e.failures++;
                    e.lastFailure = now;
                    if (e.failures >= freeAttempts) {
                        int over = Math.min(e.failures - freeAttempts, 10);
                        e.lockedUntil = now + Math.min(MAX_LOCK_MS, BASE_LOCK_MS << over);
                    }
                    return e;
                });
    }

    private void cleanup(long now) {
        entries.values()
                .removeIf(e -> now - e.lastFailure > FORGET_AFTER_MS && e.lockedUntil <= now);
        if (entries.size() > MAX_ENTRIES) {
            // Under a flood of distinct keys, drop counters that are not locking anyone out. A
            // lock is never lifted early this way.
            entries.values().removeIf(e -> e.lockedUntil <= now);
        }
    }

    private static String pair(String ip, String username) {
        return key("pair", (ip == null ? "" : ip) + "|" + (username == null ? "" : username));
    }

    private static String key(String kind, String value) {
        return kind + ":" + (value == null ? "" : value.toLowerCase(Locale.ROOT));
    }
}
