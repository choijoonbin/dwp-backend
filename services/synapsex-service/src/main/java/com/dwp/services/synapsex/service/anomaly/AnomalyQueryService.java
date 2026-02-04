package com.dwp.services.synapsex.service.anomaly;

import com.dwp.services.synapsex.dto.anomaly.AnomalyListRowDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.entity.*;
import com.dwp.services.synapsex.repository.*;
import com.dwp.services.synapsex.scope.DrillDownCodeResolver;
import com.dwp.services.synapsex.util.DrillDownParamUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Phase 2 Anomalies - agent_case where case_type in anomaly whitelist
 */
@Service
@RequiredArgsConstructor
public class AnomalyQueryService {

    private static final Set<String> ANOMALY_TYPE_PREFIXES = Set.of("ANOMALY_");
    private static final Set<String> ANOMALY_TYPE_WHITELIST = Set.of(
            "DUPLICATE_INVOICE", "BANK_CHANGE", "AMOUNT_MISMATCH", "DATE_ANOMALY", "VENDOR_ANOMALY");

    private final JPAQueryFactory queryFactory;
    private final AgentCaseRepository agentCaseRepository;
    private final AgentActionRepository agentActionRepository;
    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final FiDocItemRepository fiDocItemRepository;
    private final BpPartyRepository bpPartyRepository;
    private final DrillDownCodeResolver drillDownCodeResolver;

    private static final QAgentCase c = QAgentCase.agentCase;
    private static final QFiDocHeader h = QFiDocHeader.fiDocHeader;

    private boolean isAnomalyType(String caseType) {
        if (caseType == null) return false;
        if (ANOMALY_TYPE_WHITELIST.contains(caseType.toUpperCase())) return true;
        return ANOMALY_TYPE_PREFIXES.stream().anyMatch(caseType::startsWith);
    }

    @Transactional(readOnly = true)
    public PageResponse<AnomalyListRowDto> findAnomalies(Long tenantId, AnomalyListQuery query) {
        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(c.tenantId.eq(tenantId));
        predicate.and(c.caseType.upper().like("ANOMALY_%")
                .or(c.caseType.in(ANOMALY_TYPE_WHITELIST)));

        List<String> severityList = drillDownCodeResolver.filterValid(DrillDownCodeResolver.GROUP_SEVERITY,
                DrillDownParamUtil.parseMulti(query.getSeverity()));
        if (!severityList.isEmpty()) {
            predicate.and(c.severity.in(severityList));
        } else if (query.getSeverity() != null && !query.getSeverity().isBlank()) {
            predicate.and(c.severity.eq(query.getSeverity()));
        }
        if (query.getAnomalyType() != null && !query.getAnomalyType().isBlank()) {
            predicate.and(c.caseType.eq(query.getAnomalyType()));
        }
        if (query.getDetectedFrom() != null) {
            predicate.and(c.detectedAt.goe(query.getDetectedFrom()));
        }
        if (query.getDetectedTo() != null) {
            predicate.and(c.detectedAt.loe(query.getDetectedTo()));
        }
        if (query.getConfidenceMin() != null && query.getConfidenceMin() >= 0 && query.getConfidenceMin() <= 100) {
            java.math.BigDecimal minScore = java.math.BigDecimal.valueOf(query.getConfidenceMin())
                    .divide(java.math.BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
            predicate.and(c.score.goe(minScore));
        }
        if (query.getWaers() != null && !query.getWaers().isEmpty()) {
            List<Long> caseIdsWithWaers = queryFactory
                    .select(c.caseId)
                    .from(c)
                    .innerJoin(h).on(h.tenantId.eq(c.tenantId)
                            .and(h.bukrs.eq(c.bukrs))
                            .and(h.belnr.eq(c.belnr))
                            .and(h.gjahr.eq(c.gjahr))
                            .and(h.waers.upper().in(query.getWaers().stream().map(String::toUpperCase).toList())))
                    .where(c.tenantId.eq(tenantId))
                    .fetch();
            if (!caseIdsWithWaers.isEmpty()) {
                predicate.and(c.caseId.in(caseIdsWithWaers));
            } else {
                predicate.and(c.caseId.eq(-1L));
            }
        }
        if (query.getQ() != null && !query.getQ().isBlank()) {
            String q = query.getQ().trim();
            predicate.and(c.belnr.containsIgnoreCase(q)
                    .or(c.reasonText.containsIgnoreCase(q)));
        }

        boolean asc = !"desc".equalsIgnoreCase(query.getOrder() != null ? query.getOrder() : "desc");
        OrderSpecifier<?> orderBy = c.detectedAt.desc();
        if (query.getSort() != null && !query.getSort().isBlank()) {
            String[] parts = query.getSort().split(",");
            if (parts.length >= 2) asc = "asc".equalsIgnoreCase(parts[1].trim());
            String field = parts[0].trim().toLowerCase();
            orderBy = "detectedat".equals(field) || "detected_at".equals(field)
                    ? (asc ? c.detectedAt.asc() : c.detectedAt.desc())
                    : (asc ? c.detectedAt.asc() : c.detectedAt.desc());
        }
        int page = Math.max(0, query.getPage());
        int size = Math.min(100, Math.max(1, query.getSize()));

        List<AgentCase> cases = queryFactory.selectFrom(c)
                .where(predicate)
                .orderBy(orderBy)
                .offset((long) page * size)
                .limit(size)
                .fetch();
        long total = queryFactory.selectFrom(c).where(predicate).fetchCount();

        List<AnomalyListRowDto> rows = cases.stream()
                .map(case_ -> buildAnomalyRow(tenantId, case_))
                .toList();
        Map<String, Object> filtersApplied = buildFiltersApplied(query);
        return PageResponse.of(rows, total, page, size,
                query.getSort() != null ? query.getSort() : "detectedAt",
                query.getOrder() != null ? query.getOrder() : "desc",
                filtersApplied);
    }

    private Map<String, Object> buildFiltersApplied(AnomalyListQuery query) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (query.getRange() != null && !query.getRange().isBlank()) m.put("range", query.getRange());
        if (query.getDetectedFrom() != null) m.put("from", query.getDetectedFrom().toString());
        if (query.getDetectedTo() != null) m.put("to", query.getDetectedTo().toString());
        if (query.getStatus() != null && !query.getStatus().isBlank())
            m.put("status", DrillDownParamUtil.parseMulti(query.getStatus()));
        if (query.getSeverity() != null && !query.getSeverity().isBlank())
            m.put("severity", DrillDownParamUtil.parseMulti(query.getSeverity()));
        if (query.getAnomalyType() != null && !query.getAnomalyType().isBlank()) m.put("driverType", query.getAnomalyType());
        if (query.getIds() != null && !query.getIds().isEmpty()) m.put("ids", query.getIds());
        if (query.getCompany() != null && !query.getCompany().isEmpty()) m.put("company", query.getCompany());
        if (query.getWaers() != null && !query.getWaers().isEmpty()) m.put("waers", query.getWaers());
        if (query.getConfidenceMin() != null) m.put("confidenceMin", query.getConfidenceMin());
        if (query.getQ() != null && !query.getQ().isBlank()) m.put("q", query.getQ());
        return m.isEmpty() ? null : m;
    }

