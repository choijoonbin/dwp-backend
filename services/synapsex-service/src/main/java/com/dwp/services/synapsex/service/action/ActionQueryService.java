package com.dwp.services.synapsex.service.action;

import com.dwp.services.synapsex.dto.action.ActionDetailDto;
import com.dwp.services.synapsex.dto.action.ActionListRowDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.entity.AgentActionStatus;
import com.dwp.services.synapsex.entity.AgentCase;
import com.dwp.services.synapsex.entity.QAgentAction;
import com.dwp.services.synapsex.entity.QAgentCase;
import com.dwp.services.synapsex.repository.AgentActionRepository;
import com.dwp.services.synapsex.scope.DrillDownCodeResolver;
import com.dwp.services.synapsex.util.DrillDownParamUtil;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 2 Actions 조회 서비스
 */
@Service
@RequiredArgsConstructor
public class ActionQueryService {

    private final JPAQueryFactory queryFactory;
    private final AgentActionRepository agentActionRepository;
    private final DrillDownCodeResolver drillDownCodeResolver;

    private static final QAgentAction a = QAgentAction.agentAction;
    private static final QAgentCase c = QAgentCase.agentCase;

    @Transactional(readOnly = true)
    public PageResponse<ActionListRowDto> findActions(Long tenantId, ActionListQuery query) {
        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(a.tenantId.eq(tenantId));

        List<String> statusList = drillDownCodeResolver.filterValid(DrillDownCodeResolver.GROUP_ACTION_STATUS,
                DrillDownParamUtil.parseMulti(query.getStatus()));
        if (!statusList.isEmpty()) {
            List<AgentActionStatus> statusEnums = statusList.stream()
                    .map(AgentActionStatus::fromString)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (!statusEnums.isEmpty()) predicate.and(a.status.in(statusEnums));
        } else if (query.getStatus() != null && !query.getStatus().isBlank()) {
            AgentActionStatus statusEnum = AgentActionStatus.fromString(query.getStatus());
            if (statusEnum != null) predicate.and(a.status.eq(statusEnum));
        }
        if (query.getType() != null && !query.getType().isBlank()) {
            predicate.and(a.actionType.eq(query.getType()));
        }
        if (query.getCaseId() != null) {
            predicate.and(a.caseId.eq(query.getCaseId()));
        }
        if (query.getIds() != null && !query.getIds().isEmpty()) {
            predicate.and(a.actionId.in(query.getIds()));
        }
        if (query.getCreatedFrom() != null) {
            predicate.and(a.createdAt.goe(query.getCreatedFrom()).or(a.plannedAt.goe(query.getCreatedFrom())));
        }
        if (query.getCreatedTo() != null) {
            predicate.and(a.createdAt.loe(query.getCreatedTo()).or(a.plannedAt.loe(query.getCreatedTo())));
        }
        if (query.getCompany() != null && !query.getCompany().isEmpty()) {
            predicate.and(a.caseId.in(
                    queryFactory.select(c.caseId).from(c)
                            .where(c.tenantId.eq(tenantId).and(c.bukrs.in(query.getCompany())))
            ));
        }
        if (query.getAssigneeUserId() != null) {
            predicate.and(a.caseId.in(
                    queryFactory.select(c.caseId).from(c)
                            .where(c.tenantId.eq(tenantId).and(c.assigneeUserId.eq(query.getAssigneeUserId())))
            ));
        }
        if (query.getSeverity() != null && !query.getSeverity().isBlank()) {
            List<String> severityList = drillDownCodeResolver.filterValid(DrillDownCodeResolver.GROUP_SEVERITY,
                    DrillDownParamUtil.parseMulti(query.getSeverity()));
            if (!severityList.isEmpty()) {
                predicate.and(a.caseId.in(
                        queryFactory.select(c.caseId).from(c)
                                .where(c.tenantId.eq(tenantId).and(c.severity.in(severityList)))
                ));
            } else {
                predicate.and(a.caseId.in(
                        queryFactory.select(c.caseId).from(c)
                                .where(c.tenantId.eq(tenantId).and(c.severity.eq(query.getSeverity())))
                ));
            }
        }
        if (Boolean.TRUE.equals(query.getRequiresApproval())) {
            List<String> approvalStatuses = drillDownCodeResolver.filterValid(DrillDownCodeResolver.GROUP_ACTION_STATUS,
                    List.of("PENDING_APPROVAL", "PENDING", "PROPOSED", "WAITING_APPROVAL"));
            if (approvalStatuses.isEmpty()) approvalStatuses = List.of("PENDING_APPROVAL", "PENDING", "PROPOSED");
            List<AgentActionStatus> statusEnums = approvalStatuses.stream()
                    .map(AgentActionStatus::fromString)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (!statusEnums.isEmpty()) predicate.and(a.status.in(statusEnums));
        }

        int page = Math.max(0, query.getPage());
        int size = Math.min(200, Math.max(1, query.getSize()));
        boolean asc = !"desc".equalsIgnoreCase(query.getOrder());
        com.querydsl.core.types.OrderSpecifier<?> orderBy = a.plannedAt.desc();
        if (query.getSort() != null && !query.getSort().isBlank()) {
            String[] parts = query.getSort().split(",");
            if (parts.length >= 2) asc = "asc".equalsIgnoreCase(parts[1].trim());
            String field = parts[0].trim().toLowerCase();
            orderBy = switch (field) {
                case "createdat", "created_at" -> asc ? a.createdAt.asc() : a.createdAt.desc();
                case "executedat", "executed_at" -> asc ? a.executedAt.asc() : a.executedAt.desc();
                case "actiontype", "action_type" -> asc ? a.actionType.asc() : a.actionType.desc();
                default -> asc ? a.plannedAt.asc() : a.plannedAt.desc();
            };
        }

        List<AgentAction> actions = queryFactory.selectFrom(a)
                .where(predicate)
                .orderBy(orderBy)
                .offset((long) page * size)
                .limit(size)
                .fetch();
        long total = queryFactory.selectFrom(a).where(predicate).fetchCount();

        List<ActionListRowDto> rows = actions.stream()
                .map(this::toListRow)
                .toList();
        Map<String, Object> filtersApplied = buildFiltersApplied(query);
        return PageResponse.of(rows, total, page, size,
                query.getSort() != null ? query.getSort() : "plannedAt",
                query.getOrder() != null ? query.getOrder() : "desc",
                filtersApplied);
    }

