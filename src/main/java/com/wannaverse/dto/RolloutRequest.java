package com.wannaverse.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Create or change a rollout. Every field is sent; the form shows all of them. */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RolloutRequest {
    private String name;
    private String description;
    private String dockerHostId;

    /** BLUE_GREEN or CANARY. */
    private String strategy;

    private String containerName;
    private ContainerSpec containerSpec;
    private int containerPort;

    /** An existing ingress route to take over (its container becomes the current version). */
    private String routeId;

    /** Or a new address, created at the first release. */
    private String hostname;

    private String pathPrefix;
    private String tlsMode;
    private String previewHostname;

    private String healthPath;
    private Integer startTimeoutSeconds;
    private List<RolloutTest> tests = new ArrayList<>();

    private String canarySteps;
    private Integer stepSeconds;
    private Boolean stickySessions;

    private Boolean autoRollback;
    private Double maxErrorRate;
    private Integer maxAvgResponseMs;
    private Integer minRequests;
    private Integer bakeSeconds;
    private Boolean keepPreviousRunning;
}
