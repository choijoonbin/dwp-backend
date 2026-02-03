package com.dwp.services.synapsex.service.entity;

import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.entity.Entity360Dto;
import com.dwp.services.synapsex.dto.entity.EntityChangeLogDto;
import com.dwp.services.synapsex.dto.entity.EntityListRowDto;
import com.dwp.services.synapsex.entity.*;
import com.dwp.services.synapsex.repository.*;
import com.dwp.services.synapsex.scope.TenantScopeResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EntityQueryService {

    private final JPAQueryFactory queryFactory;
    private final BpPartyRepository bpPartyRepository;
    private final FiOpenItemRepository fiOpenItemRepository;
    private final FiDocItemRepository fiDocItemRepository;
    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final SapChangeLogRepository sapChangeLogRepository;
    private final TenantScopeResolver tenantScopeResolver;

    private static final QBpParty p = QBpParty.bpParty;
    private static final QAgentCase ac = QAgentCase.agentCase;
    private static final QFiDocItem fi = QFiDocItem.fiDocItem;

    @Transactional(readOnly = true)
    public PageResponse<EntityListRowDto> findEntities(Long tenantId, EntityListQuery query) {
        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(p.tenantId.eq(tenantId));

        if (query.getType() != null && !query.getType().isBlank()) {
            predicate.and(p.partyType.eq(query.getType().toUpperCase()));
        }
        Set<String> allowedBukrs = tenantScopeResolver.resolveEnabledBukrs(tenantId);
        if (allowedBukrs.isEmpty()) {
            predicate.and(p.partyId.eq(-1L));
        } else {
            Set<String> bukrsFilter = query.getBukrs() != null && !query.getBukrs().isBlank()
                    ? (allowedBukrs.contains(query.getBukrs().toUpperCase()) ? Set.of(query.getBukrs().toUpperCase()) : Set.<String>of())
                    : allowedBukrs;
            if (bukrsFilter.isEmpty()) {
                predicate.and(p.partyId.eq(-1L));
            } else {
                predicate.and(JPAExpressions.selectOne().from(fi)
                        .where(fi.tenantId.eq(tenantId), fi.bukrs.in(bukrsFilter),
                                fi.lifnr.eq(p.partyCode).or(fi.kunnr.eq(p.partyCode)))
                        .exists());
            }
        }
        if (query.getCountry() != null && !query.getCountry().isBlank()) {
            predicate.and(p.country.eq(query.getCountry()));
        }
        if (query.getQ() != null && !query.getQ().isBlank()) {
            String q = query.getQ().trim();
            predicate.and(p.nameDisplay.containsIgnoreCase(q).or(p.partyCode.containsIgnoreCase(q)));
        }

        OrderSpecifier<?> orderBy = p.updatedAt.desc();
        if (query.getSort() != null && !query.getSort().isBlank()) {
            String[] parts = query.getSort().split(",");
            String prop = parts[0].trim();
            boolean asc = parts.length < 2 || !"desc".equalsIgnoreCase(parts[1].trim());
            orderBy = switch (prop.toLowerCase()) {
                case "partycode" -> asc ? p.partyCode.asc() : p.partyCode.desc();
                case "namedisplay" -> asc ? p.nameDisplay.asc() : p.nameDisplay.desc();
                default -> asc ? p.updatedAt.asc() : p.updatedAt.desc();
            };
        }

        int page = Math.max(0, query.getPage());
        int size = Math.min(100, Math.max(1, query.getSize()));

        List<BpParty> parties = queryFactory.selectFrom(p)
                .where(predicate)
                .orderBy(orderBy)
                .offset((long) page * size)
                .limit(size)
                .fetch();

        long total = queryFactory.selectFrom(p)
                .where(predicate)
                .fetchCount();

        List<EntityListRowDto> rows = buildEntityListRows(tenantId, parties);
        if (query.getRiskMin() != null || query.getRiskMax() != null) {
            rows = rows.stream()
                    .filter(r -> r.getRiskScore() != null)
                    .filter(r -> {
                        if (query.getRiskMin() != null && r.getRiskScore() < query.getRiskMin()) return false;
                        if (query.getRiskMax() != null && r.getRiskScore() > query.getRiskMax()) return false;
                        return true;
                    })
                    .toList();
        }
        if (Boolean.TRUE.equals(query.getHasOpenItems())) {
            rows = rows.stream().filter(r -> r.getOpenItemsCount() > 0).toList();
        }

        return PageResponse.of(rows, total, page, size);
    }

    private List<EntityListRowDto> buildEntityListRows(Long tenantId, List<BpParty> parties) {
        List<EntityListRowDto> rows = new ArrayList<>();
        LocalDate now = LocalDate.now();
        for (BpParty party : parties) {
            List<FiOpenItem> openItems = getOpenItemsForParty(tenantId, party);
            List<FiOpenItem> notCleared = openItems.stream().filter(oi -> !Boolean.TRUE.equals(oi.getCleared())).toList();
            BigDecimal totalOpen = openItems.stream()
                    .map(FiOpenItem::getOpenAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            long overdueCount = notCleared.stream()
                    .filter(oi -> oi.getDueDate() != null && oi.getDueDate().isBefore(now))
                    .count();
            BigDecimal overdueTotal = notCleared.stream()
                    .filter(oi -> oi.getDueDate() != null && oi.getDueDate().isBefore(now))
                    .map(FiOpenItem::getOpenAmount)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            List<AgentCase> cases = findCasesForParty(tenantId, party);
            double riskScore = Math.min(100, computeRiskScore(party, openItems.size(), tenantId));
            rows.add(EntityListRowDto.builder()
                    .partyId(party.getPartyId())
                    .type(party.getPartyType())
                    .name(party.getNameDisplay())
                    .country(party.getCountry())
                    .riskScore(riskScore)
                    .riskTrend("STABLE")
                    .openItemsCount(openItems.size())
                    .openItemsTotal(totalOpen)
                    .overdueCount((int) overdueCount)
                    .overdueTotal(overdueTotal)
                    .recentAnomaliesCount(cases.size())
                    .lastChangedAt(party.getLastChangeTs())
                    .build());
        }
        return rows;
    }

    private List<FiOpenItem> getOpenItemsForParty(Long tenantId, BpParty party) {
        if ("VENDOR".equalsIgnoreCase(party.getPartyType())) {
            return fiOpenItemRepository.findByTenantIdAndLifnr(tenantId, party.getPartyCode(), PageRequest.of(0, 1000));
        }
        if ("CUSTOMER".equalsIgnoreCase(party.getPartyType())) {
            return fiOpenItemRepository.findByTenantIdAndKunnr(tenantId, party.getPartyCode(), PageRequest.of(0, 1000));
        }
        return List.of();
    }

    private double computeRiskScore(BpParty party, int openItemsCount, Long tenantId) {
        JsonNode flags = party.getRiskFlags();
        if (flags != null && flags.has("score") && flags.get("score").isNumber()) {
            return flags.get("score").asDouble();
        }
        long caseCount = findCasesForParty(tenantId, party).size();
        int changeCount = sapChangeLogRepository.findByTenantIdAndObjectidOrderByUdateDescUtimeDesc(
                tenantId, party.getPartyCode(), PageRequest.of(0, 50)).size();
        return openItemsCount * 0.3 + changeCount * 0.3 + caseCount * 0.4;
    }

    /** fi_doc_item의 lifnr/kunnr로 party와 연결된 agent_case 조회 */
    private List<AgentCase> findCasesForParty(Long tenantId, BpParty party) {
        BooleanExpression partyMatch = "VENDOR".equalsIgnoreCase(party.getPartyType())
                ? fi.lifnr.eq(party.getPartyCode())
                : fi.kunnr.eq(party.getPartyCode());
        BooleanExpression buzeiMatch = ac.buzei.isNull()
                .or(ac.buzei.eq(fi.buzei));

        return queryFactory.selectFrom(ac)
                .innerJoin(fi).on(
                        ac.tenantId.eq(fi.tenantId),
                        ac.bukrs.eq(fi.bukrs),
                        ac.belnr.eq(fi.belnr),
                        ac.gjahr.eq(fi.gjahr),
                        buzeiMatch)
                .where(ac.tenantId.eq(tenantId), partyMatch)
                .distinct()
                .fetch();
    }

    @Transactional(readOnly = true)
    public PageResponse<EntityChangeLogDto> findChangeLogs(Long tenantId, Long partyId, int page, int size) {
        return bpPartyRepository.findById(partyId)
                .filter(p -> tenantId.equals(p.getTenantId()))
                .map(party -> {
                    var logs = sapChangeLogRepository.findByTenantIdAndObjectidOrderByUdateDescUtimeDesc(
                            tenantId, party.getPartyCode(), PageRequest.of(page, size));
                    long total = sapChangeLogRepository.countByTenantIdAndObjectid(tenantId, party.getPartyCode());
                    var rows = logs.stream()
                            .map(cl -> EntityChangeLogDto.builder()
                                    .changenr(cl.getChangenr())
                                    .udate(cl.getUdate() != null ? cl.getUdate().toString() : null)
                                    .utime(cl.getUtime() != null ? cl.getUtime().toString() : null)
                                    .tabname(cl.getTabname())
                                    .fname(cl.getFname())
                                    .valueOld(cl.getValueOld())
                                    .valueNew(cl.getValueNew())
                                    .build())
                            .toList();
                    return PageResponse.of(rows, total, page, size);
                })
                .orElse(PageResponse.of(List.of(), 0, page, size));
    }

    @Transactional(readOnly = true)
    public Optional<Entity360Dto> findEntity360(Long tenantId, Long partyId) {
        return bpPartyRepository.findById(partyId)
                .filter(party -> tenantId.equals(party.getTenantId()))
                .map(party -> buildEntity360(tenantId, party));
    }

    private Entity360Dto buildEntity360(Long tenantId, BpParty party) {
        List<FiOpenItem> openItems = getOpenItemsForParty(tenantId, party);
        BigDecimal totalOpen = openItems.stream()
                .map(FiOpenItem::getOpenAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate now = LocalDate.now();
        BigDecimal overdue = openItems.stream()
                .filter(oi -> !Boolean.TRUE.equals(oi.getCleared()))
                .filter(oi -> oi.getDueDate() != null && oi.getDueDate().isBefore(now))
                .map(FiOpenItem::getOpenAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var changeLogs = sapChangeLogRepository.findByTenantIdAndObjectidOrderByUdateDescUtimeDesc(
                tenantId, party.getPartyCode(), PageRequest.of(0, 50));

        List<FiDocItem> docItems = "VENDOR".equalsIgnoreCase(party.getPartyType())
                ? fiDocItemRepository.findByTenantIdAndLifnr(tenantId, party.getPartyCode(), PageRequest.of(0, 20))
                : fiDocItemRepository.findByTenantIdAndKunnr(tenantId, party.getPartyCode(), PageRequest.of(0, 20));

        List<AgentCase> linkedCases = findCasesForParty(tenantId, party);

        Set<String> docKeys = new HashSet<>();
        List<Entity360Dto.LinkedDocumentDto> linkedDocs = new ArrayList<>();
        for (FiDocItem item : docItems) {
            String key = item.getBukrs() + "-" + item.getBelnr() + "-" + item.getGjahr();
            if (docKeys.add(key)) {
                fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                        tenantId, item.getBukrs(), item.getBelnr(), item.getGjahr())
                        .ifPresent(h -> linkedDocs.add(Entity360Dto.LinkedDocumentDto.builder()
                                .docKey(key)
                                .budat(h.getBudat() != null ? h.getBudat().toString() : null)
                                .xblnr(h.getXblnr())
                                .bktxt(h.getBktxt())
                                .build()));
            }
        }

        Map<String, Long> riskTrend = new HashMap<>();
        for (AgentCase c : linkedCases) {
            if (c.getDetectedAt() != null) {
                String day = LocalDate.ofInstant(c.getDetectedAt(), java.time.ZoneId.systemDefault()).toString();
                riskTrend.merge(day, 1L, Long::sum);
            }
        }
        for (int i = 0; i < 7; i++) {
            String d = now.minusDays(i).toString();
            riskTrend.putIfAbsent(d, 0L);
        }

        return Entity360Dto.builder()
                .base(Entity360Dto.EntityBaseDto.builder()
                        .partyId(party.getPartyId())
                        .partyType(party.getPartyType())
                        .partyCode(party.getPartyCode())
                        .nameDisplay(party.getNameDisplay())
                        .country(party.getCountry())
                        .lastChangeTs(party.getLastChangeTs() != null ? party.getLastChangeTs().toString() : null)
                        .build())
                .exposureSummary(Entity360Dto.ExposureSummaryDto.builder()
                        .totalOpenAmount(totalOpen != null ? totalOpen.toString() : null)
                        .overdueAmount(overdue != null ? overdue.toString() : null)
                        .avgPaymentDays(null)
                        .build())
                .riskTrend(Entity360Dto.RiskTrendDto.builder()
                        .caseCountByDay(riskTrend)
                        .build())
                .sensitiveChangesTimeline(changeLogs.stream()
                        .map(cl -> Entity360Dto.SensitiveChangeDto.builder()
                                .changenr(cl.getChangenr())
                                .udate(cl.getUdate() != null ? cl.getUdate().toString() : null)
                                .utime(cl.getUtime() != null ? cl.getUtime().toString() : null)
                                .tabname(cl.getTabname())
                                .fname(cl.getFname())
                                .valueOld(cl.getValueOld())
                                .valueNew(cl.getValueNew())
                                .build())
                        .toList())
                .tabs(Entity360Dto.EntityTabsDto.builder()
                        .linkedDocuments(linkedDocs)
                        .linkedOpenItems(openItems.stream().limit(20)
                                .map(oi -> Entity360Dto.LinkedOpenItemDto.builder()
                                        .bukrs(oi.getBukrs())
                                        .belnr(oi.getBelnr())
                                        .gjahr(oi.getGjahr())
                                        .buzei(oi.getBuzei())
                                        .openAmount(oi.getOpenAmount() != null ? oi.getOpenAmount().toString() : null)
                                        .dueDate(oi.getDueDate() != null ? oi.getDueDate().toString() : null)
                                        .build())
                                .toList())
                        .linkedCases(linkedCases.stream().limit(20)
                                .map(c -> Entity360Dto.LinkedCaseDto.builder()
                                        .caseId(c.getCaseId())
                                        .caseType(c.getCaseType())
                                        .severity(c.getSeverity())
                                        .detectedAt(c.getDetectedAt() != null ? c.getDetectedAt().toString() : null)
                                        .build())
                                .toList())
                        .build())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class EntityListQuery {
        private String type;
        private String bukrs;
        private String country;
        private Double riskMin;
        private Double riskMax;
        private Boolean hasOpenItems;
        private String q;
        @lombok.Builder.Default
        private int page = 0;
        @lombok.Builder.Default
        private int size = 20;
        private String sort;
    }
}
