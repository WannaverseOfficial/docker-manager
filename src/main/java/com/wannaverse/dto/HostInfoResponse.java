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
    private String dockerVersion;

    private String operatingSystem;
    private String osType;
    private String architecture;
    private String kernelVersion;
    private String hostname;

    private Long totalMemory;
    private Integer cpus;

    private Integer containersTotal;
    private Integer containersRunning;
    private Integer containersPaused;
    private Integer containersStopped;

    private Integer imagesTotal;

    private String storageDriver;
    private String dockerRootDir;

    private String serverTime;
}
