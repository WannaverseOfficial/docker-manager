package com.wannaverse.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "email_logs",
        indexes = {
            @Index(name = "idx_email_recipient", columnList = "recipientEmail"),
            @Index(name = "idx_email_event_type", columnList = "eventType"),
            @Index(name = "idx_email_sent_at", columnList = "sentAt"),
            @Index(name = "idx_email_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
public class EmailLog {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String recipientEmail;

    @Column(length = 36)
    private String recipientUserId;

    @Column(nullable = false, length = 255)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailStatus status = EmailStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    @Column(length = 36)
    private String relatedResourceId;

    public enum EmailStatus {
        PENDING,
        SENT,
        FAILED
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
