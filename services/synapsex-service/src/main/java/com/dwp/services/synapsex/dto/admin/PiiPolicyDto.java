package com.dwp.services.synapsex.dto.admin;

import com.dwp.services.synapsex.entity.PolicyPiiField;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class PiiPolicyDto {
    private Long piiId;
    private Long tenantId;
    private Long profileId;
    private String fieldKey;  // field_name in DB
    private String handling;
    private String maskRule;
    private String hashRule;
    private String encryptRule;
    private String note;
    private Instant createdAt;
    private Instant updatedAt;

    public static PiiPolicyDto from(PolicyPiiField e) {
        if (e == null) return null;
        return PiiPolicyDto.builder()
                .piiId(e.getPiiId())
                .tenantId(e.getTenantId())
                .profileId(e.getProfileId())
                .fieldKey(e.getFieldName())
                .handling(e.getHandling())
                .maskRule(e.getMaskRule())
                .hashRule(e.getHashRule())
                .encryptRule(e.getEncryptRule())
                .note(e.getNote())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
