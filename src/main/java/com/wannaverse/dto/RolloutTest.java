package com.wannaverse.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A check run against a new version before it gets visitors.
 *
 * <ul>
 *   <li>HTTP: request {@link #path} on the new version; pass when the status matches {@link
 *       #expectStatus} and the body contains {@link #bodyContains}.
 *   <li>COMMAND: run {@link #script} in {@link #image} next to the new version, with TARGET_URL
 *       pointing at it; exit code 0 passes.
 *   <li>PIPELINE: run a pipeline with TARGET_URL set; pass when it succeeds.
 *   <li>APPROVAL: wait until someone approves (or rejects) the release.
 * </ul>
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RolloutTest {
    private String name;

    /** HTTP, COMMAND, PIPELINE or APPROVAL. */
    private String kind;

    // HTTP
    private String path;
    private String expectStatus;
    private String bodyContains;

    /** HTTP: tries before failing, a few seconds apart. */
    private Integer retries;

    // COMMAND
    private String image;
    private String script;

    // PIPELINE
    private String pipelineId;

    /** Time limit in seconds. APPROVAL: 0 or empty waits until someone decides. */
    private Integer timeoutSeconds;

    public String kindOrHttp() {
        return kind == null || kind.isBlank() ? "HTTP" : kind;
    }

    public String displayName() {
        if (name != null && !name.isBlank()) return name.trim();
        return switch (kindOrHttp()) {
            case "COMMAND" -> "Run " + (image == null ? "a command" : image);
            case "PIPELINE" -> "Run a pipeline";
            case "APPROVAL" -> "Approval";
            default -> "Check " + (path == null || path.isBlank() ? "/" : path);
        };
    }
}
