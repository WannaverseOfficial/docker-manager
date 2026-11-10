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

    /**
     * Line diff. Common leading and trailing lines are matched first, then an LCS table is built
     * for the changed middle and walked back iteratively. (The previous version recursed once per
     * line and built the table for the whole file, so large compose files overflowed the stack or
     * ran out of memory.) Very large changed regions fall back to "all removed, all added".
     */
    private List<DiffLine> computeUnifiedDiff(String[] before, String[] after) {
        List<DiffLine> result = new ArrayList<>();
        int m = before.length, n = after.length;
        int start = 0;
        while (start < m && start < n && before[start].equals(after[start])) start++;
        int endB = m, endA = n;
        while (endB > start && endA > start && before[endB - 1].equals(after[endA - 1])) {
            endB--;
            endA--;
        }
        for (int k = 0; k < start; k++) {
            result.add(new DiffLine(DiffLineType.CONTEXT, before[k], k + 1, k + 1));
        }

        int rows = endB - start, cols = endA - start;
        if ((long) rows * cols > 4_000_000L) {
            for (int i = start; i < endB; i++)
                result.add(new DiffLine(DiffLineType.REMOVED, before[i], i + 1, null));
            for (int j = start; j < endA; j++)
                result.add(new DiffLine(DiffLineType.ADDED, after[j], null, j + 1));
        } else {
            int[][] dp = new int[rows + 1][cols + 1];
            for (int i = rows - 1; i >= 0; i--) {
                for (int j = cols - 1; j >= 0; j--) {
                    dp[i][j] =
                            before[start + i].equals(after[start + j])
                                    ? dp[i + 1][j + 1] + 1
                                    : Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
            int i = 0, j = 0;
            while (i < rows || j < cols) {
                if (i < rows && j < cols && before[start + i].equals(after[start + j])) {
                    result.add(
                            new DiffLine(
                                    DiffLineType.CONTEXT,
                                    before[start + i],
                                    start + i + 1,
                                    start + j + 1));
                    i++;
                    j++;
                } else if (i < rows && (j == cols || dp[i + 1][j] >= dp[i][j + 1])) {
                    result.add(
                            new DiffLine(
                                    DiffLineType.REMOVED, before[start + i], start + i + 1, null));
                    i++;
                } else {
                    result.add(
                            new DiffLine(
                                    DiffLineType.ADDED, after[start + j], null, start + j + 1));
                    j++;
                }
            }
        }

        for (int k = 0; k < m - endB; k++) {
            result.add(
                    new DiffLine(
                            DiffLineType.CONTEXT, before[endB + k], endB + k + 1, endA + k + 1));
        }
        return result;
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
