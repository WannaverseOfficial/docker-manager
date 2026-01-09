package com.wannaverse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HostInfoResponse {
    // Docker Info
    private String dockerVersion;

    // System Info
    private String operatingSystem;
    private String osType;
    private String architecture;
    private String kernelVersion;
    private String hostname;

    // Resources
    private Long totalMemory;
    private Integer cpus;

    // Container Stats
    private Integer containersTotal;
    private Integer containersRunning;
    private Integer containersPaused;
    private Integer containersStopped;

    // Images
    private Integer imagesTotal;

    // Storage
    private String storageDriver;
    private String dockerRootDir;

    // Time
    private String serverTime;
}
