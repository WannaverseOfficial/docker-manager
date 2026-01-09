package com.wannaverse.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ContainerHealthSummary {
    private long activeIssues;
    private long totalEvents24h;
    private long totalEvents7d;
    private Map<String, Long> eventsByType;
    private Map<String, Long> eventsByType24h;
}
