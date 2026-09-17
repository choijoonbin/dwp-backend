package com.dwp.services.platform.workplace.workplaceservices;

import com.fasterxml.jackson.core.JsonProcessingException;
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
public class WorkplaceServiceOperationsRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkplaceServiceOperationsRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
                """, this::commandRow, tenantId, actorUserId, scope, key).stream().findFirst();
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
        return jdbc.query(providerSelect() + " WHERE profile.tenant_id = ?"
                + " ORDER BY profile.display_name_en, profile.provider_code",
                this::providerRow, tenantId);
    }

    public Optional<ProviderRow> provider(long tenantId, UUID providerId) {
        return jdbc.query(providerSelect() + " WHERE profile.tenant_id = ?"
                + " AND profile.provider_profile_id = ?", this::providerRow,
                tenantId, providerId).stream().findFirst();
    }

    public Optional<ProviderRow> providerByCode(long tenantId, String providerCode) {
        return jdbc.query(providerSelect() + " WHERE profile.tenant_id = ?"
                + " AND profile.provider_code = ?", this::providerRow,
                tenantId, providerCode).stream().findFirst();
    }

    private static String providerSelect() {
        return """
                SELECT profile.*, truth.configured, truth.observed_configuration_version,
                       truth.reported_state, truth.evidence_reference, truth.observed_at,
                       truth.received_at, truth.error_code
                  FROM wp_service_provider_profiles profile
                  JOIN wp_service_provider_truth truth
                    ON truth.tenant_id = profile.tenant_id
                   AND truth.provider_code = profile.provider_code
                """;
    }

    public void createProvider(long tenantId, UUID providerId, ProviderCreateRequest request,
                               OffsetDateTime now) {
        String code = request.providerCode().trim();
        jdbc.update("""
                INSERT INTO wp_service_provider_truth (
                    tenant_id, provider_code, configured, configuration_version,
                    version, updated_at)
                VALUES (?, ?, FALSE, 1, 1, ?)
                """, tenantId, code, now);
        jdbc.update("""
                INSERT INTO wp_service_provider_profiles (
                    provider_profile_id, tenant_id, provider_code, display_name_ko,
                    display_name_en, adapter_type, lifecycle_state, site_scope,
                    capabilities, support_metadata, credential_binding_reference,
                    configuration_version, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?::jsonb, ?::jsonb, ?::jsonb,
                        ?, 1, 1, ?, ?)
                """, providerId, tenantId, code, request.displayNameKo().trim(),
                request.displayNameEn().trim(), request.adapterType().trim(),
                json(request.siteScope()), json(request.capabilities()), json(request.support()),
                normalize(request.credentialBindingReference()), now, now);
    }

    public boolean updateProvider(long tenantId, UUID providerId,
                                  ProviderUpdateRequest request, OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE wp_service_provider_profiles
                   SET display_name_ko = ?, display_name_en = ?, adapter_type = ?,
                       site_scope = ?::jsonb, capabilities = ?::jsonb,
                       support_metadata = ?::jsonb,
                       credential_binding_reference = CASE WHEN ? THEN NULL
                           WHEN ? IS NOT NULL THEN ? ELSE credential_binding_reference END,
                       configuration_version = configuration_version + 1,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND provider_profile_id = ? AND version = ?
                """, request.displayNameKo().trim(), request.displayNameEn().trim(),
                request.adapterType().trim(), json(request.siteScope()),
                json(request.capabilities()), json(request.support()),
                request.clearCredentialBinding(), normalize(request.credentialBindingReference()),
                normalize(request.credentialBindingReference()), now, tenantId, providerId,
                request.expectedVersion());
        if (changed == 1) {
            jdbc.update("""
                    UPDATE wp_service_provider_truth truth
                       SET configured = FALSE, configuration_version = profile.configuration_version,
                           observed_configuration_version = NULL, reported_state = NULL,
                           evidence_reference = NULL, observed_at = NULL, received_at = NULL,
                           error_code = NULL, version = truth.version + 1, updated_at = ?
                      FROM wp_service_provider_profiles profile
                     WHERE truth.tenant_id = profile.tenant_id
                       AND truth.provider_code = profile.provider_code
                       AND profile.tenant_id = ? AND profile.provider_profile_id = ?
                    """, now, tenantId, providerId);
        }
        return changed == 1;
    }

    public boolean changeProviderState(long tenantId, UUID providerId, long expectedVersion,
                                       ProviderLifecycleState state, OffsetDateTime now) {
        int changed = jdbc.update("""
                UPDATE wp_service_provider_profiles
                   SET lifecycle_state = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND provider_profile_id = ? AND version = ?
                """, state.name(), now, tenantId, providerId, expectedVersion);
        if (changed == 1) {
            jdbc.update("""
                    UPDATE wp_service_provider_truth truth
                       SET configured = (? = 'ACTIVE'
                           AND profile.credential_binding_reference IS NOT NULL),
                           configuration_version = profile.configuration_version,
                           observed_configuration_version = CASE WHEN ? = 'ACTIVE'
                               THEN observed_configuration_version ELSE NULL END,
                           reported_state = CASE WHEN ? = 'ACTIVE' THEN reported_state ELSE NULL END,
                           evidence_reference = CASE WHEN ? = 'ACTIVE' THEN evidence_reference ELSE NULL END,
                           observed_at = CASE WHEN ? = 'ACTIVE' THEN observed_at ELSE NULL END,
                           received_at = CASE WHEN ? = 'ACTIVE' THEN received_at ELSE NULL END,
                           error_code = CASE WHEN ? = 'ACTIVE' THEN error_code ELSE NULL END,
                           version = truth.version + 1, updated_at = ?
                      FROM wp_service_provider_profiles profile
                     WHERE truth.tenant_id = profile.tenant_id
                       AND truth.provider_code = profile.provider_code
                       AND profile.tenant_id = ? AND profile.provider_profile_id = ?
                    """, state.name(), state.name(), state.name(), state.name(), state.name(),
                    state.name(), state.name(), now, tenantId, providerId);
        }
        return changed == 1;
    }

    public boolean completeProviderVerification(
            long tenantId, UUID providerId, long expectedVersion, long actorUserId,
            WorkplaceServiceProviderVerifier.VerificationResult result, OffsetDateTime receivedAt) {
        ProviderRow provider = provider(tenantId, providerId).orElse(null);
        if (provider == null || provider.version() != expectedVersion) return false;
        jdbc.update("""
                INSERT INTO wp_service_provider_verifications (
                    provider_verification_id, tenant_id, provider_profile_id,
                    configuration_version, reported_state, evidence_reference,
                    capability_evidence, source_observed_at, received_at,
                    error_code, verified_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, providerId, provider.configurationVersion(),
                result.reportedState(), result.evidenceReference(),
                json(result.capabilityEvidence()), result.sourceObservedAt(), receivedAt,
                normalize(result.errorCode()), actorUserId, receivedAt);
        return jdbc.update("""
                UPDATE wp_service_provider_truth
                   SET configured = TRUE, configuration_version = ?,
                       observed_configuration_version = ?, reported_state = ?,
                       evidence_reference = ?, observed_at = ?, received_at = ?, error_code = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND provider_code = ? AND configuration_version = ?
                """, provider.configurationVersion(), provider.configurationVersion(),
                result.reportedState(), result.evidenceReference(), result.sourceObservedAt(),
                receivedAt, normalize(result.errorCode()), receivedAt, tenantId,
                provider.providerCode(), provider.configurationVersion()) == 1;
    }

    public CatalogOperationsPolicy catalogPolicy(long tenantId, UUID itemId) {
        return jdbc.query("""
                SELECT catalog_item_id, capacity_mode, capacity_freshness_seconds,
                       inspection_mode, inspection_checklist_schema
                  FROM wp_service_catalog_items
                 WHERE tenant_id = ? AND catalog_item_id = ?
                """, (rs, row) -> new CatalogOperationsPolicy(
                        rs.getObject("catalog_item_id", UUID.class),
                        CapacityMode.valueOf(rs.getString("capacity_mode")),
                        rs.getInt("capacity_freshness_seconds"),
                        InspectionMode.valueOf(rs.getString("inspection_mode")),
                        node(rs.getString("inspection_checklist_schema"))),
                tenantId, itemId).stream().findFirst().orElse(null);
    }

    public List<CapacityBucketRow> capacityBuckets(
            long tenantId, UUID itemId, String siteReference,
            OffsetDateTime from, OffsetDateTime to, OffsetDateTime now) {
        return jdbc.query("""
                SELECT bucket.*,
                       COALESCE((SELECT SUM(hold.quantity)
                         FROM wp_service_capacity_holds hold
                        WHERE hold.tenant_id = bucket.tenant_id
                          AND hold.capacity_bucket_id = bucket.capacity_bucket_id
                          AND hold.hold_state = 'HELD' AND hold.expires_at > ?), 0) held_quantity
                  FROM wp_service_capacity_buckets bucket
                 WHERE bucket.tenant_id = ? AND bucket.catalog_item_id = ?
                   AND bucket.site_reference = ?
                   AND bucket.bucket_starts_at < ? AND bucket.bucket_ends_at > ?
                 ORDER BY bucket.bucket_starts_at, bucket.capacity_bucket_id
                """, this::capacityBucketRow, now, tenantId, itemId, siteReference, to, from);
    }

    public void upsertCapacityBucket(long tenantId, UUID itemId, String siteReference,
                                     CapacityBucketInput input, OffsetDateTime receivedAt) {
        jdbc.update("""
                INSERT INTO wp_service_capacity_buckets (
                    capacity_bucket_id, tenant_id, catalog_item_id, site_reference,
                    bucket_starts_at, bucket_ends_at, capacity_limit, committed_quantity,
                    source_version, source_observed_at, received_at, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (tenant_id, catalog_item_id, site_reference,
                    bucket_starts_at, bucket_ends_at)
                DO UPDATE SET capacity_limit = EXCLUDED.capacity_limit,
                    source_version = EXCLUDED.source_version,
                    source_observed_at = EXCLUDED.source_observed_at,
                    received_at = EXCLUDED.received_at,
                    version = wp_service_capacity_buckets.version + 1,
                    updated_at = EXCLUDED.updated_at
                WHERE wp_service_capacity_buckets.committed_quantity <= EXCLUDED.capacity_limit
                """, UUID.randomUUID(), tenantId, itemId, siteReference,
                input.startsAt(), input.endsAt(), input.capacityLimit(),
                input.sourceVersion().trim(), input.sourceObservedAt(), receivedAt,
                receivedAt, receivedAt);
    }

    public CapacityHoldResult createCapacityHolds(
            long tenantId, UUID previewId, UUID itemId, String siteReference,
            OffsetDateTime from, OffsetDateTime to, int quantity,
            int freshnessSeconds, OffsetDateTime expiresAt, OffsetDateTime now) {
        List<CapacityBucketRow> buckets = jdbc.query("""
                SELECT bucket.*, COALESCE((SELECT SUM(hold.quantity)
                         FROM wp_service_capacity_holds hold
                        WHERE hold.tenant_id = bucket.tenant_id
                          AND hold.capacity_bucket_id = bucket.capacity_bucket_id
                          AND hold.hold_state = 'HELD' AND hold.expires_at > ?), 0) held_quantity
                  FROM wp_service_capacity_buckets bucket
                 WHERE bucket.tenant_id = ? AND bucket.catalog_item_id = ?
                   AND bucket.site_reference = ?
                   AND bucket.bucket_starts_at < ? AND bucket.bucket_ends_at > ?
                 ORDER BY bucket.bucket_starts_at, bucket.capacity_bucket_id
                 FOR UPDATE OF bucket
                """, this::capacityBucketRow, now, tenantId, itemId, siteReference, to, from);
        if (buckets.isEmpty()) return CapacityHoldResult.missing();
        OffsetDateTime cursor = from;
        OffsetDateTime freshUntil = null;
        for (CapacityBucketRow bucket : buckets) {
            if (bucket.startsAt().isAfter(cursor)) return CapacityHoldResult.missing();
            if (bucket.receivedAt().plusSeconds(freshnessSeconds).isBefore(now)) {
                return CapacityHoldResult.stale(bucket.receivedAt().plusSeconds(freshnessSeconds));
            }
            if (bucket.capacityLimit() - bucket.committedQuantity() - bucket.heldQuantity()
                    < quantity) return CapacityHoldResult.exhausted();
            if (bucket.endsAt().isAfter(cursor)) cursor = bucket.endsAt();
            OffsetDateTime candidate = bucket.receivedAt().plusSeconds(freshnessSeconds);
            if (freshUntil == null || candidate.isBefore(freshUntil)) freshUntil = candidate;
        }
        if (cursor.isBefore(to)) return CapacityHoldResult.missing();
        List<UUID> holdIds = buckets.stream().map(ignored -> UUID.randomUUID()).toList();
        for (int index = 0; index < buckets.size(); index++) {
            CapacityBucketRow bucket = buckets.get(index);
            jdbc.update("""
                    INSERT INTO wp_service_capacity_holds (
                        capacity_hold_id, tenant_id, preview_id, catalog_item_id,
                        capacity_bucket_id, quantity, hold_state, bucket_version,
                        expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'HELD', ?, ?, ?, ?)
                    """, holdIds.get(index), tenantId, previewId, itemId,
                    bucket.bucketId(), quantity, bucket.version(), expiresAt, now, now);
        }
        return CapacityHoldResult.held(holdIds, expiresAt, freshUntil);
    }

    public boolean commitCapacityHolds(long tenantId, UUID previewId, UUID orderId,
                                       OffsetDateTime now) {
        List<CapacityHoldRow> holds = jdbc.query("""
                SELECT hold.* FROM wp_service_capacity_holds hold
                 WHERE hold.tenant_id = ? AND hold.preview_id = ?
                 ORDER BY hold.capacity_bucket_id
                 FOR UPDATE
                """, this::capacityHoldRow, tenantId, previewId);
        if (holds.isEmpty()) return true;
        if (holds.stream().anyMatch(hold -> !"HELD".equals(hold.state())
                || !hold.expiresAt().isAfter(now))) return false;
        for (CapacityHoldRow hold : holds) {
            int updated = jdbc.update("""
                    UPDATE wp_service_capacity_buckets
                       SET committed_quantity = committed_quantity + ?,
                           version = version + 1, updated_at = ?
                     WHERE tenant_id = ? AND capacity_bucket_id = ?
                       AND version = ?
                       AND committed_quantity + ? <= capacity_limit
                    """, hold.quantity(), now, tenantId, hold.bucketId(),
                    hold.bucketVersion(), hold.quantity());
            if (updated != 1) return false;
            jdbc.update("""
                    UPDATE wp_service_capacity_holds
                       SET hold_state = 'COMMITTED', service_order_id = ?, committed_at = ?,
                           updated_at = ?
                     WHERE tenant_id = ? AND capacity_hold_id = ? AND hold_state = 'HELD'
                    """, orderId, now, now, tenantId, hold.holdId());
        }
        return true;
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
                """, this::assigneeRow, tenantId, now, providerCode,
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
                """, this::taskContextRow, tenantId, orderId, taskId).stream().findFirst();
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
                """, this::taskContextRow, tenantId, orderId, lineId,
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
                """, this::accessGrantRow, tenantId, orderId, lineId, grantId,
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
                """, this::accessGrantRow, limit);
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
                """, this::commandRow, tenantId, commandId).stream().findFirst();
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

    private ProviderRow providerRow(ResultSet rs, int ignored) throws SQLException {
        return new ProviderRow(rs.getObject("provider_profile_id", UUID.class),
                rs.getString("provider_code"), rs.getString("display_name_ko"),
                rs.getString("display_name_en"), rs.getString("adapter_type"),
                ProviderLifecycleState.valueOf(rs.getString("lifecycle_state")),
                uuids(rs.getString("site_scope")), strings(rs.getString("capabilities")),
                node(rs.getString("support_metadata")),
                rs.getString("credential_binding_reference"),
                rs.getLong("configuration_version"), rs.getBoolean("configured"),
                nullableLong(rs, "observed_configuration_version"),
                rs.getString("reported_state"), rs.getString("evidence_reference"),
                rs.getObject("observed_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class), rs.getString("error_code"),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private OperationsCommandRow commandRow(ResultSet rs, int ignored) throws SQLException {
        return new OperationsCommandRow(rs.getObject("operations_command_id", UUID.class),
                rs.getLong("tenant_id"), rs.getLong("actor_user_id"),
                rs.getString("command_scope"), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"), rs.getString("resource_type"),
                rs.getObject("resource_id", UUID.class),
                CommandState.valueOf(rs.getString("command_state")),
                rs.getString("status_href"), rs.getString("correlation_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private CapacityBucketRow capacityBucketRow(ResultSet rs, int ignored) throws SQLException {
        return new CapacityBucketRow(rs.getObject("capacity_bucket_id", UUID.class),
                rs.getObject("catalog_item_id", UUID.class), rs.getString("site_reference"),
                rs.getObject("bucket_starts_at", OffsetDateTime.class),
                rs.getObject("bucket_ends_at", OffsetDateTime.class),
                rs.getInt("capacity_limit"), rs.getInt("committed_quantity"),
                rs.getInt("held_quantity"), rs.getString("source_version"),
                rs.getObject("source_observed_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class), rs.getLong("version"));
    }

    private CapacityHoldRow capacityHoldRow(ResultSet rs, int ignored) throws SQLException {
        return new CapacityHoldRow(rs.getObject("capacity_hold_id", UUID.class),
                rs.getObject("capacity_bucket_id", UUID.class), rs.getInt("quantity"),
                rs.getString("hold_state"), rs.getLong("bucket_version"),
                rs.getObject("expires_at", OffsetDateTime.class));
    }

    private AssigneeRow assigneeRow(ResultSet rs, int ignored) throws SQLException {
        return new AssigneeRow(rs.getString("directory_subject_id"),
                rs.getString("public_display_name"), rs.getBoolean("contact_available"),
                strings(rs.getString("capabilities")), rs.getString("directory_version"),
                rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("fresh_until", OffsetDateTime.class));
    }

    private TaskContextRow taskContextRow(ResultSet rs, int ignored) throws SQLException {
        return new TaskContextRow(rs.getObject("fulfillment_task_id", UUID.class),
                rs.getObject("service_order_id", UUID.class),
                rs.getObject("service_order_line_id", UUID.class), rs.getString("provider_code"),
                rs.getLong("task_version"), rs.getLong("requester_user_id"),
                rs.getString("site_reference"), rs.getLong("order_version"),
                InspectionMode.valueOf(rs.getString("inspection_mode")),
                node(rs.getString("inspection_checklist_schema")), rs.getInt("quantity"),
                rs.getInt("fulfilled_quantity"), rs.getString("line_state"),
                node(rs.getString("option_schema_snapshot")),
                rs.getObject("provider_profile_id", UUID.class), rs.getString("adapter_type"),
                rs.getString("credential_binding_reference"),
                strings(rs.getString("capabilities")), node(rs.getString("support_metadata")),
                ProviderLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getLong("configuration_version"), rs.getBoolean("configured"),
                nullableLong(rs, "observed_configuration_version"),
                rs.getString("reported_state"), rs.getString("evidence_reference"),
                rs.getObject("observed_at", OffsetDateTime.class),
                rs.getObject("received_at", OffsetDateTime.class));
    }

    private InspectionAttempt inspectionAttempt(ResultSet rs, int ignored) throws SQLException {
        return new InspectionAttempt(rs.getObject("inspection_attempt_id", UUID.class),
                rs.getObject("service_order_id", UUID.class),
                rs.getObject("service_order_line_id", UUID.class),
                rs.getObject("fulfillment_task_id", UUID.class),
                InspectionMode.valueOf(rs.getString("inspection_mode")),
                InspectionActorRole.valueOf(rs.getString("inspector_role")),
                InspectionDecision.valueOf(rs.getString("decision")),
                node(rs.getString("checklist_schema_snapshot")),
                node(rs.getString("checklist_responses")),
                uuids(rs.getString("evidence_attachment_ids")), rs.getString("reason"),
                rs.getBoolean("remediation_required"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private AccessGrantRow accessGrantRow(ResultSet rs, int ignored) throws SQLException {
        return new AccessGrantRow(rs.getObject("access_grant_id", UUID.class),
                rs.getLong("tenant_id"), rs.getObject("service_order_id", UUID.class),
                rs.getObject("service_order_line_id", UUID.class),
                rs.getLong("requester_user_id"), rs.getString("provider_code"),
                rs.getString("adapter_type_snapshot"),
                rs.getLong("provider_configuration_version"),
                rs.getString("provider_credential_binding_reference"),
                rs.getString("provider_operation_kind"),
                rs.getObject("provider_operation_command_id", UUID.class),
                rs.getString("provider_grant_reference"),
                AccessGrantState.valueOf(rs.getString("grant_state")),
                rs.getString("reason"),
                rs.getObject("issued_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class), rs.getLong("version"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Workplace service operations JSON serialization failed.",
                    exception);
        }
    }

    private JsonNode node(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Workplace service operations JSON is invalid.",
                    exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readerForListOf(String.class).readValue(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Workplace string array is invalid.", exception);
        }
    }

    private List<UUID> uuids(String value) {
        try {
            return objectMapper.readerForListOf(UUID.class).readValue(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Workplace UUID array is invalid.", exception);
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
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
