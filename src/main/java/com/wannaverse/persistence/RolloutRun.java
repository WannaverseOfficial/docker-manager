package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One release of a new version through a {@link Rollout}, or a rollback to the previous one. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "rollout_runs_t",
        indexes = {@Index(name = "idx_rollout_run_rollout", columnList = "rollout_id")})
public class RolloutRun {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "rollout_id", nullable = false)
    private String rolloutId;

    /** 1, 2, 3... per rollout. */
    private int number;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind = Kind.RELEASE;

    @Column(nullable = false)
    private String image;

    /** The slot the new version runs in. */
    private String targetContainer;

    /** What served visitors when the run started. */
    private String stableContainer;

    private String stableImage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    /** What it is doing now, in words, for the UI. */
    private String phase;

    /** The new version's share of traffic right now (0-100). */
    private int trafficPercent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Trigger triggerType = Trigger.MANUAL;

    /** Who or what started it: a username, a repository or a pipeline. */
    private String triggeredBy;

    private String commitSha;

    /**
     * Progress of each step: a JSON list of {name, kind, status, message, startedAt, finishedAt}.
     */
    @Column(columnDefinition = "TEXT")
    private String stepsJson;

    /** Traffic samples taken while the new version had traffic: a JSON list. */
    @Column(columnDefinition = "TEXT")
    private String metricsJson;

    @Column(columnDefinition = "TEXT")
    private String logs;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /** Set while waiting for approval: which step is waiting. */
    private String approvalStep;

    private String approvedBy;

    @Column(nullable = false)
    private long createdAt;

    private long startedAt;

    private long finishedAt;

    public enum Kind {
        RELEASE,
        ROLLBACK
    }

    public enum Status {
        PENDING,
        RUNNING,
        WAITING_APPROVAL,
        SUCCEEDED,
        FAILED,
        ROLLED_BACK,
        CANCELLED;

        public boolean finished() {
            return this == SUCCEEDED || this == FAILED || this == ROLLED_BACK || this == CANCELLED;
        }
    }

    public enum Trigger {
        MANUAL,
        GIT,
        PIPELINE,
        ROLLBACK
    }

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
    }
}
