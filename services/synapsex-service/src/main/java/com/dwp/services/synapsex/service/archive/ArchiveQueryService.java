package com.dwp.services.synapsex.service.archive;

import com.dwp.services.synapsex.dto.archive.ArchiveListRowDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.entity.AgentActionStatus;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.QAgentAction;
import com.dwp.services.synapsex.repository.AgentActionRepository;
import com.dwp.services.synapsex.repository.AgentCaseRepository;
import com.dwp.services.synapsex.repository.FiDocItemRepository;
import com.dwp.services.synapsex.repository.BpPartyRepository;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 2 Archive - actions with status EXECUTED/FAILED/CANCELED
 */
@Service
@RequiredArgsConstructor
public class ArchiveQueryService {

    private static final Set<String> ARCHIVE_STATUSES = Set.of("EXECUTED", "FAILED", "CANCELED", "SUCCESS", "PLANNED");

    private final JPAQueryFactory queryFactory;
    private final AgentActionRepository agentActionRepository;
    private final AgentCaseRepository agentCaseRepository;
    private final FiDocItemRepository fiDocItemRepository;
    private final BpPartyRepository bpPartyRepository;

    private static final QAgentAction a = QAgentAction.agentAction;

    @Transactional(readOnly = true)
    public PageResponse<ArchiveListRowDto> findArchivedActions(Long tenantId, ArchiveListQuery query) {
        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(a.tenantId.eq(tenantId));
        predicate.and(a.status.in(AgentActionStatus.EXECUTED, AgentActionStatus.FAILED, AgentActionStatus.CANCELED, AgentActionStatus.SUCCESS));

        if (query.getOutcome() != null && !query.getOutcome().isBlank()) {
            if ("SUCCESS".equalsIgnoreCase(query.getOutcome())) {
                predicate.and(a.status.in(AgentActionStatus.EXECUTED, AgentActionStatus.SUCCESS));
            } else if ("FAILED".equalsIgnoreCase(query.getOutcome())) {
                predicate.and(a.status.eq(AgentActionStatus.FAILED));
            }
        }
        if (query.getType() != null && !query.getType().isBlank()) {
            predicate.and(a.actionType.eq(query.getType()));
        }
        if (query.getFrom() != null) {
            predicate.and(a.executedAt.goe(query.getFrom()).or(a.plannedAt.goe(query.getFrom())));
        }
        if (query.getTo() != null) {
            predicate.and(a.executedAt.loe(query.getTo()).or(a.plannedAt.loe(query.getTo())));
        }

        int page = Math.max(0, query.getPage());
        int size = Math.min(100, Math.max(1, query.getSize()));
        com.querydsl.core.types.OrderSpecifier<?> orderBy = a.executedAt.desc().nullsLast();
        if (query.getSort() != null && !query.getSort().isBlank()) {
            String[] parts = query.getSort().split(",");
            boolean asc = parts.length < 2 || !"desc".equalsIgnoreCase(parts[parts.length - 1].trim());
            String field = parts[0].trim().toLowerCase();
            orderBy = switch (field) {
                case "executedat", "executed_at" -> asc ? a.executedAt.asc().nullsFirst() : a.executedAt.desc().nullsLast();
                case "actiontype", "action_type" -> asc ? a.actionType.asc() : a.actionType.desc();
                default -> asc ? a.executedAt.asc().nullsFirst() : a.executedAt.desc().nullsLast();
            };
        }

        List<AgentAction> actions = queryFactory.selectFrom(a)
                .where(predicate)
                .orderBy(orderBy)
                .offset((long) page * size)
                .limit(size)
                .fetch();
        long total = queryFactory.selectFrom(a).where(predicate).fetchCount();

        List<ArchiveListRowDto> rows = actions.stream()
                .map(action -> toArchiveRow(tenantId, action))
                .toList();
        return PageResponse.of(rows, total, page, size);
    }

    @Transactional(readOnly = true)
    public Optional<ArchiveListRowDto> findArchivedActionDetail(Long tenantId, Long actionId) {
        return agentActionRepository.findById(actionId)
                .filter(a -> tenantId.equals(a.getTenantId()))
                .filter(a -> a.getStatus() != null && java.util.Set.of(AgentActionStatus.EXECUTED, AgentActionStatus.FAILED, AgentActionStatus.CANCELED, AgentActionStatus.SUCCESS).contains(a.getStatus()))
                .map(a -> toArchiveRow(tenantId, a));
    }

    private ArchiveListRowDto toArchiveRow(Long tenantId, AgentAction action) {
        AgentActionStatus st = action.getStatus();
        String outcome = st == AgentActionStatus.EXECUTED || st == AgentActionStatus.SUCCESS ? "SUCCESS"
                : st == AgentActionStatus.FAILED ? "FAILED" : (st != null ? st.name() : null);
        String docKey = null;
        Long partyId = null;
        Optional<AgentCase> caseOpt = agentCaseRepository.findByCaseIdAndTenantId(action.getCaseId(), tenantId);
        if (caseOpt.isPresent()) {
            AgentCase case_ = caseOpt.get();
            if (case_.getBukrs() != null && case_.getBelnr() != null && case_.getGjahr() != null) {
                docKey = case_.getBukrs() + "-" + case_.getBelnr() + "-" + case_.getGjahr();
                var items = fiDocItemRepository.findByTenantIdAndBukrsAndBelnrAndGjahrOrderByBuzeiAsc(
                        tenantId, case_.getBukrs(), case_.getBelnr(), case_.getGjahr());
                for (var item : items) {
                    if (item.getLifnr() != null) {
                        partyId = bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "VENDOR", item.getLifnr())
                                .map(p -> p.getPartyId())
                                .orElse(null);
                        break;
                    }
                    if (item.getKunnr() != null) {
                        partyId = bpPartyRepository.findByTenantIdAndPartyTypeAndPartyCode(tenantId, "CUSTOMER", item.getKunnr())
                                .map(p -> p.getPartyId())
                                .orElse(null);
                        break;
                    }
                }
            }
        }
        return ArchiveListRowDto.builder()
                .actionId(action.getActionId())
                .caseId(action.getCaseId())
                .actionType(action.getActionType())
                .status(action.getStatus() != null ? action.getStatus().name() : null)
                .outcome(outcome)
                .executedAt(action.getExecutedAt())
                .failureReason(action.getFailureReason() != null ? action.getFailureReason() : action.getErrorMessage())
                .docKey(docKey)
                .partyId(partyId)
                .build();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ArchiveListQuery {
        private String outcome;
        private String type;
        private java.time.Instant from;
        private java.time.Instant to;
        @lombok.Builder.Default
        private int page = 0;
        @lombok.Builder.Default
        private int size = 20;
        private String sort;
    }
}
