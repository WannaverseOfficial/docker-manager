package com.wannaverse.dto;

import com.wannaverse.service.DiffService;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffResponse {
    private String unifiedDiff;
    private List<SideBySideLineResponse> sideBySideDiff;
    private int additions;
    private int deletions;
    private int modifications;
    private boolean identical;

    public static DiffResponse fromDiffResult(DiffService.DiffResult result) {
        DiffResponse response = new DiffResponse();
        response.setUnifiedDiff(result.unifiedDiff());
        response.setSideBySideDiff(
                result.sideBySideDiff().stream()
                        .map(SideBySideLineResponse::fromSideBySideLine)
                        .toList());
        response.setAdditions(result.additions());
        response.setDeletions(result.deletions());
        response.setModifications(result.modifications());
        response.setIdentical(result.identical());
        return response;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SideBySideLineResponse {
        private Integer leftLineNum;
        private String leftContent;
        private Integer rightLineNum;
        private String rightContent;
        private String type;

        public static SideBySideLineResponse fromSideBySideLine(DiffService.SideBySideLine line) {
            SideBySideLineResponse response = new SideBySideLineResponse();
            response.setLeftLineNum(line.leftLineNum());
            response.setLeftContent(line.leftContent());
            response.setRightLineNum(line.rightLineNum());
            response.setRightContent(line.rightContent());
            response.setType(line.type().name());
            return response;
        }
    }
}
