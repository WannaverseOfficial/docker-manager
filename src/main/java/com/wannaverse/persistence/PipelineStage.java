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
@Table(name = "pipeline_stages_t")
public class PipelineStage {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExecutionMode executionMode = ExecutionMode.SEQUENTIAL;

    @OneToMany(mappedBy = "stage", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<PipelineStep> steps = new ArrayList<>();

    // Dependencies - IDs of stages that must complete before this stage starts
    // Stages with no dependencies (or all dependencies met) can run in parallel
    @ElementCollection
    @CollectionTable(
            name = "pipeline_stage_dependencies_t",
            joinColumns = @JoinColumn(name = "stage_id"))
    @Column(name = "depends_on_stage_id")
    private List<String> dependsOn = new ArrayList<>();

    // If true, pipeline execution stops if this stage fails
    // If false, other parallel branches can continue
    private boolean stopOnFailure = true;

    // Graph position for visual editor
    private int positionX;
    private int positionY;

    public enum ExecutionMode {
        SEQUENTIAL, // Steps within this stage run one after another
        PARALLEL // Steps within this stage run concurrently
    }
}
