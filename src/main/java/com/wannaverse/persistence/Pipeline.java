package com.wannaverse.persistence;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "pipelines_t")
public class Pipeline {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String graphLayout;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "git_repository_id")
    private GitRepository gitRepository;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "docker_host_id", nullable = false)
    private DockerHost dockerHost;

    private boolean enabled = true;

    private boolean webhookEnabled = true;

    private boolean pollingEnabled = false;

    private int pollingIntervalSeconds = 300;

    private String branchFilter;

    @Column(unique = true)
    private String webhookSecret;

    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<PipelineStage> stages = new ArrayList<>();

    @Column(nullable = false)
    private long createdAt;

    private long updatedAt;

    private String createdBy;

    @PrePersist
    protected void onCreate() {
        createdAt = System.currentTimeMillis();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = System.currentTimeMillis();
    }
}
