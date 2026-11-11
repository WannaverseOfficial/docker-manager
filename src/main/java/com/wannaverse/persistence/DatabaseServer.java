package com.wannaverse.persistence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A PostgreSQL server Docker Manager runs (one container, one data volume, one network). */
@Entity
@Table(
        name = "database_servers_t",
        uniqueConstraints = @UniqueConstraint(columnNames = {"dockerHostId", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class DatabaseServer {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String dockerHostId;

    /** Short name, also used for the container (dm-pg-NAME), volume and network. */
    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false)
    private String image;

    @Column(nullable = false)
    private String containerName;

    @Column(nullable = false)
    private String volumeName;

    /** Containers that use this server join this network and reach it by its container name. */
    @Column(nullable = false)
    private String networkName;

    @Column(nullable = false)
    private String superuser;

    @JsonIgnore private String superuserPassword;

    /** Port on the host, or null when it's reachable only by containers on its network. */
    private Integer publishedPort;

    /** 127.0.0.1 (this machine only) or 0.0.0.0 (the network). */
    private String publishedAddress;

    private long createdAt;

    // --- backups ---

    /** Hours between automatic backups; 0 turns them off. */
    private int backupIntervalHours;

    /** How many automatic backups to keep (manual ones are kept until deleted). */
    private int backupKeep;

    private long lastAutoBackupAt;

    // --- copies to S3-compatible storage ---

    private boolean s3Enabled;
    private String s3Endpoint;
    private String s3Region;
    private String s3Bucket;
    private String s3Prefix;
    private String s3AccessKey;
    @JsonIgnore private String s3SecretKey;

    /** What the server is doing right now (a restore, an import), shown in the UI. */
    @Column(length = 300)
    private String activity;

    /** How the last restore or import went. */
    @Column(columnDefinition = "TEXT")
    private String lastResult;

    private boolean lastResultFailed;

    @JsonProperty("hasS3Secret")
    public boolean hasS3Secret() {
        return s3SecretKey != null && !s3SecretKey.isBlank();
    }
}
