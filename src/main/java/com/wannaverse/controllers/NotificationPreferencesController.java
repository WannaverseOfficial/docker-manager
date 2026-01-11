package com.wannaverse.controllers;

import com.wannaverse.dto.NotificationPreferencesResponse;
import com.wannaverse.persistence.NotificationEventType;
import com.wannaverse.persistence.Resource;
import com.wannaverse.persistence.UserNotificationPreference;
import com.wannaverse.persistence.UserNotificationPreferenceRepository;
import com.wannaverse.persistence.UserRepository;
import com.wannaverse.security.RequirePermission;
import com.wannaverse.security.SecurityContext;
import com.wannaverse.security.SecurityContextHolder;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/notifications")
public class NotificationPreferencesController {

    private final UserNotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

    public NotificationPreferencesController(
            UserNotificationPreferenceRepository preferenceRepository,
            UserRepository userRepository) {
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
    }

    private String getCurrentUserId() {
        SecurityContext ctx = SecurityContextHolder.getContext();
        if (ctx == null || ctx.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return ctx.getUserId();
    }

    @GetMapping("/preferences")
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "read")
    public ResponseEntity<NotificationPreferencesResponse> getPreferences() {
        String userId = getCurrentUserId();

        List<UserNotificationPreference> prefs = preferenceRepository.findByUserId(userId);

        return ResponseEntity.ok(NotificationPreferencesResponse.fromEntities(userId, prefs));
    }

    @PutMapping("/preferences")
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "manage")
    public ResponseEntity<NotificationPreferencesResponse> updatePreferences(
            @RequestBody Map<String, Boolean> preferences) {
        String userId = getCurrentUserId();

        var user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "User not found"));

        List<UserNotificationPreference> updatedPrefs = new ArrayList<>();

        for (Map.Entry<String, Boolean> entry : preferences.entrySet()) {
            try {
                NotificationEventType eventType = NotificationEventType.valueOf(entry.getKey());

                UserNotificationPreference pref =
                        preferenceRepository
                                .findByUserIdAndEventType(userId, eventType)
                                .orElseGet(
                                        () -> {
                                            UserNotificationPreference newPref =
                                                    new UserNotificationPreference();
                                            newPref.setUser(user);
                                            newPref.setEventType(eventType);
                                            return newPref;
                                        });

                pref.setEmailEnabled(entry.getValue());
                updatedPrefs.add(preferenceRepository.save(pref));

            } catch (IllegalArgumentException e) {
            }
        }

        List<UserNotificationPreference> allPrefs = preferenceRepository.findByUserId(userId);
        return ResponseEntity.ok(NotificationPreferencesResponse.fromEntities(userId, allPrefs));
    }

    @GetMapping("/available-events")
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "read")
    public ResponseEntity<List<Map<String, Object>>> getAvailableEvents() {
        List<Map<String, Object>> events =
                Arrays.stream(NotificationEventType.values())
                        .map(
                                type ->
                                        Map.<String, Object>of(
                                                "name", type.name(),
                                                "displayName", type.getDisplayName(),
                                                "category", type.getCategory()))
                        .collect(Collectors.toList());

        return ResponseEntity.ok(events);
    }

    @GetMapping("/categories")
    @RequirePermission(resource = Resource.NOTIFICATIONS, action = "read")
    public ResponseEntity<List<String>> getCategories() {
        List<String> categories =
                Arrays.stream(NotificationEventType.values())
                        .map(NotificationEventType::getCategory)
                        .distinct()
                        .collect(Collectors.toList());

        return ResponseEntity.ok(categories);
    }
}
