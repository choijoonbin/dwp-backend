package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Immutable workflow binding and concurrency rules for delegation commands. */
public final class ApprovalDelegationCommandSupport {

    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalCommandPayloadSupport payloadSupport;

    ApprovalDelegationCommandSupport(
            NamedParameterJdbcTemplate jdbc,
            ApprovalCommandPayloadSupport payloadSupport) {
        this.jdbc = jdbc;
        this.payloadSupport = payloadSupport;
    }

    Created create(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateDelegationRequest request,
            ApprovalIdentityDirectory.Subject delegate) {
        validateActorsAndWindow(actor, request, delegate);
        String scope = request.scopeType().trim().toUpperCase(Locale.ROOT);
        validateScopeShape(scope, request);
        String requestedWorkflowKey = "WORKFLOW".equals(scope)
                && request.workflowKey() != null && !request.workflowKey().isBlank()
                ? request.workflowKey().trim().toUpperCase(Locale.ROOT)
                : null;
        Workflow workflow = resolveWorkflow(actor, request, scope, requestedWorkflowKey);
        MapSqlParameterSource lockParams = actorParams(actor)
                .addValue("delegateUserId", request.delegateUserId())
                .addValue("scopeType", scope)
                .addValue("workflowId", workflow.id(), java.sql.Types.OTHER)
                .addValue("workflowKey", workflow.key())
                .addValue("startsAt", Timestamp.from(request.startsAt()))
                .addValue("endsAt", Timestamp.from(request.endsAt()));
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                lockParams.addValue(
                        "lockKey",
                        actor.tenantId() + ":approval-delegation:" + actor.userId()),
                Object.class);
        Integer overlaps = jdbc.queryForObject(
                ApprovalCommandSql01.COUNT_SELECT_APR_DELEGATIONS,
                lockParams,
                Integer.class);
        if (overlaps != null && overlaps > 0) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        UUID id = UUID.randomUUID();
        jdbc.update(ApprovalCommandSql01.COALESCE_INSERT_APR_DELEGATIONS,
                actorParams(actor)
                        .addValue("id", id)
                        .addValue("delegateUserId", request.delegateUserId())
                        .addValue("delegatePersonPublicId", delegate.personPublicId())
                        .addValue("delegateDisplayName", delegate.displayName())
                        .addValue("delegateEmail", delegate.email())
                        .addValue("delegatedRoles", payloadSupport.json(
                                actor.roles().stream().sorted().toList()))
                        .addValue("scopeType", scope)
                        .addValue("workflowId", workflow.id(), java.sql.Types.OTHER)
                        .addValue("workflowKey", workflow.key())
                        .addValue("startsAt", Timestamp.from(request.startsAt()))
                        .addValue("endsAt", Timestamp.from(request.endsAt()))
                        .addValue("reason", request.reason().trim()));
        return new Created(id, scope, workflow.id(), workflow.key());
    }

    void revoke(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            long expectedVersion) {
        int updated = jdbc.update(
                ApprovalCommandSql01.REVOKE_DELEGATION_UPDATE_APR_DELEGATIONS,
                actorParams(actor)
                        .addValue("delegationId", delegationId)
                        .addValue("expectedVersion", expectedVersion));
        if (updated != 1) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private Workflow resolveWorkflow(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateDelegationRequest request,
            String scope,
            String requestedWorkflowKey) {
        if (!"WORKFLOW".equals(scope)) return new Workflow(null, null);
        String sql = request.workflowId() == null
                ? ApprovalCommandSql01.CREATE_LEGACY_DELEGATION_SELECT_APR_WORKFLOW_DEFINITIONS
                : ApprovalCommandSql01.CREATE_DELEGATION_SELECT_APR_WORKFLOW_DEFINITIONS;
        List<Workflow> workflows = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenantId", actor.tenantId())
                        .addValue("workflowId", request.workflowId())
                        .addValue("workflowKey", requestedWorkflowKey),
                (result, ignored) -> new Workflow(
                        result.getObject("workflow_id", UUID.class),
                        result.getString("workflow_key")));
        if (workflows.size() != 1) throw new BaseException(ErrorCode.NOT_FOUND);
        Workflow workflow = workflows.getFirst();
        if (requestedWorkflowKey != null
                && !requestedWorkflowKey.equals(workflow.key())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return workflow;
    }

    private void validateActorsAndWindow(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateDelegationRequest request,
            ApprovalIdentityDirectory.Subject delegate) {
        if (request.delegateUserId().equals(actor.userId())
                || !request.endsAt().isAfter(request.startsAt())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Instant now = Instant.now();
        if (request.startsAt().isBefore(now.minus(Duration.ofMinutes(5)))
                || Duration.between(request.startsAt(), request.endsAt())
                        .compareTo(Duration.ofDays(90)) > 0
                || !delegate.active()
                || !request.delegateUserId().equals(delegate.userId())
                || !actor.tenantId().equals(delegate.tenantId())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private void validateScopeShape(
            String scope,
            ApprovalDtos.CreateDelegationRequest request) {
        if (!Set.of("ALL", "WORKFLOW").contains(scope)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if ("WORKFLOW".equals(scope)
                && request.workflowId() == null
                && (request.workflowKey() == null || request.workflowKey().isBlank())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if ("ALL".equals(scope)
                && (request.workflowId() != null
                    || request.workflowKey() != null && !request.workflowKey().isBlank())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private MapSqlParameterSource actorParams(ApprovalRequestContext.Actor actor) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId());
    }

    public record Created(
            UUID delegationId,
            String scopeType,
            UUID workflowId,
            String workflowKey) {

        Map<String, Object> auditAfterState(long delegateUserId, Instant endsAt) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("delegateUserId", delegateUserId);
            state.put("scopeType", scopeType);
            state.put("endsAt", endsAt.toString());
            if (workflowId != null) {
                state.put("workflowId", workflowId.toString());
                state.put("workflowKey", workflowKey);
            }
            return Map.copyOf(state);
        }
    }

    private record Workflow(UUID id, String key) {
    }
}
