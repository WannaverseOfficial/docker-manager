package com.wannaverse.security;

import com.wannaverse.persistence.UserRepository;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The current admin and enabled flags of each user, read from the database and cached for a few
 * seconds. Access tokens carry these flags too, but trusting the token alone would let a user who
 * was just demoted, disabled or deleted keep their rights until the token expires.
 */
@Component
public class UserStatusCache {
    private static final long TTL_MS = 5_000;

    public record Status(
            boolean exists, boolean enabled, boolean admin, boolean mustChangePassword) {}

    private record Entry(Status status, long at) {}

    private final UserRepository userRepository;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public UserStatusCache(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Status get(String userId) {
        long now = System.currentTimeMillis();
        Entry e = cache.get(userId);
        if (e != null && now - e.at() < TTL_MS) {
            return e.status();
        }
        Status status =
                userRepository
                        .findById(userId)
                        .map(
                                u ->
                                        new Status(
                                                true,
                                                u.isEnabled(),
                                                u.isAdmin(),
                                                u.isMustChangePassword()))
                        .orElse(new Status(false, false, false, false));
        if (cache.size() > 5_000) cache.clear();
        cache.put(userId, new Entry(status, now));
        return status;
    }

    /** Call after changing a user so the change applies to their very next request. */
    public void evict(String userId) {
        if (userId == null) cache.clear();
        else cache.remove(userId);
    }
}
