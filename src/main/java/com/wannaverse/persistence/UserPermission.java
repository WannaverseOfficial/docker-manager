package com.wannaverse.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "user_permissions",
        uniqueConstraints =
                @UniqueConstraint(
                        columnNames = {
                            "user_id",
                            "resource",
                            "action",
                            "scope_host_id",
                            "scope_resource_id"
                        }))
@Getter
@Setter
@NoArgsConstructor
public class UserPermission {

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Resource resource;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "scope_host_id", length = 36)
    private String scopeHostId;

    @Column(name = "scope_resource_id", length = 100)
    private String scopeResourceId;

    @Column(nullable = false)
    private Instant createdAt;

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
