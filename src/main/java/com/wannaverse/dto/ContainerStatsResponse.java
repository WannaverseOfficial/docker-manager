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

    // CPU
    private Double cpuPercent;
    private Long cpuUsage;
    private Long systemCpuUsage;

    // Memory
    private Long memoryUsage;
    private Long memoryLimit;
    private Double memoryPercent;

    // Network I/O
    private Long networkRxBytes;
    private Long networkTxBytes;

    // Block I/O
    private Long blockReadBytes;
    private Long blockWriteBytes;

    // Metadata
    private Long timestamp;
    private Integer pids;
}
