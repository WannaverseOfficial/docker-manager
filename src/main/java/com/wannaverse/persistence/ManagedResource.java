package com.wannaverse.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Something the configuration file created or took over. Docker Manager deletes it when its entry
 * is removed from the file, and the UI does not let people change it by hand. Anything without a
 * record here was made by hand and is never touched by applying the file.
 */
@Entity
@Table(
        name = "config_managed_t",
        uniqueConstraints = @UniqueConstraint(columnNames = {"section", "entryKey"}),
        indexes = @Index(name = "idx_config_managed_target", columnList = "section, targetId"))
@Getter
@Setter
@NoArgsConstructor
public class ManagedResource {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** The file's section, such as "users" or "ingress.routes". */
    @Column(nullable = false, length = 100)
    private String section;

    /** The entry's key in that section ("alice", or "web-01/app" for nested entries). */
    @Column(nullable = false, length = 300)
    private String entryKey;

    /** What it is in Docker Manager: a database id, or a name for Docker objects. */
    @Column(nullable = false, length = 300)
    private String targetId;

    /** The Docker host it lives on, if any. */
    @Column(length = 36)
    private String hostId;

    /** A short description for lists, such as the user name or "app.example.com". */
    @Column(length = 300)
    private String label;

    /** The entry as last applied, as JSON with secrets replaced by keyed hashes. */
    @Column(columnDefinition = "TEXT")
    private String appliedSpec;

    private long appliedAt;
}
