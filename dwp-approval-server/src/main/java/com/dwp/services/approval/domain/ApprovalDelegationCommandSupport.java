package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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
        lockPair(actor, request.delegateUserId(), lockParams);
        Integer overlaps = jdbc.queryForObject(
                ApprovalCommandSql01.COUNT_SELECT_APR_DELEGATIONS,
                lockParams,
                Integer.class);
        Integer reverse = jdbc.queryForObject(
                ApprovalDelegationSql01.COUNT_REVERSE,
                lockParams,
                Integer.class);
        if (positive(overlaps) || positive(reverse)) {
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

    UpdateReplay updateReplay(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            ApprovalDelegationUpdateRequest request,
            String idempotencyKey) {
        String key = normalizedIdempotencyKey(idempotencyKey);
        String fingerprint = updateFingerprint(delegationId, request);
        UUID eventId = UUID.nameUUIDFromBytes(("approval.delegation.update:"
                + actor.tenantId() + ":" + actor.userId() + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
        MapSqlParameterSource params = actorParams(actor)
                .addValue("delegationId", delegationId)
                .addValue("idempotencyKey", key)
                .addValue("eventId", eventId)
                .addValue("lockKey", actor.tenantId() + ":approval-delegation-update:"
                        + actor.userId() + ":" + key);
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                params,
                Object.class);
        List<UpdateReceipt> receipts = jdbc.query("""
                SELECT tenant_id, payload ->> 'action' AS action,
                       payload ->> 'actorId' AS actor_id,
                       payload ->> 'targetId' AS target_id,
                       payload -> 'afterState' ->> 'idempotencyKey' AS idempotency_key,
                       payload -> 'afterState' ->> 'commandFingerprint' AS fingerprint
                  FROM sys_audit_outbox
                 WHERE event_id = :eventId
                """, params, (result, ignored) -> new UpdateReceipt(
                result.getLong("tenant_id"), result.getString("action"),
                result.getString("actor_id"), result.getString("target_id"),
                result.getString("idempotency_key"), result.getString("fingerprint")));
        if (receipts.isEmpty()) return new UpdateReplay(false, key, fingerprint, eventId);
        if (receipts.size() != 1
                || receipts.getFirst().tenantId() != actor.tenantId()
                || !"approval.delegation.updated".equals(receipts.getFirst().action())
                || !actor.userId().toString().equals(receipts.getFirst().actorId())
                || !delegationId.toString().equals(receipts.getFirst().targetId())
                || !key.equals(receipts.getFirst().idempotencyKey())
                || !fingerprint.equals(receipts.getFirst().fingerprint())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The delegation idempotency key is bound to another command.");
        }
        return new UpdateReplay(true, key, fingerprint, eventId);
    }

    Updated update(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            ApprovalDelegationUpdateRequest request,
            ApprovalIdentityDirectory.Subject delegate,
            UpdateReplay replay) {
        if (replay.replayed()) {
            return new Updated(delegationId, replay.idempotencyKey(), replay.fingerprint(),
                    request.expectedVersion() + 1);
        }
        DelegationOwner observed = owner(actor, delegationId, false);
        if (observed == null || observed.delegateUserId() != request.delegateUserId()) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE);
        }
        MapSqlParameterSource params = actorParams(actor)
                .addValue("delegationId", delegationId)
                .addValue("delegateUserId", request.delegateUserId());
        lockPair(actor, request.delegateUserId(), params);
        DelegationOwner current = owner(actor, delegationId, true);
        if (current == null || current.delegateUserId() != request.delegateUserId()
                || !"ACTIVE".equals(current.lifecycleState())) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE);
        }
        if (current.version() != request.expectedVersion()) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "Delegation version changed.");
        }
        validateActorsAndWindow(actor, request, delegate, current.startsAt());
        String scope = request.scopeType().trim().toUpperCase(Locale.ROOT);
        validateScopeShape(scope, request.workflowId());
        Workflow workflow = resolveWorkflow(actor, scope, request.workflowId(), null);
        params.addValue("scopeType", scope)
                .addValue("workflowId", workflow.id(), java.sql.Types.OTHER)
                .addValue("workflowKey", workflow.key())
                .addValue("startsAt", Timestamp.from(request.startsAt()))
                .addValue("endsAt", Timestamp.from(request.endsAt()));
        Integer overlaps = jdbc.queryForObject(
                ApprovalDelegationSql01.COUNT_UPDATE_CONFLICT,
                params,
                Integer.class);
        Integer reverse = jdbc.queryForObject(
                ApprovalDelegationSql01.COUNT_UPDATE_REVERSE,
                params,
                Integer.class);
        if (positive(overlaps) || positive(reverse)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        int updated = jdbc.update(
                ApprovalDelegationSql01.UPDATE,
                params.addValue("delegatePersonPublicId", delegate.personPublicId())
                        .addValue("delegateDisplayName", delegate.displayName())
                        .addValue("delegateEmail", delegate.email())
                        .addValue("delegatedRoles", payloadSupport.json(
                                actor.roles().stream().sorted().toList()))
                        .addValue("reason", request.reason().trim())
                        .addValue("expectedVersion", request.expectedVersion()));
        if (updated != 1) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
        }
        return new Updated(delegationId, replay.idempotencyKey(), replay.fingerprint(),
                request.expectedVersion() + 1);
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
        return resolveWorkflow(actor, scope, request.workflowId(), requestedWorkflowKey);
    }

    private Workflow resolveWorkflow(
            ApprovalRequestContext.Actor actor,
            String scope,
            UUID workflowId,
            String requestedWorkflowKey) {
        if (!"WORKFLOW".equals(scope)) return new Workflow(null, null);
        String sql = workflowId == null
                ? ApprovalCommandSql01.CREATE_LEGACY_DELEGATION_SELECT_APR_WORKFLOW_DEFINITIONS
                : ApprovalCommandSql01.CREATE_DELEGATION_SELECT_APR_WORKFLOW_DEFINITIONS;
        List<Workflow> workflows = jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("tenantId", actor.tenantId())
                        .addValue("workflowId", workflowId)
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
                || !request.endsAt().isAfter(now)
                || Duration.between(request.startsAt(), request.endsAt())
                        .compareTo(Duration.ofDays(90)) > 0
                || !delegate.active()
                || !request.delegateUserId().equals(delegate.userId())
                || !actor.tenantId().equals(delegate.tenantId())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private void validateActorsAndWindow(
            ApprovalRequestContext.Actor actor,
            ApprovalDelegationUpdateRequest request,
            ApprovalIdentityDirectory.Subject delegate,
            Instant currentStartsAt) {
        Instant now = Instant.now();
        boolean retainedStartedWindow = request.startsAt().equals(currentStartsAt);
        if (request.delegateUserId().equals(actor.userId())
                || !request.endsAt().isAfter(request.startsAt())
                || !request.endsAt().isAfter(now)
                || (request.startsAt().isBefore(now.minus(Duration.ofMinutes(5)))
                        && !retainedStartedWindow)
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

    private void validateScopeShape(String scope, UUID workflowId) {
        if (!Set.of("ALL", "WORKFLOW").contains(scope)
                || "WORKFLOW".equals(scope) && workflowId == null
                || "ALL".equals(scope) && workflowId != null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private DelegationOwner owner(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            boolean lock) {
        String sql = lock
                ? ApprovalDelegationSql01.SELECT_OWNER_FOR_UPDATE
                : ApprovalDelegationSql01.SELECT_OWNER;
        List<DelegationOwner> owners = jdbc.query(
                sql,
                actorParams(actor).addValue("delegationId", delegationId),
                (result, ignored) -> new DelegationOwner(
                        result.getLong("delegate_user_id"),
                        result.getTimestamp("starts_at").toInstant(),
                        result.getString("lifecycle_state"),
                        result.getLong("version")));
        return owners.size() == 1 ? owners.getFirst() : null;
    }

    private void lockPair(
            ApprovalRequestContext.Actor actor,
            long delegateUserId,
            MapSqlParameterSource params) {
        long first = Math.min(actor.userId(), delegateUserId);
        long second = Math.max(actor.userId(), delegateUserId);
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                params.addValue("lockKey", actor.tenantId()
                        + ":approval-delegation-pair:" + first + ":" + second),
                Object.class);
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private String normalizedIdempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,120}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return value;
    }

    private String updateFingerprint(
            UUID delegationId,
            ApprovalDelegationUpdateRequest request) {
        String canonical = String.join("\u0000",
                delegationId.toString(),
                String.valueOf(request.delegateUserId()),
                request.scopeType().trim().toUpperCase(Locale.ROOT),
                request.workflowId() == null ? "" : request.workflowId().toString(),
                request.startsAt().toString(),
                request.endsAt().toString(),
                request.reason().trim(),
                String.valueOf(request.expectedVersion()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
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

    public record UpdateReplay(
            boolean replayed,
            String idempotencyKey,
            String fingerprint,
            UUID eventId) {
    }

    public record Updated(
            UUID delegationId,
            String idempotencyKey,
            String fingerprint,
            long version) {

        Map<String, Object> auditAfterState(ApprovalDelegationUpdateRequest request) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("delegateUserId", request.delegateUserId());
            state.put("scopeType", request.scopeType().trim().toUpperCase(Locale.ROOT));
            state.put("startsAt", request.startsAt().toString());
            state.put("endsAt", request.endsAt().toString());
            state.put("version", version);
            state.put("idempotencyKey", idempotencyKey);
            state.put("commandFingerprint", fingerprint);
            if (request.workflowId() != null) {
                state.put("workflowId", request.workflowId().toString());
            }
            return Map.copyOf(state);
        }
    }

    private record DelegationOwner(
            long delegateUserId,
            Instant startsAt,
            String lifecycleState,
            long version) {
    }

    private record UpdateReceipt(
            long tenantId,
            String action,
            String actorId,
            String targetId,
            String idempotencyKey,
            String fingerprint) {
    }

    private record Workflow(UUID id, String key) {
    }
}
