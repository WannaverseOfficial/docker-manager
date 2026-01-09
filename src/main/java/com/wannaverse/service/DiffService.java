package com.wannaverse.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.wannaverse.persistence.StateSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DiffService {
    private static final Logger log = LoggerFactory.getLogger(DiffService.class);

    private final OperationTrackingService trackingService;
    private final ObjectMapper objectMapper;

    public DiffService(OperationTrackingService trackingService) {
        this.trackingService = trackingService;
        this.objectMapper =
                new ObjectMapper()
                        .enable(SerializationFeature.INDENT_OUTPUT)
                        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public DiffResult compareSnapshots(StateSnapshot before, StateSnapshot after) {
        String beforeJson = getFormattedJson(before);
        String afterJson = getFormattedJson(after);

        return computeDiff(beforeJson, afterJson, "snapshot");
    }

    public DiffResult compareComposeContent(String before, String after) {
        return computeDiff(before, after, "docker-compose.yml");
    }

    public DiffResult compareJson(String beforeJson, String afterJson, String fileName) {
        // Pretty-print JSON for better diffs
        String formattedBefore = formatJson(beforeJson);
        String formattedAfter = formatJson(afterJson);

        return computeDiff(formattedBefore, formattedAfter, fileName);
    }

    private String getFormattedJson(StateSnapshot snapshot) {
        if (snapshot == null) {
            return "";
        }

        String json = trackingService.getDecompressedInspectData(snapshot);
        if (json == null) {
            // Build from individual fields
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("resourceId", snapshot.getResourceId());
            data.put("resourceName", snapshot.getResourceName());
            data.put("imageName", snapshot.getImageName());
            data.put("imageId", snapshot.getImageId());
            data.put("composeContent", snapshot.getComposeContent());

            if (snapshot.getEnvironmentVars() != null) {
                data.put("environmentVars", parseJson(snapshot.getEnvironmentVars()));
            }
            if (snapshot.getVolumeBindings() != null) {
                data.put("volumeBindings", parseJson(snapshot.getVolumeBindings()));
            }
            if (snapshot.getPortBindings() != null) {
                data.put("portBindings", parseJson(snapshot.getPortBindings()));
            }
            if (snapshot.getNetworkSettings() != null) {
                data.put("networkSettings", parseJson(snapshot.getNetworkSettings()));
            }

            try {
                json = objectMapper.writeValueAsString(data);
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize snapshot data", e);
                return "";
            }
        }

        return formatJson(json);
    }

    private String formatJson(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }

        try {
            JsonNode node = objectMapper.readTree(json);
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // Return as-is if not valid JSON
            return json;
        }
    }

    private Object parseJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }

    public DiffResult computeDiff(String before, String after, String fileName) {
        if (before == null) before = "";
        if (after == null) after = "";

        String[] beforeLines = before.split("\n", -1);
        String[] afterLines = after.split("\n", -1);

        // Compute LCS-based diff
        List<DiffLine> unifiedDiff = computeUnifiedDiff(beforeLines, afterLines);
        List<SideBySideLine> sideBySideDiff = computeSideBySideDiff(beforeLines, afterLines);

        // Build unified diff string
        StringBuilder unified = new StringBuilder();
        unified.append("--- a/").append(fileName).append("\n");
        unified.append("+++ b/").append(fileName).append("\n");

        int additions = 0;
        int deletions = 0;
        int modifications = 0;

        for (DiffLine line : unifiedDiff) {
            switch (line.type) {
                case ADDED -> {
                    unified.append("+ ").append(line.content).append("\n");
                    additions++;
                }
                case REMOVED -> {
                    unified.append("- ").append(line.content).append("\n");
                    deletions++;
                }
                case CONTEXT -> unified.append("  ").append(line.content).append("\n");
            }
        }

        // Estimate modifications as min of additions/deletions pairs
        modifications = Math.min(additions, deletions);

        return new DiffResult(
                unified.toString(),
                sideBySideDiff,
                additions,
                deletions,
                modifications,
                before.equals(after));
    }

    private List<DiffLine> computeUnifiedDiff(String[] before, String[] after) {
        List<DiffLine> result = new ArrayList<>();

        // Simple LCS-based diff
        int[][] lcs = computeLCS(before, after);
        collectDiff(before, after, lcs, before.length, after.length, result);

        return result;
    }

    private int[][] computeLCS(String[] before, String[] after) {
        int m = before.length;
        int n = after.length;
        int[][] dp = new int[m + 1][n + 1];

        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (before[i - 1].equals(after[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        return dp;
    }

    private void collectDiff(
            String[] before, String[] after, int[][] lcs, int i, int j, List<DiffLine> result) {
        if (i > 0 && j > 0 && before[i - 1].equals(after[j - 1])) {
            collectDiff(before, after, lcs, i - 1, j - 1, result);
            result.add(new DiffLine(DiffLineType.CONTEXT, before[i - 1], i, j));
        } else if (j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j])) {
            collectDiff(before, after, lcs, i, j - 1, result);
            result.add(new DiffLine(DiffLineType.ADDED, after[j - 1], null, j));
        } else if (i > 0 && (j == 0 || lcs[i][j - 1] < lcs[i - 1][j])) {
            collectDiff(before, after, lcs, i - 1, j, result);
            result.add(new DiffLine(DiffLineType.REMOVED, before[i - 1], i, null));
        }
    }

    private List<SideBySideLine> computeSideBySideDiff(String[] before, String[] after) {
        List<DiffLine> unifiedDiff = computeUnifiedDiff(before, after);
        List<SideBySideLine> result = new ArrayList<>();

        int beforeIdx = 0;
        int afterIdx = 0;

        for (DiffLine line : unifiedDiff) {
            switch (line.type) {
                case CONTEXT -> {
                    result.add(
                            new SideBySideLine(
                                    ++beforeIdx,
                                    line.content,
                                    ++afterIdx,
                                    line.content,
                                    SideBySideType.UNCHANGED));
                }
                case REMOVED -> {
                    result.add(
                            new SideBySideLine(
                                    ++beforeIdx, line.content, null, null, SideBySideType.REMOVED));
                }
                case ADDED -> {
                    result.add(
                            new SideBySideLine(
                                    null, null, ++afterIdx, line.content, SideBySideType.ADDED));
                }
            }
        }

        return result;
    }

    // Result classes
    public record DiffResult(
            String unifiedDiff,
            List<SideBySideLine> sideBySideDiff,
            int additions,
            int deletions,
            int modifications,
            boolean identical) {}

    public record DiffLine(
            DiffLineType type, String content, Integer beforeLineNum, Integer afterLineNum) {}

    public enum DiffLineType {
        ADDED,
        REMOVED,
        CONTEXT
    }

    public record SideBySideLine(
            Integer leftLineNum,
            String leftContent,
            Integer rightLineNum,
            String rightContent,
            SideBySideType type) {}

    public enum SideBySideType {
        UNCHANGED,
        ADDED,
        REMOVED,
        MODIFIED
    }
}
