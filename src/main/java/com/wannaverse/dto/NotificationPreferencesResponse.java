package com.wannaverse.dto;

import com.wannaverse.persistence.NotificationEventType;
import com.wannaverse.persistence.UserNotificationPreference;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreferencesResponse {

    private String userId;
    private Map<String, Boolean> preferences;

    public static NotificationPreferencesResponse fromEntities(
            String userId, List<UserNotificationPreference> prefs) {

        Map<String, Boolean> map = new LinkedHashMap<>();
        for (NotificationEventType type : NotificationEventType.values()) {
            map.put(type.name(), true);
        }

        for (UserNotificationPreference pref : prefs) {
            map.put(pref.getEventType().name(), pref.isEmailEnabled());
        }

        return NotificationPreferencesResponse.builder().userId(userId).preferences(map).build();
    }
}
