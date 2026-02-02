package com.dwp.services.synapsex.dto.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 데이터 보호 정책 업데이트 요청.
 */
@Data
public class UpdateDataProtectionRequest {
    @NotNull(message = "profileId는 필수입니다")
    private Long profileId;

    private Boolean atRestEncryptionEnabled;

    @Pattern(regexp = "KMS_MOCK|KMS|HSM", message = "keyProvider는 KMS_MOCK, KMS, HSM 중 하나여야 합니다")
    private String keyProvider;

    @Pattern(regexp = "KMS_MANAGED_KEYS", message = "kmsMode는 현재 KMS_MANAGED_KEYS만 허용됩니다")
    private String kmsMode;

    @Min(value = 1, message = "auditRetentionYears는 1~20 사이여야 합니다")
    @Max(value = 20, message = "auditRetentionYears는 1~20 사이여야 합니다")
    private Integer auditRetentionYears;

    private Boolean exportRequiresApproval;

    @Pattern(regexp = "ZIP|CSV", message = "exportMode는 ZIP 또는 CSV여야 합니다")
    private String exportMode;
}
