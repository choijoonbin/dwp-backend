package com.dwp.services.synapsex.service.case_;

import com.dwp.services.synapsex.dto.case_.CaseDetailDto;
import com.dwp.services.synapsex.dto.case_.CaseListRowDto;
import com.dwp.services.synapsex.dto.case_.DocumentLineItemDto;
import com.dwp.services.synapsex.dto.case_.DocumentOrOpenItemDto;
import com.dwp.services.synapsex.dto.case_.CaseTimelineDto;
import com.dwp.services.synapsex.dto.common.PageInfo;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.entity.*;
import com.dwp.services.synapsex.repository.*;
import com.dwp.services.synapsex.service.security.OwnershipAccessService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.dwp.services.synapsex.scope.DrillDownCodeResolver;
import com.dwp.services.synapsex.util.DocKeyUtil;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
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
    private final CaseExplanationRepository caseExplanationRepository;
    private final AuditEventLogRepository auditEventLogRepository;
    private final AgentCaseActionHistoryRepository agentCaseActionHistoryRepository;
    private final AgentActivityLogRepository agentActivityLogRepository;
    private final CaseAnalysisRunRepository caseAnalysisRunRepository;
    private final CaseAnalysisResultRepository caseAnalysisResultRepository;
    private final DrillDownCodeResolver drillDownCodeResolver;
    private final OwnershipAccessService ownershipAccessService;

    private static final String RESOURCE_TYPE_AGENT_CASE = "AGENT_CASE";
    private static final String EVENT_TYPE_AGENT_STREAM = "AGENT_STREAM";
    private static final int CASE_DETAIL_ACTION_HISTORY_LIMIT = 50;
    private static final int CASE_DETAIL_AI_THOUGHTS_LIMIT = 50;
    /** 사고 과정 전체 전달용 (절단 방지). 100건 이상 권장. */
    private static final int REASONING_PROCESS_LIMIT = 500;
    /** 이력 탭: 모든 이벤트 타입 수집 상한. */
    private static final int ACTIVITY_HISTORY_LIMIT = 500;

    private static final QAgentCase c = QAgentCase.agentCase;
    private static final QFiDocItem fi = QFiDocItem.fiDocItem;
    private static final QBpParty p = QBpParty.bpParty;

    @Transactional(readOnly = true)
    public PageResponse<CaseListRowDto> findCases(Long tenantId, CaseListQuery query) {
        return findCases(tenantId, null, query);
    }

    @Transactional(readOnly = true)
    public PageResponse<CaseListRowDto> findCases(Long tenantId, Long actorUserId, CaseListQuery query) {
        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(c.tenantId.eq(tenantId));
        boolean isAdmin = ownershipAccessService.isAdmin(tenantId, actorUserId);
        if (!isAdmin) {
            if (actorUserId != null) {
                predicate.and(c.userId.eq(actorUserId));
            } else {
                predicate.and(c.caseId.eq(-1L));
            }
        }

        // status 미전달 시 상태 필터 미적용 (모든 상태 조회). 전달 시 표준 7개(ANALYZING, NEW, IN_REVIEW 등) 복수 지원(쉼표 구분).
        List<String> statusList = com.dwp.services.synapsex.util.DrillDownParamUtil.parseMulti(query.getStatus());
        // Drill-down 계약: TRIAGE/TRIAGED → NEW 매핑 (V72 표준 7개)
        statusList = statusList.stream()
                .map(s -> "TRIAGE".equalsIgnoreCase(s) || "TRIAGED".equalsIgnoreCase(s) ? "NEW" : s)
                .distinct()
                .toList();
        statusList = drillDownCodeResolver.filterValid(DrillDownCodeResolver.GROUP_CASE_STATUS, statusList);
        if (!statusList.isEmpty()) {
            List<AgentCaseStatus> statusEnums = statusList.stream()
                    .map(AgentCaseStatus::fromString)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (!statusEnums.isEmpty()) predicate.and(c.status.in(statusEnums));
        } else if (query.getStatus() != null && !query.getStatus().isBlank()) {
            AgentCaseStatus statusEnum = AgentCaseStatus.fromString(query.getStatus());
            if (statusEnum != null) predicate.and(c.status.eq(statusEnum));
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
            List<com.dwp.services.synapsex.entity.AgentActionStatus> statusEnums = pendingStatuses.stream()
                    .map(com.dwp.services.synapsex.entity.AgentActionStatus::fromString)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (statusEnums.isEmpty()) statusEnums = List.of(com.dwp.services.synapsex.entity.AgentActionStatus.PENDING_APPROVAL, com.dwp.services.synapsex.entity.AgentActionStatus.PROPOSED, com.dwp.services.synapsex.entity.AgentActionStatus.PLANNED);
            List<Long> caseIdsWithPending = queryFactory.select(QAgentAction.agentAction.caseId)
                    .from(QAgentAction.agentAction)
                    .where(QAgentAction.agentAction.tenantId.eq(tenantId)
                            .and(QAgentAction.agentAction.status.in(statusEnums)))
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
            Map<String, Long> summary = buildCaseSummary(tenantId, actorUserId, isAdmin);
            boolean hasNext = (long) (page + 1) * size < total;
            return PageResponse.<CaseListRowDto>builder()
                    .items(rows)
                    .total(total)
                    .pageInfo(PageInfo.builder().page(page + 1).size(size).hasNext(hasNext).build())
                    .sort(query.getSort() != null ? query.getSort() : "createdAt")
                    .order(query.getOrder() != null ? query.getOrder() : "desc")
                    .filtersApplied(filtersApplied)
                    .summary(summary)
                    .build();
        }

        cases = queryFactory.selectFrom(c)
                .where(predicate)
                .orderBy(orderBy)
                .offset((long) page * size)
                .limit(size)
                .fetch();
        Long totalLong = queryFactory.select(c.count()).from(c).where(predicate).fetchOne();
        long total = totalLong != null ? totalLong : 0L;
        List<CaseListRowDto> rows = buildCaseListRows(tenantId, cases);
        Map<String, Object> filtersApplied = buildFiltersApplied(query);
        Map<String, Long> summary = buildCaseSummary(tenantId, actorUserId, isAdmin);
        boolean hasNext = (long) (page + 1) * size < total;
        return PageResponse.<CaseListRowDto>builder()
                .items(rows)
                .total(total)
                .pageInfo(PageInfo.builder().page(page + 1).size(size).hasNext(hasNext).build())
                .sort(query.getSort() != null ? query.getSort() : "createdAt")
                .order(query.getOrder() != null ? query.getOrder() : "desc")
                .filtersApplied(filtersApplied)
                .summary(summary)
                .build();
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
                .filter(c -> c.getStatus() != null && List.of(AgentCaseStatus.ANALYZING, AgentCaseStatus.NEW, AgentCaseStatus.IN_REVIEW, AgentCaseStatus.PENDING_EXPLANATION, AgentCaseStatus.PENDING_APPROVAL).contains(c.getStatus()))
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

    /** P0-2: Case list summary (total, open, triage, inReview) */
    private Map<String, Long> buildCaseSummary(Long tenantId, Long actorUserId, boolean isAdmin) {
        BooleanExpression visibility = c.tenantId.eq(tenantId);
        if (!isAdmin) {
            if (actorUserId != null) {
                visibility = visibility.and(c.userId.eq(actorUserId));
            } else {
                visibility = visibility.and(c.caseId.eq(-1L));
            }
        }

        Long totalLong = queryFactory.select(c.count()).from(c).where(visibility).fetchOne();
        long total = totalLong != null ? totalLong : 0L;
        Long openLong = queryFactory.select(c.count()).from(c)
                .where(visibility
                        .and(c.status.in(AgentCaseStatus.ANALYZING, AgentCaseStatus.NEW, AgentCaseStatus.IN_REVIEW, AgentCaseStatus.PENDING_EXPLANATION, AgentCaseStatus.PENDING_APPROVAL)))
                .fetchOne();
        long open = openLong != null ? openLong : 0L;
        Long triageLong = queryFactory.select(c.count()).from(c)
                .where(visibility.and(c.status.eq(AgentCaseStatus.TRIAGED)))
                .fetchOne();
        long triage = triageLong != null ? triageLong : 0L;
        Long inReviewLong = queryFactory.select(c.count()).from(c)
                .where(visibility.and(c.status.eq(AgentCaseStatus.IN_REVIEW)))
                .fetchOne();
        long inReview = inReviewLong != null ? inReviewLong : 0L;
        return Map.of("total", total, "open", open, "triage", triage, "inReview", inReview);
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

            var amountCurrency = resolveAmountAndCurrency(tenantId, case_);
            rows.add(CaseListRowDto.builder()
                    .caseId(case_.getCaseId())
                    .detectedAt(case_.getDetectedAt())
                    .caseType(case_.getCaseType())
                    .severity(case_.getSeverity())
                    .score(case_.getScore())
                    .status(case_.getStatus() != null ? case_.getStatus().name() : null)
                    .reasonTextShort(reasonShort)
                    .docKeys(docKeys)
                    .partySummary(partySummary)
                    .relatedActionsCount(actionCount)
                    .assigneeUserId(case_.getAssigneeUserId())
                    .amount(amountCurrency != null ? amountCurrency.amount() : null)
                    .currency(amountCurrency != null ? amountCurrency.currency() : null)
                    .build());
        }
        return rows;
    }

    /** P0-3: fi_doc_item wrbtr 합계 또는 fi_open_item open_amount + currency */
    private record AmountCurrency(BigDecimal amount, String currency) {}
    private AmountCurrency resolveAmountAndCurrency(Long tenantId, AgentCase case_) {
        if (case_.getBukrs() == null || case_.getBelnr() == null || case_.getGjahr() == null) return null;
        var headerOpt = fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
        if (headerOpt.isPresent()) {
            var items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                    tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
            BigDecimal sum = items.stream().map(FiDocItem::getWrbtr).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(BigDecimal.ZERO) > 0) {
                return new AmountCurrency(sum, headerOpt.get().getWaers());
            }
        }
        var openItems = fiOpenItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
        if (!openItems.isEmpty()) {
            var oi = openItems.get(0);
            return new AmountCurrency(oi.getOpenAmount(), oi.getCurrency());
        }
        return null;
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
        // Internal path (Aura/agent-tool server-to-server): do not apply actor ownership filter.
        return agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .map(case_ -> buildCaseDetail(tenantId, case_));
    }

    @Transactional(readOnly = true)
    public Optional<CaseDetailDto> findCaseDetail(Long tenantId, Long actorUserId, Long caseId) {
        boolean isAdmin = ownershipAccessService.isAdmin(tenantId, actorUserId);
        return agentCaseRepository.findByCaseIdAndTenantId(caseId, tenantId)
                .filter(case_ -> canAccessCase(tenantId, actorUserId, isAdmin, case_))
                .map(case_ -> buildCaseDetail(tenantId, case_));
    }

    private boolean canAccessCase(Long tenantId, Long actorUserId, boolean isAdmin, AgentCase case_) {
        if (isAdmin) {
            return true;
        }
        if (actorUserId == null) {
            return false;
        }
        return actorUserId.equals(case_.getUserId());
    }

    private CaseDetailDto buildCaseDetail(Long tenantId, AgentCase case_) {
        Optional<FiDocHeader> headerOpt = Optional.empty();
        if (case_.getBukrs() != null && case_.getBelnr() != null && case_.getGjahr() != null) {
            headerOpt = fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                    tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
        }
        CaseDetailDto.EvidencePanelDto evidence = buildEvidencePanel(tenantId, case_);
        Optional<CaseAnalysisResult> latestResult = findLatestAnalysisResult(tenantId, case_.getCaseId());
        com.fasterxml.jackson.databind.JsonNode evidenceMapJson = latestResult.map(CaseAnalysisResult::getEvidenceMapJson).orElse(null);
        // V65: 최신 case_analysis_result.evidence_map_json → FE 스플릿 뷰(Split-View) 필수. API 응답 필드명: evidenceMapJson(camelCase)
        if (log.isDebugEnabled()) {
            int size = (evidenceMapJson != null && evidenceMapJson.isArray()) ? evidenceMapJson.size() : 0;
            log.debug("buildCaseDetail evidenceMapJson: caseId={} hasEvidenceMap={} entryCount={}", case_.getCaseId(), evidenceMapJson != null, size);
        }
        String summaryVerdict = parseSummaryVerdictFromEvidenceMap(evidenceMapJson);
        List<String> keyGrounds = parseKeyGroundsFromEvidenceMap(evidenceMapJson);
        CaseDetailDto.ReasoningPanelDto reasoning = CaseDetailDto.ReasoningPanelDto.builder()
                .score(case_.getScore())
                .reasonText(case_.getReasonText())
                .evidenceJson(case_.getEvidenceJson())
                .ragRefsJson(case_.getRagRefsJson())
                .evidenceMapJson(evidenceMapJson)
                .summaryVerdict(summaryVerdict)
                .keyGrounds(keyGrounds)
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
                                .status(a.getStatus() != null ? a.getStatus().name() : null)
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

        String sourceType = resolveSourceType(case_);
        String openItemsUrl = "/api/synapse/open-items?caseId=" + case_.getCaseId();
        String lineageUrl = "/api/synapse/lineage?caseId=" + case_.getCaseId();

        List<CaseDetailDto.CaseActionHistoryItemRefDto> actionHistory = loadActionHistory(tenantId, case_.getCaseId());
        List<CaseDetailDto.AiThoughtItemDto> aiThoughts = loadAiThoughts(tenantId, case_.getCaseId());
        List<CaseDetailDto.AiThoughtItemDto> activityHistory = loadActivityHistory(tenantId, case_.getCaseId());

        List<String> reasoningProcess = buildReasoningProcess(tenantId, case_.getCaseId());
        List<CaseDetailDto.LogicCheckpointDto> logicCheckpoints = buildLogicCheckpoints(case_, latestResult);
        List<CaseDetailDto.RegulationCheckpointDto> regulationCheckpoints = buildRegulationCheckpoints(case_, latestResult, logicCheckpoints);
        List<CaseDetailDto.EvidenceLinkDto> evidenceLinks = buildEvidenceLinks(evidenceMapJson);
        List<CaseDetailDto.ExplanationHistoryItemDto> explanationHistory = caseExplanationRepository
                .findByTenantIdAndCaseIdOrderByCreatedAtDesc(tenantId, case_.getCaseId())
                .stream()
                .map(e -> CaseDetailDto.ExplanationHistoryItemDto.builder()
                        .explanationId(e.getExplanationId())
                        .userId(e.getUserId())
                        .explanationText(e.getExplanationText())
                        .evidenceAttachmentId(e.getEvidenceAttachmentId())
                        .createdAt(e.getCreatedAt())
                        .build())
                .toList();
        CaseDetailDto.FinalReportDto finalReport = buildFinalReport(case_);
        CaseDetailDto.CaseContextDto context = parseContextFromEvidence(case_.getEvidenceJson());
        if ((context.getBudgetExceededFlag() == null || context.getBudgetExceededFlag().isBlank()) && headerOpt.isPresent()) {
            String flag = headerOpt.get().getBudgetExceededFlag();
            Boolean exceeded = flag != null ? "Y".equalsIgnoreCase(flag) : context.getBudgetExceeded();
            context = CaseDetailDto.CaseContextDto.builder()
                    .hrStatus(context.getHrStatus())
                    .mccCode(context.getMccCode())
                    .budgetExceeded(exceeded)
                    .budgetExceededFlag(flag)
                    .build();
        }

        CaseDetailDto dto = CaseDetailDto.builder()
                .caseId(case_.getCaseId())
                .status(case_.getStatus() != null ? case_.getStatus().name() : null)
                .userId(case_.getUserId() != null ? case_.getUserId() : headerOpt.map(FiDocHeader::getUserId).orElse(null))
                .caseType(case_.getCaseType())
                .reasonText(case_.getReasonText())
                .analysisScore(case_.getScore())
                .keys(CaseDetailDto.CaseKeysDto.builder()
                        .sourceType(sourceType)
                        .bukrs(case_.getBukrs())
                        .belnr(case_.getBelnr())
                        .gjahr(case_.getGjahr())
                        .buzei(case_.getBuzei())
                        .dedupKey(case_.getDedupKey())
                        .build())
                .links(CaseDetailDto.CaseLinksDto.builder()
                        .openItems(openItemsUrl)
                        .lineage(lineageUrl)
                        .build())
                .fiDocItems(evidence.getDocumentOrOpenItem() != null ? evidence.getDocumentOrOpenItem().getItems() : null)
                .actionHistory(actionHistory)
                .aiThoughts(aiThoughts)
                .evidence(evidence)
                .reasoning(reasoning)
                .action(action)
                .reasoningProcess(reasoningProcess != null ? reasoningProcess : List.of())
                .logicCheckpoints(logicCheckpoints != null ? logicCheckpoints : List.of())
                .regulationCheckpoints(regulationCheckpoints != null ? regulationCheckpoints : List.of())
                .evidenceLinks(evidenceLinks != null ? evidenceLinks : List.of())
                .finalReport(finalReport != null ? finalReport : CaseDetailDto.FinalReportDto.builder().summary("").verdict("").requestClarificationEnabled(false).closeCaseEnabled(false).build())
                .activityHistory(activityHistory != null ? activityHistory : List.of())
                .explanationHistory(explanationHistory)
                .context(context != null ? context : CaseDetailDto.CaseContextDto.builder().build())
                .build();
        if (log.isDebugEnabled()) {
            log.debug("buildCaseDetail caseId={} caseType={} reasonText={}", dto.getCaseId(), dto.getCaseType(), dto.getReasonText() != null ? "(present)" : "null");
        }
        return dto;
    }

    /** 규정 v2.0: agent_case.evidence_json에서 hr_status, mcc_code, budget_exceeded 추출 → CaseContextDto. */
    private static CaseDetailDto.CaseContextDto parseContextFromEvidence(JsonNode evidenceJson) {
        if (evidenceJson == null || !evidenceJson.isObject()) return CaseDetailDto.CaseContextDto.builder().build();
        String hrStatus = evidenceJson.has("hr_status") && evidenceJson.get("hr_status").isTextual() ? evidenceJson.get("hr_status").asText() : null;
        String mccCode = evidenceJson.has("mcc_code") && evidenceJson.get("mcc_code").isTextual() ? evidenceJson.get("mcc_code").asText() : null;
        Boolean budgetExceeded = evidenceJson.has("budget_exceeded") && evidenceJson.get("budget_exceeded").isBoolean() ? evidenceJson.get("budget_exceeded").asBoolean() : null;
        String budgetExceededFlag = evidenceJson.has("budget_exceeded_flag") && evidenceJson.get("budget_exceeded_flag").isTextual()
                ? evidenceJson.get("budget_exceeded_flag").asText()
                : null;
        if (budgetExceeded == null && budgetExceededFlag != null) {
            budgetExceeded = "Y".equalsIgnoreCase(budgetExceededFlag);
        }
        return CaseDetailDto.CaseContextDto.builder()
                .hrStatus(hrStatus)
                .mccCode(mccCode)
                .budgetExceeded(budgetExceeded)
                .budgetExceededFlag(budgetExceededFlag)
                .build();
    }

    private List<CaseDetailDto.CaseActionHistoryItemRefDto> loadActionHistory(Long tenantId, Long caseId) {
        Pageable limit = PageRequest.of(0, CASE_DETAIL_ACTION_HISTORY_LIMIT);
        return agentCaseActionHistoryRepository
                .findByTenantIdAndCaseIdOrderByActionAtDesc(tenantId, caseId, limit)
                .getContent().stream()
                .map(h -> CaseDetailDto.CaseActionHistoryItemRefDto.builder()
                        .id(h.getId())
                        .actionType(h.getActionType())
                        .actorId(h.getActorId())
                        .commentText(h.getCommentText())
                        .actionAt(h.getActionAt())
                        .createdAt(h.getCreatedAt())
                        .build())
                .toList();
    }

    /** [추론 탭] AGENT_STREAM만 최신순(DESC), 기술 로그 제외, 비즈니스 추론만. */
    private List<CaseDetailDto.AiThoughtItemDto> loadAiThoughts(Long tenantId, Long caseId) {
        Pageable limit = PageRequest.of(0, CASE_DETAIL_AI_THOUGHTS_LIMIT);
        List<AgentActivityLog> logs = agentActivityLogRepository
                .findByTenantIdAndResourceTypeAndResourceIdAndEventTypeOrderByOccurredAtDesc(
                        tenantId, RESOURCE_TYPE_AGENT_CASE, String.valueOf(caseId), EVENT_TYPE_AGENT_STREAM, limit);
        List<CaseDetailDto.AiThoughtItemDto> list = logs.stream()
                .map(log -> {
                    String message = resolveMessageFromMetadata(log.getMetadataJson());
                    return CaseDetailDto.AiThoughtItemDto.builder()
                            .stage(log.getStage())
                            .eventType(log.getEventType())
                            .message(message)
                            .occurredAt(log.getOccurredAt())
                            .build();
                })
                .filter(dto -> dto.getMessage() != null && isRealContent(dto.getMessage()))
                .toList();
        // 동일 논리 이벤트 = event_type + message + occurred_at(1초 단위). CSV 분석: 동일 이벤트가 REST 푸시/감사 등으로 중복 적재됨.
        return deduplicateAiThoughtsByLogicalEvent(list);
    }

    /** [이력 탭] 모든 이벤트 타입(AGENT_STREAM, STATUS_CHANGE, 분석 시작/종료, 승인/반려 등) 시간순 ASC, 상한 500건. 표시 메시지 없으면 eventType/metadata 기반 문구 생성. */
    private List<CaseDetailDto.AiThoughtItemDto> loadActivityHistory(Long tenantId, Long caseId) {
        Pageable limit = PageRequest.of(0, ACTIVITY_HISTORY_LIMIT);
        List<AgentActivityLog> logs = agentActivityLogRepository
                .findByTenantIdAndResourceTypeAndResourceIdOrderByOccurredAtAsc(
                        tenantId, RESOURCE_TYPE_AGENT_CASE, String.valueOf(caseId), limit);
        if (logs == null) return List.of();
        return logs.stream()
                .map(log -> {
                    String message = resolveMessageFromMetadata(log.getMetadataJson());
                    if (message == null && log.getMetadataJson() != null && log.getMetadataJson().containsKey("message")) {
                        Object m = log.getMetadataJson().get("message");
                        if (m != null) message = m.toString();
                    }
                    if (message == null || message.isBlank()) {
                        message = formatActivityHistoryMessage(log);
                    }
                    return CaseDetailDto.AiThoughtItemDto.builder()
                            .stage(log.getStage())
                            .eventType(log.getEventType())
                            .message(message != null ? message : "")
                            .occurredAt(log.getOccurredAt())
                            .build();
                })
                .toList();
    }

    /** 이력 탭 표시용: eventType + metadata 기반 감사 추적 문구 생성. */
    private static String formatActivityHistoryMessage(AgentActivityLog log) {
        String et = log.getEventType() != null ? log.getEventType() : "";
        Map<String, Object> meta = log.getMetadataJson();
        String actor = log.getActorDisplayName() != null && !log.getActorDisplayName().isBlank()
                ? log.getActorDisplayName()
                : (meta != null && meta.containsKey("actor_display_name") && meta.get("actor_display_name") != null)
                ? meta.get("actor_display_name").toString()
                : (meta != null && meta.containsKey("actorDisplayName") && meta.get("actorDisplayName") != null)
                ? meta.get("actorDisplayName").toString()
                : null;
        switch (et) {
            case "STATUS_CHANGE":
                if (meta != null && meta.containsKey("from_status") && meta.containsKey("to_status")) {
                    String from = meta.get("from_status") != null ? meta.get("from_status").toString() : "";
                    String to = meta.get("to_status") != null ? meta.get("to_status").toString() : "";
                    return String.format("상태가 '%s'에서 '%s'(으)로 변경되었습니다.", from, to);
                }
                return "상태가 변경되었습니다.";
            case "ANALYSIS_STARTED":
            case "RUN_STARTED":
                return "분석이 시작되었습니다.";
            case "ANALYSIS_DONE":
            case "RUN_COMPLETED":
            case "RUN_COMPLETED_SUCCESS":
                return "분석이 완료되었습니다.";
            case "RUN_FAILED":
            case "RUN_COMPLETED_FAILED":
                return "분석이 실패하였습니다.";
            case "ACTION_APPROVED":
            case "APPROVAL":
                return actor != null ? String.format("사용자 %s이(가) 분석 결과를 승인했습니다.", actor) : "분석 결과가 승인되었습니다.";
            case "ACTION_REJECTED":
            case "REJECTION":
                return actor != null ? String.format("사용자 %s이(가) 반려했습니다.", actor) : "반려되었습니다.";
            case "AGENT_STREAM":
                return null;
            default:
                if (meta != null && meta.containsKey("message")) {
                    Object m = meta.get("message");
                    if (m != null && !m.toString().isBlank()) return m.toString();
                }
                return et.isBlank() ? "활동이 기록되었습니다." : String.format("[%s] 활동이 기록되었습니다.", et);
        }
    }

    /**
     * 동일 논리 이벤트 중복 제거. 조건: event_type + message + occurred_at(초 단위) 동일 시 1건만 유지.
     * (같은 텍스트라도 다른 시각/다른 event_type이면 별도 이벤트로 유지.)
     */
    private static List<CaseDetailDto.AiThoughtItemDto> deduplicateAiThoughtsByLogicalEvent(List<CaseDetailDto.AiThoughtItemDto> list) {
        if (list == null || list.isEmpty()) return list;
        java.util.Set<String> seenKeys = new java.util.LinkedHashSet<>();
        return list.stream()
                .filter(dto -> {
                    String eventType = dto.getEventType() != null ? dto.getEventType() : "";
                    String message = dto.getMessage() != null ? dto.getMessage() : "";
                    long epochSecond = dto.getOccurredAt() != null ? dto.getOccurredAt().getEpochSecond() : 0L;
                    String key = eventType + "|" + message + "|" + epochSecond;
                    return seenKeys.add(key);
                })
                .toList();
    }

    /**
     * metadata_json에서 표시용 메시지 추출. thought_stream > reasoning > message 우선순위.
     * "사고 중" 등 기술 로그는 배경용으로만 쓰고, API 응답에는 Aura 실제 추론 문장이 나가도록 기술 문구는 스킵.
     */
    private static String resolveMessageFromMetadata(Map<String, Object> metadataJson) {
        if (metadataJson == null) return null;
        // thought_stream (또는 camelCase thoughtStream) 최우선
        Object v = firstNonTechnical(metadataJson.get("thought_stream"));
        if (v != null) return v.toString();
        v = metadataJson.get("thoughtStream");
        if (v != null && isRealContent(v.toString())) return v.toString();
        v = firstNonTechnical(metadataJson.get("reasoning"));
        if (v != null) return v.toString();
        v = metadataJson.get("message");
        return v != null ? v.toString() : null;
    }

    /** 기술용 플레이스홀더("사고 중" 등)면 null, 실제 내용이면 그대로 반환 */
    private static Object firstNonTechnical(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return isRealContent(s) ? value : null;
    }

    private static final java.util.Set<String> TECHNICAL_PLACEHOLDERS = java.util.Set.of(
            "사고 중", "thinking", "처리 중", "processing", "데이터 분석 중"
    );

    private static boolean isRealContent(String s) {
        if (s == null || s.isBlank()) return false;
        String trimmed = s.trim();
        return !TECHNICAL_PLACEHOLDERS.contains(trimmed);
    }

    /** evidenceMapJson 객체에서 summary_verdict / summaryVerdict 추출 (보고서 탭 종합 판정). */
    private String parseSummaryVerdictFromEvidenceMap(JsonNode evidenceMapJson) {
        if (evidenceMapJson == null || !evidenceMapJson.isObject()) return null;
        JsonNode v = evidenceMapJson.get("summary_verdict");
        if (v == null || !v.isTextual()) v = evidenceMapJson.get("summaryVerdict");
        return (v != null && v.isTextual()) ? v.asText() : null;
    }

    /** evidenceMapJson 객체에서 key_grounds / keyGrounds 문자열 배열 추출 (보고서 탭 핵심 근거). */
    private List<String> parseKeyGroundsFromEvidenceMap(JsonNode evidenceMapJson) {
        if (evidenceMapJson == null || !evidenceMapJson.isObject()) return null;
        JsonNode arr = evidenceMapJson.get("key_grounds");
        if (arr == null || !arr.isArray()) arr = evidenceMapJson.get("keyGrounds");
        if (arr == null || !arr.isArray()) return null;
        List<String> list = new ArrayList<>();
        for (JsonNode el : arr) {
            if (el != null && el.isTextual()) list.add(el.asText());
        }
        return list.isEmpty() ? null : list;
    }

    /** 케이스 최신 분석 결과의 evidence_map(사실-규정 1:1) 조회. 없으면 null */
    private com.fasterxml.jackson.databind.JsonNode findLatestEvidenceMapJson(Long tenantId, Long caseId) {
        List<CaseAnalysisRun> runs = caseAnalysisRunRepository.findByTenantIdAndCaseIdOrderByStartedAtDesc(tenantId, caseId);
        if (runs.isEmpty()) {
            if (log.isTraceEnabled()) log.trace("findLatestEvidenceMapJson: caseId={} no analysis runs", caseId);
            return null;
        }
        Optional<CaseAnalysisResult> resultOpt = caseAnalysisResultRepository.findByRunId(runs.get(0).getRunId());
        if (resultOpt.isEmpty()) {
            if (log.isDebugEnabled()) log.debug("findLatestEvidenceMapJson: caseId={} runId={} no result row", caseId, runs.get(0).getRunId());
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode node = resultOpt.get().getEvidenceMapJson();
        if (log.isTraceEnabled()) {
            int count = (node != null && node.isArray()) ? node.size() : 0;
            log.trace("findLatestEvidenceMapJson: caseId={} runId={} evidenceMapJson present={} entryCount={}", caseId, runs.get(0).getRunId(), node != null, count);
        }
        return node;
    }

    /** 케이스 최신 분석 결과 전체 조회. 없으면 empty. */
    private Optional<CaseAnalysisResult> findLatestAnalysisResult(Long tenantId, Long caseId) {
        List<CaseAnalysisRun> runs = caseAnalysisRunRepository.findByTenantIdAndCaseIdOrderByStartedAtDesc(tenantId, caseId);
        if (runs.isEmpty()) return Optional.empty();
        return caseAnalysisResultRepository.findByRunId(runs.get(0).getRunId());
    }

    /** [사고 과정] AGENT_STREAM 로그만 시간순(ASC), message만 추출. 기술 문구도 포함하여 절단 없이 전체 전달. */
    private List<String> buildReasoningProcess(Long tenantId, Long caseId) {
        Pageable limit = PageRequest.of(0, REASONING_PROCESS_LIMIT);
        List<AgentActivityLog> logs = agentActivityLogRepository
                .findByTenantIdAndResourceTypeAndResourceIdAndEventTypeOrderByOccurredAtAsc(
                        tenantId, RESOURCE_TYPE_AGENT_CASE, String.valueOf(caseId), EVENT_TYPE_AGENT_STREAM, limit);
        if (logs == null) return List.of();
        List<String> out = new ArrayList<>();
        for (AgentActivityLog logEntry : logs) {
            String message = resolveMessageFromMetadata(logEntry.getMetadataJson());
            if (message == null && logEntry.getMetadataJson() != null && logEntry.getMetadataJson().containsKey("message")) {
                Object m = logEntry.getMetadataJson().get("message");
                if (m != null) message = m.toString();
            }
            if (message != null && !message.isBlank()) out.add(message);
        }
        return out;
    }

    /** [검토 로직] violation_clause 우선, 없으면 evidence_json에서 clause/status/description 추출 → LogicCheckpointDto 배열. */
    private List<CaseDetailDto.LogicCheckpointDto> buildLogicCheckpoints(AgentCase case_, Optional<CaseAnalysisResult> resultOpt) {
        if (resultOpt.isEmpty()) return List.of();
        CaseAnalysisResult r = resultOpt.get();
        String raw = r.getViolationClause();
        if (raw != null && !raw.isBlank()) {
            raw = raw.trim();
            // JSON 배열 형태면 파싱 (예: [{"clause":"제1조 1항","status":"VIOLATED","description":"..."}])
            if (raw.startsWith("[")) {
                try {
                    com.fasterxml.jackson.databind.JsonNode arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
                    if (arr != null && arr.isArray()) {
                        List<CaseDetailDto.LogicCheckpointDto> list = new ArrayList<>();
                        for (JsonNode el : arr) {
                            if (el == null || !el.isObject()) continue;
                            String clause = el.has("clause") && el.get("clause").isTextual() ? el.get("clause").asText() : null;
                            String status = el.has("status") && el.get("status").isTextual() ? el.get("status").asText() : "COMPLETED";
                            String description = el.has("description") && el.get("description").isTextual() ? el.get("description").asText() : null;
                            list.add(CaseDetailDto.LogicCheckpointDto.builder()
                                    .clause(clause != null ? clause : "")
                                    .status("VIOLATED".equalsIgnoreCase(status) ? "VIOLATED" : "COMPLETED")
                                    .description(description != null ? description : "")
                                    .build());
                        }
                        if (!list.isEmpty()) return list;
                    }
                } catch (Exception e) {
                    if (log.isDebugEnabled()) log.debug("buildLogicCheckpoints parse violation_clause JSON failed: {}", e.getMessage());
                }
            }
            // 단일 조항 문자열이면 1건으로
            String status = case_.getSeverity() != null && !"LOW".equalsIgnoreCase(case_.getSeverity()) ? "VIOLATED" : "COMPLETED";
            return List.of(CaseDetailDto.LogicCheckpointDto.builder()
                    .clause(raw)
                    .status(status)
                    .description("")
                    .build());
        }
        // violation_clause 없을 때 evidence_json에서 조항/상태/설명 추출
        JsonNode evidenceJson = r.getEvidenceJson();
        if (evidenceJson != null && evidenceJson.isArray()) {
            List<CaseDetailDto.LogicCheckpointDto> list = new ArrayList<>();
            for (JsonNode el : evidenceJson) {
                if (el == null || !el.isObject()) continue;
                String clause = el.has("clause") && el.get("clause").isTextual() ? el.get("clause").asText()
                        : el.has("violation_clause") && el.get("violation_clause").isTextual() ? el.get("violation_clause").asText()
                        : el.has("item") && el.get("item").isTextual() ? el.get("item").asText() : null;
                String status = el.has("status") && el.get("status").isTextual() ? el.get("status").asText() : "COMPLETED";
                String description = el.has("description") && el.get("description").isTextual() ? el.get("description").asText()
                        : el.has("reason") && el.get("reason").isTextual() ? el.get("reason").asText() : null;
                list.add(CaseDetailDto.LogicCheckpointDto.builder()
                        .clause(clause != null ? clause : "")
                        .status("VIOLATED".equalsIgnoreCase(status) ? "VIOLATED" : "COMPLETED")
                        .description(description != null ? description : "")
                        .build());
            }
            if (!list.isEmpty()) return list;
        }
        return List.of();
    }

    /** [판단 규정 탭] regulation_checkpoints 우선(evidence_map_json.regulation_checkpoints), 없으면 logicCheckpoints를 RegulationCheckpointDto로 변환. */
    private List<CaseDetailDto.RegulationCheckpointDto> buildRegulationCheckpoints(
            AgentCase case_, Optional<CaseAnalysisResult> resultOpt, List<CaseDetailDto.LogicCheckpointDto> logicCheckpoints) {
        boolean holdDecision = isHoldOrReevaluationDecision(case_, resultOpt);
        if (resultOpt.isPresent()) {
            JsonNode evidenceMapJson = resultOpt.get().getEvidenceMapJson();
            if (evidenceMapJson != null && evidenceMapJson.isObject()) {
                JsonNode regArr = evidenceMapJson.get("regulation_checkpoints");
                if (regArr == null) regArr = evidenceMapJson.get("regulationCheckpoints");
                if (regArr != null && regArr.isArray()) {
                    List<CaseDetailDto.RegulationCheckpointDto> list = parseRegulationCheckpointsFromJson(regArr);
                    if (!list.isEmpty()) return alignRegulationStatusWithFinalDecision(list, holdDecision);
                }
            }
        }
        return alignRegulationStatusWithFinalDecision(convertLogicCheckpointsToRegulationCheckpoints(logicCheckpoints), holdDecision);
    }

    /**
     * "준수"와 "확정 보류/재검토" 동시 노출 방지:
     * 최종 판단이 보류/재검토 계열이면 COMPLIANT를 NEEDS_REVIEW로 강등.
     */
    private List<CaseDetailDto.RegulationCheckpointDto> alignRegulationStatusWithFinalDecision(
            List<CaseDetailDto.RegulationCheckpointDto> checkpoints, boolean holdDecision) {
        if (!holdDecision || checkpoints == null || checkpoints.isEmpty()) return checkpoints != null ? checkpoints : List.of();
        List<CaseDetailDto.RegulationCheckpointDto> out = new ArrayList<>(checkpoints.size());
        for (CaseDetailDto.RegulationCheckpointDto cp : checkpoints) {
            if (cp == null) continue;
            String status = cp.getStatus();
            if ("COMPLIANT".equalsIgnoreCase(status)) {
                out.add(CaseDetailDto.RegulationCheckpointDto.builder()
                        .ruleId(cp.getRuleId())
                        .version(cp.getVersion())
                        .chapter(cp.getChapter())
                        .article(cp.getArticle())
                        .clause(cp.getClause())
                        .title(cp.getTitle())
                        .status("NEEDS_REVIEW")
                        .statusReason(cp.getStatusReason() != null && !cp.getStatusReason().isBlank()
                                ? cp.getStatusReason()
                                : "최종 판단이 보류/재검토 상태여서 규정 상태를 재검토로 정렬")
                        .description(cp.getDescription())
                        .evidenceRefs(cp.getEvidenceRefs() != null ? cp.getEvidenceRefs() : List.of())
                        .qualitySignals(cp.getQualitySignals() != null ? cp.getQualitySignals() : List.of())
                        .applied(cp.getApplied())
                        .priority(cp.getPriority())
                        .build());
            } else {
                out.add(cp);
            }
        }
        return out;
    }

    private boolean isHoldOrReevaluationDecision(AgentCase case_, Optional<CaseAnalysisResult> resultOpt) {
        if (resultOpt.isEmpty()) return false;
        CaseAnalysisResult r = resultOpt.get();
        if (hasHoldSignals(r.getQualityGateCodes())) return true;
        return containsHoldKeyword(r.getReasonText())
                || containsHoldKeyword(r.getReasoningSummary())
                || containsHoldKeyword(case_ != null ? case_.getReasonText() : null);
    }

    private static boolean hasHoldSignals(JsonNode codes) {
        if (codes == null || !codes.isArray()) return false;
        for (JsonNode n : codes) {
            if (n == null || !n.isTextual()) continue;
            String c = n.asText();
            if ("SENTENCE_CITATION_MISSING".equals(c)
                    || "EVIDENCE_COVERAGE_LOW".equals(c)
                    || "POLICY_CONFLICT".equals(c)
                    || "POLICY_CONFLICT_DETECTED".equals(c)
                    || "RAG_ZERO".equals(c)
                    || "EVIDENCE_MISSING".equals(c)
                    || "INPUT_PARTIAL".equals(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHoldKeyword(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase();
        return t.contains("보류") || t.contains("재검토");
    }

    private List<CaseDetailDto.RegulationCheckpointDto> parseRegulationCheckpointsFromJson(JsonNode arr) {
        List<CaseDetailDto.RegulationCheckpointDto> list = new ArrayList<>();
        for (JsonNode el : arr) {
            if (el == null || !el.isObject()) continue;
            String ruleId = getText(el, "ruleId", "rule_id");
            String version = getText(el, "version");
            String chapter = getText(el, "chapter");
            String article = getText(el, "article");
            String clause = getText(el, "clause");
            String title = getText(el, "title");
            String status = normalizeRegulationStatus(getText(el, "status"));
            String statusReason = getText(el, "statusReason", "status_reason");
            String description = getText(el, "description");
            List<String> evidenceRefs = getStringArray(el, "evidenceRefs", "evidence_refs");
            List<String> qualitySignals = getStringArray(el, "qualitySignals", "quality_signals");
            Boolean applied = el.has("applied") && !el.get("applied").isNull() ? el.get("applied").asBoolean() : null;
            Integer priority = el.has("priority") && !el.get("priority").isNull() ? el.get("priority").asInt() : null;
            list.add(CaseDetailDto.RegulationCheckpointDto.builder()
                    .ruleId(ruleId)
                    .version(version)
                    .chapter(chapter)
                    .article(article)
                    .clause(clause)
                    .title(title)
                    .status(status != null ? status : "NEEDS_REVIEW")
                    .statusReason(statusReason)
                    .description(description)
                    .evidenceRefs(evidenceRefs != null ? evidenceRefs : List.of())
                    .qualitySignals(qualitySignals != null ? qualitySignals : List.of())
                    .applied(applied)
                    .priority(priority)
                    .build());
        }
        return list;
    }

    private static String getText(JsonNode n, String... keys) {
        for (String k : keys) {
            if (n.has(k) && n.get(k).isTextual()) return n.get(k).asText();
        }
        return null;
    }

    private static List<String> getStringArray(JsonNode n, String... keys) {
        JsonNode arr = null;
        for (String k : keys) {
            if (n.has(k)) { arr = n.get(k); break; }
        }
        if (arr == null || !arr.isArray()) return null;
        List<String> out = new ArrayList<>();
        for (JsonNode x : arr) {
            if (x != null && x.isTextual()) out.add(x.asText());
        }
        return out.isEmpty() ? null : out;
    }

    private static String normalizeRegulationStatus(String s) {
        if (s == null || s.isBlank()) return null;
        return switch (s.toUpperCase()) {
            case "COMPLIANT", "COMPLETED", "OK" -> "COMPLIANT";
            case "VIOLATION", "VIOLATED" -> "VIOLATION";
            case "HOLD" -> "HOLD";
            case "CONFLICT" -> "CONFLICT";
            case "NEEDS_REVIEW", "NEED_REVIEW" -> "NEEDS_REVIEW";
            default -> s;
        };
    }

    private List<CaseDetailDto.RegulationCheckpointDto> convertLogicCheckpointsToRegulationCheckpoints(
            List<CaseDetailDto.LogicCheckpointDto> logicCheckpoints) {
        if (logicCheckpoints == null || logicCheckpoints.isEmpty()) return List.of();
        List<CaseDetailDto.RegulationCheckpointDto> list = new ArrayList<>();
        int idx = 0;
        for (CaseDetailDto.LogicCheckpointDto lp : logicCheckpoints) {
            String status = normalizeRegulationStatus(lp.getStatus());
            if (status == null) status = "NEEDS_REVIEW";
            list.add(CaseDetailDto.RegulationCheckpointDto.builder()
                    .clause(lp.getClause())
                    .status(status)
                    .description(lp.getDescription())
                    .applied(true)
                    .priority(idx + 1)
                    .evidenceRefs(List.of())
                    .qualitySignals(List.of())
                    .build());
            idx++;
        }
        return list;
    }

    /** [증거 맵] evidence_map_json → EvidenceLinkDto 배열. 배열/객체(items|entries|evidence|links|results|data) 및 itemId/item_id/item_idx/buzei 지원. */
    private List<CaseDetailDto.EvidenceLinkDto> buildEvidenceLinks(JsonNode evidenceMapJson) {
        if (evidenceMapJson == null) return List.of();
        List<CaseDetailDto.EvidenceLinkDto> list = new ArrayList<>();
        if (evidenceMapJson.isArray()) {
            addEvidenceLinkItems(evidenceMapJson, list);
        } else if (evidenceMapJson.isObject()) {
            JsonNode items = evidenceMapJson.get("items");
            if (items == null) items = evidenceMapJson.get("entries");
            if (items == null) items = evidenceMapJson.get("evidence");
            if (items == null) items = evidenceMapJson.get("links");
            if (items == null) items = evidenceMapJson.get("results");
            if (items == null) items = evidenceMapJson.get("data");
            if (items != null && items.isArray()) addEvidenceLinkItems(items, list);
        }
        return list;
    }

    private void addEvidenceLinkItems(JsonNode array, List<CaseDetailDto.EvidenceLinkDto> list) {
        for (JsonNode el : array) {
            if (el == null || !el.isObject()) continue;
            String itemIdx = null;
            if (el.has("itemId") && el.get("itemId").isTextual()) itemIdx = el.get("itemId").asText();
            else if (el.has("item_id") && el.get("item_id").isTextual()) itemIdx = el.get("item_id").asText();
            else if (el.has("item_id") && el.get("item_id").isNumber()) itemIdx = String.valueOf(el.get("item_id").asInt());
            else if (el.has("item_idx") && el.get("item_idx").isTextual()) itemIdx = el.get("item_idx").asText();
            else if (el.has("buzei") && el.get("buzei").isTextual()) itemIdx = el.get("buzei").asText();
            String reason = el.has("reason") && el.get("reason").isTextual() ? el.get("reason").asText()
                    : el.has("summary") && el.get("summary").isTextual() ? el.get("summary").asText()
                    : el.has("key_ground") && el.get("key_ground").isTextual() ? el.get("key_ground").asText() : null;
            String severity = el.has("severity") && el.get("severity").isTextual() ? el.get("severity").asText()
                    : el.has("severity_level") && el.get("severity_level").isTextual() ? el.get("severity_level").asText() : null;
            if (severity != null) {
                String u = severity.toUpperCase();
                if (!"HIGH".equals(u) && !"MEDIUM".equals(u) && !"LOW".equals(u)) severity = "MEDIUM";
            } else severity = "MEDIUM";
            list.add(CaseDetailDto.EvidenceLinkDto.builder()
                    .itemIdx(itemIdx != null ? itemIdx : "")
                    .reason(reason != null ? reason : "")
                    .severity(severity)
                    .build());
        }
    }

    /** [분석 리포트] reason_text + status → FinalReportDto. 빈 객체 반환 가능. */
    private CaseDetailDto.FinalReportDto buildFinalReport(AgentCase case_) {
        String summary = case_.getReasonText() != null ? case_.getReasonText() : "";
        String severity = case_.getSeverity() != null ? case_.getSeverity() : "";
        String verdict = severity.isEmpty() ? "" : (case_.getScore() != null
                ? severity + " (score: " + case_.getScore() + ")" : severity);
        boolean resolvedOrClosed = case_.getStatus() == AgentCaseStatus.RESOLVED || case_.getStatus() == AgentCaseStatus.IGNORED;
        boolean requestClarificationEnabled = !resolvedOrClosed;
        boolean closeCaseEnabled = resolvedOrClosed || case_.getStatus() == AgentCaseStatus.IN_REVIEW || case_.getStatus() == AgentCaseStatus.NEW || case_.getStatus() == AgentCaseStatus.PENDING_EXPLANATION || case_.getStatus() == AgentCaseStatus.PENDING_APPROVAL;
        return CaseDetailDto.FinalReportDto.builder()
                .summary(summary)
                .verdict(verdict)
                .requestClarificationEnabled(requestClarificationEnabled)
                .closeCaseEnabled(closeCaseEnabled)
                .build();
    }

    private CaseDetailDto.EvidencePanelDto buildEvidencePanel(Long tenantId, AgentCase case_) {
        String docKey = case_.getBukrs() != null && case_.getBelnr() != null && case_.getGjahr() != null
                ? case_.getBukrs() + "-" + case_.getBelnr() + "-" + case_.getGjahr() : null;
        DocumentOrOpenItemDto docOrOi = null;
        if (docKey != null) {
            var headerOpt = fiDocHeaderRepository.findByTenantIdAndBukrsAndBelnrAndGjahr(
                    tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
            if (headerOpt.isPresent()) {
                var header = headerOpt.get();
                List<FiDocItem> items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                        tenantId, header.getBukrs(), header.getBelnr(), header.getGjahr());
                BigDecimal docAmount = items.stream().map(FiDocItem::getWrbtr).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
                String caseBuzei = case_.getBuzei();
                List<DocumentLineItemDto> lineItems = items.stream()
                        .map(i -> toDocumentLineItem(i, caseBuzei, docKey))
                        .toList();
                docOrOi = DocumentOrOpenItemDto.builder()
                        .type("DOCUMENT")
                        .docKey(docKey)
                        .headerSummary(Map.of("bukrs", header.getBukrs(), "belnr", header.getBelnr(), "gjahr", header.getGjahr(),
                                "budat", header.getBudat() != null ? header.getBudat().toString() : "", "xblnr", header.getXblnr() != null ? header.getXblnr() : ""))
                        .items(lineItems)
                        .lineCount(lineItems.size())
                        .amount(docAmount.compareTo(BigDecimal.ZERO) > 0 ? docAmount : null)
                        .currency(header.getWaers())
                        .build();
            } else {
                var openItems = fiOpenItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                        tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
                if (!openItems.isEmpty()) {
                    var oi = openItems.get(0);
                    docOrOi = DocumentOrOpenItemDto.builder()
                            .type("OPEN_ITEM")
                            .docKey(docKey)
                            .headerSummary(Map.of("bukrs", oi.getBukrs(), "belnr", oi.getBelnr(), "gjahr", oi.getGjahr()))
                            .items(List.of())
                            .lineCount(0)
                            .amount(oi.getOpenAmount())
                            .currency(oi.getCurrency())
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
        var amountCurrency = resolveAmountAndCurrency(tenantId, case_);
        return CaseDetailDto.EvidencePanelDto.builder()
                .documentOrOpenItem(docOrOi)
                .reversalChainSummary(CaseDetailDto.ReversalChainSummaryDto.builder()
                        .nodeDocKeys(reversalNodes)
                        .edgeCount(Math.max(0, reversalNodes.size() - 1))
                        .build())
                .relatedPartyIds(relatedPartyIds.stream().distinct().toList())
                .amount(amountCurrency != null ? amountCurrency.amount() : null)
                .currency(amountCurrency != null ? amountCurrency.currency() : null)
                .build();
    }

    /** Phase A: fi_doc_item → DocumentLineItemDto (확장 필드 + isTarget + id for FE data-row-id) */
    private DocumentLineItemDto toDocumentLineItem(FiDocItem i, String caseBuzei, String docKey) {
        boolean isTarget = caseBuzei != null && !caseBuzei.isBlank() && caseBuzei.equals(i.getBuzei());
        String rowId = (docKey != null && !docKey.isBlank()) ? docKey + "-" + i.getBuzei() : i.getBuzei();
        return DocumentLineItemDto.builder()
                .id(rowId)
                .buzei(i.getBuzei())
                .lifnr(i.getLifnr())
                .kunnr(i.getKunnr())
                .wrbtr(i.getWrbtr())
                .hkont(i.getHkont())
                .bschl(i.getBschl())
                .shkzg(i.getShkzg())
                .dmbtr(i.getDmbtr())
                .waers(i.getWaers())
                .mwskz(i.getMwskz())
                .kostl(i.getKostl())
                .prctr(i.getPrctr())
                .aufnr(i.getAufnr())
                .zterm(i.getZterm())
                .zfbdt(i.getZfbdt())
                .dueDate(i.getDueDate())
                .paymentBlock(i.getPaymentBlock())
                .disputeFlag(i.getDisputeFlag())
                .zuonr(i.getZuonr())
                .sgtxt(i.getSgtxt())
                .isTarget(isTarget)
                .build();
    }

    private Long resolvePartyId(Long tenantId, AgentCase case_) {
        return resolvePartySummary(tenantId, case_) != null ? resolvePartySummary(tenantId, case_).getPartyId() : null;
    }

    /** P0-2: evidence_json.window 또는 case_type 기반 sourceType */
    private String resolveSourceType(AgentCase case_) {
        if (case_.getEvidenceJson() != null && case_.getEvidenceJson().has("window")) {
            var w = case_.getEvidenceJson().get("window");
            if (w != null && !w.isNull()) {
                String s = w.asText();
                if (s != null && !s.isBlank()) return s;
            }
        }
        return case_.getCaseType() != null ? case_.getCaseType() : "DEFAULT";
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
