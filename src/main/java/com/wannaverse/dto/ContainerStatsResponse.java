package com.wannaverse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContainerStatsResponse {
    private String containerId;
    private String containerName;

    private Double cpuPercent;
    private Long cpuUsage;
    private Long systemCpuUsage;

    private Long memoryUsage;
    private Long memoryLimit;
    private Double memoryPercent;

    private Long networkRxBytes;
    private Long networkTxBytes;

    private Long blockReadBytes;
    private Long blockWriteBytes;

    private Long timestamp;
    private Integer pids;
}
