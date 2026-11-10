package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A Docker Compose project managed by Docker Manager. The compose file and .env are kept here and
 * on disk (STACKS_DIR/&lt;host&gt;/&lt;name&gt;/), so the stack can be edited, redeployed, stopped
 * and removed at any time after it was created.
 */
@Data
@NoArgsConstructor
@Entity
@Table(
        name = "stacks_t",
        uniqueConstraints = @UniqueConstraint(columnNames = {"dockerHostId", "name"}))
public class Stack {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String dockerHostId;

    /** The compose project name. */
    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String composeContent;

    @Column(columnDefinition = "TEXT")
    private String envContent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.UI;

    /** Set when the stack is deployed from a git repository. */
    private String gitRepositoryId;

    /**
     * For stacks imported from a folder on the Docker host: that folder, so relative paths in the
     * compose file (./data) keep pointing at the same files. Null for stacks created here.
     */
    @Column(length = 1024)
    private String projectDir;

    /** Template it was created from, for display. */
    private String templateName;

    private String lastError;

    private String createdBy;

    private long createdAt;

    private long updatedAt;

    private Long lastDeployedAt;

    public enum Source {
        UI,
        TEMPLATE,
        GIT,
        IMPORTED
    }

    @PrePersist
    void onCreate() {
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = System.currentTimeMillis();
    }
}
