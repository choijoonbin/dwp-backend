package com.dwp.services.synapsex.service.admin;

import com.dwp.services.synapsex.dto.admin.CatalogDto;
import com.dwp.services.synapsex.repository.TenantCompanyCodeScopeRepository;
import com.dwp.services.synapsex.repository.TenantCurrencyScopeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tenant Scope Catalog (BUKRS, WAERS 카탈로그).
 * FI 데이터 + tenant_scope 테이블 조합.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantScopeCatalogService {

    @PersistenceContext
    private EntityManager entityManager;

    private final TenantCompanyCodeScopeRepository companyCodeRepo;
    private final TenantCurrencyScopeRepository currencyRepo;

    /**
     * 회사코드 카탈로그: FI distinct + tenant_company_code_scope.
     */
    @Transactional(readOnly = true)
    public CatalogDto getCompanyCodesCatalog(Long tenantId) {
        List<CatalogDto.CompanyCodeCatalogItem> fromFi = fetchBukrsFromFiWithCount(tenantId);
        Set<String> fromScope = companyCodeRepo.findByTenantIdOrderByBukrsAsc(tenantId).stream()
                .map(e -> e.getBukrs())
                .collect(Collectors.toSet());
        Set<String> all = new LinkedHashSet<>();
        fromFi.forEach(c -> all.add(c.getBukrs()));
        fromScope.forEach(all::add);
        List<CatalogDto.CompanyCodeCatalogItem> result = new ArrayList<>();
        for (String bukrs : all.stream().sorted().toList()) {
            Optional<CatalogDto.CompanyCodeCatalogItem> fiOpt = fromFi.stream().filter(c -> c.getBukrs().equals(bukrs)).findFirst();
            result.add(CatalogDto.CompanyCodeCatalogItem.builder()
                    .bukrs(bukrs)
                    .docCount(fiOpt.map(CatalogDto.CompanyCodeCatalogItem::getDocCount).orElse(null))
                    .lastSeenAt(fiOpt.map(CatalogDto.CompanyCodeCatalogItem::getLastSeenAt).orElse(null))
                    .build());
        }
        return CatalogDto.builder().companyCodes(result).currencies(List.of()).build();
    }

    /**
     * 통화 카탈로그: FI distinct + tenant_currency_scope.
     */
    @Transactional(readOnly = true)
    public CatalogDto getCurrenciesCatalog(Long tenantId) {
        List<CatalogDto.CurrencyCatalogItem> fromFi = fetchWaersFromFiWithCount(tenantId);
        Set<String> fromScope = currencyRepo.findByTenantIdOrderByWaersAsc(tenantId).stream()
                .map(e -> e.getWaers())
                .collect(Collectors.toSet());
        Set<String> all = new LinkedHashSet<>();
        fromFi.forEach(c -> all.add(c.getWaers()));
        fromScope.forEach(all::add);
        List<CatalogDto.CurrencyCatalogItem> result = new ArrayList<>();
        for (String waers : all.stream().sorted().toList()) {
            Optional<CatalogDto.CurrencyCatalogItem> fiOpt = fromFi.stream().filter(c -> c.getWaers().equals(waers)).findFirst();
            result.add(CatalogDto.CurrencyCatalogItem.builder()
                    .waers(waers)
                    .docCount(fiOpt.map(CatalogDto.CurrencyCatalogItem::getDocCount).orElse(null))
                    .lastSeenAt(fiOpt.map(CatalogDto.CurrencyCatalogItem::getLastSeenAt).orElse(null))
                    .build());
        }
        return CatalogDto.builder().companyCodes(List.of()).currencies(result).build();
    }

    /**
     * 전체 카탈로그 (company + currencies).
     */
    @Transactional(readOnly = true)
    public CatalogDto getFullCatalog(Long tenantId) {
        return CatalogDto.builder()
                .companyCodes(getCompanyCodesCatalog(tenantId).getCompanyCodes())
                .currencies(getCurrenciesCatalog(tenantId).getCurrencies())
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<CatalogDto.CompanyCodeCatalogItem> fetchBukrsFromFiWithCount(Long tenantId) {
        try {
            List<Object[]> rows = entityManager.createNativeQuery(
                    "SELECT bukrs, COUNT(*) as cnt, MAX(created_at) as last_at " +
                    "FROM dwp_aura.fi_doc_header WHERE tenant_id = :tid GROUP BY bukrs ORDER BY cnt DESC LIMIT 50")
                    .setParameter("tid", tenantId)
                    .getResultList();
            List<Object[]> oiRows = entityManager.createNativeQuery(
                    "SELECT bukrs, COUNT(*) FROM dwp_aura.fi_open_item WHERE tenant_id = :tid GROUP BY bukrs")
                    .setParameter("tid", tenantId)
                    .getResultList();
            Map<String, Long> countByBukrs = new HashMap<>();
            Map<String, Instant> lastByBukrs = new HashMap<>();
            for (Object[] r : rows) {
                String bukrs = r[0].toString();
                countByBukrs.merge(bukrs, ((Number) r[1]).longValue(), Long::sum);
                if (r[2] != null) lastByBukrs.put(bukrs, ((java.sql.Timestamp) r[2]).toInstant());
            }
            for (Object[] r : oiRows) {
                String bukrs = r[0].toString();
                countByBukrs.merge(bukrs, ((Number) r[1]).longValue(), Long::sum);
            }
            return countByBukrs.entrySet().stream()
                    .map(e -> CatalogDto.CompanyCodeCatalogItem.builder()
                            .bukrs(e.getKey())
                            .docCount(e.getValue())
                            .lastSeenAt(lastByBukrs.get(e.getKey()))
                            .build())
                    .sorted(Comparator.comparing(CatalogDto.CompanyCodeCatalogItem::getDocCount, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(50)
                    .toList();
        } catch (Exception e) {
            log.debug("fi_doc_header/fi_open_item not available: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<CatalogDto.CurrencyCatalogItem> fetchWaersFromFiWithCount(Long tenantId) {
        try {
            List<Object[]> rows = entityManager.createNativeQuery(
                    "SELECT waers, COUNT(*) as cnt, MAX(created_at) as last_at " +
                    "FROM dwp_aura.fi_doc_header WHERE tenant_id = :tid AND waers IS NOT NULL GROUP BY waers ORDER BY cnt DESC LIMIT 20")
                    .setParameter("tid", tenantId)
                    .getResultList();
            List<Object[]> oiRows = entityManager.createNativeQuery(
                    "SELECT currency, COUNT(*) as cnt, MAX(last_update_ts) as last_at " +
                    "FROM dwp_aura.fi_open_item WHERE tenant_id = :tid GROUP BY currency ORDER BY cnt DESC LIMIT 20")
                    .setParameter("tid", tenantId)
                    .getResultList();
            Map<String, Long> countByWaers = new HashMap<>();
            Map<String, Instant> lastByWaers = new HashMap<>();
            for (Object[] r : rows) {
                String waers = r[0].toString();
                countByWaers.merge(waers, ((Number) r[1]).longValue(), Long::sum);
                if (r[2] != null) lastByWaers.put(waers, ((java.sql.Timestamp) r[2]).toInstant());
            }
            for (Object[] r : oiRows) {
                String waers = r[0].toString();
                countByWaers.merge(waers, ((Number) r[1]).longValue(), Long::sum);
                if (r[2] != null) lastByWaers.put(waers, ((java.sql.Timestamp) r[2]).toInstant());
            }
            return countByWaers.entrySet().stream()
                    .map(e -> CatalogDto.CurrencyCatalogItem.builder()
                            .waers(e.getKey())
                            .docCount(e.getValue())
                            .lastSeenAt(lastByWaers.get(e.getKey()))
                            .build())
                    .sorted(Comparator.comparing(CatalogDto.CurrencyCatalogItem::getDocCount, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(20)
                    .toList();
        } catch (Exception e) {
            log.debug("fi_doc_header/fi_open_item not available: {}", e.getMessage());
            return List.of();
        }
    }
}
