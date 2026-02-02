package com.dwp.services.synapsex.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PII 필드 카탈로그 응답 (tenant-agnostic).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PiiFieldCatalogDto {
    private List<PiiFieldCatalogItem> fields;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PiiFieldCatalogItem {
        private String fieldKey;
        private String label;
        private String description;
        private String dataDomain;
        private String defaultHandling;
        private Boolean supportsMask;
        private Boolean supportsHash;
        private Boolean supportsEncrypt;
        private Boolean supportsVault;
        private String sampleMaskedFormat;
    }
}
