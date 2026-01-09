package com.wannaverse.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserNotificationPreferenceRepository
        extends JpaRepository<UserNotificationPreference, String> {

    List<UserNotificationPreference> findByUserId(String userId);

    Optional<UserNotificationPreference> findByUserIdAndEventType(
            String userId, NotificationEventType eventType);

    void deleteByUserId(String userId);

    @Query(
            "SELECT p FROM UserNotificationPreference p "
                    + "WHERE p.eventType = :eventType AND p.emailEnabled = true")
    List<UserNotificationPreference> findEnabledForEventType(
            @Param("eventType") NotificationEventType eventType);

    @Query(
            "SELECT p FROM UserNotificationPreference p JOIN FETCH p.user u WHERE p.eventType ="
                    + " :eventType AND p.emailEnabled = true AND u.email IS NOT NULL")
    List<UserNotificationPreference> findEnabledWithEmailForEventType(
            @Param("eventType") NotificationEventType eventType);
}
