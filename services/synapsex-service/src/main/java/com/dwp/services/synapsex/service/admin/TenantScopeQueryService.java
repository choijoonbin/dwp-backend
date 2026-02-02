package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.TenantScopeResponseDto;
import com.dwp.services.synapsex.entity.TenantCompanyCodeScope;
import com.dwp.services.synapsex.entity.TenantCurrencyScope;
import com.dwp.services.synapsex.entity.TenantSodRule;
import com.dwp.services.synapsex.repository.TenantCompanyCodeScopeRepository;
import com.dwp.services.synapsex.repository.TenantCurrencyScopeRepository;
import com.dwp.services.synapsex.repository.TenantScopeSeedStateRepository;
import com.dwp.services.synapsex.repository.TenantSodRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Tenant Scope 조회 전용 (tenant-level V8 테이블).
 */
@Service
@RequiredArgsConstructor
public class TenantScopeQueryService {

    private final TenantCompanyCodeScopeRepository companyCodeRepo;
    private final TenantCurrencyScopeRepository currencyRepo;
    private final TenantSodRuleRepository sodRuleRepo;
    private final TenantScopeSeedStateRepository seedStateRepo;

    @Transactional(readOnly = true)
    public TenantScopeResponseDto buildResponse(Long tenantId) {
        List<TenantScopeResponseDto.CompanyCodeDto> companyCodes = companyCodeRepo.findByTenantIdOrderByBukrsAsc(tenantId)
                .stream()
                .map(e -> TenantScopeResponseDto.CompanyCodeDto.builder()
                        .bukrs(e.getBukrs())
                        .enabled(e.getIsEnabled())
                        .source(e.getSource())
                        .build())
                .toList();

        List<TenantScopeResponseDto.CurrencyDto> currencies = currencyRepo.findByTenantIdOrderByWaersAsc(tenantId)
                .stream()
                .map(e -> TenantScopeResponseDto.CurrencyDto.builder()
                        .waers(e.getWaers())
                        .enabled(e.getIsEnabled())
                        .fxControlMode(e.getFxControlMode())
                        .build())
                .toList();

        List<TenantScopeResponseDto.SodRuleDto> sodRules = sodRuleRepo.findByTenantIdOrderByRuleKeyAsc(tenantId)
                .stream()
                .map(e -> {
                    List<String> appliesTo = new ArrayList<>();
                    if (e.getAppliesTo() != null && e.getAppliesTo().isArray()) {
                        e.getAppliesTo().forEach(n -> appliesTo.add(n.asText()));
                    }
                    return TenantScopeResponseDto.SodRuleDto.builder()
                            .ruleKey(e.getRuleKey())
                            .title(e.getTitle())
                            .description(e.getDescription())
                            .enabled(e.getIsEnabled())
                            .severity(e.getSeverity())
                            .appliesTo(appliesTo)
                            .build();
                })
                .toList();

        Instant lastUpdated = Stream.of(
                        companyCodeRepo.findByTenantIdOrderByBukrsAsc(tenantId).stream().map(TenantCompanyCodeScope::getUpdatedAt),
                        currencyRepo.findByTenantIdOrderByWaersAsc(tenantId).stream().map(TenantCurrencyScope::getUpdatedAt),
                        sodRuleRepo.findByTenantIdOrderByRuleKeyAsc(tenantId).stream().map(TenantSodRule::getUpdatedAt))
                .flatMap(s -> s)
                .max(Instant::compareTo)
                .orElse(Instant.now());

        return TenantScopeResponseDto.builder()
                .companyCodes(companyCodes)
                .currencies(currencies)
                .sodRules(sodRules)
                .meta(TenantScopeResponseDto.TenantScopeMetaDto.builder()
                        .tenantId(tenantId)
                        .lastUpdatedAt(lastUpdated)
                        .seeded(seedStateRepo.findByTenantId(tenantId).isPresent())
                        .build())
                .build();
    }
}