    private AnomalyListRowDto buildAnomalyRow(Long tenantId, AgentCase case_) {
        List<String> docKeys = new ArrayList<>();
        if (case_.getBukrs() != null && case_.getBelnr() != null && case_.getGjahr() != null) {
            docKeys.add(case_.getBukrs() + "-" + case_.getBelnr() + "-" + case_.getGjahr());
        }
        Map<String, Object> topEvidence = extractTopEvidence(case_.getEvidenceJson());
        List<Long> partyIds = resolvePartyIds(tenantId, case_);

        return AnomalyListRowDto.builder()
                .anomalyId(case_.getCaseId())
                .anomalyType(case_.getCaseType())
                .severity(case_.getSeverity())
                .score(case_.getScore())
                .detectedAt(case_.getDetectedAt())
                .topEvidence(topEvidence)
                .docKeys(docKeys)
                .partyIds(partyIds)
                .build();
    }

    private Map<String, Object> extractTopEvidence(JsonNode evidenceJson) {
        if (evidenceJson == null || !evidenceJson.isObject()) return Map.of();
        Map<String, Object> result = new HashMap<>();
        if (evidenceJson.has("xblnr_match")) result.put("xblnrMatch", evidenceJson.get("xblnr_match").asText());
        if (evidenceJson.has("amount_match")) result.put("amountMatch", evidenceJson.get("amount_match").asText());
        if (evidenceJson.has("score")) result.put("score", evidenceJson.get("score").asDouble());
        return result;
    }

    private List<Long> resolvePartyIds(Long tenantId, AgentCase case_) {
        if (case_.getBukrs() == null || case_.getBelnr() == null || case_.getGjahr() == null) return List.of();
        List<FiDocItem> items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
        Set<Long> ids = new HashSet<>();
        for (FiDocItem item : items) {
            if (item.getLifnr() != null) {
                bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "VENDOR", item.getLifnr())
                        .ifPresent(p -> ids.add(p.getPartyId()));
            }
            if (item.getKunnr() != null) {
                bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "CUSTOMER", item.getKunnr())
                        .ifPresent(p -> ids.add(p.getPartyId()));
            }
        }
        return new ArrayList<>(ids);
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AnomalyListQuery {
        private String range;
        private java.time.Instant detectedFrom;
        private java.time.Instant detectedTo;
        private String status;
        private String severity;
        private String anomalyType;
        private List<Long> ids;
        private List<String> company;
        private List<String> waers;
        private Integer confidenceMin;
        private String q;
        @lombok.Builder.Default
        private int page = 0;
        @lombok.Builder.Default
        private int size = 20;
        private String sort;
        private String order;
    }
}
