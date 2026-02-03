package com.dwp.services.synapsex.service.case_;

import com.dwp.services.synapsex.dto.case_.CaseDetailDto;
import com.dwp.services.synapsex.dto.case_.CaseListRowDto;
import com.dwp.services.synapsex.dto.case_.CaseTimelineDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.entity.*;
import com.dwp.services.synapsex.repository.*;
import com.dwp.services.synapsex.scope.DrillDownCodeResolver;
import com.dwp.services.synapsex.util.DocKeyUtil;
import org.springframework.data.domain.PageRequest;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Phase 2 Cases 조회 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseQueryService {

    private final JPAQueryFactory queryFactory;
    private final AgentCaseRepository agentCaseRepository;
    private final AgentActionRepository agentActionRepository;
    private final FiDocHeaderRepository fiDocHeaderRepository;
    private final FiDocItemRepository fiDocItemRepository;
    private final FiOpenItemRepository fiOpenItemRepository;
    private final BpPartyRepository bpPartyRepository;
    private final CaseCommentRepository caseCommentRepository;
    private final AuditEventLogRepository auditEventLogRepository;
    private final DrillDownCodeResolver drillDownCodeResolver;

    private static final QAgentCase c = QAgentCase.agentCase;
    private static final QFiDocItem fi = QFiDocItem.fiDocItem;
    private static final QBpParty p = QBpParty.bpParty;

    @Transactional(readOnly = true)
    public PageResponse<CaseListRowDto> findCases(Long tenantId, CaseListQuery query) {
        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(c.tenantId.eq(tenantId));

        List<String> statusList = drillDownCodeResolver.filterValid(DrillDownCodeResolver.GROUP_CASE_STATUS,
                com.dwp.services.synapsex.util.DrillDownParamUtil.parseMulti(query.getStatus()));
        if (!statusList.isEmpty()) {
            predicate.and(c.status.in(statusList));
        } else if (query.getStatus() != null && !query.getStatus().isBlank()) {
            predicate.and(c.status.eq(query.getStatus()));
        }
        List<String> severityList = drillDownCodeResolver.filterValid(DrillDownCodeResolver.GROUP_SEVERITY,
                com.dwp.services.synapsex.util.DrillDownParamUtil.parseMulti(query.getSeverity()));
        if (!severityList.isEmpty()) {
            predicate.and(c.severity.in(severityList));
        } else if (query.getSeverity() != null && !query.getSeverity().isBlank()) {
            predicate.and(c.severity.eq(query.getSeverity()));
        }
        if (query.getCaseType() != null && !query.getCaseType().isBlank()) {
            predicate.and(c.caseType.eq(query.getCaseType()));
        }
        if (query.getDetectedFrom() != null) {
            predicate.and(c.detectedAt.goe(query.getDetectedFrom()));
        }
        if (query.getDetectedTo() != null) {
            predicate.and(c.detectedAt.loe(query.getDetectedTo()));
        }
        if (query.getAssigneeUserId() != null) {
            predicate.and(c.assigneeUserId.eq(query.getAssigneeUserId()));
        }
        if (query.getSlaRisk() != null && !query.getSlaRisk().isBlank()) {
            List<Long> assigneeIdsBySla = resolveAssigneeIdsBySlaRisk(tenantId, query.getSlaRisk());
            if (!assigneeIdsBySla.isEmpty()) {
                predicate.and(c.assigneeUserId.in(assigneeIdsBySla));
            } else if ("AT_RISK".equalsIgnoreCase(query.getSlaRisk()) || "ON_TRACK".equalsIgnoreCase(query.getSlaRisk())) {
                predicate.and(c.caseId.eq(-1L));
            }
        }
        if (query.getCompany() != null && !query.getCompany().isEmpty()) {
            predicate.and(c.bukrs.in(query.getCompany()));
        } else if (query.getCompanyCode() != null && !query.getCompanyCode().isBlank()) {
            predicate.and(c.bukrs.eq(query.getCompanyCode()));
        }
        if (query.getBukrs() != null && !query.getBukrs().isBlank()) {
            predicate.and(c.bukrs.eq(query.getBukrs()));
        }
        if (query.getBelnr() != null && !query.getBelnr().isBlank()) {
            predicate.and(c.belnr.eq(query.getBelnr()));
        }
        if (query.getGjahr() != null && !query.getGjahr().isBlank()) {
            predicate.and(c.gjahr.eq(query.getGjahr()));
        }
        if (query.getBuzei() != null && !query.getBuzei().isBlank()) {
            predicate.and(c.buzei.eq(query.getBuzei()));
        }
        if (query.getDateFrom() != null) {
            predicate.and(c.detectedAt.goe(query.getDateFrom()));
        }
        if (query.getDateTo() != null) {
            predicate.and(c.detectedAt.loe(query.getDateTo()));
        }
        if (query.getSavedViewKey() != null && !query.getSavedViewKey().isBlank()) {
            predicate.and(c.savedViewKey.eq(query.getSavedViewKey()));
        }
        if (query.getIds() != null && !query.getIds().isEmpty()) {
            predicate.and(c.caseId.in(query.getIds()));
        }
        if (query.getCaseKey() != null && !query.getCaseKey().isBlank()) {
            String key = query.getCaseKey().trim();
            if (key.matches("CS-\\d+")) {
                try {
                    long id = Long.parseLong(key.substring(3));
                    predicate.and(c.caseId.eq(id));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (query.getDocumentKey() != null && !query.getDocumentKey().isBlank()) {
            DocKeyUtil.ParsedDocKey docKey = DocKeyUtil.parse(query.getDocumentKey());
            if (docKey != null) {
                predicate.and(c.bukrs.eq(docKey.getBukrs())
                        .and(c.belnr.eq(docKey.getBelnr()))
                        .and(c.gjahr.eq(docKey.getGjahr())));
            }
        }
        if (Boolean.TRUE.equals(query.getHasPendingAction())) {
            List<String> pendingStatuses = drillDownCodeResolver.filterValid(DrillDownCodeResolver.GROUP_ACTION_STATUS,
                    List.of("PENDING_APPROVAL", "PENDING", "QUEUED", "PROPOSED", "PLANNED"));
            if (pendingStatuses.isEmpty()) pendingStatuses = List.of("PENDING_APPROVAL", "PENDING", "QUEUED", "PROPOSED", "PLANNED");
            List<Long> caseIdsWithPending = queryFactory.select(QAgentAction.agentAction.caseId)
                    .from(QAgentAction.agentAction)
                    .where(QAgentAction.agentAction.tenantId.eq(tenantId)
                            .and(QAgentAction.agentAction.status.in(pendingStatuses)))
                    .distinct()
                    .fetch();
            if (!caseIdsWithPending.isEmpty()) {
                predicate.and(c.caseId.in(caseIdsWithPending));
            } else {
                predicate.and(c.caseId.eq(-1L)); // no pending actions exist
            }
        }
        if (query.getCompany() != null && !query.getCompany().isEmpty()) {
            predicate.and(c.bukrs.in(query.getCompany()));
        }
        if (query.getQ() != null && !query.getQ().isBlank()) {
            String q = query.getQ().trim();
            BooleanExpression qPred = c.belnr.containsIgnoreCase(q)
                    .or(c.reasonText.containsIgnoreCase(q));
            predicate.and(qPred);
        }

        OrderSpecifier<?> orderBy = c.createdAt.desc();
        boolean asc = !"asc".equalsIgnoreCase(query.getOrder());
        if (query.getSort() != null && !query.getSort().isBlank()) {
            String[] parts = query.getSort().split(",");
            if (parts.length >= 2) asc = "asc".equalsIgnoreCase(parts[1].trim());
            String sortField = parts[0].trim();
            orderBy = "createdAt".equalsIgnoreCase(sortField)
                    ? (asc ? c.createdAt.asc() : c.createdAt.desc())
                    : "detectedAt".equalsIgnoreCase(sortField)
                    ? (asc ? c.detectedAt.asc() : c.detectedAt.desc())
                    : (asc ? c.createdAt.asc() : c.createdAt.desc());
        }

        int page = Math.max(0, query.getPage());
        int size = Math.min(100, Math.max(1, query.getSize()));

        List<AgentCase> cases = queryFactory.selectFrom(c)
                .where(predicate)
                .orderBy(orderBy)
                .offset((long) page * size)
                .limit(query.getPartyId() != null ? size * 3 : size)
                .fetch();

        if (query.getPartyId() != null) {
            List<AgentCase> allMatching = queryFactory.selectFrom(c).where(predicate).orderBy(orderBy).fetch();
            cases = filterCasesByParty(tenantId, allMatching, query.getPartyId());
            long total = cases.size();
            cases = cases.stream().skip((long) page * size).limit(size).toList();
            List<CaseListRowDto> rows = buildCaseListRows(tenantId, cases);
            Map<String, Object> filtersApplied = buildFiltersApplied(query);
            return PageResponse.of(rows, total, page, size,
                    query.getSort() != null ? query.getSort() : "createdAt",
                    query.getOrder() != null ? query.getOrder() : "desc",
                    filtersApplied);
        }

        cases = queryFactory.selectFrom(c)
                .where(predicate)
                .orderBy(orderBy)
                .offset((long) page * size)
                .limit(size)
                .fetch();
        long total = queryFactory.selectFrom(c).where(predicate).fetchCount();
        List<CaseListRowDto> rows = buildCaseListRows(tenantId, cases);
        Map<String, Object> filtersApplied = buildFiltersApplied(query);
        return PageResponse.of(rows, total, page, size,
                query.getSort() != null ? query.getSort() : "createdAt",
                query.getOrder() != null ? query.getOrder() : "desc",
                filtersApplied);
    }

    private List<AgentCase> filterCasesByParty(Long tenantId, List<AgentCase> cases, Long partyId) {
        return bpPartyRepository.findById(partyId)
                .filter(party -> tenantId.equals(party.getTenantId()))
                .map(party -> cases.stream()
                        .filter(case_ -> matchesParty(case_, party))
                        .toList())
                .orElse(List.of());
    }

    private List<Long> resolveAssigneeIdsBySlaRisk(Long tenantId, String slaRisk) {
        List<AgentCase> openCases = agentCaseRepository.findByTenantId(tenantId).stream()
                .filter(c -> c.getStatus() != null && List.of("OPEN", "ACTIVE", "IN_PROGRESS", "IN_REVIEW", "TRIAGED").contains(c.getStatus().toUpperCase()))
                .filter(c -> c.getAssigneeUserId() != null)
                .toList();
        Map<Long, Long> countByAssignee = new java.util.HashMap<>();
        for (AgentCase c : openCases) {
            countByAssignee.merge(c.getAssigneeUserId(), 1L, Long::sum);
        }
        int threshold = 5;
        return countByAssignee.entrySet().stream()
                .filter(e -> "AT_RISK".equalsIgnoreCase(slaRisk) ? e.getValue() > threshold : e.getValue() <= threshold)
                .map(Map.Entry::getKey)
                .toList();
    }

    private Map<String, Object> buildFiltersApplied(CaseListQuery query) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (query.getRange() != null && !query.getRange().isBlank()) m.put("range", query.getRange());
        if (query.getDateFrom() != null) m.put("from", query.getDateFrom().toString());
        if (query.getDateTo() != null) m.put("to", query.getDateTo().toString());
        if (query.getStatus() != null && !query.getStatus().isBlank())
            m.put("status", com.dwp.services.synapsex.util.DrillDownParamUtil.parseMulti(query.getStatus()));
        if (query.getSeverity() != null && !query.getSeverity().isBlank())
            m.put("severity", com.dwp.services.synapsex.util.DrillDownParamUtil.parseMulti(query.getSeverity()));
        if (query.getCaseType() != null && !query.getCaseType().isBlank()) m.put("driverType", query.getCaseType());
        if (query.getAssigneeUserId() != null) m.put("assigneeUserId", query.getAssigneeUserId());
        if (query.getCompany() != null && !query.getCompany().isEmpty()) m.put("company", query.getCompany());
        else if (query.getCompanyCode() != null && !query.getCompanyCode().isBlank()) m.put("company", List.of(query.getCompanyCode()));
        if (query.getDocumentKey() != null && !query.getDocumentKey().isBlank()) m.put("documentKey", query.getDocumentKey());
        if (Boolean.TRUE.equals(query.getHasPendingAction())) m.put("hasPendingAction", true);
        return m.isEmpty() ? null : m;
    }

    private boolean matchesParty(AgentCase case_, BpParty party) {
        if (case_.getBukrs() == null || case_.getBelnr() == null || case_.getGjahr() == null) return false;
        List<FiDocItem> items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                case_.getTenantId(), case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
        return items.stream().anyMatch(i ->
                (i.getLifnr() != null && i.getLifnr().equals(party.getPartyCode()))
                        || (i.getKunnr() != null && i.getKunnr().equals(party.getPartyCode())));
    }

    private List<CaseListRowDto> buildCaseListRows(Long tenantId, List<AgentCase> cases) {
        List<CaseListRowDto> rows = new ArrayList<>();
        for (AgentCase case_ : cases) {
            List<String> docKeys = new ArrayList<>();
            if (case_.getBukrs() != null && case_.getBelnr() != null && case_.getGjahr() != null) {
                docKeys.add(case_.getBukrs() + "-" + case_.getBelnr() + "-" + case_.getGjahr());
            }
            CaseListRowDto.PartySummaryDto partySummary = resolvePartySummary(tenantId, case_);
            int actionCount = (int) agentActionRepository.countByTenantIdAndCaseId(tenantId, case_.getCaseId());
            String reasonShort = case_.getReasonText() != null && case_.getReasonText().length() > 200
                    ? case_.getReasonText().substring(0, 200) + "..." : case_.getReasonText();

            rows.add(CaseListRowDto.builder()
                    .caseId(case_.getCaseId())
                    .detectedAt(case_.getDetectedAt())
                    .caseType(case_.getCaseType())
                    .severity(case_.getSeverity())
                    .score(case_.getScore())
                    .status(case_.getStatus())
                    .reasonTextShort(reasonShort)
                    .docKeys(docKeys)
                    .partySummary(partySummary)
                    .relatedActionsCount(actionCount)
                    .assigneeUserId(case_.getAssigneeUserId())
                    .build());
        }
        return rows;
    }

    private CaseListRowDto.PartySummaryDto resolvePartySummary(Long tenantId, AgentCase case_) {
        if (case_.getBukrs() == null || case_.getBelnr() == null || case_.getGjahr() == null) return null;
        List<FiDocItem> items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
        for (FiDocItem item : items) {
            if (item.getLifnr() != null && !item.getLifnr().isBlank()) {
                return bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "VENDOR", item.getLifnr())
                        .map(party -> CaseListRowDto.PartySummaryDto.builder()
                                .partyId(party.getPartyId())
                                .partyCode(party.getPartyCode())
                                .nameDisplay(party.getNameDisplay())
                                .build())
                        .orElse(null);
            }
            if (item.getKunnr() != null && !item.getKunnr().isBlank()) {
                return bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "CUSTOMER", item.getKunnr())
                        .map(party -> CaseListRowDto.PartySummaryDto.builder()
                                .partyId(party.getPartyId())
                                .partyCode(party.getPartyCode())
                                .nameDisplay(party.getNameDisplay())
                                .build())
                        .orElse(null);
            }
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Optional<CaseDetailDto> findCaseDetail(Long tenantId, Long caseId) {
        return agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .map(case_ -> buildCaseDetail(tenantId, case_));
    }

    private CaseDetailDto buildCaseDetail(Long tenantId, AgentCase case_) {
        CaseDetailDto.EvidencePanelDto evidence = buildEvidencePanel(tenantId, case_);
        CaseDetailDto.ReasoningPanelDto reasoning = CaseDetailDto.ReasoningPanelDto.builder()
                .score(case_.getScore())
                .reasonText(case_.getReasonText())
                .evidenceJson(case_.getEvidenceJson())
                .ragRefsJson(case_.getRagRefsJson())
                .confidenceBreakdown(CaseDetailDto.ConfidenceBreakdownDto.builder()
                        .anomalyScore(case_.getScore() != null ? case_.getScore().doubleValue() : null)
                        .patternMatch(0.8)
                        .ruleCompliance(0.9)
                        .build())
                .build();
        List<AgentAction> actions = agentActionRepository.findByTenantIdAndCaseId(tenantId, case_.getCaseId());
        CaseDetailDto.ActionPanelDto action = CaseDetailDto.ActionPanelDto.builder()
                .availableActionTypes(List.of("PAYMENT_BLOCK", "REQUEST_INFO", "DISMISS", "RELEASE_BLOCK"))
                .actions(actions.stream()
                        .map(a -> CaseDetailDto.ActionSummaryDto.builder()
                                .actionId(a.getActionId())
                                .actionType(a.getActionType())
                                .status(a.getStatus())
                                .createdAt(a.getCreatedAt() != null ? a.getCreatedAt().toString() : null)
                                .executedAt(a.getExecutedAt() != null ? a.getExecutedAt().toString() : null)
                                .build())
                        .toList())
                .lineageLinkParams(CaseDetailDto.LineageLinkParamsDto.builder()
                        .caseId(case_.getCaseId())
                        .docKey(case_.getBukrs() != null && case_.getBelnr() != null && case_.getGjahr() != null
                                ? case_.getBukrs() + "-" + case_.getBelnr() + "-" + case_.getGjahr() : null)
                        .partyId(resolvePartyId(tenantId, case_))
                        .build())
                .build();
        return CaseDetailDto.builder()
                .caseId(case_.getCaseId())
                .status(case_.getStatus())
                .evidence(evidence)
                .reasoning(reasoning)
                .action(action)
                .build();
    }

    private CaseDetailDto.EvidencePanelDto buildEvidencePanel(Long tenantId, AgentCase case_) {
        String docKey = case_.getBukrs() != null && case_.getBelnr() != null && case_.getGjahr() != null
                ? case_.getBukrs() + "-" + case_.getBelnr() + "-" + case_.getGjahr() : null;
        CaseDetailDto.DocumentOrOpenItemDto docOrOi = null;
        if (docKey != null) {
            var headerOpt = fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                    tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
            if (headerOpt.isPresent()) {
                var header = headerOpt.get();
                var items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                        tenantId, header.getBukrs(), header.getBelnr(), header.getGjahr());
                docOrOi = CaseDetailDto.DocumentOrOpenItemDto.builder()
                        .type("DOCUMENT")
                        .docKey(docKey)
                        .headerSummary(Map.of("bukrs", header.getBukrs(), "belnr", header.getBelnr(), "gjahr", header.getGjahr(),
                                "budat", header.getBudat() != null ? header.getBudat().toString() : "", "xblnr", header.getXblnr() != null ? header.getXblnr() : ""))
                        .items(items.stream().map(i -> (Object) Map.of("buzei", i.getBuzei(), "lifnr", i.getLifnr() != null ? i.getLifnr() : "", "kunnr", i.getKunnr() != null ? i.getKunnr() : "", "wrbtr", i.getWrbtr() != null ? i.getWrbtr().toString() : "")).toList())
                        .build();
            } else {
                var openItems = fiOpenItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                        tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
                if (!openItems.isEmpty()) {
                    var oi = openItems.get(0);
                    docOrOi = CaseDetailDto.DocumentOrOpenItemDto.builder()
                            .type("OPEN_ITEM")
                            .docKey(docKey)
                            .headerSummary(Map.of("bukrs", oi.getBukrs(), "belnr", oi.getBelnr(), "gjahr", oi.getGjahr()))
                            .items(List.of())
                            .build();
                }
            }
        }
        List<String> reversalNodes = new ArrayList<>();
        if (docKey != null) {
            reversalNodes.add(docKey);
            fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr())
                    .ifPresent(h -> {
                        if (h.getReversalBelnr() != null) reversalNodes.add(case_.getBukrs() + "-" + h.getReversalBelnr() + "-" + case_.getGjahr());
                    });
        }
        List<Long> relatedPartyIds = new ArrayList<>();
        if (case_.getBukrs() != null && case_.getBelnr() != null && case_.getGjahr() != null) {
            for (FiDocItem item : fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                    tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr())) {
                if (item.getLifnr() != null) {
                    bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "VENDOR", item.getLifnr())
                            .ifPresent(party -> relatedPartyIds.add(party.getPartyId()));
                }
                if (item.getKunnr() != null) {
                    bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "CUSTOMER", item.getKunnr())
                            .ifPresent(party -> relatedPartyIds.add(party.getPartyId()));
                }
            }
        }
        return CaseDetailDto.EvidencePanelDto.builder()
                .documentOrOpenItem(docOrOi)
                .reversalChainSummary(CaseDetailDto.ReversalChainSummaryDto.builder()
                        .nodeDocKeys(reversalNodes)
                        .edgeCount(Math.max(0, reversalNodes.size() - 1))
                        .build())
                .relatedPartyIds(relatedPartyIds.stream().distinct().toList())
                .build();
    }

    private Long resolvePartyId(Long tenantId, AgentCase case_) {
        return resolvePartySummary(tenantId, case_) != null ? resolvePartySummary(tenantId, case_).getPartyId() : null;
    }

    @Transactional(readOnly = true)
    public List<CaseTimelineDto> findTimeline(Long tenantId, Long caseId, int page, int size) {
        agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        List<CaseTimelineDto> timeline = new ArrayList<>();
        var auditPage = PageRequest.of(0, 50);
        List<AuditEventLog> audits = auditEventLogRepository.findByTenantIdAndResourceTypeAndResourceIdOrderByCreatedAtDesc(
                tenantId, "AGENT_CASE", String.valueOf(caseId), auditPage);
        for (AuditEventLog a : audits) {
            timeline.add(CaseTimelineDto.builder()
                    .eventId(a.getAuditId())
                    .eventType(a.getEventType())
                    .createdAt(a.getCreatedAt())
                    .actorUserId(a.getActorUserId())
                    .actorAgentId(a.getActorAgentId())
                    .summary(a.getEventType())
                    .detail(a.getDiffJson() != null ? a.getDiffJson() : a.getAfterJson())
                    .build());
        }
        List<CaseComment> comments = caseCommentRepository.findByTenantIdAndCaseIdOrderByCreatedAtDesc(tenantId, caseId);
        for (CaseComment cc : comments) {
            timeline.add(CaseTimelineDto.builder()
                    .eventId(cc.getCommentId())
                    .eventType("COMMENT_CREATE")
                    .createdAt(cc.getCreatedAt())
                    .actorUserId(cc.getAuthorUserId())
                    .actorAgentId(cc.getAuthorAgentId())
                    .summary(cc.getCommentText().length() > 200 ? cc.getCommentText().substring(0, 200) + "..." : cc.getCommentText())
                    .detail(Map.of("commentText", cc.getCommentText()))
                    .build());
        }
        timeline.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        int from = page * size;
        int to = Math.min(from + size, timeline.size());
        return from < timeline.size() ? timeline.subList(from, to) : List.of();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CaseListQuery {
        private String status;           // single or comma-separated
        private String severity;         // single or comma-separated
        private String caseType;         // driverType 별칭
        private Long assigneeUserId;
        private String companyCode;       // bukrs, single
        private List<String> company;    // multi (BUKRS)
        private String waers;
        private List<String> currency;
        private Instant dateFrom;
        private Instant dateTo;
        private Instant detectedFrom;
        private Instant detectedTo;
        private String bukrs;
        private String belnr;
        private String gjahr;
        private String buzei;
        private Long partyId;
        private String q;
        private String savedViewKey;
        private List<Long> ids;           // drill-down: ids=1,2,3
        private String caseKey;           // CS-2026-0001 형식
        private String range;             // 1h|6h|24h|7d|30d|90d (filtersApplied용)
        private String documentKey;
        private Boolean hasPendingAction;
        private String slaRisk;  // AT_RISK | ON_TRACK
        @lombok.Builder.Default
        private int page = 0;
        @lombok.Builder.Default
        private int size = 20;
        private String sort;
        private String order;
    }
}
