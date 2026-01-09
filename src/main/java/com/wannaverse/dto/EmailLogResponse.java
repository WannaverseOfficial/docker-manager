package com.wannaverse.dto;

import com.wannaverse.persistence.EmailLog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailLogResponse {

    private String id;
    private String recipientEmail;
    private String recipientUserId;
    private String subject;
    private String eventType;
    private String status;
    private String errorMessage;
    private long createdAt;
    private Long sentAt;
    private String relatedResourceId;

    public static EmailLogResponse fromEntity(EmailLog log) {
        return EmailLogResponse.builder()
                .id(log.getId())
                .recipientEmail(log.getRecipientEmail())
                .recipientUserId(log.getRecipientUserId())
                .subject(log.getSubject())
                .eventType(log.getEventType().name())
                .status(log.getStatus().name())
                .errorMessage(log.getErrorMessage())
                .createdAt(log.getCreatedAt().toEpochMilli())
                .sentAt(log.getSentAt() != null ? log.getSentAt().toEpochMilli() : null)
                .relatedResourceId(log.getRelatedResourceId())
                .build();
    }
}
