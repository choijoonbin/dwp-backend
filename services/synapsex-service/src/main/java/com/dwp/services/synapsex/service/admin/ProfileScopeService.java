package com.dwp.services.synapsex.service.admin;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.synapsex.dto.admin.*;
import com.dwp.services.synapsex.entity.*;
import com.dwp.services.synapsex.repository.*;
import com.dwp.services.synapsex.scope.ScopeResolver;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Profile-scoped Tenant Scope 서비스.
 * md_company_code, md_currency 마스터 + policy_scope_company, policy_scope_currency, policy_sod_rule 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileScopeService {

    private static final List<PolicySodRuleSeed> DEFAULT_SOD_RULES = List.of(
            new PolicySodRuleSeed("NO_SELF_APPROVE", "Requester cannot approve own action", "요청자는 본인 액션을 승인할 수 없습니다."),
            new PolicySodRuleSeed("DUAL_CONTROL", "High value actions require two approvals", "고액 액션은 이중 승인이 필요합니다."),
            new PolicySodRuleSeed("FINANCE_VS_SECURITY", "Security admins cannot edit finance policy", "보안 관리자는 재무 정책을 수정할 수 없습니다.")
    );

    private static final String SOD_MODE_DEFAULT = "BASELINE";

    private final MdCompanyCodeRepository mdCompanyCodeRepository;
    private final MdCurrencyRepository mdCurrencyRepository;
    private final PolicyScopeCompanyRepository policyScopeCompanyRepository;
    private final PolicyScopeCurrencyRepository policyScopeCurrencyRepository;
    private final PolicySodRuleRepository policySodRuleRepository;
    private final ConfigKvRepository configKvRepository;
    private final ScopeResolver scopeResolver;
    private final AuditWriter auditWriter;

    private Long resolveProfileId(Long tenantId, Long profileId) {
        Long effective = scopeResolver.resolveProfileId(tenantId, profileId);
        if (effective == null) {
            throw new BaseException(ErrorCode.ENTITY_NOT_FOUND, "프로파일을 찾을 수 없습니다. 테넌트에 기본 프로파일이 없습니다.");
        }
        return effective;
    }

    // --- Company Codes ---

    @Transactional(readOnly = true)
    public TenantScopeCompanyCodesResponseDto getCompanyCodes(Long tenantId, Long profileId) {
        Long effectiveProfileId = resolveProfileId(tenantId, profileId);
        List<MdCompanyCode> masters = mdCompanyCodeRepository.findByTenantIdAndIsActiveTrueOrderByBukrsAsc(tenantId);
        Map<String, PolicyScopeCompany> policyMap = policyScopeCompanyRepository.findByTenantIdAndProfileId(tenantId, effectiveProfileId).stream()
                .collect(Collectors.toMap(PolicyScopeCompany::getBukrs, p -> p, (a, b) -> a));

        List<TenantScopeCompanyCodesResponseDto.CompanyCodeItemDto> items = masters.stream()
                .map(m -> {
                    PolicyScopeCompany p = policyMap.get(m.getBukrs());
                    boolean included = p == null ? true : Boolean.TRUE.equals(p.getIncluded());
                    return TenantScopeCompanyCodesResponseDto.CompanyCodeItemDto.builder()
                            .bukrs(m.getBukrs())
                            .bukrsName(m.getBukrsName())
                            .defaultCurrency(m.getDefaultCurrency())
                            .isActive(m.getIsActive())
                            .included(included)
                            .lastSyncTs(m.getLastSyncTs())
                            .build();
                })
                .toList();

        Instant lastUpdatedAt = resolveLastUpdatedAt(tenantId, effectiveProfileId);
        return TenantScopeCompanyCodesResponseDto.builder()
                .profileId(effectiveProfileId)
                .lastUpdatedAt(lastUpdatedAt)
                .items(items)
                .build();
    }

    @Transactional
    public TenantScopeCompanyCodesResponseDto bulkUpdateCompanyCodes(Long tenantId, Long actorUserId,
                                                                      BulkCompanyCodesProfileRequest request,
                                                                      String ipAddress, String userAgent, String gatewayRequestId) {
        Long effectiveProfileId = resolveProfileId(tenantId, request.getProfileId());
        if (!effectiveProfileId.equals(request.getProfileId())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "profileId가 테넌트 프로파일과 일치하지 않습니다.");
        }

        Map<String, Boolean> beforeMap = new HashMap<>();
        Map<String, Boolean> afterMap = new HashMap<>();
        Instant now = Instant.now();

        for (BulkCompanyCodesProfileRequest.CompanyCodeUpdateDto item : request.getUpdates()) {
            String bukrs = item.getBukrs().toUpperCase();
            if (!mdCompanyCodeRepository.existsByTenantIdAndBukrs(tenantId, bukrs)) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "bukrs가 md_company_code에 존재하지 않습니다: " + bukrs);
            }

            Optional<PolicyScopeCompany> opt = policyScopeCompanyRepository.findByTenantIdAndProfileIdAndBukrs(tenantId, effectiveProfileId, bukrs);
            Boolean beforeIncluded = opt.map(PolicyScopeCompany::getIncluded).orElse(true);
            Boolean afterIncluded = item.getIncluded() != null ? item.getIncluded() : true;

            beforeMap.put(bukrs, beforeIncluded);
            afterMap.put(bukrs, afterIncluded);

            if (opt.isPresent()) {
                PolicyScopeCompany p = opt.get();
                p.setIncluded(afterIncluded);
                p.setUpdatedAt(now);
                p.setUpdatedBy(actorUserId);
                policyScopeCompanyRepository.save(p);
            } else {
                policyScopeCompanyRepository.save(PolicyScopeCompany.builder()
                        .tenantId(tenantId)
                        .profileId(effectiveProfileId)
                        .bukrs(bukrs)
                        .included(afterIncluded)
                        .createdAt(now)
                        .createdBy(actorUserId)
                        .updatedAt(now)
                        .updatedBy(actorUserId)
                        .build());
            }
        }

        Map<String, Object> diff = new HashMap<>();
        diff.put("before_json", beforeMap);
        diff.put("after_json", afterMap);
        auditWriter.logTenantScopeChange(tenantId, actorUserId, "BULK_UPDATE", "TENANT_SCOPE_COMPANY",
                String.valueOf(effectiveProfileId), diff, "Company Codes", ipAddress, userAgent, gatewayRequestId);

        return getCompanyCodes(tenantId, effectiveProfileId);
    }

    // --- Currencies ---

    @Transactional(readOnly = true)
    public TenantScopeCurrenciesResponseDto getCurrencies(Long tenantId, Long profileId) {
        Long effectiveProfileId = resolveProfileId(tenantId, profileId);
        List<MdCurrency> masters = mdCurrencyRepository.findByIsActiveTrueOrderByCurrencyCodeAsc();
        Map<String, PolicyScopeCurrency> policyMap = policyScopeCurrencyRepository.findByTenantIdAndProfileId(tenantId, effectiveProfileId).stream()
                .collect(Collectors.toMap(PolicyScopeCurrency::getCurrencyCode, p -> p, (a, b) -> a));

        List<TenantScopeCurrenciesResponseDto.CurrencyItemDto> items = masters.stream()
                .map(m -> {
                    PolicyScopeCurrency p = policyMap.get(m.getCurrencyCode());
                    boolean included = p == null ? true : Boolean.TRUE.equals(p.getIncluded());
                    String fxControlMode = p == null ? "ALLOW" : (p.getFxControlMode() != null ? p.getFxControlMode() : "ALLOW");
                    return TenantScopeCurrenciesResponseDto.CurrencyItemDto.builder()
                            .currencyCode(m.getCurrencyCode())
                            .currencyName(m.getCurrencyName())
                            .isActive(m.getIsActive())
                            .included(included)
                            .fxControlMode(fxControlMode)
                            .build();
                })
                .toList();

        Instant lastUpdatedAt = resolveLastUpdatedAt(tenantId, effectiveProfileId);
        return TenantScopeCurrenciesResponseDto.builder()
                .profileId(effectiveProfileId)
                .lastUpdatedAt(lastUpdatedAt)
                .items(items)
                .build();
    }

    @Transactional
    public TenantScopeCurrenciesResponseDto bulkUpdateCurrencies(Long tenantId, Long actorUserId,
                                                                 BulkCurrenciesProfileRequest request,
                                                                 String ipAddress, String userAgent, String gatewayRequestId) {
        Long effectiveProfileId = resolveProfileId(tenantId, request.getProfileId());
        if (!effectiveProfileId.equals(request.getProfileId())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "profileId가 테넌트 프로파일과 일치하지 않습니다.");
        }

        Map<String, Object> diff = new HashMap<>();
        Instant now = Instant.now();

        for (BulkCurrenciesProfileRequest.CurrencyUpdateDto item : request.getUpdates()) {
            String currencyCode = item.getCurrencyCode().toUpperCase();
            if (!mdCurrencyRepository.existsByCurrencyCode(currencyCode)) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "currencyCode가 md_currency에 존재하지 않습니다: " + currencyCode);
            }

            Optional<PolicyScopeCurrency> opt = policyScopeCurrencyRepository.findByTenantIdAndProfileIdAndCurrencyCode(tenantId, effectiveProfileId, currencyCode);
            Boolean afterIncluded = item.getIncluded() != null ? item.getIncluded() : true;

            if (opt.isPresent()) {
                PolicyScopeCurrency p = opt.get();
                p.setIncluded(afterIncluded);
                if (item.getFxControlMode() != null && List.of("ALLOW", "FX_REQUIRED", "FX_LOCKED").contains(item.getFxControlMode())) {
                    p.setFxControlMode(item.getFxControlMode());
                }
                p.setUpdatedAt(now);
                p.setUpdatedBy(actorUserId);
                policyScopeCurrencyRepository.save(p);
            } else {
                String fxControlMode = item.getFxControlMode() != null && List.of("ALLOW", "FX_REQUIRED", "FX_LOCKED").contains(item.getFxControlMode())
                        ? item.getFxControlMode() : "ALLOW";
                policyScopeCurrencyRepository.save(PolicyScopeCurrency.builder()
                        .tenantId(tenantId)
                        .profileId(effectiveProfileId)
                        .currencyCode(currencyCode)
                        .included(afterIncluded)
                        .fxControlMode(fxControlMode)
                        .createdAt(now)
                        .createdBy(actorUserId)
                        .updatedAt(now)
                        .updatedBy(actorUserId)
                        .build());
            }
        }

        diff.put("profileId", effectiveProfileId);
        diff.put("count", request.getUpdates().size());
        auditWriter.logTenantScopeBulkChange(tenantId, actorUserId, "TENANT_SCOPE_CURRENCY", diff, "Currencies", request.getUpdates().size(), ipAddress, userAgent, gatewayRequestId);

        return getCurrencies(tenantId, effectiveProfileId);
    }

    // --- SoD Rules ---

    @Transactional(readOnly = true)
    public TenantScopeSodRulesResponseDto getSodRules(Long tenantId, Long profileId) {
        Long effectiveProfileId = resolveProfileId(tenantId, profileId);
        ensureSodRulesSeeded(tenantId, effectiveProfileId);

        String mode = resolveSodMode(tenantId, effectiveProfileId);
        List<PolicySodRule> rules = policySodRuleRepository.findByTenantIdAndProfileIdOrderByRuleKeyAsc(tenantId, effectiveProfileId);

        List<TenantScopeSodRulesResponseDto.SodRuleItemDto> items = rules.stream()
                .map(r -> TenantScopeSodRulesResponseDto.SodRuleItemDto.builder()
                        .ruleKey(r.getRuleKey())
                        .title(r.getTitle())
                        .description(r.getDescription())
                        .isEnabled(r.getIsEnabled())
                        .severity(r.getSeverity() != null ? r.getSeverity() : "WARN")
                        .build())
                .toList();

        Instant lastUpdatedAt = resolveLastUpdatedAt(tenantId, effectiveProfileId);
        return TenantScopeSodRulesResponseDto.builder()
                .profileId(effectiveProfileId)
                .mode(mode)
                .lastUpdatedAt(lastUpdatedAt)
                .rules(items)
                .build();
    }

    @Transactional
    public TenantScopeSodRulesResponseDto bulkUpdateSodRules(Long tenantId, Long actorUserId,
                                                             BulkSodRulesProfileRequest request,
                                                             String ipAddress, String userAgent, String gatewayRequestId) {
        Long effectiveProfileId = resolveProfileId(tenantId, request.getProfileId());
        ensureSodRulesSeeded(tenantId, effectiveProfileId);

        Map<String, Object> diff = new HashMap<>();
        List<String> changed = new ArrayList<>();
        Instant now = Instant.now();

        for (BulkSodRulesProfileRequest.SodRuleUpdateDto item : request.getUpdates()) {
            String ruleKey = item.getRuleKey().toUpperCase().replace(' ', '_');
            Optional<PolicySodRule> opt = policySodRuleRepository.findByTenantIdAndProfileIdAndRuleKey(tenantId, effectiveProfileId, ruleKey);
            if (opt.isEmpty()) continue;

            PolicySodRule r = opt.get();
            boolean updated = false;
            if (item.getIsEnabled() != null && !item.getIsEnabled().equals(r.getIsEnabled())) {
                r.setIsEnabled(item.getIsEnabled());
                updated = true;
            }
            String severity = item.getSeverity() != null && List.of("INFO", "WARN", "BLOCK").contains(item.getSeverity())
                    ? item.getSeverity() : null;
            if (severity != null && !severity.equals(r.getSeverity())) {
                r.setSeverity(severity);
                updated = true;
            }
            if (item.getConfigJson() != null && !item.getConfigJson().equals(r.getConfigJson())) {
                r.setConfigJson(item.getConfigJson());
                updated = true;
            }
            if (updated) {
                r.setUpdatedAt(now);
                r.setUpdatedBy(actorUserId);
                policySodRuleRepository.save(r);
                changed.add(ruleKey);
            }
        }

        diff.put("changedKeys", changed);
        diff.put("count", changed.size());
        auditWriter.logTenantScopeBulkChange(tenantId, actorUserId, "SOD_RULE", diff, "SoD Rules", changed.size(), ipAddress, userAgent, gatewayRequestId);

        return getSodRules(tenantId, effectiveProfileId);
    }

    private void ensureSodRulesSeeded(Long tenantId, Long profileId) {
        if (policySodRuleRepository.existsByTenantIdAndProfileIdAndRuleKey(tenantId, profileId, "NO_SELF_APPROVE")) {
            return;
        }
        Instant now = Instant.now();
        for (PolicySodRuleSeed seed : DEFAULT_SOD_RULES) {
            policySodRuleRepository.save(PolicySodRule.builder()
                    .tenantId(tenantId)
                    .profileId(profileId)
                    .ruleKey(seed.ruleKey)
                    .title(seed.title)
                    .description(seed.description)
                    .isEnabled(true)
                    .severity("WARN")
                    .configJson(JsonNodeFactory.instance.objectNode())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        log.info("SoD rules seeded for tenantId={}, profileId={}", tenantId, profileId);
    }

    private String resolveSodMode(Long tenantId, Long profileId) {
        try {
            Optional<ConfigKv> kv = configKvRepository.findByTenantIdAndProfileIdAndConfigKey(tenantId, profileId, "security.sod_mode");
            if (kv.isPresent() && kv.get().getConfigValue() != null) {
                String val = kv.get().getConfigValue().asText();
                if (List.of("PLANNED", "BASELINE", "ENFORCED").contains(val)) {
                    return val;
                }
            }
        } catch (Exception e) {
            log.debug("config_kv security.sod_mode not found: {}", e.getMessage());
        }
        return SOD_MODE_DEFAULT;
    }

    private record PolicySodRuleSeed(String ruleKey, String title, String description) {}

    /**
     * policy_scope_company, policy_scope_currency, policy_sod_rule 중 가장 최근 updated_at 반환.
     */
    private Instant resolveLastUpdatedAt(Long tenantId, Long profileId) {
        return Stream.of(
                        policyScopeCompanyRepository.findMaxUpdatedAtByTenantIdAndProfileId(tenantId, profileId),
                        policyScopeCurrencyRepository.findMaxUpdatedAtByTenantIdAndProfileId(tenantId, profileId),
                        policySodRuleRepository.findMaxUpdatedAtByTenantIdAndProfileId(tenantId, profileId))
                .flatMap(Optional::stream)
                .max(Instant::compareTo)
                .orElse(null);
    }
}
