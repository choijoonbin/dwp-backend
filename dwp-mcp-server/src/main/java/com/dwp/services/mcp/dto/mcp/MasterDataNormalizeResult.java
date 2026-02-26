package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MasterDataNormalizeResult {
    private Mapping mcc;
    private Mapping expenseType;
    private Mapping hrStatus;

    @Data
    @Builder
    public static class Mapping {
        private String raw;
        private String normalized;
        private String normalizedName;
        private String mappingConfidence;
        private String source;
    }
}
