package com.dwp.services.synapsex.dto.policy;

import com.dwp.services.synapsex.dto.admin.DataProtectionDto;
import com.dwp.services.synapsex.dto.admin.PiiPolicyDto;
import com.dwp.services.synapsex.dto.admin.ThresholdDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PolicyProfileDetailDto {
    private Long profileId;
    private String profileName;
    private String description;
    private Boolean isDefault;
    private Instant createdAt;
    private Instant updatedAt;
    private DataProtectionDto dataProtection;
    private List<ThresholdDto> thresholds;
    private List<PiiPolicyDto> piiPolicies;
}
