package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.*;

/** Integrity-fenced adapter over the existing V1/V4 generic People outbox. */
public final class Hrm013TaskDispatchOutboxStore
        implements Hrm013PersistencePorts.TaskDispatchStore {
    private static final String FENCE_ACTION = "HRM013_TASK_DISPATCH_FENCE_CREATED";
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate mandatory;
    private final TransactionTemplate required;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Hrm013PersistencePorts.CallbackAuthorityStore authorities;

    public Hrm013TaskDispatchOutboxStore(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager,
            ObjectMapper mapper, Clock clock,
            Hrm013PersistencePorts.CallbackAuthorityStore authorities) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.authorities = java.util.Objects.requireNonNull(authorities, "authorities");
        mandatory = new TransactionTemplate(manager);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        required = new TransactionTemplate(manager);
        required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override public TaskDispatchFence insert(
            TaskDispatchFence fence, CallbackAuthorityRecord callbackFence) {
        TaskDispatchFence result = mandatory.execute(status -> {
            validate(fence, callbackFence);
            Hrm013TenantScope.setLocal(jdbc, fence.tenantId());
            lock(fence.tenantId(), fence.dispatchEventId());
            List<TaskDispatchFence> existing = query(
                    fence.tenantId(), fence.dispatchEventId(), true);
            if (!existing.isEmpty()) {
                if (!same(existing.getFirst(), fence)) {
                    throw new IllegalStateException("HRM-013 dispatch identity conflict");
                }
                return existing.getFirst();
            }
            int inserted = jdbc.update("""
                    INSERT INTO sys_people_outbox_events(
                        event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                        payload,correlation_id,occurred_at,next_attempt_at)
                    VALUES(:event,:tenant,'SEPARATION_TASK',:aggregate,:eventType,
                        CAST(:payload AS jsonb),:correlation,:occurred,:occurred)
                    """, parameters(fence));
            if (inserted != 1) throw new IllegalStateException(
                    "HRM-013 task dispatch insert failed");
            authorities.append(callbackFence);
            int audited = jdbc.update("""
                    INSERT INTO sys_people_audit_events(
                        audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                        target_id,outcome,correlation_id,after_snapshot,occurred_at)
                    VALUES(:audit,:tenant,'SERVICE','dwp-people-server',:action,
                        'SEPARATION_TASK_DISPATCH',:eventText,'SUCCESS',:correlation,
                        CAST(:snapshot AS jsonb),:occurred)
                    """, parameters(fence)
                    .addValue("audit", UUID.randomUUID())
                    .addValue("action", FENCE_ACTION)
                    .addValue("eventText", fence.dispatchEventId().toString())
                    .addValue("snapshot", snapshot(fence)));
            if (audited != 1) throw new IllegalStateException(
                    "HRM-013 task dispatch audit insert failed");
            return fence;
        });
        if (result == null) throw new IllegalStateException("dispatch transaction returned no result");
        return result;
    }

    @Override public Optional<TaskDispatchClaim> claimNext(long tenantId, Instant now) {
        if (tenantId < 1 || now == null) throw new IllegalArgumentException("claim selector");
        Optional<TaskDispatchClaim> result = required.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, tenantId);
            recoverExpiredFinalClaim(tenantId, now);
            List<UUID> candidates = jdbc.queryForList("""
                    SELECT event_id FROM sys_people_outbox_events
                     WHERE tenant_id=:tenant AND aggregate_type='SEPARATION_TASK'
                       AND event_type IN (
                         'SeparationTimeFinalizationTaskDispatchRequested.v1',
                         'SeparationPayrollSettlementTaskDispatchRequested.v1',
                         'SeparationIdentityAccessRevocationTaskDispatchRequested.v1')
                       AND published_at IS NULL AND dead_lettered_at IS NULL
                       AND attempt_count < 10 AND next_attempt_at <= :now
                     ORDER BY occurred_at,event_id FOR UPDATE SKIP LOCKED LIMIT 1
                    """, new MapSqlParameterSource().addValue("tenant", tenantId)
                    .addValue("now", Timestamp.from(now)), UUID.class);
            if (candidates.isEmpty()) return Optional.empty();
            UUID event = candidates.getFirst();
            Instant claimUntil = now.plusSeconds(30);
            int updated = jdbc.update("""
                    UPDATE sys_people_outbox_events
                       SET attempt_count=attempt_count+1,
                           next_attempt_at=:leaseUntil,last_error=NULL
                     WHERE tenant_id=:tenant AND event_id=:event
                       AND published_at IS NULL AND dead_lettered_at IS NULL
                       AND attempt_count < 10
                    """, new MapSqlParameterSource().addValue("tenant", tenantId)
                    .addValue("event", event)
                    .addValue("leaseUntil", Timestamp.from(claimUntil)));
            if (updated != 1) throw new IllegalStateException("dispatch claim CAS lost");
            TaskDispatchFence fence;
            try {
                fence = requireIntegrityInTransaction(event, tenantId, true);
            } catch (RuntimeException integrityFailure) {
                quarantineInTransaction(tenantId, event, "INTEGRITY_CONFLICT");
                return Optional.empty();
            }
            Integer attempt = jdbc.queryForObject("""
                    SELECT attempt_count FROM sys_people_outbox_events
                     WHERE tenant_id=:tenant AND event_id=:event
                    """, new MapSqlParameterSource().addValue("tenant", tenantId)
                    .addValue("event", event), Integer.class);
            return Optional.of(new TaskDispatchClaim(
                    fence, attempt, now, claimUntil));
        });
        return result == null ? Optional.empty() : result;
    }

    @Override public TaskDispatchFence requireIntegrity(UUID event, long tenantId) {
        try {
            TaskDispatchFence result = required.execute(status ->
                    requireIntegrityInTransaction(event, tenantId, true));
            if (result == null) throw new IllegalStateException("dispatch integrity unavailable");
            return result;
        } catch (RuntimeException integrityFailure) {
            required.executeWithoutResult(status -> {
                Hrm013TenantScope.setLocal(jdbc, tenantId);
                quarantineInTransaction(tenantId, event, "INTEGRITY_CONFLICT");
            });
            throw integrityFailure;
        }
    }

    @Override public TaskDispatchFence requireStoredIntegrity(
            UUID event, long tenantId) {
        TaskDispatchFence result = required.execute(status ->
                requireIntegrityInTransaction(event, tenantId, false));
        if (result == null) {
            throw new IllegalStateException("stored dispatch integrity unavailable");
        }
        return result;
    }

    private TaskDispatchFence requireIntegrityInTransaction(
            UUID event, long tenantId, boolean requireOpenCallbackFence) {
        Hrm013TenantScope.setLocal(jdbc, tenantId);
        lock(tenantId, event);
        List<TaskDispatchFence> rows = query(tenantId, event, true);
        if (rows.size() != 1) throw new IllegalStateException("dispatch fence missing");
        TaskDispatchFence value = rows.getFirst();
        Hrm013TaskDispatchV1.Profile profile = profile(value.eventType());
        var decoded = Hrm013TaskDispatchV1.decode(
                profile, tenantId, value.exactRequestBody());
        Hrm013TaskDispatchV1.requireProfileBinding(profile, tenantId, decoded);
        if (!decoded.dispatchEventId().equals(value.dispatchEventId())
                || !decoded.casePublicId().equals(value.casePublicId())
                || !decoded.taskPublicId().equals(value.taskPublicId())
                || !decoded.taskType().equals(value.taskType())
                || decoded.taskVersion() != value.taskVersion()
                || decoded.taskOwnerRevision() != value.expectedOwnerRevision()
                || !decoded.correlationId().equals(value.correlationId())
                || !decoded.dispatchedAt().equals(value.occurredAt())
                || !decoded.payloadSha256().equals(value.payloadSha256())
                || !Hrm013TaskDispatchV1.requestBodySha256(value.exactRequestBody())
                .equals(value.requestBodySha256())) {
            throw new IllegalStateException("dispatch outbox integrity conflict");
        }
        CallbackAuthorityKey callbackKey = new CallbackAuthorityKey(
                tenantId, profile.callbackBindingId(), profile.ownerWorkloadId(),
                decoded.casePublicId(), decoded.taskPublicId(),
                profile.callbackPurpose(), null);
        requireCallbackFence(value, profile, callbackKey, requireOpenCallbackFence);
        return value;
    }

    @Override public TaskDispatchDisposition accept(TaskDispatchAcceptance acceptance) {
        TaskDispatchDisposition result = required.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, acceptance.tenantId());
            TaskDispatchFence fence = requireIntegrityInTransaction(
                    acceptance.dispatchEventId(), acceptance.tenantId(), false);
            Hrm013TaskDispatchV1.Profile profile = profile(fence.eventType());
            Hrm013TaskDispatchV1.DispatchBody request = Hrm013TaskDispatchV1.decode(
                    profile, fence.tenantId(), fence.exactRequestBody());
            var receipt = Hrm013TaskDispatchV1.decodeAdmissionReceipt(
                    acceptance.exactReceiptBody());
            Hrm013TaskDispatchV1.requireAdmissionReceiptBinding(
                    profile, fence.tenantId(), request,
                    fence.exactRequestBody(), receipt);
            if (!receipt.receiptSha256().equals(acceptance.receiptSha256())
                    || !receipt.admittedAt().equals(acceptance.acceptedAt())) {
                throw new IllegalStateException("dispatch acceptance receipt differs");
            }
            int updated = jdbc.update("""
                    UPDATE sys_people_outbox_events SET published_at=:accepted,last_error=NULL
                     WHERE tenant_id=:tenant AND event_id=:event
                       AND published_at IS NULL AND dead_lettered_at IS NULL
                       AND attempt_count=:attempt
                       AND next_attempt_at=:claimUntil
                    """, new MapSqlParameterSource().addValue("tenant", acceptance.tenantId())
                    .addValue("event", acceptance.dispatchEventId())
                    .addValue("attempt", acceptance.expectedAttemptCount())
                    .addValue("claimUntil", Timestamp.from(
                            acceptance.expectedClaimUntil()))
                    .addValue("accepted", Timestamp.from(acceptance.acceptedAt())));
            if (updated != 1) {
                requireExactAcceptedReplay(fence, acceptance, receipt);
            } else {
                auditReceipt(fence, acceptance, receipt);
            }
            return TaskDispatchDisposition.ACCEPTED;
        });
        if (result == null) throw new IllegalStateException("dispatch acceptance unavailable");
        return result;
    }

    @Override public TaskDispatchDisposition fail(TaskDispatchFailure failure) {
        TaskDispatchDisposition result = required.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, failure.tenantId());
            lock(failure.tenantId(), failure.dispatchEventId());
            List<Integer> attempts = jdbc.queryForList("""
                    SELECT attempt_count FROM sys_people_outbox_events
                     WHERE tenant_id=:tenant AND event_id=:event
                       AND published_at IS NULL AND dead_lettered_at IS NULL
                       AND attempt_count=:attempt AND next_attempt_at=:claimUntil
                     FOR UPDATE
                    """, new MapSqlParameterSource().addValue("tenant", failure.tenantId())
                    .addValue("event", failure.dispatchEventId())
                    .addValue("attempt", failure.expectedAttemptCount())
                    .addValue("claimUntil", Timestamp.from(
                            failure.expectedClaimUntil())), Integer.class);
            if (attempts.size() != 1) {
                throw new IllegalStateException("dispatch claim lost");
            }
            int attempt = attempts.getFirst();
            TaskDispatchDisposition disposition = failure.retryable() && attempt < 10
                    ? TaskDispatchDisposition.RETRY
                    : failure.resultUnknown() ? TaskDispatchDisposition.RESULT_UNKNOWN
                    : TaskDispatchDisposition.DEAD;
            int updated = jdbc.update("""
                    UPDATE sys_people_outbox_events
                       SET next_attempt_at=CASE WHEN :retry
                               THEN CAST(:retryAt AS timestamptz) ELSE next_attempt_at END,
                           dead_lettered_at=CASE WHEN :retry THEN NULL::timestamptz
                               ELSE CAST(:now AS timestamptz) END,
                           last_error=:failure
                     WHERE tenant_id=:tenant AND event_id=:event
                       AND published_at IS NULL AND dead_lettered_at IS NULL
                       AND attempt_count=:attempt AND next_attempt_at=:claimUntil
                    """, new MapSqlParameterSource().addValue("retry", disposition == TaskDispatchDisposition.RETRY)
                    .addValue("retryAt", failure.retryAt() == null ? null : Timestamp.from(failure.retryAt()))
                    .addValue("now", Timestamp.from(clock.instant()))
                    .addValue("failure", disposition == TaskDispatchDisposition.RESULT_UNKNOWN
                            ? "RESULT_UNKNOWN" : failure.failureCode())
                    .addValue("tenant", failure.tenantId())
                    .addValue("event", failure.dispatchEventId())
                    .addValue("attempt", failure.expectedAttemptCount())
                    .addValue("claimUntil", Timestamp.from(
                            failure.expectedClaimUntil())));
            if (updated != 1) throw new IllegalStateException("dispatch failure CAS lost");
            if (disposition != TaskDispatchDisposition.RETRY) {
                auditTerminalFailure(failure, disposition);
            }
            return disposition;
        });
        if (result == null) throw new IllegalStateException("dispatch failure unavailable");
        return result;
    }

    private void requireCallbackFence(
            TaskDispatchFence value, Hrm013TaskDispatchV1.Profile profile,
            CallbackAuthorityKey callbackKey, boolean requireOpen) {
        if (requireOpen) {
            CallbackAuthorityRecord callback = authorities.latestForUpdate(callbackKey)
                    .filter(candidate -> "ADMIT".equals(candidate.decision()))
                    .orElseThrow(() -> new IllegalStateException(
                            "dispatch callback authority fence is not open"));
            if (!matchesCallbackFence(value, profile, callback)) {
                throw new IllegalStateException(
                        "dispatch callback authority integrity conflict");
            }
            return;
        }
        List<Long> frozen = jdbc.queryForList("""
                SELECT separation_callback_authority_id
                  FROM ppl_separation_callback_authorities
                 WHERE tenant_id=:tenant AND binding_id=:binding
                   AND caller_workload_id=:caller AND case_public_id=:casePublic
                   AND task_public_id=:taskPublic AND purpose_code=:purpose
                   AND approval_request_id IS NULL AND authority_revision=1
                   AND decision='ADMIT' AND callback_type='TASK_RECEIPT'
                   AND audience='dwp-people-server' AND task_type=:taskType
                   AND consumer_event_id IS NULL
                   AND expected_next_owner_revision=:ownerRevision
                   AND correlation_id=:correlation
                   AND originating_payload_sha256=:payload
                   AND tenant_grant_revision=:tenantRevision
                   AND authorization_revision=:authorizationRevision
                 FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("tenant", value.tenantId())
                .addValue("binding", profile.callbackBindingId())
                .addValue("caller", profile.ownerWorkloadId())
                .addValue("casePublic", value.casePublicId())
                .addValue("taskPublic", value.taskPublicId())
                .addValue("purpose", profile.callbackPurpose())
                .addValue("taskType", profile.taskType())
                .addValue("ownerRevision", value.expectedOwnerRevision())
                .addValue("correlation", value.correlationId())
                .addValue("payload", value.payloadSha256())
                .addValue("tenantRevision", value.callbackTenantGrantRevision())
                .addValue("authorizationRevision",
                        value.callbackAuthorizationRevision()), Long.class);
        if (frozen.size() != 1) {
            throw new IllegalStateException(
                    "dispatch frozen callback authority integrity conflict");
        }
    }

    private static boolean matchesCallbackFence(
            TaskDispatchFence value, Hrm013TaskDispatchV1.Profile profile,
            CallbackAuthorityRecord callback) {
        return "TASK_RECEIPT".equals(callback.callbackType())
                && "dwp-people-server".equals(callback.audience())
                && profile.taskType().equals(callback.taskType())
                && callback.consumerEventId() == null
                && callback.key().approvalRequestId() == null
                && callback.expectedNextOwnerRevision()
                == value.expectedOwnerRevision()
                && callback.correlationId().equals(value.correlationId())
                && callback.originatingPayloadSha256().equals(value.payloadSha256())
                && callback.tenantGrantRevision()
                == value.callbackTenantGrantRevision()
                && callback.authorizationRevision()
                == value.callbackAuthorizationRevision();
    }

    private List<TaskDispatchFence> query(long tenantId, UUID event, boolean lock) {
        return jdbc.query("""
                SELECT o.event_id,o.tenant_id,o.aggregate_type,o.aggregate_id,
                       o.event_type,o.payload::text payload,
                       o.correlation_id,o.occurred_at,a.after_snapshot::text snapshot
                  FROM sys_people_outbox_events o
                  JOIN sys_people_audit_events a
                    ON a.tenant_id=o.tenant_id AND a.target_id=o.event_id::text
                   AND a.action=:action
                 WHERE o.tenant_id=:tenant AND o.event_id=:event
                """ + (lock ? " FOR UPDATE OF o" : ""),
                new MapSqlParameterSource().addValue("tenant", tenantId)
                        .addValue("event", event).addValue("action", FENCE_ACTION),
                (row, number) -> {
                    JsonNode snapshot = readSnapshot(row.getString("snapshot"));
                    Hrm013TaskDispatchV1.Profile profile = profile(row.getString("event_type"));
                    requireSnapshot(snapshot);
                    JsonNode payload = readSnapshot(row.getString("payload"));
                    var decoded = decodeStored(profile, tenantId, payload);
                    byte[] canonical = Hrm013TaskDispatchV1.encode(profile, tenantId, decoded);
                    UUID rowEvent = row.getObject("event_id", UUID.class);
                    UUID correlation;
                    try {
                        correlation = UUID.fromString(row.getString("correlation_id"));
                    } catch (RuntimeException malformed) {
                        throw new IllegalStateException(
                                "invalid dispatch correlation identity", malformed);
                    }
                    UUID aggregate;
                    try {
                        aggregate = UUID.fromString(row.getString("aggregate_id"));
                    } catch (RuntimeException malformed) {
                        throw new IllegalStateException("invalid dispatch aggregate identity", malformed);
                    }
                    String canonicalDigest = Hrm013TaskDispatchV1.requestBodySha256(canonical);
                    if (!"SEPARATION_TASK".equals(row.getString("aggregate_type"))
                            || !rowEvent.equals(decoded.dispatchEventId())
                            || !aggregate.equals(decoded.taskPublicId())
                            || !correlation.equals(decoded.correlationId())
                            || !row.getTimestamp("occurred_at").toInstant()
                            .equals(decoded.dispatchedAt())
                            || !profile.bindingId().equals(snapshot.path("bindingId").textValue())
                            || !profile.ownerWorkloadId().equals(
                            snapshot.path("ownerWorkloadId").textValue())
                            || !profile.purpose().equals(snapshot.path("purpose").textValue())
                            || !profile.taskType().equals(snapshot.path("taskType").textValue())
                            || !canonicalDigest.equals(
                            snapshot.path("requestBodySha256").textValue())
                            || !decoded.payloadSha256().equals(
                            snapshot.path("payloadSha256").textValue())) {
                        throw new IllegalStateException("dispatch row and fence snapshot differ");
                    }
                    return new TaskDispatchFence(
                            rowEvent, tenantId,
                            decoded.casePublicId(), decoded.taskPublicId(), profile.bindingId(),
                            profile.ownerWorkloadId(), profile.purpose(), profile.taskType(),
                            profile.eventType(), decoded.taskVersion(), decoded.taskOwnerRevision(),
                            canonical, canonicalDigest,
                            decoded.payloadSha256(), correlation,
                            snapshot.path("dispatchTenantGrantRevision").longValue(),
                            snapshot.path("dispatchAuthorizationRevision").longValue(),
                            snapshot.path("callbackTenantGrantRevision").longValue(),
                            snapshot.path("callbackAuthorizationRevision").longValue(),
                            row.getTimestamp("occurred_at").toInstant());
                });
    }

    private JsonNode readSnapshot(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("invalid dispatch fence snapshot", failure);
        }
    }

    private static void requireSnapshot(JsonNode value) {
        java.util.Set<String> expected = java.util.Set.of(
                "bindingId", "ownerWorkloadId", "purpose", "taskType",
                "requestBodySha256", "payloadSha256",
                "dispatchTenantGrantRevision", "dispatchAuthorizationRevision",
                "callbackTenantGrantRevision", "callbackAuthorizationRevision");
        java.util.Set<String> actual = new java.util.HashSet<>();
        if (value == null || !value.isObject()) {
            throw new IllegalStateException("dispatch fence snapshot is not an object");
        }
        value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)
                || value.path("dispatchTenantGrantRevision").longValue() < 1
                || value.path("dispatchAuthorizationRevision").longValue() < 1
                || value.path("callbackTenantGrantRevision").longValue() < 1
                || value.path("callbackAuthorizationRevision").longValue() < 1) {
            throw new IllegalStateException("dispatch fence snapshot shape is invalid");
        }
    }

    private static Hrm013TaskDispatchV1.DispatchBody decodeStored(
            Hrm013TaskDispatchV1.Profile profile, long tenantId, JsonNode node) {
        try {
            Hrm013TaskDispatchV1.DispatchBody body;
            if (profile.equals(Hrm013TaskDispatchV1.IDENTITY)) {
                body = new Hrm013TaskDispatchV1.IdentityTaskDispatch(
                        text(node, "schemaName"), exactInt(node, "schemaVersion"),
                        uuid(node, "dispatchEventId"), uuid(node, "casePublicId"),
                        uuid(node, "taskPublicId"), text(node, "taskType"),
                        positive(node, "taskOwnerRevision"),
                        positive(node, "taskVersion"), instant(node, "dispatchedAt"),
                        text(node, "payloadSha256"), uuid(node, "correlationId"),
                        uuid(node, "personPublicId"), text(node, "separationType"),
                        LocalDate.parse(text(node, "effectiveDate")));
            } else {
                body = new Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch(
                        text(node, "schemaName"), exactInt(node, "schemaVersion"),
                        uuid(node, "dispatchEventId"), uuid(node, "casePublicId"),
                        uuid(node, "taskPublicId"), text(node, "taskType"),
                        positive(node, "taskOwnerRevision"),
                        positive(node, "taskVersion"), instant(node, "dispatchedAt"),
                        text(node, "payloadSha256"), uuid(node, "correlationId"),
                        uuid(node, "workerPublicId"),
                        uuid(node, "relationshipPublicId"),
                        text(node, "separationType"),
                        LocalDate.parse(text(node, "effectiveDate")),
                        LocalDate.parse(text(node, "lastWorkingDate")));
            }
            Hrm013TaskDispatchV1.requireProfileBinding(profile, tenantId, body);
            return body;
        } catch (RuntimeException malformed) {
            throw new IllegalStateException("invalid stored dispatch payload", malformed);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value.textValue();
    }

    private static UUID uuid(JsonNode node, String field) {
        UUID value = UUID.fromString(text(node, field));
        if (!value.toString().equals(text(node, field))) throw new IllegalArgumentException(field);
        return value;
    }

    private static long positive(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < 1) throw new IllegalArgumentException(field);
        return value.longValue();
    }

    private static int exactInt(JsonNode node, String field) {
        long value = positive(node, field);
        if (value > Integer.MAX_VALUE) throw new IllegalArgumentException(field);
        return (int) value;
    }

    private static Instant instant(JsonNode node, String field) {
        Instant value = Instant.parse(text(node, field));
        if (!value.toString().equals(text(node, field))) throw new IllegalArgumentException(field);
        return value;
    }

    private void quarantineInTransaction(long tenantId, UUID event, String code) {
        var parameters = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("event", event).addValue("now", Timestamp.from(clock.instant()))
                .addValue("code", code);
        int changed = jdbc.update("""
                UPDATE sys_people_outbox_events
                   SET dead_lettered_at=COALESCE(dead_lettered_at,:now),
                       last_error=:code
                 WHERE tenant_id=:tenant AND event_id=:event AND published_at IS NULL
                """, parameters);
        if (changed == 1) {
            jdbc.update("""
                    INSERT INTO sys_people_audit_events(
                        audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                        target_id,outcome,correlation_id,after_snapshot,occurred_at)
                    SELECT :audit,:tenant,'SERVICE','dwp-people-server',
                           'HRM013_TASK_DISPATCH_QUARANTINED','SEPARATION_TASK_DISPATCH',
                           event_id::text,'DENIED',correlation_id,
                           jsonb_build_object('failureCode',:code),:now
                      FROM sys_people_outbox_events
                     WHERE tenant_id=:tenant AND event_id=:event
                    """, parameters.addValue("audit", UUID.randomUUID()));
        }
    }

    private void lock(long tenantId, UUID event) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:value,0))",
                new MapSqlParameterSource().addValue("value",
                        "hrm013-dispatch\n" + tenantId + "\n" + event), row -> null);
    }

    private MapSqlParameterSource parameters(TaskDispatchFence value) {
        return new MapSqlParameterSource().addValue("event", value.dispatchEventId())
                .addValue("tenant", value.tenantId())
                .addValue("aggregate", value.taskPublicId().toString())
                .addValue("eventType", value.eventType())
                .addValue("payload", new String(value.exactRequestBody(), java.nio.charset.StandardCharsets.UTF_8))
                .addValue("correlation", value.correlationId().toString())
                .addValue("occurred", Timestamp.from(value.occurredAt()));
    }

    private String snapshot(TaskDispatchFence value) {
        try {
            return mapper.writeValueAsString(Map.ofEntries(
                    Map.entry("bindingId", value.bindingId()),
                    Map.entry("ownerWorkloadId", value.ownerWorkloadId()),
                    Map.entry("purpose", value.purpose()),
                    Map.entry("taskType", value.taskType()),
                    Map.entry("requestBodySha256", value.requestBodySha256()),
                    Map.entry("payloadSha256", value.payloadSha256()),
                    Map.entry("dispatchTenantGrantRevision", value.dispatchTenantGrantRevision()),
                    Map.entry("dispatchAuthorizationRevision", value.dispatchAuthorizationRevision()),
                    Map.entry("callbackTenantGrantRevision", value.callbackTenantGrantRevision()),
                    Map.entry("callbackAuthorizationRevision", value.callbackAuthorizationRevision())));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("dispatch fence serialization failed", failure);
        }
    }

    private void auditReceipt(
            TaskDispatchFence fence, TaskDispatchAcceptance acceptance,
            Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1 receipt) {
        try {
            String snapshot = mapper.writeValueAsString(Map.ofEntries(
                    Map.entry("dispatchEventId", receipt.dispatchEventId().toString()),
                    Map.entry("taskPublicId", receipt.taskPublicId().toString()),
                    Map.entry("payloadSha256", receipt.payloadSha256()),
                    Map.entry("requestBodySha256", fence.requestBodySha256()),
                    Map.entry("responseBodySha256", acceptance.responseBodySha256()),
                    Map.entry("receiptSha256", acceptance.receiptSha256()),
                    Map.entry("admittedAt", receipt.admittedAt().toString()),
                    Map.entry("correlationId", receipt.correlationId().toString())));
            jdbc.update("""
                    INSERT INTO sys_people_audit_events(
                        audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                        target_id,outcome,correlation_id,after_snapshot,occurred_at)
                    VALUES(:audit,:tenant,'SERVICE','dwp-people-server',
                        'HRM013_TASK_DISPATCH_ADMITTED','SEPARATION_TASK_DISPATCH',
                        :target,'SUCCESS',:correlation,CAST(:snapshot AS jsonb),:occurred)
                    """, new MapSqlParameterSource().addValue("audit", UUID.randomUUID())
                    .addValue("tenant", fence.tenantId())
                    .addValue("target", fence.dispatchEventId().toString())
                    .addValue("correlation", fence.correlationId().toString())
                    .addValue("snapshot", snapshot)
                    .addValue("occurred", Timestamp.from(acceptance.acceptedAt())));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("dispatch receipt audit failed", failure);
        }
    }

    private void requireExactAcceptedReplay(
            TaskDispatchFence fence, TaskDispatchAcceptance acceptance,
            Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1 receipt) {
        List<JsonNode> rows = jdbc.query("""
                SELECT after_snapshot FROM sys_people_audit_events
                 WHERE tenant_id=:tenant
                   AND action='HRM013_TASK_DISPATCH_ADMITTED'
                   AND target_id=:target
                 ORDER BY occurred_at DESC,audit_event_id DESC LIMIT 1 FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenant", fence.tenantId())
                .addValue("target", fence.dispatchEventId().toString()),
                (row, ignored) -> readSnapshot(row.getString(1)));
        if (rows.size() != 1) throw new IllegalStateException("dispatch acceptance CAS lost");
        JsonNode stored = rows.getFirst();
        if (!acceptance.responseBodySha256().equals(
                stored.path("responseBodySha256").textValue())
                || !acceptance.receiptSha256().equals(
                stored.path("receiptSha256").textValue())
                || !receipt.dispatchEventId().toString().equals(
                stored.path("dispatchEventId").textValue())
                || !receipt.taskPublicId().toString().equals(
                stored.path("taskPublicId").textValue())
                || !receipt.payloadSha256().equals(
                stored.path("payloadSha256").textValue())
                || !receipt.requestBodySha256().equals(
                stored.path("requestBodySha256").textValue())
                || !receipt.admittedAt().toString().equals(
                stored.path("admittedAt").textValue())
                || !receipt.correlationId().toString().equals(
                stored.path("correlationId").textValue())) {
            throw new IllegalStateException("conflicting dispatch admission replay");
        }
    }

    private void recoverExpiredFinalClaim(long tenantId, Instant now) {
        List<UUID> expired = jdbc.queryForList("""
                SELECT event_id FROM sys_people_outbox_events
                 WHERE tenant_id=:tenant AND aggregate_type='SEPARATION_TASK'
                   AND published_at IS NULL AND dead_lettered_at IS NULL
                   AND attempt_count=10 AND next_attempt_at <= :now
                 ORDER BY next_attempt_at,event_id FOR UPDATE SKIP LOCKED LIMIT 1
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("now", Timestamp.from(now)), UUID.class);
        if (expired.isEmpty()) return;
        UUID event = expired.getFirst();
        int changed = jdbc.update("""
                UPDATE sys_people_outbox_events
                   SET dead_lettered_at=:now,last_error='RESULT_UNKNOWN'
                 WHERE tenant_id=:tenant AND event_id=:event
                   AND published_at IS NULL AND dead_lettered_at IS NULL
                   AND attempt_count=10 AND next_attempt_at <= :now
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("event", event).addValue("now", Timestamp.from(now)));
        if (changed == 1) {
            auditTerminal(tenantId, event, "RESULT_UNKNOWN", "FINAL_CLAIM_EXPIRED", now);
        }
    }

    private void auditTerminalFailure(
            TaskDispatchFailure failure, TaskDispatchDisposition disposition) {
        auditTerminal(failure.tenantId(), failure.dispatchEventId(),
                disposition.name(), failure.failureCode(), clock.instant());
    }

    private void auditTerminal(
            long tenantId, UUID event, String outcome, String code, Instant now) {
        jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                SELECT :audit,:tenant,'SERVICE','dwp-people-server',
                       'HRM013_TASK_DISPATCH_TERMINATED','SEPARATION_TASK_DISPATCH',
                       event_id::text,'FAILED',correlation_id,
                       jsonb_build_object('disposition',:outcome,
                                          'failureCode',:code),:now
                  FROM sys_people_outbox_events
                 WHERE tenant_id=:tenant AND event_id=:event
                """, new MapSqlParameterSource().addValue("audit", UUID.randomUUID())
                .addValue("tenant", tenantId).addValue("event", event)
                .addValue("outcome", outcome).addValue("code", code)
                .addValue("now", Timestamp.from(now)));
    }

    private static void validate(TaskDispatchFence fence, CallbackAuthorityRecord callback) {
        Hrm013TaskDispatchV1.Profile profile = profile(fence.eventType());
        var decoded = Hrm013TaskDispatchV1.decode(profile, fence.tenantId(), fence.exactRequestBody());
        if (!profile.bindingId().equals(fence.bindingId())
                || !profile.ownerWorkloadId().equals(fence.ownerWorkloadId())
                || !profile.purpose().equals(fence.purpose())
                || !profile.taskType().equals(fence.taskType())
                || !decoded.dispatchEventId().equals(fence.dispatchEventId())
                || !Arrays.equals(fence.exactRequestBody(),
                Hrm013TaskDispatchV1.encode(profile, fence.tenantId(), decoded))
                || !decoded.casePublicId().equals(fence.casePublicId())
                || !decoded.taskPublicId().equals(fence.taskPublicId())
                || decoded.taskVersion() != fence.taskVersion()
                || decoded.taskOwnerRevision() != fence.expectedOwnerRevision()
                || !decoded.correlationId().equals(fence.correlationId())
                || !decoded.dispatchedAt().equals(fence.occurredAt())
                || !callback.key().bindingId().equals(profile.callbackBindingId())
                || !callback.key().callerWorkloadId().equals(profile.ownerWorkloadId())
                || !callback.key().purposeCode().equals(profile.callbackPurpose())
                || callback.tenantGrantRevision() != fence.callbackTenantGrantRevision()
                || callback.authorizationRevision() != fence.callbackAuthorizationRevision()
                || !callback.originatingPayloadSha256().equals(fence.payloadSha256())) {
            throw new IllegalArgumentException("dispatch and callback fences do not match");
        }
    }

    private static boolean same(TaskDispatchFence a, TaskDispatchFence b) {
        return a.dispatchEventId().equals(b.dispatchEventId())
                && a.tenantId() == b.tenantId()
                && a.requestBodySha256().equals(b.requestBodySha256())
                && a.payloadSha256().equals(b.payloadSha256())
                && a.bindingId().equals(b.bindingId())
                && a.dispatchTenantGrantRevision() == b.dispatchTenantGrantRevision()
                && a.dispatchAuthorizationRevision() == b.dispatchAuthorizationRevision()
                && a.callbackTenantGrantRevision() == b.callbackTenantGrantRevision()
                && a.callbackAuthorizationRevision() == b.callbackAuthorizationRevision();
    }

    static Hrm013TaskDispatchV1.Profile profile(String eventType) {
        return Hrm013TaskDispatchV1.PROFILES.stream()
                .filter(value -> value.eventType().equals(eventType))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown dispatch event"));
    }
}