    private Map<String, Object> buildFiltersApplied(ActionListQuery query) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (query.getRange() != null && !query.getRange().isBlank()) m.put("range", query.getRange());
        if (query.getCreatedFrom() != null) m.put("from", query.getCreatedFrom().toString());
        if (query.getCreatedTo() != null) m.put("to", query.getCreatedTo().toString());
        if (query.getStatus() != null && !query.getStatus().isBlank())
            m.put("status", DrillDownParamUtil.parseMulti(query.getStatus()));
        if (query.getType() != null && !query.getType().isBlank()) m.put("type", query.getType());
        if (query.getCaseId() != null) m.put("caseId", query.getCaseId());
        if (query.getIds() != null && !query.getIds().isEmpty()) m.put("ids", query.getIds());
        if (query.getCompany() != null && !query.getCompany().isEmpty()) m.put("company", query.getCompany());
        if (query.getAssigneeUserId() != null) m.put("assigneeUserId", query.getAssigneeUserId());
        if (query.getSeverity() != null && !query.getSeverity().isBlank())
            m.put("severity", DrillDownParamUtil.parseMulti(query.getSeverity()));
        if (Boolean.TRUE.equals(query.getRequiresApproval())) m.put("requiresApproval", true);
        return m.isEmpty() ? null : m;
    }

    @Transactional(readOnly = true)
    public java.util.Optional<ActionDetailDto> findActionDetail(Long tenantId, Long actionId) {
        return agentActionRepository.findById(actionId)
                .filter(a -> tenantId.equals(a.getTenantId()))
                .map(this::toDetailDto);
    }

    private ActionDetailDto toDetailDto(AgentAction action) {
        Instant created = action.getCreatedAt() != null ? action.getCreatedAt() : action.getPlannedAt();
        return ActionDetailDto.builder()
                .actionId(action.getActionId())
                .caseId(action.getCaseId())
                .actionType(action.getActionType())
                .status(action.getStatus() != null ? action.getStatus().name() : null)
                .payload(action.getPayloadJson())
                .simulationBefore(action.getSimulationBefore())
                .simulationAfter(action.getSimulationAfter())
                .diffJson(action.getDiffJson())
                .createdAt(created)
                .build();
    }

    private ActionListRowDto toListRow(AgentAction action) {
        Instant created = action.getCreatedAt() != null ? action.getCreatedAt() : action.getPlannedAt();
        AgentActionStatus st = action.getStatus();
        String outcome = st == AgentActionStatus.EXECUTED || st == AgentActionStatus.SUCCESS ? "SUCCESS"
                : st == AgentActionStatus.FAILED ? "FAILED" : null;
        return ActionListRowDto.builder()
                .actionId(action.getActionId())
                .caseId(action.getCaseId())
                .actionType(action.getActionType())
                .status(action.getStatus() != null ? action.getStatus().name() : null)
                .createdAt(created)
                .executedAt(action.getExecutedAt())
                .outcome(outcome)
                .failureReason(action.getFailureReason() != null ? action.getFailureReason() : action.getErrorMessage())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ActionListQuery {
        private String range;
        private Instant createdFrom;
        private Instant createdTo;
        private String status;
        private String type;
        private Boolean requiresApproval;
        private Long caseId;
        private List<Long> ids;
        private List<String> company;
        private Long assigneeUserId;
        private String severity;
        @lombok.Builder.Default
        private int page = 0;
        @lombok.Builder.Default
        private int size = 20;
        private String sort;
        private String order;
    }
}
