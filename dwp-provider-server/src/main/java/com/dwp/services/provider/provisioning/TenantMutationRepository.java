package com.dwp.services.provider.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.provisioning.ProviderTenantCommand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Repository
public class TenantMutationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TenantMutationCompensationPlanner compensationPlanner;

    public TenantMutationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TenantMutationCompensationPlanner compensationPlanner) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.compensationPlanner = compensationPlanner;
    }

    @Transactional
    public Mutation create(MutationRequest request) {
        TenantRow tenant = lockTenant(request.providerTenantId());
        Mutation replay = byIdempotencyKey(request.idempotencyKey());
        if (replay != null) {
            if (!replay.providerTenantId().equals(request.providerTenantId())
                    || !replay.mutationType().equals(request.mutationType())
                    || replay.expectedTenantVersion() != request.expectedTenantVersion()
                    || !replay.payloadSha256().equals(request.payloadSha256())) {
                throw conflict("The tenant mutation idempotency key was already used for a different payload.");
            }
            return replay;
        }
        if (tenant.version() != request.expectedTenantVersion()) {
            throw conflict("The tenant changed before the durable mutation could be created.");
        }

        Integer active = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_tenant_mutations
                 WHERE provider_tenant_id = ?
                   AND lifecycle_state IN (
                       'PENDING', 'EXECUTING', 'RETRY_WAIT',
                       'COMPENSATING', 'RECONCILIATION_REQUIRED')
                """, Integer.class, request.providerTenantId());
        if (active != null && active > 0) {
            throw conflict("Another tenant mutation is already active.");
        }

        Long lastRevision = jdbc.queryForObject("""
                SELECT COALESCE(MAX(target_revision), 0)
                  FROM prv_tenant_mutations
                 WHERE provider_tenant_id = ?
                """, Long.class, request.providerTenantId());
        long targetRevision = (lastRevision == null ? 0L : lastRevision) + 1L;
        UUID mutationId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO prv_tenant_mutations (
                        mutation_id, provider_tenant_id, mutation_type, idempotency_key,
                        payload_sha256, expected_tenant_version, target_revision,
                        previous_payload, desired_payload, lifecycle_state,
                        requested_by, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                            'PENDING', ?, ?)
                    """, mutationId, request.providerTenantId(), request.mutationType(),
                    request.idempotencyKey(), request.payloadSha256(), request.expectedTenantVersion(),
                    targetRevision, json(request.previousPayload()), json(request.desiredPayload()),
                    request.requestedBy(), request.correlationId());
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A conflicting tenant mutation was created concurrently.",
                    exception);
        }

        int order = 1;
        Map<String, Long> serviceRevisions = new HashMap<>();
        for (CommandSpec spec : request.commands()) {
            String cursorKey = spec.targetService() + ':' + spec.commandType();
            long expectedRevision = serviceRevisions.computeIfAbsent(
                    cursorKey,
                    ignored -> currentAppliedServiceRevision(
                            request.providerTenantId(), spec.targetService(), spec.commandType()));
            UUID commandId = UUID.randomUUID();
            String payloadHash = ProviderTenantCommand.payloadSha256(objectMapper, spec.payload());
            jdbc.update("""
                    INSERT INTO prv_tenant_command_outbox (
                        command_id, mutation_id, command_order, target_service, command_type,
                        expected_revision, target_revision, payload_sha256, payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """, commandId, mutationId, order++, spec.targetService(), spec.commandType(),
                    expectedRevision, expectedRevision + 1L, payloadHash, json(spec.payload()));
            serviceRevisions.put(cursorKey, expectedRevision + 1L);
        }
        return Objects.requireNonNull(byId(mutationId));
    }

    @Transactional
    public int releaseExpiredLeases() {
        return jdbc.update("""
                UPDATE prv_tenant_command_outbox command
                   SET lifecycle_state = CASE
                           WHEN compensation THEN 'COMPENSATION_PENDING'
                           ELSE 'RETRY_WAIT'
                       END,
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       next_attempt_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP,
                       last_error_code = 'LEASE_EXPIRED',
                       last_error_message = 'Command lease expired before acknowledgement.'
                 WHERE lifecycle_state = 'LEASED'
                   AND lease_expires_at < CURRENT_TIMESTAMP
                """);
    }

    @Transactional
    public CommandLease claimNext(
            UUID mutationId,
            String workerId,
            Duration leaseDuration) {
        UUID leaseToken = UUID.randomUUID();
        List<CommandLease> claimed = jdbc.query("""
                WITH candidate AS (
                    SELECT command.command_id, mutation.provider_tenant_id
                      FROM prv_tenant_command_outbox command
                      JOIN prv_tenant_mutations mutation ON mutation.mutation_id = command.mutation_id
                     WHERE (?::uuid IS NULL OR mutation.mutation_id = ?::uuid)
                       AND mutation.lifecycle_state IN (
                           'PENDING', 'EXECUTING', 'RETRY_WAIT', 'COMPENSATING')
                       AND command.lifecycle_state IN (
                           'PENDING', 'RETRY_WAIT', 'COMPENSATION_PENDING')
                       AND command.next_attempt_at <= CURRENT_TIMESTAMP
                       AND NOT EXISTS (
                           SELECT 1
                             FROM prv_tenant_command_outbox predecessor
                            WHERE predecessor.mutation_id = command.mutation_id
                              AND predecessor.command_order < command.command_order
                              AND predecessor.lifecycle_state IN (
                                  'PENDING', 'LEASED', 'RETRY_WAIT', 'COMPENSATION_PENDING'))
                     ORDER BY mutation.created_at, command.command_order
                     FOR UPDATE OF command SKIP LOCKED
                     LIMIT 1
                )
                UPDATE prv_tenant_command_outbox command
                   SET lifecycle_state = 'LEASED', lease_owner = ?, lease_token = ?,
                       lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                       attempt_count = command.attempt_count + 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM candidate
                 WHERE command.command_id = candidate.command_id
                RETURNING command.command_id, command.mutation_id,
                          candidate.provider_tenant_id, command.target_service,
                          command.command_type, command.expected_revision,
                          command.target_revision, command.payload_sha256,
                          command.payload, command.attempt_count,
                          command.compensation, command.lease_token
                """, this::mapLease,
                mutationId, mutationId, workerId, leaseToken, leaseDuration.toMillis());
        if (!claimed.isEmpty()) {
            CommandLease command = claimed.get(0);
            jdbc.update("""
                    UPDATE prv_tenant_mutations
                       SET lifecycle_state = CASE
                               WHEN lifecycle_state = 'COMPENSATING' THEN 'COMPENSATING'
                               ELSE 'EXECUTING'
                           END,
                           started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                           updated_at = CURRENT_TIMESTAMP
                     WHERE mutation_id = ?
                    """, command.mutationId());
            return command;
        }
        return null;
    }

    @Transactional
    public void markApplied(
            CommandLease command,
            ProviderTenantCommand.Receipt receipt) {
        int updated = jdbc.update("""
                UPDATE prv_tenant_command_outbox
                   SET lifecycle_state = CASE WHEN compensation THEN 'COMPENSATED' ELSE 'APPLIED' END,
                       response_payload = CAST(? AS jsonb), applied_at = ?,
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       last_error_code = NULL, last_error_message = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE command_id = ? AND lifecycle_state = 'LEASED' AND lease_token = ?
                """, json(objectMapper.valueToTree(receipt)),
                java.sql.Timestamp.from(receipt.appliedAt()),
                command.commandId(), command.leaseToken());
        if (updated != 1) {
            throw conflict("The tenant command lease was lost before acknowledgement.");
        }
    }

    @Transactional
    public FailureDisposition markFailed(
            CommandLease command,
            int maximumAttempts,
            boolean permanent,
            String errorCode,
            String message) {
        CommandState current = lockCommand(command.commandId());
        if (!"LEASED".equals(current.lifecycleState())
                || !command.leaseToken().equals(current.leaseToken())) {
            return FailureDisposition.LOST_LEASE;
        }

        if (!permanent && current.attemptCount() < maximumAttempts) {
            Duration backoff = retryBackoff(current.attemptCount(), command.commandId());
            jdbc.update("""
                    UPDATE prv_tenant_command_outbox
                       SET lifecycle_state = CASE
                               WHEN compensation THEN 'COMPENSATION_PENDING'
                               ELSE 'RETRY_WAIT'
                           END,
                           next_attempt_at = ?, lease_owner = NULL, lease_token = NULL,
                           lease_expires_at = NULL, last_error_code = ?, last_error_message = ?,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE command_id = ?
                    """, java.sql.Timestamp.from(Instant.now().plus(backoff)),
                    bounded(errorCode, 80), bounded(message, 500),
                    command.commandId());
            jdbc.update("""
                    UPDATE prv_tenant_mutations
                       SET lifecycle_state = CASE
                               WHEN lifecycle_state = 'COMPENSATING' THEN 'COMPENSATING'
                               ELSE 'RETRY_WAIT'
                           END,
                           failure_code = ?, failure_message = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE mutation_id = ?
                    """, bounded(errorCode, 80), bounded(message, 500), command.mutationId());
            return FailureDisposition.RETRY_SCHEDULED;
        }

        jdbc.update("""
                UPDATE prv_tenant_command_outbox
                   SET lifecycle_state = 'RECONCILIATION_REQUIRED',
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       last_error_code = ?, last_error_message = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE command_id = ?
                """, bounded(errorCode, 80), bounded(message, 500), command.commandId());

        Mutation mutation = Objects.requireNonNull(byIdForUpdate(command.mutationId()));
        if (!command.compensation() && compensationPlanner.scheduleSafe(mutation)) {
            jdbc.update("""
                    UPDATE prv_tenant_mutations
                       SET lifecycle_state = 'COMPENSATING', failure_code = ?, failure_message = ?,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE mutation_id = ?
                    """, bounded(errorCode, 80), bounded(message, 500), mutation.mutationId());
            return FailureDisposition.COMPENSATION_SCHEDULED;
        }
        markReconciliation(mutation, errorCode, message);
        return FailureDisposition.RECONCILIATION_REQUIRED;
    }

    @Transactional
    public Completion completeIfReady(UUID mutationId) {
        Mutation mutation = byIdForUpdate(mutationId);
        if (mutation == null) return Completion.NOT_READY;
        if ("SUCCEEDED".equals(mutation.lifecycleState())) return Completion.SUCCEEDED;
        if ("COMPENSATED".equals(mutation.lifecycleState())) return Completion.COMPENSATED;
        if ("RECONCILIATION_REQUIRED".equals(mutation.lifecycleState())) {
            return Completion.RECONCILIATION_REQUIRED;
        }
        Integer unfinished = jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_tenant_command_outbox
                 WHERE mutation_id = ?
                   AND lifecycle_state IN ('PENDING', 'LEASED', 'RETRY_WAIT', 'COMPENSATION_PENDING')
                """, Integer.class, mutationId);
        if (unfinished != null && unfinished > 0) return Completion.NOT_READY;

        if ("COMPENSATING".equals(mutation.lifecycleState())) {
            Integer failed = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM prv_tenant_command_outbox
                     WHERE mutation_id = ? AND compensation
                       AND lifecycle_state = 'RECONCILIATION_REQUIRED'
                    """, Integer.class, mutationId);
            if (failed != null && failed > 0) {
                markReconciliation(mutation, "COMPENSATION_FAILED",
                        "A safe compensation command could not be confirmed.");
                return Completion.RECONCILIATION_REQUIRED;
            }
            jdbc.update("""
                    UPDATE prv_tenant_mutations
                       SET lifecycle_state = 'COMPENSATED', completed_at = CURRENT_TIMESTAMP,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE mutation_id = ?
                    """, mutationId);
            audit(mutation, "SUCCESS", "provider.tenant-mutation.compensated",
                    objectMapper.createObjectNode()
                            .put("mutationId", mutationId.toString())
                            .put("payloadSha256", mutation.payloadSha256()));
            return Completion.COMPENSATED;
        }

        Integer nonApplied = jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_tenant_command_outbox
                 WHERE mutation_id = ? AND NOT compensation AND lifecycle_state <> 'APPLIED'
                """, Integer.class, mutationId);
        if (nonApplied != null && nonApplied > 0) return Completion.NOT_READY;
        return finalizeMutation(mutation);
    }

    @Transactional
    public Completion completeNextReady() {
        UUID mutationId = jdbc.query("""
                SELECT mutation.mutation_id
                  FROM prv_tenant_mutations mutation
                 WHERE mutation.lifecycle_state IN (
                     'PENDING', 'EXECUTING', 'RETRY_WAIT', 'COMPENSATING')
                   AND NOT EXISTS (
                       SELECT 1 FROM prv_tenant_command_outbox command
                        WHERE command.mutation_id = mutation.mutation_id
                          AND command.lifecycle_state IN (
                              'PENDING', 'LEASED', 'RETRY_WAIT', 'COMPENSATION_PENDING'))
                 ORDER BY mutation.created_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT 1
                """, (result, ignored) -> result.getObject("mutation_id", UUID.class))
                .stream().findFirst().orElse(null);
        return mutationId == null ? Completion.NOT_READY : completeIfReady(mutationId);
    }

    public Mutation byId(UUID mutationId) {
        return jdbc.query("""
                SELECT mutation_id, provider_tenant_id, mutation_type, idempotency_key,
                       payload_sha256, expected_tenant_version, target_revision,
                       previous_payload, desired_payload, lifecycle_state,
                       requested_by, correlation_id
                  FROM prv_tenant_mutations WHERE mutation_id = ?
                """, this::mapMutation, mutationId).stream().findFirst().orElse(null);
    }

    public List<String> activeEntitlementKeys(UUID tenantId) {
        return jdbc.queryForList("""
                SELECT entitlement.entitlement_key
                  FROM prv_tenant_entitlements assignment
                  JOIN prv_entitlement_catalog entitlement
                    ON entitlement.entitlement_id = assignment.entitlement_id
                 WHERE assignment.provider_tenant_id = ?
                   AND assignment.lifecycle_state = 'ACTIVE'
                 ORDER BY entitlement.entitlement_key
                """, String.class, tenantId);
    }

    private Mutation byIdempotencyKey(String key) {
        return jdbc.query("""
                SELECT mutation_id, provider_tenant_id, mutation_type, idempotency_key,
                       payload_sha256, expected_tenant_version, target_revision,
                       previous_payload, desired_payload, lifecycle_state,
                       requested_by, correlation_id
                  FROM prv_tenant_mutations WHERE idempotency_key = ?
                """, this::mapMutation, key).stream().findFirst().orElse(null);
    }

    private Mutation byIdForUpdate(UUID mutationId) {
        return jdbc.query("""
                SELECT mutation_id, provider_tenant_id, mutation_type, idempotency_key,
                       payload_sha256, expected_tenant_version, target_revision,
                       previous_payload, desired_payload, lifecycle_state,
                       requested_by, correlation_id
                  FROM prv_tenant_mutations WHERE mutation_id = ? FOR UPDATE
                """, this::mapMutation, mutationId).stream().findFirst().orElse(null);
    }

    private Completion finalizeMutation(Mutation mutation) {
        int tenantUpdated;
        if ("LIFECYCLE".equals(mutation.mutationType())) {
            String state = mutation.desiredPayload().path("lifecycleState").asText();
            tenantUpdated = jdbc.update("""
                    UPDATE prv_tenants
                       SET lifecycle_state = ?, version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE provider_tenant_id = ? AND version = ?
                    """, state, mutation.requestedBy(), mutation.providerTenantId(),
                    mutation.expectedTenantVersion());
        } else {
            tenantUpdated = jdbc.update("""
                    UPDATE prv_tenants
                       SET entitlement_revision = entitlement_revision + 1,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                     WHERE provider_tenant_id = ? AND version = ?
                    """, mutation.requestedBy(), mutation.providerTenantId(),
                    mutation.expectedTenantVersion());
            if (tenantUpdated == 1) replaceEntitlements(mutation);
        }
        if (tenantUpdated != 1) {
            markReconciliation(mutation, "PROVIDER_REVISION_CONFLICT",
                    "Downstream commands applied but the provider tenant revision changed.");
            return Completion.RECONCILIATION_REQUIRED;
        }
        jdbc.update("""
                UPDATE prv_tenant_mutations
                   SET lifecycle_state = 'SUCCEEDED', failure_code = NULL, failure_message = NULL,
                       completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                 WHERE mutation_id = ?
                """, mutation.mutationId());
        String action = "LIFECYCLE".equals(mutation.mutationType())
                ? "provider.tenant.lifecycle-changed"
                : "provider.tenant-entitlements.replaced";
        audit(mutation, "SUCCESS", action, mutation.desiredPayload());
        return Completion.SUCCEEDED;
    }

    private void replaceEntitlements(Mutation mutation) {
        List<String> keys = strings(mutation.desiredPayload().path("entitlementKeys"));
        jdbc.update("""
                UPDATE prv_tenant_entitlements
                   SET lifecycle_state = 'RETIRED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE provider_tenant_id = ? AND lifecycle_state <> 'RETIRED'
                """, mutation.requestedBy(), mutation.providerTenantId());
        for (String key : keys) {
            int updated = jdbc.update("""
                    UPDATE prv_tenant_entitlements assignment
                       SET lifecycle_state = 'ACTIVE', version = version + 1,
                           updated_at = CURRENT_TIMESTAMP, updated_by = ?
                      FROM prv_entitlement_catalog entitlement
                     WHERE assignment.provider_tenant_id = ?
                       AND assignment.entitlement_id = entitlement.entitlement_id
                       AND entitlement.entitlement_key = ?
                    """, mutation.requestedBy(), mutation.providerTenantId(), key);
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO prv_tenant_entitlements (
                            provider_tenant_id, entitlement_id, lifecycle_state,
                            configuration, created_by, updated_by)
                        SELECT ?, entitlement_id, 'ACTIVE', '{}'::jsonb, ?, ?
                          FROM prv_entitlement_catalog
                         WHERE entitlement_key = ? AND lifecycle_state = 'ACTIVE'
                        ON CONFLICT (provider_tenant_id, entitlement_id) DO UPDATE
                        SET lifecycle_state = 'ACTIVE', version = prv_tenant_entitlements.version + 1,
                            updated_at = CURRENT_TIMESTAMP, updated_by = EXCLUDED.updated_by
                        """, mutation.providerTenantId(), mutation.requestedBy(),
                        mutation.requestedBy(), key);
            }
        }
    }

    private void markReconciliation(Mutation mutation, String code, String message) {
        jdbc.update("""
                UPDATE prv_tenant_mutations
                   SET lifecycle_state = 'RECONCILIATION_REQUIRED', failure_code = ?,
                       failure_message = ?, completed_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE mutation_id = ?
                """, bounded(code, 80), bounded(message, 500), mutation.mutationId());
        ObjectNode snapshot = objectMapper.createObjectNode()
                .put("mutationId", mutation.mutationId().toString())
                .put("mutationType", mutation.mutationType())
                .put("payloadSha256", mutation.payloadSha256())
                .put("failureCode", bounded(code, 80))
                .put("failureMessage", bounded(message, 500));
        audit(mutation, "FAILED", "provider.tenant-mutation.reconciliation-required", snapshot);
    }

    private void audit(Mutation mutation, String outcome, String action, JsonNode snapshot) {
        jdbc.update("""
                INSERT INTO prv_audit_events (
                    audit_event_id, actor_id, action, target_type, target_id,
                    outcome, correlation_id, redacted_snapshot, provider_operator_id,
                    provider_tenant_id, organization_id, event_category)
                SELECT gen_random_uuid(), operator.auth_user_id, ?, 'PROVIDER_TENANT', ?,
                       ?, ?, CAST(? AS jsonb), operator.provider_operator_id,
                       tenant.provider_tenant_id, tenant.organization_id, 'TENANT_LIFECYCLE'
                  FROM prv_operators operator
                  JOIN prv_tenants tenant ON tenant.provider_tenant_id = ?
                 WHERE operator.provider_operator_id = ?
                """, action, mutation.providerTenantId().toString(), outcome,
                mutation.correlationId(), json(snapshot), mutation.providerTenantId(),
                mutation.requestedBy());
    }

    private long currentAppliedServiceRevision(UUID tenantId, String target, String type) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(command.target_revision), 0)
                  FROM prv_tenant_command_outbox command
                  JOIN prv_tenant_mutations mutation ON mutation.mutation_id = command.mutation_id
                 WHERE mutation.provider_tenant_id = ?
                   AND command.target_service = ? AND command.command_type = ?
                   AND command.lifecycle_state IN ('APPLIED', 'COMPENSATED')
                """, Long.class, tenantId, target, type);
        return value == null ? 0L : value;
    }

    private TenantRow lockTenant(UUID tenantId) {
        return jdbc.query("""
                SELECT provider_tenant_id, version FROM prv_tenants
                 WHERE provider_tenant_id = ? FOR UPDATE
                """, (result, ignored) -> new TenantRow(
                result.getObject("provider_tenant_id", UUID.class), result.getLong("version")), tenantId)
                .stream().findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private CommandState lockCommand(UUID commandId) {
        return jdbc.query("""
                SELECT lifecycle_state, attempt_count, lease_token
                  FROM prv_tenant_command_outbox WHERE command_id = ? FOR UPDATE
                """, (result, ignored) -> new CommandState(
                result.getString("lifecycle_state"), result.getInt("attempt_count"),
                result.getObject("lease_token", UUID.class)), commandId)
                .stream().findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private Mutation mapMutation(ResultSet result, int ignored) throws SQLException {
        return new Mutation(
                result.getObject("mutation_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("mutation_type"), result.getString("idempotency_key"),
                result.getString("payload_sha256"), result.getLong("expected_tenant_version"),
                result.getLong("target_revision"), readJson(result, "previous_payload"),
                readJson(result, "desired_payload"), result.getString("lifecycle_state"),
                result.getLong("requested_by"), result.getString("correlation_id"));
    }

    private CommandLease mapLease(ResultSet result, int ignored) throws SQLException {
        return new CommandLease(
                result.getObject("command_id", UUID.class),
                result.getObject("mutation_id", UUID.class),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("target_service"), result.getString("command_type"),
                result.getLong("expected_revision"), result.getLong("target_revision"),
                result.getString("payload_sha256"), readJson(result, "payload"),
                result.getInt("attempt_count"), result.getBoolean("compensation"),
                result.getObject("lease_token", UUID.class));
    }

    private JsonNode readJson(ResultSet result, String column) throws SQLException {
        try {
            return objectMapper.readTree(result.getString(column));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Could not read durable tenant mutation JSON.", exception);
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize durable tenant mutation JSON.", exception);
        }
    }

    private List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private Duration retryBackoff(int attempt, UUID commandId) {
        long exponential = Math.min(300_000L, 500L * (1L << Math.min(attempt - 1, 9)));
        long jitter = Math.floorMod(commandId.getLeastSignificantBits() ^ attempt, 251L);
        return Duration.ofMillis(exponential + jitter);
    }

    private String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) return "UNSPECIFIED";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    public record MutationRequest(
            UUID providerTenantId,
            String mutationType,
            String idempotencyKey,
            String payloadSha256,
            long expectedTenantVersion,
            JsonNode previousPayload,
            JsonNode desiredPayload,
            long requestedBy,
            String correlationId,
            List<CommandSpec> commands) {
    }

    public record CommandSpec(String targetService, String commandType, JsonNode payload) {
    }

    public record Mutation(
            UUID mutationId,
            UUID providerTenantId,
            String mutationType,
            String idempotencyKey,
            String payloadSha256,
            long expectedTenantVersion,
            long targetRevision,
            JsonNode previousPayload,
            JsonNode desiredPayload,
            String lifecycleState,
            long requestedBy,
            String correlationId) {
    }

    public record CommandLease(
            UUID commandId,
            UUID mutationId,
            UUID providerTenantId,
            String targetService,
            String commandType,
            long expectedRevision,
            long targetRevision,
            String payloadSha256,
            JsonNode payload,
            int attemptCount,
            boolean compensation,
            UUID leaseToken) {

        public ProviderTenantCommand.Request request() {
            return new ProviderTenantCommand.Request(
                    commandId, commandType, expectedRevision, targetRevision, payloadSha256, payload);
        }
    }

    public enum FailureDisposition {
        RETRY_SCHEDULED,
        COMPENSATION_SCHEDULED,
        RECONCILIATION_REQUIRED,
        LOST_LEASE
    }

    public enum Completion {
        NOT_READY,
        SUCCEEDED,
        COMPENSATED,
        RECONCILIATION_REQUIRED
    }

    private record TenantRow(UUID tenantId, long version) {
    }

    private record CommandState(String lifecycleState, int attemptCount, UUID leaseToken) {
    }
}
