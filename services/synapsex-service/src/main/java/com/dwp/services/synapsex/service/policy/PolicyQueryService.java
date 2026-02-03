package com.dwp.services.synapsex.service.policy;

import com.dwp.services.synapsex.dto.admin.ConfigProfileDto;
import com.dwp.services.synapsex.dto.admin.DataProtectionDto;
import com.dwp.services.synapsex.dto.admin.PiiPolicyDto;
import com.dwp.services.synapsex.dto.admin.ThresholdDto;
import com.dwp.services.synapsex.dto.policy.EffectivePolicyDto;
import com.dwp.services.synapsex.dto.policy.PolicyProfileDetailDto;
import com.dwp.services.synapsex.dto.policy.PolicyProfileListDto;
import com.dwp.services.synapsex.scope.ScopeResolver;
import com.dwp.services.synapsex.service.admin.ConfigProfileQueryService;
import com.dwp.services.synapsex.service.admin.DataProtectionQueryService;
import com.dwp.services.synapsex.service.admin.PiiPolicyQueryService;
import com.dwp.services.synapsex.service.admin.ThresholdQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * Phase 3 Policies read-optimized 조회 서비스
 */
@Service
@RequiredArgsConstructor
public class PolicyQueryService {

    private final ConfigProfileQueryService configProfileQueryService;
    private final DataProtectionQueryService dataProtectionQueryService;
    private final PiiPolicyQueryService piiPolicyQueryService;
    private final ThresholdQueryService thresholdQueryService;
    private final ScopeResolver scopeResolver;

    @Transactional(readOnly = true)
    public PolicyProfileListDto listProfiles(Long tenantId) {
        List<ConfigProfileDto> profiles = configProfileQueryService.listByTenant(tenantId);
        Long defaultProfileId = profiles.stream()
                .filter(ConfigProfileDto::getIsDefault)
                .map(ConfigProfileDto::getProfileId)
                .findFirst()
                .orElse(profiles.isEmpty() ? null : profiles.get(0).getProfileId());

        List<PolicyProfileListDto.ProfileSummaryDto> items = profiles.stream()
                .map(p -> PolicyProfileListDto.ProfileSummaryDto.builder()
                        .profileId(p.getProfileId())
                        .profileName(p.getProfileName())
                        .description(p.getDescription())
                        .isDefault(p.getIsDefault())
                        .createdAt(p.getCreatedAt())
                        .build())
                .toList();

        return PolicyProfileListDto.builder()
                .profiles(items)
                .defaultProfileId(defaultProfileId)
                .build();
    }

    @Transactional(readOnly = true)
    public Optional<PolicyProfileDetailDto> getProfileDetail(Long tenantId, Long profileId) {
        return ofNullable(configProfileQueryService.getByTenantAndId(tenantId, profileId))
                .map(p -> {
                    DataProtectionDto dataProtection = dataProtectionQueryService.getByProfile(tenantId, profileId);
                    List<ThresholdDto> thresholds = thresholdQueryService.search(
                            tenantId, profileId, null, null, null, PageRequest.of(0, 500)).getContent();
                    List<PiiPolicyDto> piiPolicies = piiPolicyQueryService.listByProfile(tenantId, profileId);

                    return PolicyProfileDetailDto.builder()
                            .profileId(p.getProfileId())
                            .profileName(p.getProfileName())
                            .description(p.getDescription())
                            .isDefault(p.getIsDefault())
                            .createdAt(p.getCreatedAt())
                            .updatedAt(p.getUpdatedAt())
                            .dataProtection(dataProtection)
                            .thresholds(thresholds)
                            .piiPolicies(piiPolicies)
                            .build();
                });
    }

    @Transactional(readOnly = true)
    public Optional<EffectivePolicyDto> getEffectivePolicy(Long tenantId, Long profileId, String bukrs) {
        Long effectiveProfileId = scopeResolver.resolveProfileId(tenantId, profileId);
        if (effectiveProfileId == null) return Optional.empty();

        return ofNullable(configProfileQueryService.getByTenantAndId(tenantId, effectiveProfileId))
                .map(p -> {
                    DataProtectionDto dataProtection = dataProtectionQueryService.getByProfile(tenantId, effectiveProfileId);
                    List<ThresholdDto> thresholds = thresholdQueryService.search(
                            tenantId, effectiveProfileId, null, null, null, PageRequest.of(0, 500)).getContent();
                    List<PiiPolicyDto> piiPolicies = piiPolicyQueryService.listByProfile(tenantId, effectiveProfileId);

                    var enabledBukrs = scopeResolver.resolveEnabledBukrs(tenantId, effectiveProfileId);
                    var enabledCurrencies = scopeResolver.resolveEnabledCurrencies(tenantId, effectiveProfileId);

                    return EffectivePolicyDto.builder()
                            .profileId(effectiveProfileId)
                            .profileName(p.getProfileName())
                            .enabledBukrs(enabledBukrs)
                            .enabledCurrencies(enabledCurrencies)
                            .dataProtection(dataProtection)
                            .thresholds(thresholds)
                            .piiPolicies(piiPolicies)
                            .build();
                });
    }
}
