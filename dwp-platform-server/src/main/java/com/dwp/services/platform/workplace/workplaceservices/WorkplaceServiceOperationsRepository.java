package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.CommandState;

@Repository
public class WorkplaceServiceOperationsRepository
        extends WorkplaceServiceOperationsRepositorySupport {
    private final WorkplaceServiceInventoryRepository inventory;

    public WorkplaceServiceOperationsRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
        this.inventory = new WorkplaceServiceInventoryRepository(jdbc, objectMapper);
    }

    public void lockCommand(long tenantId, long actorUserId, String scope, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(
                        1, tenantId + ":" + actorUserId + ":" + scope + ":" + key),
                resultSet -> null);
    }

    public Optional<OperationsCommandRow> command(
            long tenantId, long actorUserId, String scope, String key) {
        return jdbc.query("""
                SELECT * FROM wp_service_operations_commands
                 WHERE tenant_id = ? AND actor_user_id = ?
                   AND command_scope = ? AND idempotency_key = ?
                """, (rs, row) -> commandRow(rs, row, OperationsCommandRow::new),
                tenantId, actorUserId, scope, key).stream().findFirst();
    }

    public void createCommand(OperationsCommandRow row) {
        jdbc.update("""
                INSERT INTO wp_service_operations_commands (
                    operations_command_id, tenant_id, actor_user_id, command_scope,
                    idempotency_key, request_fingerprint, resource_type, resource_id,
                    command_state, status_href, correlation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, row.commandId(), row.tenantId(), row.actorUserId(), row.scope(),
                row.idempotencyKey(), row.fingerprint(), row.resourceType(), row.resourceId(),
                row.state().name(), row.statusHref(), row.correlationId(), row.createdAt(),
                row.updatedAt());
    }

    public void updateCommand(long tenantId, UUID commandId, CommandState state,
                              OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_service_operations_commands
                   SET command_state = ?, updated_at = ?
                 WHERE tenant_id = ? AND operations_command_id = ?
                """, state.name(), now, tenantId, commandId);
    }

    public void snapshotCommandProvider(
            long tenantId, UUID commandId, ProviderRow provider) {
        int changed = jdbc.update("""
                UPDATE wp_service_operations_commands
                   SET provider_profile_id_snapshot = ?, provider_code_snapshot = ?,
                       adapter_type_snapshot = ?, provider_configuration_version = ?,
                       provider_credential_binding_reference = ?,
                       provider_capabilities_snapshot = ?::jsonb,
                       provider_profile_version = ?
                 WHERE tenant_id = ? AND operations_command_id = ?
                   AND provider_code_snapshot IS NULL
                """, provider.providerId(), provider.providerCode(), provider.adapterType(),
                provider.configurationVersion(), provider.credentialBindingReference(),
                json(provider.capabilities()), provider.version(), tenantId, commandId);
        if (changed != 1) {
            throw new IllegalStateException("Provider command snapshot was not persisted.");
        }
    }

    public Optional<ProviderCommandSnapshot> commandProviderSnapshot(
            long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT provider_profile_id_snapshot, provider_code_snapshot,
                       adapter_type_snapshot, provider_configuration_version,
                       provider_credential_binding_reference,
                       provider_capabilities_snapshot, provider_profile_version
                  FROM wp_service_operations_commands
                 WHERE tenant_id = ? AND operations_command_id = ?
                   AND provider_code_snapshot IS NOT NULL
                """, (rs, row) -> new ProviderCommandSnapshot(
                        rs.getObject("provider_profile_id_snapshot", UUID.class),
                        rs.getString("provider_code_snapshot"),
                        rs.getString("adapter_type_snapshot"),
                        rs.getLong("provider_configuration_version"),
                        rs.getString("provider_credential_binding_reference"),
                        strings(rs.getString("provider_capabilities_snapshot")),
                        rs.getLong("provider_profile_version")), tenantId, commandId)
                .stream().findFirst();
    }

    public List<ProviderRow> providers(long tenantId) {
        return inventory.providers(tenantId).stream()
                .map(WorkplaceServiceOperationsRepository::providerResult).toList();
    }

    public Optional<ProviderRow> provider(long tenantId, UUID providerId) {
        return inventory.provider(tenantId, providerId)
                .map(WorkplaceServiceOperationsRepository::providerResult);
    }

    public Optional<ProviderRow> providerByCode(long tenantId, String providerCode) {
        return inventory.providerByCode(tenantId, providerCode)
                .map(WorkplaceServiceOperationsRepository::providerResult);
    }

    public void createProvider(long tenantId, UUID providerId, ProviderCreateRequest request,
                               OffsetDateTime now) {
        inventory.createProvider(tenantId, providerId, request, now);
    }

    public boolean updateProvider(long tenantId, UUID providerId,
                                  ProviderUpdateRequest request, OffsetDateTime now) {
        return inventory.updateProvider(tenantId, providerId, request, now);
    }

    public boolean changeProviderState(long tenantId, UUID providerId, long expectedVersion,
                                       ProviderLifecycleState state, OffsetDateTime now) {
        return inventory.changeProviderState(
                tenantId, providerId, expectedVersion, state, now);
    }

    public boolean completeProviderVerification(
            long tenantId, UUID providerId, long expectedVersion, long actorUserId,
            WorkplaceServiceProviderVerifier.VerificationResult result, OffsetDateTime receivedAt) {
        return inventory.completeProviderVerification(
                tenantId, providerId, expectedVersion, actorUserId, result, receivedAt);
    }

    public CatalogOperationsPolicy catalogPolicy(long tenantId, UUID itemId) {
        var value = inventory.catalogPolicy(tenantId, itemId);
        return value == null ? null : new CatalogOperationsPolicy(
                value.catalogItemId(), value.capacityMode(), value.capacityFreshnessSeconds(),
                value.inspectionMode(), value.inspectionChecklistSchema());
    }

    public List<CapacityBucketRow> capacityBuckets(
            long tenantId, UUID itemId, String siteReference,
            OffsetDateTime from, OffsetDateTime to, OffsetDateTime now) {
        return inventory.capacityBuckets(tenantId, itemId, siteReference, from, to, now)
                .stream().map(value -> new CapacityBucketRow(
                        value.bucketId(), value.catalogItemId(), value.siteReference(),
                        value.startsAt(), value.endsAt(), value.capacityLimit(),
                        value.committedQuantity(), value.heldQuantity(), value.sourceVersion(),
                        value.sourceObservedAt(), value.receivedAt(), value.version())).toList();
    }

    public void upsertCapacityBucket(long tenantId, UUID itemId, String siteReference,
                                     CapacityBucketInput input, OffsetDateTime receivedAt) {
        inventory.upsertCapacityBucket(
                tenantId, itemId, siteReference, input, receivedAt);
    }

    public CapacityHoldResult createCapacityHolds(
            long tenantId, UUID previewId, UUID itemId, String siteReference,
            OffsetDateTime from, OffsetDateTime to, int quantity,
            int freshnessSeconds, OffsetDateTime expiresAt, OffsetDateTime now) {
        var value = inventory.createCapacityHolds(
                tenantId, previewId, itemId, siteReference, from, to, quantity,
                freshnessSeconds, expiresAt, now);
        return new CapacityHoldResult(
                value.holdIds(), value.expiresAt(), value.freshUntil(), value.limitation());
    }

    public boolean commitCapacityHolds(long tenantId, UUID previewId, UUID orderId,
                                       OffsetDateTime now) {
        return inventory.commitCapacityHolds(tenantId, previewId, orderId, now);
    }

    private static ProviderRow providerResult(
            WorkplaceServiceInventoryRepository.ProviderRow value) {
        return new ProviderRow(
                value.providerId(), value.providerCode(), value.displayNameKo(),
                value.displayNameEn(), value.adapterType(), value.lifecycleState(),
                value.siteScope(), value.capabilities(), value.support(),
                value.credentialBindingReference(), value.configurationVersion(),
                value.configured(), value.observedConfigurationVersion(),
                value.reportedState(), value.evidenceReference(), value.observedAt(),
                value.receivedAt(), value.errorCode(), value.version(), value.updatedAt());
    }

    public List<AssigneeRow> searchAssignees(
            long tenantId, String providerCode, String siteReference,
            String query, int limit, OffsetDateTime now) {
        return jdbc.query("""
                SELECT * FROM wp_service_assignee_directory_entries
                 WHERE tenant_id = ? AND active = TRUE AND fresh_until > ?
                   AND jsonb_exists(capabilities, 'WORKPLACE_SERVICE_FULFILLMENT')
                   AND jsonb_exists(provider_codes, ?)
                   AND (site_scope = '[]'::jsonb OR jsonb_exists(site_scope, ?))
                   AND (lower(public_display_name) LIKE lower(?)
                        OR lower(directory_subject_id) LIKE lower(?))
                 ORDER BY public_display_name, directory_subject_id
                 LIMIT ?
                """, (rs, row) -> assigneeRow(rs, row, AssigneeRow::new), tenantId, now, providerCode,
                siteReference == null ? "" : siteReference,
                "%" + query + "%", "%" + query + "%", limit);
    }

    public Optional<AssigneeRow> assignee(
            long tenantId, String subjectId, String providerCode,
            String siteReference, OffsetDateTime now) {
        return searchAssignees(tenantId, providerCode, siteReference, subjectId, 100, now)
                .stream().filter(row -> row.subjectId().equals(subjectId)).findFirst();
    }

    public Optional<TaskContextRow> taskContext(long tenantId, UUID orderId, UUID taskId) {
        return jdbc.query("""
                SELECT task.fulfillment_task_id, task.service_order_id,
                       task.service_order_line_id, task.provider_code, task.version task_version,
                       order_row.requester_user_id, order_row.site_reference,
                       order_row.version order_version, line.inspection_mode,
                       line.inspection_checklist_schema, line.quantity,
                       line.fulfilled_quantity, line.line_state,
                       line.option_schema_snapshot,
                       profile.provider_profile_id, profile.adapter_type,
                       profile.credential_binding_reference, profile.capabilities,
                       profile.support_metadata, profile.lifecycle_state,
                       profile.configuration_version,
                       truth.configured, truth.observed_configuration_version,
                       truth.reported_state, truth.evidence_reference, truth.observed_at,
                       truth.received_at
                  FROM wp_service_fulfillment_tasks task
                  JOIN wp_service_orders order_row
                    ON order_row.tenant_id = task.tenant_id
                   AND order_row.service_order_id = task.service_order_id
                  JOIN wp_service_order_lines line
                    ON line.tenant_id = task.tenant_id
                   AND line.service_order_line_id = task.service_order_line_id
                  JOIN wp_service_provider_profiles profile
                    ON profile.tenant_id = task.tenant_id
                   AND profile.provider_code = task.provider_code
                  JOIN wp_service_provider_truth truth
                    ON truth.tenant_id = task.tenant_id
                   AND truth.provider_code = task.provider_code
                 WHERE task.tenant_id = ? AND task.service_order_id = ?
                   AND task.fulfillment_task_id = ?
                """, (rs, row) -> taskContextRow(rs, row, TaskContextRow::new),
                tenantId, orderId, taskId).stream().findFirst();
    }

    public Optional<TaskContextRow> lineContext(
            long tenantId, UUID orderId, UUID lineId, Long requesterUserId) {
        return jdbc.query("""
                SELECT task.fulfillment_task_id, task.service_order_id,
                       task.service_order_line_id, task.provider_code, task.version task_version,
                       order_row.requester_user_id, order_row.site_reference,
                       order_row.version order_version, line.inspection_mode,
                       line.inspection_checklist_schema, line.quantity,
                       line.fulfilled_quantity, line.line_state,
                       line.option_schema_snapshot,
                       profile.provider_profile_id, profile.adapter_type,
                       profile.credential_binding_reference, profile.capabilities,
                       profile.support_metadata, profile.lifecycle_state,
                       profile.configuration_version,
                       truth.configured, truth.observed_configuration_version,
                       truth.reported_state, truth.evidence_reference, truth.observed_at,
                       truth.received_at
                  FROM wp_service_order_lines line
                  JOIN wp_service_orders order_row
                    ON order_row.tenant_id = line.tenant_id
                   AND order_row.service_order_id = line.service_order_id
                  JOIN wp_service_fulfillment_tasks task
                    ON task.tenant_id = line.tenant_id
                   AND task.service_order_line_id = line.service_order_line_id
                  JOIN wp_service_provider_profiles profile
                    ON profile.tenant_id = line.tenant_id
                   AND profile.provider_code = line.provider_code
                  JOIN wp_service_provider_truth truth
                    ON truth.tenant_id = line.tenant_id
                   AND truth.provider_code = line.provider_code
                 WHERE line.tenant_id = ? AND line.service_order_id = ?
                   AND line.service_order_line_id = ?
                   AND (? IS NULL OR order_row.requester_user_id = ?)
                """, (rs, row) -> taskContextRow(rs, row, TaskContextRow::new), tenantId, orderId, lineId,
                requesterUserId, requesterUserId).stream().findFirst();
    }

    public boolean assignTask(long tenantId, UUID taskId, long expectedVersion,
                              AssigneeRow assignee, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_fulfillment_tasks
                   SET assignee_user_id = NULL, assignee_directory_subject_id = ?,
                       assignee_display_name = ?, assignee_directory_version = ?,
                       assignee_verified_at = ?, assignee_capabilities_snapshot = ?::jsonb,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND fulfillment_task_id = ? AND version = ?
                """, assignee.subjectId(), assignee.displayName(), assignee.directoryVersion(),
                assignee.receivedAt(), json(assignee.capabilities()), now,
                tenantId, taskId, expectedVersion) == 1;
    }

    public Optional<InspectionAttempt> latestInspection(
            long tenantId, UUID orderId, UUID lineId) {
        return jdbc.query("""
                SELECT * FROM wp_service_inspection_attempts
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND service_order_line_id = ?
                 ORDER BY created_at DESC, inspection_attempt_id DESC LIMIT 1
                """, this::inspectionAttempt, tenantId, orderId, lineId).stream().findFirst();
    }

    public void createInspection(long tenantId, UUID attemptId, TaskContextRow context,
                                 long actorUserId, InspectionActorRole role,
                                 InspectionAttemptRequest request, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_service_inspection_attempts (
                    inspection_attempt_id, tenant_id, service_order_id,
                    service_order_line_id, fulfillment_task_id, inspection_mode,
                    inspector_role, decision, checklist_schema_snapshot,
                    checklist_responses, evidence_attachment_ids, reason,
                    remediation_required, actor_user_id, order_version, task_version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                        ?, ?, ?, ?, ?, ?)
                """, attemptId, tenantId, context.orderId(), context.lineId(), context.taskId(),
                context.inspectionMode().name(), role.name(), request.decision().name(),
                json(context.inspectionSchema()), json(request.checklistResponses()),
                json(request.attachmentIds()), request.reason().trim(),
                request.decision() == InspectionDecision.FAILED, actorUserId,
                context.orderVersion(), context.taskVersion(), now);
    }

    public boolean inspectionSatisfied(long tenantId, UUID lineId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT CASE WHEN line.inspection_mode = 'NONE' THEN TRUE ELSE EXISTS (
                    SELECT 1 FROM wp_service_inspection_attempts attempt
                     WHERE attempt.tenant_id = line.tenant_id
                       AND attempt.service_order_line_id = line.service_order_line_id
                       AND attempt.inspection_mode = line.inspection_mode
                       AND attempt.decision = 'PASSED'
                       AND NOT EXISTS (
                           SELECT 1 FROM wp_service_inspection_attempts later
                            WHERE later.tenant_id = attempt.tenant_id
                              AND later.service_order_line_id = attempt.service_order_line_id
                              AND (later.created_at, later.inspection_attempt_id)
                                  > (attempt.created_at, attempt.inspection_attempt_id)))
                    END
                  FROM wp_service_order_lines line
                 WHERE line.tenant_id = ? AND line.service_order_line_id = ?
                """, Boolean.class, tenantId, lineId));
    }

    public boolean attachmentsClean(long tenantId, UUID orderId, List<UUID> ids) {
        if (ids.isEmpty()) return true;
        return ids.stream().distinct().count() == ids.size()
                && ids.stream().allMatch(id -> Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM wp_service_order_attachments
                     WHERE tenant_id = ? AND service_order_id = ?
                       AND attachment_id = ? AND scan_state = 'CLEAN')
                    """, Boolean.class, tenantId, orderId, id)));
    }

    public void createPendingGrant(
            long tenantId, UUID grantId, TaskContextRow context, long requesterUserId,
            String providerReference, String fingerprint, String reason,
            UUID operationCommandId, OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        jdbc.update("""
                INSERT INTO wp_service_ephemeral_access_grants (
                    access_grant_id, tenant_id, service_order_id, service_order_line_id,
                    provider_code, requester_user_id, provider_grant_reference,
                    credential_fingerprint, grant_state, reason, issued_at, expires_at,
                    adapter_type_snapshot, provider_configuration_version,
                    provider_credential_binding_reference, provider_operation_kind,
                    provider_operation_command_id,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RESULT_UNKNOWN', ?, ?, ?,
                        ?, ?, ?, 'ISSUE', ?, 1, ?, ?)
                """, grantId, tenantId, context.orderId(), context.lineId(),
                context.providerCode(), requesterUserId, providerReference, fingerprint,
                reason, issuedAt, expiresAt, context.adapterType(),
                context.providerConfigurationVersion(), context.credentialBindingReference(),
                operationCommandId, issuedAt, issuedAt);
    }

    public void finalizeGrant(long tenantId, UUID grantId, String providerReference,
                              String fingerprint, OffsetDateTime expiresAt,
                              OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_service_ephemeral_access_grants
                   SET provider_grant_reference = ?, credential_fingerprint = ?,
                       grant_state = 'ISSUED', expires_at = ?, version = version + 1,
                       updated_at = ?
                 WHERE tenant_id = ? AND access_grant_id = ?
                   AND grant_state = 'RESULT_UNKNOWN' AND provider_operation_kind = 'ISSUE'
                """, providerReference, fingerprint, expiresAt, now, tenantId, grantId);
    }

    public Optional<AccessGrantRow> accessGrant(long tenantId, UUID orderId,
                                                UUID lineId, UUID grantId,
                                                Long requesterUserId) {
        return jdbc.query("""
                SELECT * FROM wp_service_ephemeral_access_grants
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND service_order_line_id = ? AND access_grant_id = ?
                   AND (? IS NULL OR requester_user_id = ?)
                """, (rs, row) -> accessGrantRow(rs, row, AccessGrantRow::new),
                tenantId, orderId, lineId, grantId,
                requesterUserId, requesterUserId).stream().findFirst();
    }

    public void markGrantRevoked(long tenantId, UUID grantId, boolean resultUnknown,
                                 OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_service_ephemeral_access_grants
                   SET grant_state = ?, revoked_at = CASE WHEN ? THEN NULL ELSE ? END,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND access_grant_id = ?
                   AND provider_operation_kind = 'REVOKE'
                """, resultUnknown ? "RESULT_UNKNOWN" : "REVOKED", resultUnknown,
                now, now, tenantId, grantId);
    }

    public void completeGrantRevocation(
            long tenantId, UUID grantId, boolean revoked, boolean resultUnknown,
            OffsetDateTime now) {
        String state = revoked ? "REVOKED" : resultUnknown ? "RESULT_UNKNOWN" : "ISSUED";
        jdbc.update("""
                UPDATE wp_service_ephemeral_access_grants
                   SET grant_state = ?, revoked_at = CASE WHEN ? THEN ? ELSE NULL END,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND access_grant_id = ?
                   AND provider_operation_kind = 'REVOKE'
                   AND grant_state = 'RESULT_UNKNOWN'
                """, state, revoked, now, now, tenantId, grantId);
    }

    public boolean beginGrantRevocation(
            long tenantId, UUID grantId, long expectedVersion,
            UUID operationCommandId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_ephemeral_access_grants
                   SET grant_state = 'RESULT_UNKNOWN', provider_operation_kind = 'REVOKE',
                       provider_operation_command_id = ?, provider_recovery_attempt_count = 0,
                       provider_next_attempt_at = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND access_grant_id = ? AND version = ?
                   AND grant_state = 'ISSUED'
                   AND provider_operation_kind = 'ISSUE'
                   AND adapter_type_snapshot IS NOT NULL
                   AND provider_configuration_version > 0
                   AND provider_credential_binding_reference IS NOT NULL
                """, operationCommandId, now, now, tenantId, grantId, expectedVersion) == 1;
    }

    public List<AccessGrantRow> pendingAccessGrants(int limit) {
        return jdbc.query("""
                SELECT * FROM wp_service_ephemeral_access_grants
                 WHERE grant_state = 'RESULT_UNKNOWN'
                   AND provider_operation_kind = 'REVOKE'
                   AND provider_operation_command_id IS NOT NULL
                   AND provider_next_attempt_at <= CURRENT_TIMESTAMP
                 ORDER BY provider_next_attempt_at, tenant_id, access_grant_id
                 LIMIT ?
                """, (rs, row) -> accessGrantRow(rs, row, AccessGrantRow::new), limit);
    }

    public boolean claimAccessGrantRecovery(
            long tenantId, UUID grantId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_service_ephemeral_access_grants
                   SET provider_recovery_attempt_count = provider_recovery_attempt_count + 1,
                       provider_next_attempt_at = ? + make_interval(secs =>
                           (5 * (1 << LEAST(provider_recovery_attempt_count, 9))))
                 WHERE tenant_id = ? AND access_grant_id = ?
                   AND grant_state = 'RESULT_UNKNOWN'
                   AND provider_operation_kind = 'REVOKE'
                   AND provider_operation_command_id IS NOT NULL
                   AND provider_next_attempt_at <= ?
                """, now, tenantId, grantId, now) == 1;
    }

    public Optional<OperationsCommandRow> command(long tenantId, UUID commandId) {
        return jdbc.query("""
                SELECT * FROM wp_service_operations_commands
                 WHERE tenant_id = ? AND operations_command_id = ?
                """, (rs, row) -> commandRow(rs, row, OperationsCommandRow::new),
                tenantId, commandId).stream().findFirst();
    }

    public ContactTarget resolveContact(long tenantId, TaskContextRow context,
                                        ContactTargetType type, OffsetDateTime now) {
        if (type == ContactTargetType.SERVICE_DESK) {
            String reference = context.support().path("serviceDeskReference").asText("");
            String name = context.support().path("serviceDeskDisplayName").asText("");
            return reference.isBlank() || name.isBlank() ? null : new ContactTarget(reference, name);
        }
        return jdbc.query("""
                SELECT assignee_directory_subject_id, assignee_display_name
                  FROM wp_service_fulfillment_tasks
                 WHERE tenant_id = ? AND fulfillment_task_id = ?
                   AND assignee_directory_subject_id IS NOT NULL
                   AND assignee_verified_at >= ?
                """, (rs, row) -> new ContactTarget(
                        rs.getString("assignee_directory_subject_id"),
                        rs.getString("assignee_display_name")), tenantId, context.taskId(),
                now.minusMinutes(30)).stream().findFirst().orElse(null);
    }

    public UUID createContactRequest(long tenantId, TaskContextRow context, long actorUserId,
                                     ContactTargetType type, ContactTarget target, String message,
                                     String reason, OffsetDateTime now) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_service_order_messages (
                    message_id, tenant_id, service_order_id, author_user_id,
                    author_role, message, created_at)
                VALUES (?, ?, ?, ?, 'REQUESTER', ?, ?)
                """, messageId, tenantId, context.orderId(), actorUserId, message, now);
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_service_contact_requests (
                    contact_request_id, tenant_id, service_order_id, service_order_line_id,
                    requested_by, target_type, resolved_target_reference,
                    resolved_target_display_name, message_id, contact_state, reason, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
                """, requestId, tenantId, context.orderId(), context.lineId(), actorUserId,
                type.name(), target.reference(), target.displayName(), messageId, reason, now);
        return requestId;
    }

    public void appendOrderEvent(long tenantId, UUID orderId, String eventType,
                                 long actorUserId, JsonNode detail, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_service_order_events (
                    service_order_event_id, tenant_id, service_order_id,
                    event_type, actor_user_id, detail, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), tenantId, orderId, eventType,
                actorUserId, json(detail), now);
    }

    public void appendAuditAndOutbox(long tenantId, long actorUserId, UUID aggregateId,
                                     String aggregateType, String action, String eventType,
                                     String correlationId, JsonNode detail, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO wp_audit_events (
                    audit_event_id, tenant_id, action, aggregate_type, aggregate_id,
                    actor_user_id, correlation_id, snapshot, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), tenantId, action, aggregateType, aggregateId,
                actorUserId, correlationId, json(detail), now);
        jdbc.update("""
                INSERT INTO wp_service_order_outbox (
                    outbox_event_id, tenant_id, aggregate_id, event_type,
                    payload, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, UUID.randomUUID(), tenantId, aggregateId, eventType,
                json(detail), correlationId, now);
    }

    public record OperationsCommandRow(
            UUID commandId, long tenantId, long actorUserId, String scope,
            String idempotencyKey, String fingerprint, String resourceType,
            UUID resourceId, CommandState state, String statusHref,
            String correlationId, OffsetDateTime createdAt, OffsetDateTime updatedAt) { }

    public record ProviderRow(
            UUID providerId, String providerCode, String displayNameKo, String displayNameEn,
            String adapterType, ProviderLifecycleState lifecycleState, List<UUID> siteScope,
            List<String> capabilities, JsonNode support, String credentialBindingReference,
            long configurationVersion, boolean configured, Long observedConfigurationVersion,
            String reportedState, String evidenceReference, OffsetDateTime observedAt,
            OffsetDateTime receivedAt, String errorCode, long version,
            OffsetDateTime updatedAt) { }

    public record ProviderCommandSnapshot(
            UUID providerId, String providerCode, String adapterType,
            long configurationVersion, String credentialBindingReference,
            List<String> capabilities, long providerVersion) { }

    public record CatalogOperationsPolicy(
            UUID catalogItemId, CapacityMode capacityMode, int capacityFreshnessSeconds,
            InspectionMode inspectionMode, JsonNode inspectionChecklistSchema) { }

    public record CapacityBucketRow(
            UUID bucketId, UUID catalogItemId, String siteReference,
            OffsetDateTime startsAt, OffsetDateTime endsAt, int capacityLimit,
            int committedQuantity, int heldQuantity, String sourceVersion,
            OffsetDateTime sourceObservedAt, OffsetDateTime receivedAt, long version) { }

    public record CapacityHoldRow(
            UUID holdId, UUID bucketId, int quantity, String state,
            long bucketVersion, OffsetDateTime expiresAt) { }

    public record CapacityHoldResult(
            List<UUID> holdIds, OffsetDateTime expiresAt, OffsetDateTime freshUntil,
            String limitation) {
        static CapacityHoldResult held(List<UUID> ids, OffsetDateTime expiresAt,
                                       OffsetDateTime freshUntil) {
            return new CapacityHoldResult(List.copyOf(ids), expiresAt, freshUntil, null);
        }
        static CapacityHoldResult missing() {
            return new CapacityHoldResult(List.of(), null, null, "CAPACITY_BUCKET_MISSING");
        }
        static CapacityHoldResult stale(OffsetDateTime freshUntil) {
            return new CapacityHoldResult(List.of(), null, freshUntil, "CAPACITY_STALE");
        }
        static CapacityHoldResult exhausted() {
            return new CapacityHoldResult(List.of(), null, null, "CAPACITY_EXHAUSTED");
        }
        public boolean held() { return limitation == null; }
    }

    public record AssigneeRow(
            String subjectId, String displayName, boolean contactAvailable,
            List<String> capabilities, String directoryVersion,
            OffsetDateTime receivedAt, OffsetDateTime freshUntil) { }

    public record TaskContextRow(
            UUID taskId, UUID orderId, UUID lineId, String providerCode, long taskVersion,
            long requesterUserId, String siteReference, long orderVersion,
            InspectionMode inspectionMode, JsonNode inspectionSchema, int quantity,
            int fulfilledQuantity, String lineState, JsonNode optionSchema,
            UUID providerProfileId, String adapterType, String credentialBindingReference,
            List<String> providerCapabilities, JsonNode support,
            ProviderLifecycleState providerLifecycleState, long providerConfigurationVersion,
            boolean providerConfigured, Long observedConfigurationVersion,
            String reportedState, String evidenceReference, OffsetDateTime observedAt,
            OffsetDateTime receivedAt) { }

    public record AccessGrantRow(
            UUID grantId, long tenantId, UUID orderId, UUID lineId, long requesterUserId,
            String providerCode, String adapterType, long providerConfigurationVersion,
            String credentialBindingReference, String operationKind, UUID operationCommandId,
            String providerReference, AccessGrantState state, String reason,
            OffsetDateTime issuedAt, OffsetDateTime expiresAt,
            OffsetDateTime revokedAt, long version) { }

    public record ContactTarget(String reference, String displayName) { }
}
