package com.dwp.services.synapsex.service.action;

import com.dwp.services.synapsex.dto.action.ActionDetailDto;
import com.dwp.services.synapsex.dto.action.ActionListRowDto;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.entity.AgentAction;
import com.dwp.services.synapsex.entity.QAgentAction;
import com.dwp.services.synapsex.repository.AgentActionRepository;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Phase 2 Actions 조회 서비스
 */
@Service
@RequiredArgsConstructor
public class ActionQueryService {

    private final JPAQueryFactory queryFactory;
    private final AgentActionRepository agentActionRepository;

    private static final QAgentAction a = QAgentAction.agentAction;

    @Transactional(readOnly = true)
    public PageResponse<ActionListRowDto> findActions(Long tenantId, ActionListQuery query) {
        BooleanBuilder predicate = new BooleanBuilder();
        predicate.and(a.tenantId.eq(tenantId));

        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            predicate.and(a.status.eq(query.getStatus()));
        }
        if (query.getType() != null && !query.getType().isBlank()) {
            predicate.and(a.actionType.eq(query.getType()));
        }
        if (query.getCaseId() != null) {
            predicate.and(a.caseId.eq(query.getCaseId()));
        }
        if (query.getCreatedFrom() != null) {
            predicate.and(a.createdAt.goe(query.getCreatedFrom()).or(a.plannedAt.goe(query.getCreatedFrom())));
        }
        if (query.getCreatedTo() != null) {
            predicate.and(a.createdAt.loe(query.getCreatedTo()).or(a.plannedAt.loe(query.getCreatedTo())));
        }

        int page = Math.max(0, query.getPage());
        int size = Math.min(100, Math.max(1, query.getSize()));
        com.querydsl.core.types.OrderSpecifier<?> orderBy = a.plannedAt.desc();
        if (query.getSort() != null && !query.getSort().isBlank()) {
            String[] parts = query.getSort().split(",");
            boolean asc = parts.length < 2 || !"desc".equalsIgnoreCase(parts[parts.length - 1].trim());
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
        return PageResponse.of(rows, total, page, size);
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
                .status(action.getStatus())
                .payload(action.getPayloadJson())
                .simulationBefore(action.getSimulationBefore())
                .simulationAfter(action.getSimulationAfter())
                .diffJson(action.getDiffJson())
                .createdAt(created)
                .build();
    }

    private ActionListRowDto toListRow(AgentAction action) {
        Instant created = action.getCreatedAt() != null ? action.getCreatedAt() : action.getPlannedAt();
        String outcome = "EXECUTED".equals(action.getStatus()) || "SUCCESS".equals(action.getStatus()) ? "SUCCESS"
                : "FAILED".equals(action.getStatus()) ? "FAILED" : null;
        return ActionListRowDto.builder()
                .actionId(action.getActionId())
                .caseId(action.getCaseId())
                .actionType(action.getActionType())
                .status(action.getStatus())
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
        private String status;
        private String type;
        private Long caseId;
        private Instant createdFrom;
        private Instant createdTo;
        @lombok.Builder.Default
        private int page = 0;
        @lombok.Builder.Default
        private int size = 20;
        private String sort;
    }
}
