package com.dwp.services.mcp.dto.mcp;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class PolicyLookupResult {
    private String article;
    private String clause;
    private LocalDate effectiveAt;
    private Integer count;
    private List<PolicyItem> items;

    @Data
    @Builder
    public static class PolicyItem {
        private Long docId;
        private Long chunkId;
        private String article;
        private String clause;
        private String version;
        private LocalDate effectiveFrom;
        private LocalDate effectiveTo;
        private Boolean isActive;
        private String text;
    }
}
