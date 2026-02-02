package com.dwp.services.synapsex.scope;

import com.dwp.services.synapsex.entity.ConfigProfile;
import com.dwp.services.synapsex.repository.ConfigProfileRepository;
import com.dwp.services.synapsex.repository.MdCompanyCodeRepository;
import com.dwp.services.synapsex.repository.MdCurrencyRepository;
import com.dwp.services.synapsex.repository.PolicyScopeCompanyRepository;
import com.dwp.services.synapsex.repository.PolicyScopeCurrencyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Profile-scoped Scope 해석기.
 * Tenant Scope는 '선택된 Policy Profile(profileId)' 기준으로 적용된다.
 * profileId가 없으면 테넌트 기본 프로파일(is_default=true)을 사용한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScopeResolver {

    private final ConfigProfileRepository configProfileRepository;
    private final MdCompanyCodeRepository mdCompanyCodeRepository;
    private final MdCurrencyRepository mdCurrencyRepository;
    private final PolicyScopeCompanyRepository policyScopeCompanyRepository;
    private final PolicyScopeCurrencyRepository policyScopeCurrencyRepository;

    /**
     * profileId 결정: null이면 테넌트 기본 프로파일 사용.
     */
    public Long resolveProfileId(Long tenantId, Long profileId) {
        if (profileId != null) {
            return profileId;
        }
        return configProfileRepository.findByTenantIdAndIsDefaultTrue(tenantId)
                .map(ConfigProfile::getProfileId)
                .orElse(null);
    }

    /**
     * 활성화된 회사코드(BUKRS) Set. profile 기준.
     * policy_scope_company에 행이 없으면 → md_company_code의 모든 활성 회사코드 포함 (included-by-default).
     */
    public Set<String> resolveEnabledBukrs(Long tenantId, Long profileId) {
        Long effectiveProfileId = resolveProfileId(tenantId, profileId);
        if (effectiveProfileId == null) {
            log.warn("ScopeResolver: no profile for tenantId={}", tenantId);
            return Collections.emptySet();
        }

        var policyRows = policyScopeCompanyRepository.findByTenantIdAndProfileId(tenantId, effectiveProfileId);
        if (policyRows.isEmpty()) {
            // included-by-default: all active md_company_code
            return mdCompanyCodeRepository.findByTenantIdAndIsActiveTrueOrderByBukrsAsc(tenantId).stream()
                    .map(m -> m.getBukrs().toUpperCase())
                    .collect(Collectors.toUnmodifiableSet());
        }

        Set<String> included = policyRows.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIncluded()))
                .map(p -> p.getBukrs().toUpperCase())
                .collect(Collectors.toSet());

        if (included.isEmpty()) {
            log.warn("ScopeResolver: no included bukrs for tenantId={}, profileId={}", tenantId, effectiveProfileId);
        }
        return Collections.unmodifiableSet(included);
    }

    /**
     * 활성화된 통화(Currency) Set. profile 기준.
     * policy_scope_currency에 행이 없으면 → md_currency의 모든 활성 통화 포함 (included-by-default).
     */
    public Set<String> resolveEnabledCurrencies(Long tenantId, Long profileId) {
        Long effectiveProfileId = resolveProfileId(tenantId, profileId);
        if (effectiveProfileId == null) {
            log.warn("ScopeResolver: no profile for tenantId={}", tenantId);
            return Collections.emptySet();
        }

        var policyRows = policyScopeCurrencyRepository.findByTenantIdAndProfileId(tenantId, effectiveProfileId);
        if (policyRows.isEmpty()) {
            // included-by-default: all active md_currency
            return mdCurrencyRepository.findByIsActiveTrueOrderByCurrencyCodeAsc().stream()
                    .map(m -> m.getCurrencyCode().toUpperCase())
                    .collect(Collectors.toUnmodifiableSet());
        }

        Set<String> included = policyRows.stream()
                .filter(p -> Boolean.TRUE.equals(p.getIncluded()))
                .map(p -> p.getCurrencyCode().toUpperCase())
                .collect(Collectors.toSet());

        if (included.isEmpty()) {
            log.warn("ScopeResolver: no included currencies for tenantId={}, profileId={}", tenantId, effectiveProfileId);
        }
        return Collections.unmodifiableSet(included);
    }

    /**
     * bukrs가 scope 내에 있는지 검사.
     */
    public boolean isBukrsInScope(Long tenantId, Long profileId, String bukrs) {
        if (bukrs == null) return false;
        return resolveEnabledBukrs(tenantId, profileId).contains(bukrs.toUpperCase());
    }

    /**
     * currency가 scope 내에 있는지 검사.
     */
    public boolean isCurrencyInScope(Long tenantId, Long profileId, String currency) {
        if (currency == null) return false;
        return resolveEnabledCurrencies(tenantId, profileId).contains(currency.toUpperCase());
    }
}
