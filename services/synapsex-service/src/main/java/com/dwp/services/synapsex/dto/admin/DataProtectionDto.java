package com.dwp.services.synapsex.dto.admin;

import com.dwp.services.synapsex.entity.PolicyDataProtection;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 데이터 보호 정책 응답 DTO.
 */
@Data
@Builder
public class DataProtectionDto {
    private Long protectionId;
    private Long tenantId;
    private Long profileId;
    private Boolean atRestEncryptionEnabled;
    private String keyProvider;
    private String kmsMode;
    private Integer auditRetentionYears;
    private Boolean exportRequiresApproval;
    private String exportMode;
    private Instant updatedAt;

    public static DataProtectionDto from(PolicyDataProtection e) {
        if (e == null) return null;
        return DataProtectionDto.builder()
                .protectionId(e.getProtectionId())
                .tenantId(e.getTenantId())
                .profileId(e.getProfileId())
                .atRestEncryptionEnabled(e.getAtRestEncryptionEnabled())
                .keyProvider(e.getKeyProvider())
                .kmsMode(e.getKmsMode() != null ? e.getKmsMode() : "KMS_MANAGED_KEYS")
                .auditRetentionYears(e.getAuditRetentionYears())
                .exportRequiresApproval(e.getExportRequiresApproval())
                .exportMode(e.getExportMode())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
