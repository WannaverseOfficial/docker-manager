package com.wannaverse.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One backup file kept by Docker Manager: a whole server (pg_dumpall, gzipped SQL) or a single
 * database (pg_dump custom format, or an uploaded plain SQL file).
 */
@Entity
@Table(name = "database_backups_t")
@Getter
@Setter
@NoArgsConstructor
public class DatabaseBackup {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String serverId;

    /**
     * Host and name of the server, so backups kept after a server is removed come back when a
     * server of the same name is created there again.
     */
    private String dockerHostId;

    private String serverName;

    /** SERVER (every database and user) or DATABASE (one database). */
    @Column(nullable = false, length = 10)
    private String scope;

    /** For a DATABASE backup: which database it came from. */
    private String databaseName;

    /** SQL_GZ (plain SQL, gzipped) or CUSTOM (pg_dump -Fc). */
    @Column(nullable = false, length = 10)
    private String format;

    /** MANUAL, SCHEDULED, BEFORE_RESTORE, UPLOAD or IMPORT. */
    @Column(nullable = false, length = 20)
    private String origin;

    /** Free text, e.g. where an import came from. */
    private String note;

    /** RUNNING, DONE or FAILED. */
    @Column(nullable = false, length = 10)
    private String status;

    @JsonIgnore private String fileName;

    private long sizeBytes;

    private long createdAt;

    private long finishedAt;

    private String createdBy;

    @Column(length = 2000)
    private String error;

    /** Key of the copy in S3-compatible storage, when one was made. */
    private String s3Key;

    /** null (no copy wanted), DONE or FAILED. */
    @Column(length = 10)
    private String s3Status;

    @Column(length = 1000)
    private String s3Error;
}
