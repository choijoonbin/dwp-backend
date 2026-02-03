package com.dwp.services.synapsex.dto.policy;

import com.dwp.services.synapsex.dto.admin.DataProtectionDto;
import com.dwp.services.synapsex.dto.admin.PiiPolicyDto;
import com.dwp.services.synapsex.dto.admin.ThresholdDto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EffectivePolicyDto {
    private Long profileId;
    private String profileName;
    private Set<String> enabledBukrs;
    private Set<String> enabledCurrencies;
    private DataProtectionDto dataProtection;
    private List<ThresholdDto> thresholds;
    private List<PiiPolicyDto> piiPolicies;
}
