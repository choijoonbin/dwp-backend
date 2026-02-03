package com.dwp.services.synapsex.dto.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET /documents/{docKey}/reversal-chain 응답
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentReversalChainDto {

    private List<ReversalNodeDto> nodes;
    private List<ReversalEdgeDto> edges;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReversalNodeDto {
        private String docKey;
        private String belnr;
        private String reversalBelnr;
        private String budat;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReversalEdgeDto {
        private String fromDocKey;
        private String toDocKey;
    }
}
