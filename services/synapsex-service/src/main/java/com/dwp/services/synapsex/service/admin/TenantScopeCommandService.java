package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.*;
import com.dwp.services.synapsex.entity.*;
import com.dwp.services.synapsex.repository.*;
import com.dwp.services.synapsex.service.audit.AuditWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Tenant Scope 명령 전용 (tenant-level V8 테이블).
 * 시드, 토글, Bulk 업데이트.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantScopeCommandService {

    private static final List<String> DEFAULT_BUKRS = List.of("1000", "2000", "3000");
    private static final List<String> DEFAULT_WAERS = List.of("KRW", "USD", "EUR");
    private static final List<SodSeed> DEFAULT_SOD_RULES = List.of(
            new SodSeed("NO_SELF_APPROVE", "Requester cannot approve own action", "WARN", List.of()),
            new SodSeed("DUAL_CONTROL", "High value actions require two approvals", "WARN", List.of()),
            new SodSeed("FINANCE_VS_SECURITY", "Security admins cannot edit finance policy", "BLOCK", List.of())
    );

    @PersistenceContext
    private EntityManager entityManager;

    private final TenantCompanyCodeScopeRepository companyCodeRepo;
    private final TenantCurrencyScopeRepository currencyRepo;
    private final TenantSodRuleRepository sodRuleRepo;
    private final TenantScopeSeedStateRepository seedStateRepo;
    private final TenantScopeQueryService queryService;
    private final AuditWriter auditWriter;

    @Transactional
    public void ensureSeeded(Long tenantId) {
        if (seedStateRepo.findByTenantId(tenantId).isPresent()) {
            return;
        }
        if (companyCodeRepo.existsByTenantId(tenantId)
                || currencyRepo.existsByTenantId(tenantId)
                || sodRuleRepo.existsByTenantId(tenantId)) {
            seedStateRepo.save(TenantScopeSeedState.builder()
                    .tenantId(tenantId)
                    .seededAt(Instant.now())
                    .seedVersion("v1")
                    .build());
            return;
        }
        doSeed(tenantId);
    }

    @Transactional
    protected void doSeed(Long tenantId) {
        Instant now = Instant.now();

        List<String> bukrsList = fetchTopBukrsByDocCount(tenantId, 10);
        if (bukrsList.isEmpty()) bukrsList = DEFAULT_BUKRS;
        for (String bukrs : bukrsList.stream().limit(20).toList()) {
            if (companyCodeRepo.findByTenantIdAndBukrs(tenantId, bukrs).isEmpty()) {
                companyCodeRepo.save(TenantCompanyCodeScope.builder()
                        .tenantId(tenantId)
                        .bukrs(bukrs.toUpperCase())
                        .isEnabled(true)
                        .source("SEED")
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            }
        }

        List<String> waersList = fetchTopWaersByDocCount(tenantId, 5);
        if (waersList.isEmpty()) waersList = DEFAULT_WAERS;
        for (String waers : waersList.stream().limit(10).toList()) {
            if (currencyRepo.findByTenantIdAndWaers(tenantId, waers).isEmpty()) {
                currencyRepo.save(TenantCurrencyScope.builder()
                        .tenantId(tenantId)
                        .waers(waers.toUpperCase())
                        .isEnabled(true)
                        .fxControlMode("ALLOW")
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            }
        }

        for (SodSeed s : DEFAULT_SOD_RULES) {
            if (sodRuleRepo.findByTenantIdAndRuleKey(tenantId, s.ruleKey).isEmpty()) {
                ArrayNode arr = JsonNodeFactory.instance.arrayNode();
                s.appliesTo.forEach(arr::add);
                sodRuleRepo.save(TenantSodRule.builder()
                        .tenantId(tenantId)
                        .ruleKey(s.ruleKey)
                        .title(s.ruleKey)
                        .description(s.description)
                        .isEnabled(true)
                        .severity(s.severity)
                        .appliesTo(arr)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            }
        }

        seedStateRepo.save(TenantScopeSeedState.builder()
                .tenantId(tenantId)
                .seededAt(now)
                .seedVersion("v1")
                .build());
        log.info("Tenant scope seeded: tenantId={}", tenantId);
    }

    @SuppressWarnings("unchecked")
    List<String> fetchTopBukrsByDocCount(Long tenantId, int limit) {
        try {
            List<Object[]> rows = entityManager.createNativeQuery(
                    "SELECT bukrs, COUNT(*) as cnt FROM dwp_aura.fi_doc_header WHERE tenant_id = :tid GROUP BY bukrs ORDER BY cnt DESC LIMIT :lim")
                    .setParameter("tid", tenantId)
                    .setParameter("lim", limit)
                    .getResultList();
            List<Object[]> oiRows = entityManager.createNativeQuery(
                    "SELECT bukrs, COUNT(*) FROM dwp_aura.fi_open_item WHERE tenant_id = :tid GROUP BY bukrs")
                    .setParameter("tid", tenantId)
                    .getResultList();
            Map<String, Long> countByBukrs = new HashMap<>();
            for (Object[] r : rows) countByBukrs.merge(r[0].toString(), ((Number) r[1]).longValue(), Long::sum);
            for (Object[] r : oiRows) countByBukrs.merge(r[0].toString(), ((Number) r[1]).longValue(), Long::sum);
            return countByBukrs.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .toList();
        } catch (Exception e) {
            log.debug("fi_doc_header not available: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    List<String> fetchTopWaersByDocCount(Long tenantId, int limit) {
        try {
            Map<String, Long> countByWaers = new HashMap<>();
            entityManager.createNativeQuery(
                    "SELECT waers, COUNT(*) FROM dwp_aura.fi_doc_header WHERE tenant_id = :tid AND waers IS NOT NULL GROUP BY waers")
                    .setParameter("tid", tenantId)
                    .getResultList().forEach(r -> {
                        Object[] row = (Object[]) r;
                        countByWaers.merge(row[0].toString(), ((Number) row[1]).longValue(), Long::sum);
                    });
            entityManager.createNativeQuery(
                    "SELECT currency, COUNT(*) FROM dwp_aura.fi_open_item WHERE tenant_id = :tid GROUP BY currency")
                    .setParameter("tid", tenantId)
                    .getResultList().forEach(r -> {
                        Object[] row = (Object[]) r;
                        countByWaers.merge(row[0].toString(), ((Number) row[1]).longValue(), Long::sum);
                    });
            return countByWaers.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .toList();
        } catch (Exception e) {
            log.debug("fi_doc_header/fi_open_item not available: {}", e.getMessage());
            return List.of();
        }
    }

    @Transactional
    public TenantScopeResponseDto toggleCompanyCode(Long tenantId, Long actorUserId, String bukrs,
                                                    boolean enabled, String ipAddress, String userAgent, String gatewayRequestId) {
        TenantScopeValidator.validateBukrs(bukrs);
        TenantCompanyCodeScope e = companyCodeRepo.findByTenantIdAndBukrs(tenantId, bukrs)
                .orElseGet(() -> {
                    TenantCompanyCodeScope n = TenantCompanyCodeScope.builder()
                            .tenantId(tenantId)
                            .bukrs(bukrs.toUpperCase())
                            .isEnabled(enabled)
                            .source("MANUAL")
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();
                    return companyCodeRepo.save(n);
                });
        Boolean beforeEnabled = e.getIsEnabled();
        e.setIsEnabled(enabled);
        e.setUpdatedAt(Instant.now());
        companyCodeRepo.save(e);
        Map<String, Object> diff = AuditWriter.buildDiffJson("enabled", beforeEnabled, enabled);
        auditWriter.logTenantScopeChange(tenantId, actorUserId, "UPDATE", "TENANT_SCOPE_COMPANY_CODE", bukrs, diff, "Company Codes", ipAddress, userAgent, gatewayRequestId);
        return queryService.buildResponse(tenantId);
    }

    @Transactional
    public TenantScopeResponseDto toggleCurrency(Long tenantId, Long actorUserId, String waers,
                                                 Boolean enabled, String fxControlMode, String ipAddress, String userAgent, String gatewayRequestId) {
        TenantScopeValidator.validateWaers(waers);
        TenantCurrencyScope e = currencyRepo.findByTenantIdAndWaers(tenantId, waers)
                .orElseGet(() -> {
                    TenantCurrencyScope n = TenantCurrencyScope.builder()
                            .tenantId(tenantId)
                            .waers(waers.toUpperCase())
                            .isEnabled(enabled != null ? enabled : true)
                            .fxControlMode(fxControlMode != null ? fxControlMode : "ALLOW")
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();
                    return currencyRepo.save(n);
                });
        Map<String, Object> diff = new HashMap<>();
        if (enabled != null && !enabled.equals(e.getIsEnabled())) {
            diff.put("enabled", Map.of("before", e.getIsEnabled(), "after", enabled));
        }
        if (fxControlMode != null && List.of("ALLOW", "FX_REQUIRED", "FX_LOCKED").contains(fxControlMode) && !fxControlMode.equals(e.getFxControlMode())) {
            diff.put("fxControlMode", Map.of("before", e.getFxControlMode(), "after", fxControlMode));
        }
        if (enabled != null) e.setIsEnabled(enabled);
        if (fxControlMode != null && List.of("ALLOW", "FX_REQUIRED", "FX_LOCKED").contains(fxControlMode)) {
            e.setFxControlMode(fxControlMode);
        }
        e.setUpdatedAt(Instant.now());
        currencyRepo.save(e);
        if (!diff.isEmpty()) {
            auditWriter.logTenantScopeChange(tenantId, actorUserId, "UPDATE", "TENANT_SCOPE_CURRENCY", waers, diff, "Currencies", ipAddress, userAgent, gatewayRequestId);
        }
        return queryService.buildResponse(tenantId);
    }

    @Transactional
    public TenantScopeResponseDto toggleSodRule(Long tenantId, Long actorUserId, String ruleKey,
                                                Boolean enabled, String severity, String ipAddress, String userAgent, String gatewayRequestId) {
        TenantScopeValidator.validateRuleKey(ruleKey);
        TenantSodRule e = sodRuleRepo.findByTenantIdAndRuleKey(tenantId, ruleKey)
                .orElseGet(() -> {
                    ArrayNode arr = JsonNodeFactory.instance.arrayNode();
                    TenantSodRule n = TenantSodRule.builder()
                            .tenantId(tenantId)
                            .ruleKey(ruleKey.toUpperCase().replace(' ', '_'))
                            .title(ruleKey)
                            .description(null)
                            .isEnabled(enabled != null ? enabled : true)
                            .severity(severity != null ? severity : "WARN")
                            .appliesTo(arr)
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build();
                    return sodRuleRepo.save(n);
                });
        Map<String, Object> diff = new HashMap<>();
        if (enabled != null && !enabled.equals(e.getIsEnabled())) {
            diff.put("enabled", Map.of("before", e.getIsEnabled(), "after", enabled));
        }
        if (severity != null && List.of("INFO", "WARN", "BLOCK").contains(severity) && !severity.equals(e.getSeverity())) {
            diff.put("severity", Map.of("before", e.getSeverity(), "after", severity));
        }
        if (enabled != null) e.setIsEnabled(enabled);
        if (severity != null && List.of("INFO", "WARN", "BLOCK").contains(severity)) e.setSeverity(severity);
        e.setUpdatedAt(Instant.now());
        sodRuleRepo.save(e);
        if (!diff.isEmpty()) {
            auditWriter.logTenantScopeChange(tenantId, actorUserId, "UPDATE", "TENANT_SCOPE_SOD_RULE", ruleKey, diff, "SoD", ipAddress, userAgent, gatewayRequestId);
        }
        return queryService.buildResponse(tenantId);
    }

    @Transactional
    public TenantScopeResponseDto bulkUpdateCompanyCodes(Long tenantId, Long actorUserId,
                                                         BulkUpdateCompanyCodesRequest request,
                                                         String ipAddress, String userAgent, String gatewayRequestId) {
        Map<String, Object> diff = new HashMap<>();
        List<String> changed = new ArrayList<>();
        Instant now = Instant.now();
        for (BulkUpdateCompanyCodesRequest.CompanyCodeItemDto item : request.getItems()) {
            TenantScopeValidator.validateBukrs(item.getBukrs());
            String bukrs = item.getBukrs().toUpperCase();
            Optional<TenantCompanyCodeScope> opt = companyCodeRepo.findByTenantIdAndBukrs(tenantId, bukrs);
            TenantCompanyCodeScope e = opt.orElseGet(() -> companyCodeRepo.save(TenantCompanyCodeScope.builder()
                    .tenantId(tenantId)
                    .bukrs(bukrs)
                    .isEnabled(item.getEnabled() != null ? item.getEnabled() : true)
                    .source(item.getSource() != null ? item.getSource() : "MANUAL")
                    .createdAt(now)
                    .updatedAt(now)
                    .build()));
            boolean updated = false;
            if (item.getEnabled() != null && !item.getEnabled().equals(e.getIsEnabled())) {
                e.setIsEnabled(item.getEnabled());
                updated = true;
            }
            if (item.getSource() != null && !item.getSource().equals(e.getSource())) {
                e.setSource(item.getSource());
                updated = true;
            }
            if (updated) {
                e.setUpdatedAt(now);
                companyCodeRepo.save(e);
                changed.add(bukrs);
            }
        }
        diff.put("changedKeys", changed);
        diff.put("count", changed.size());
        auditWriter.logTenantScopeBulkChange(tenantId, actorUserId, "TENANT_SCOPE_COMPANY_CODE", diff, "Company Codes", changed.size(), ipAddress, userAgent, gatewayRequestId);
        return queryService.buildResponse(tenantId);
    }

    @Transactional
    public TenantScopeResponseDto bulkUpdateCurrencies(Long tenantId, Long actorUserId,
                                                       BulkUpdateCurrenciesRequest request,
                                                       String ipAddress, String userAgent, String gatewayRequestId) {
        Map<String, Object> diff = new HashMap<>();
        List<String> changed = new ArrayList<>();
        Instant now = Instant.now();
        for (BulkUpdateCurrenciesRequest.CurrencyItemDto item : request.getItems()) {
            TenantScopeValidator.validateWaers(item.getWaers());
            String waers = item.getWaers().toUpperCase();
            Optional<TenantCurrencyScope> opt = currencyRepo.findByTenantIdAndWaers(tenantId, waers);
            TenantCurrencyScope e = opt.orElseGet(() -> currencyRepo.save(TenantCurrencyScope.builder()
                    .tenantId(tenantId)
                    .waers(waers)
                    .isEnabled(item.getEnabled() != null ? item.getEnabled() : true)
                    .fxControlMode(item.getFxControlMode() != null ? item.getFxControlMode() : "ALLOW")
                    .createdAt(now)
                    .updatedAt(now)
                    .build()));
            boolean updated = false;
            if (item.getEnabled() != null && !item.getEnabled().equals(e.getIsEnabled())) {
                e.setIsEnabled(item.getEnabled());
                updated = true;
            }
            if (item.getFxControlMode() != null && List.of("ALLOW", "FX_REQUIRED", "FX_LOCKED").contains(item.getFxControlMode())
                    && !item.getFxControlMode().equals(e.getFxControlMode())) {
                e.setFxControlMode(item.getFxControlMode());
                updated = true;
            }
            if (updated) {
                e.setUpdatedAt(now);
                currencyRepo.save(e);
                changed.add(waers);
            }
        }
        diff.put("changedKeys", changed);
        diff.put("count", changed.size());
        auditWriter.logTenantScopeBulkChange(tenantId, actorUserId, "TENANT_SCOPE_CURRENCY", diff, "Currencies", changed.size(), ipAddress, userAgent, gatewayRequestId);
        return queryService.buildResponse(tenantId);
    }

    @Transactional
    public TenantScopeResponseDto bulkUpdateSodRules(Long tenantId, Long actorUserId,
                                                     BulkUpdateSodRulesRequest request,
                                                     String ipAddress, String userAgent, String gatewayRequestId) {
        Map<String, Object> diff = new HashMap<>();
        List<String> changed = new ArrayList<>();
        Instant now = Instant.now();
        for (BulkUpdateSodRulesRequest.SodRuleItemDto item : request.getItems()) {
            TenantScopeValidator.validateRuleKey(item.getRuleKey());
            String ruleKey = item.getRuleKey().toUpperCase().replace(' ', '_');
            Optional<TenantSodRule> opt = sodRuleRepo.findByTenantIdAndRuleKey(tenantId, ruleKey);
            TenantSodRule e = opt.orElseGet(() -> {
                ArrayNode arr = JsonNodeFactory.instance.arrayNode();
                if (item.getAppliesTo() != null) item.getAppliesTo().forEach(arr::add);
                return sodRuleRepo.save(TenantSodRule.builder()
                        .tenantId(tenantId)
                        .ruleKey(ruleKey)
                        .title(item.getTitle() != null ? item.getTitle() : ruleKey)
                        .description(item.getDescription())
                        .isEnabled(item.getEnabled() != null ? item.getEnabled() : true)
                        .severity(item.getSeverity() != null ? item.getSeverity() : "WARN")
                        .appliesTo(arr)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            });
            boolean updated = false;
            if (item.getEnabled() != null && !item.getEnabled().equals(e.getIsEnabled())) {
                e.setIsEnabled(item.getEnabled());
                updated = true;
            }
            if (item.getSeverity() != null && List.of("INFO", "WARN", "BLOCK").contains(item.getSeverity()) && !item.getSeverity().equals(e.getSeverity())) {
                e.setSeverity(item.getSeverity());
                updated = true;
            }
            if (item.getTitle() != null && !item.getTitle().equals(e.getTitle())) {
                e.setTitle(item.getTitle());
                updated = true;
            }
            if (item.getDescription() != null && !Objects.equals(item.getDescription(), e.getDescription())) {
                e.setDescription(item.getDescription());
                updated = true;
            }
            if (item.getAppliesTo() != null) {
                ArrayNode arr = JsonNodeFactory.instance.arrayNode();
                item.getAppliesTo().forEach(arr::add);
                e.setAppliesTo(arr);
                updated = true;
            }
            if (updated) {
                e.setUpdatedAt(now);
                sodRuleRepo.save(e);
                changed.add(ruleKey);
            }
        }
        diff.put("changedKeys", changed);
        diff.put("count", changed.size());
        auditWriter.logTenantScopeBulkChange(tenantId, actorUserId, "TENANT_SCOPE_SOD_RULE", diff, "SoD", changed.size(), ipAddress, userAgent, gatewayRequestId);
        return queryService.buildResponse(tenantId);
    }

    private record SodSeed(String ruleKey, String description, String severity, List<String> appliesTo) {}
}
