package com.wannaverse.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "smtp_configuration")
@Getter
@Setter
@NoArgsConstructor
public class SmtpConfiguration {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(length = 255)
    private String username;

    @Column(columnDefinition = "TEXT")
    private String encryptedPassword;

    @Column(nullable = false, length = 255)
    private String fromAddress;

    @Column(length = 255)
    private String fromName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SecurityType securityType = SecurityType.STARTTLS;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    public enum SecurityType {
        NONE,
        STARTTLS,
        SSL_TLS
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

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
