package com.wannaverse.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The configuration document Docker Manager last applied (one row). Kept so the configuration page
 * can show it, and so the background check can keep things in line with it.
 */
@Entity
@Table(name = "config_state_t")
@Getter
@Setter
@NoArgsConstructor
public class ConfigState {
    public static final String ID = "current";

    @Id
    @Column(length = 20)
    private String id = ID;

    /** FILE (read from DM_CONFIG_FILE) or UI (pasted or uploaded by an administrator). */
    @Column(length = 10)
    private String source;

    /** The document as written, before ${...} references are filled in. Encrypted. */
    @Column(columnDefinition = "TEXT")
    private String encryptedDocument;

    @Column(length = 64)
    private String documentHash;

    private long appliedAt;

    @Column(length = 100)
    private String appliedBy;

    /** RUNNING, SUCCEEDED, PARTIAL or FAILED. */
    @Column(length = 20)
    private String lastStatus;

    @Column(columnDefinition = "TEXT")
    private String lastRunJson;
}
