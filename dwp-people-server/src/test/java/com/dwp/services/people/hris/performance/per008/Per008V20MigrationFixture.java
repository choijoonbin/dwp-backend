package com.dwp.services.people.hris.performance.per008;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;

/** PER-008 database fixtures, transactions, RLS helpers, and deterministic identities. */
final class Per008V20MigrationFixture {

    static final String SCHEMA = "hris_performance";
    static final String HISTORY = "flyway_performance_schema_history";
    static final List<String> CREATED_TABLES = List.of(
            "prf_feedback_requests",
            "prf_feedback_assignments",
            "prf_feedback_responses",
            "prf_feedback_release_snapshots");
    static final List<String> HARDENED_TABLES = List.of(
            "prf_cycles",
            "prf_cycle_versions",
            "prf_command_receipts",
            "prf_outbox_events");
    static final List<String> PROTECTED_TABLES = List.of(
            "prf_feedback_requests",
            "prf_feedback_assignments",
            "prf_feedback_responses",
            "prf_feedback_release_snapshots",
            "prf_cycles",
            "prf_cycle_versions",
            "prf_command_receipts",
            "prf_outbox_events");
    static final long TENANT_A = 8_081L;
    static final long TENANT_B = 8_082L;
    static final String HEX_A = "a".repeat(64);
    static final String HEX_B = "b".repeat(64);
    static final String HEX_C = "c".repeat(64);
    static final Instant OPENED = Instant.parse("2026-10-01T00:00:00Z");
    static final Instant CLOSED = Instant.parse("2026-11-01T00:00:00Z");

    static void seedRequestAuthority(
            JdbcTemplate jdbc, RequestInsertFixture fixture) {
        seedRequestAuthority(jdbc, fixture, HEX_B);
    }

    static void seedRequestAuthority(
            JdbcTemplate jdbc,
            RequestInsertFixture fixture,
            String receiptPopulationScopeDigest) {
        UUID requestId = fixture.requestId();
        long tenantId = fixture.tenantId();
        UUID cycleId = id(requestId + "-cycle");
        UUID cycleVersionId = id(requestId + "-cycle-version");
        UUID projectionHeadId = id(requestId + "-projection");
        UUID artifactId = id(requestId + "-artifact");
        UUID configurationId = id(requestId + "-configuration");
        UUID preparationReceipt = id(requestId + "-preparation-receipt");
        UUID finalizationReceipt = id(requestId + "-finalization-receipt");
        UUID platformReceipt = id(requestId + "-platform-receipt");
        Instant snapshotAsOf = OPENED.minusSeconds(60);

        jdbc.update("""
                INSERT INTO hris_performance.prf_cycles (
                    cycle_id, tenant_id, cycle_key, display_name, lifecycle_state,
                    aggregate_version, retention_policy_id, created_by, updated_by)
                VALUES (?, ?, ?, 'PER-008 request authority', 'DRAFT', 0, ?, 1, 1)
                """, cycleId, tenantId, "request-authority-" + tenantId,
                id(requestId + "-retention"));
        jdbc.update("""
                INSERT INTO hris_performance.prf_cycle_versions (
                    cycle_version_id, tenant_id, cycle_id, version_no, version_state,
                    aggregate_version, effective_from, timezone_id, policy_version_id,
                    content_hash, authored_by)
                VALUES (?, ?, ?, 1, 'DRAFT', 0, ?, 'UTC', ?, ?, 1)
                """, cycleVersionId, tenantId, cycleId, Timestamp.from(OPENED),
                id(requestId + "-cycle-policy"), HEX_A);
        jdbc.update("""
                INSERT INTO hris_performance.prf_workforce_projection_heads (
                    projection_head_id, tenant_id, snapshot_as_of, purpose_code,
                    projection_code, projection_state, current_local_revision,
                    current_owner_revision, current_content_hash)
                VALUES (?, ?, ?, 'HRIS_WORKFORCE_SNAPSHOT_BOOTSTRAP',
                        'PERFORMANCE_V1', 'READY', 1, 1, ?)
                """, projectionHeadId, tenantId, Timestamp.from(snapshotAsOf), HEX_B);
        jdbc.update("""
                INSERT INTO hris_performance.prf_workforce_projection_revisions (
                    projection_revision_id, tenant_id, projection_head_id,
                    local_revision, owner_revision, snapshot_as_of, purpose_code,
                    projection_code, owner_page_digest, content_hash, member_count)
                VALUES (?, ?, ?, 1, 1, ?, 'HRIS_WORKFORCE_SNAPSHOT_BOOTSTRAP',
                        'PERFORMANCE_V1', ?, ?, 1)
                """, id(requestId + "-projection-revision"), tenantId,
                projectionHeadId, Timestamp.from(snapshotAsOf), HEX_A, HEX_B);
        jdbc.update("""
                INSERT INTO hris_performance.prf_workforce_projection_members (
                    projection_member_id, tenant_id, projection_head_id, local_revision,
                    worker_public_id, assignment_public_id, workforce_status,
                    organization_public_id)
                VALUES (?, ?, ?, 1, ?, ?, 'ACTIVE', ?)
                """, id(requestId + "-projection-member"), tenantId, projectionHeadId,
                id(requestId + "-subject-worker"),
                id(requestId + "-subject-assignment"),
                id(requestId + "-organization"));
        jdbc.update("""
                INSERT INTO hris_performance.prf_per_artifact_versions (
                    tenant_id, public_id, artifact_type, schema_version,
                    artifact_revision, owner_aggregate_type,
                    owner_aggregate_public_id, canonical_content_digest,
                    encrypted_content, encryption_key_version_public_id,
                    effective_from, effective_to, created_at, created_by)
                VALUES (?, ?, 'PERFORMANCE_REVIEW_FORM', 1, 1,
                        'PERFORMANCE_CONFIGURATION_VERSION', ?, ?, ?, ?,
                        NULL, NULL, CURRENT_TIMESTAMP, ?)
                """, tenantId, artifactId, configurationId, HEX_A, new byte[29],
                id(requestId + "-artifact-key"), id(requestId + "-artifact-creator"));
        String payloadReference = "ref_per_artifact_" + artifactId;
        jdbc.update("""
                INSERT INTO hris_performance.prf_per004_artifact_preparations (
                    tenant_id, configuration_id, artifact_public_id, artifact_revision,
                    create_subject_principal_public_id, originating_action,
                    create_idempotency_key, create_request_digest, schema_key,
                    schema_version, payload_reference, payload_digest, scope_digest,
                    preparation_receipt_id, status, finalization_receipt_id,
                    platform_create_receipt_id, platform_version, resolved_at)
                VALUES (?, ?, ?, 1, ?, 'PER008_TEST', ?, ?,
                        'dwp.hris.performance.review-form.v1', 1, ?, ?, ?, ?,
                        'FINALIZED', ?, ?, 1, CURRENT_TIMESTAMP)
                """, tenantId, configurationId, artifactId,
                id(requestId + "-principal"), "prepare-" + requestId, HEX_C,
                payloadReference, HEX_A, HEX_B, preparationReceipt,
                finalizationReceipt, platformReceipt);
        jdbc.update("""
                INSERT INTO hris_performance.prf_per004_configuration_version_bindings (
                    tenant_id, configuration_id, platform_version, artifact_public_id,
                    schema_key, schema_version, payload_reference, payload_digest,
                    scope_digest, preparation_receipt_id, finalization_receipt_id,
                    platform_create_receipt_id)
                VALUES (?, ?, 1, ?, 'dwp.hris.performance.review-form.v1', 1,
                        ?, ?, ?, ?, ?, ?)
                """, tenantId, configurationId, artifactId, payloadReference,
                HEX_A, HEX_B, preparationReceipt, finalizationReceipt, platformReceipt);
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state)
                VALUES (?, ?, ?, 'FEEDBACK_REQUEST_CREATE', 'PEP-PER-015', 1, ?, ?, 0,
                        ?, ?, 1, 'PERFORMANCE_FEEDBACK', 1, 'ACCEPTED')
                """, fixture.createReceiptId(), tenantId, "create-" + requestId,
                id(requestId + "-principal"), requestId, HEX_A,
                receiptPopulationScopeDigest);
    }

    static void seedProjectionMember(
            JdbcTemplate jdbc,
            RequestInsertFixture fixture,
            UUID workerId,
            UUID assignmentId) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_workforce_projection_members (
                    projection_member_id, tenant_id, projection_head_id, local_revision,
                    worker_public_id, assignment_public_id, workforce_status,
                    organization_public_id)
                VALUES (?, ?, ?, 1, ?, ?, 'ACTIVE', ?)
                """, id(fixture.requestId() + "-member-" + assignmentId),
                fixture.tenantId(), id(fixture.requestId() + "-projection"),
                workerId, assignmentId, id(fixture.requestId() + "-organization"));
    }

    static void insertInitialAssignment(
            JdbcTemplate jdbc,
            RequestInsertFixture fixture,
            UUID providerWorkerId,
            UUID providerAssignmentId,
            UUID assignmentId,
            Instant createdAt) {
        Timestamp timestamp = createdAt == null ? null : Timestamp.from(createdAt);
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_assignments (
                    tenant_id, public_id, feedback_request_public_id,
                    provider_worker_public_id, provider_assignment_public_id,
                    workforce_projection_head_id, workforce_local_revision,
                    relationship_type, assignment_state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, 'PEER', 'ASSIGNED',
                        COALESCE(CAST(? AS TIMESTAMPTZ), CURRENT_TIMESTAMP),
                        COALESCE(CAST(? AS TIMESTAMPTZ), CURRENT_TIMESTAMP))
                """, fixture.tenantId(), assignmentId, fixture.requestId(),
                providerWorkerId, providerAssignmentId,
                id(fixture.requestId() + "-projection"), timestamp, timestamp);
    }

    static void completeCreateReceipt(
            JdbcTemplate jdbc, RequestInsertFixture fixture) {
        assertThat(jdbc.update("""
                UPDATE hris_performance.prf_command_receipts receipt
                   SET applied_aggregate_version = 1,
                       result_ref = ?,
                       receipt_state = 'SUCCEEDED',
                       completed_at = (
                           SELECT request.created_at
                             FROM hris_performance.prf_feedback_requests request
                            WHERE request.tenant_id = receipt.tenant_id
                              AND request.public_id = receipt.aggregate_id)
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, fixture.requestId(), fixture.tenantId(), fixture.createReceiptId()))
                .isEqualTo(1);
    }

    static int requestCount(JdbcTemplate jdbc, RequestInsertFixture fixture) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_feedback_requests
                 WHERE tenant_id = ? AND public_id = ?
                """, Integer.class, fixture.tenantId(), fixture.requestId());
    }

    static RequestInsertFixture requestInsertFixture(long tenantId, String key) {
        return new RequestInsertFixture(
                tenantId, id("request-insert-" + key), id("request-create-" + key));
    }

    static void seedProtectedRowsForBothTenants(DataSource source) throws Exception {
        withReplica(source, jdbc -> {
            for (long tenantId : List.of(TENANT_A, TENANT_B)) {
                BaseRows base = baseRows(tenantId, "rls");
                insertBaseRows(jdbc, base);
                UUID request = id("rls-request-" + tenantId);
                UUID assignment = id("rls-assignment-" + tenantId);
                UUID response = id("rls-response-" + tenantId);
                UUID snapshot = id("rls-snapshot-" + tenantId);
                insertRequest(jdbc, tenantId, request, 1, "DRAFT",
                        base.receiptId(), null, null, null, 1, OPENED, CLOSED);
                insertAssignment(jdbc, tenantId, request, assignment, "ASSIGNED", null);
                insertResponse(jdbc, tenantId, request, assignment,
                        base.receiptId(), OPENED.plusSeconds(1), response);
                insertSnapshot(jdbc, tenantId, request, base.receiptId(),
                        1, CLOSED.plusSeconds(1), snapshot);
            }
        });
    }

    static void insertBaseRows(JdbcTemplate jdbc, BaseRows rows) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_cycles (
                    cycle_id, tenant_id, cycle_key, display_name, lifecycle_state,
                    aggregate_version, retention_policy_id, created_by, updated_by)
                VALUES (?, ?, ?, 'PER-008 migration fixture', 'DRAFT', 0, ?, 1, 1)
                """, rows.cycleId(), rows.tenantId(), rows.key(), id(rows.key() + "-retention"));
        jdbc.update("""
                INSERT INTO hris_performance.prf_cycle_versions (
                    cycle_version_id, tenant_id, cycle_id, version_no, version_state,
                    aggregate_version, effective_from, timezone_id, policy_version_id,
                    content_hash, authored_by)
                VALUES (?, ?, ?, 1, 'DRAFT', 0, ?, 'UTC', ?, ?, 1)
                """, rows.cycleVersionId(), rows.tenantId(), rows.cycleId(),
                Timestamp.from(OPENED), id(rows.key() + "-policy"), HEX_A);
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state)
                VALUES (?, ?, ?, 'PER008_TEST', 'PER008_TEST', 1, ?, ?, 0,
                        ?, ?, 1, 'PERFORMANCE_FEEDBACK', 1, 'ACCEPTED')
                """, rows.receiptId(), rows.tenantId(), rows.key() + "-receipt",
                id(rows.key() + "-principal"), rows.cycleId(), HEX_A, HEX_B);
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload)
                VALUES (?, ?, ?, 'PER008_TEST', ?, 1, 'PER008.Test.v1', '{}'::jsonb)
                """, rows.eventId(), rows.tenantId(), rows.receiptId(), rows.cycleId());
    }

    static void insertRequest(
            JdbcTemplate jdbc,
            long tenantId,
            UUID requestId,
            long aggregateVersion,
            String state,
            UUID createReceipt,
            UUID openReceipt,
            UUID closeReceipt,
            UUID aggregationReceipt,
            int effectiveThreshold,
            Instant opensAt,
            Instant closesAt) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_requests (
                    tenant_id, public_id, aggregate_version, cycle_version_id,
                    subject_worker_public_id, subject_assignment_public_id,
                    workforce_projection_head_id, workforce_local_revision,
                    workforce_owner_revision, workforce_snapshot_as_of,
                    workforce_owner_page_digest, workforce_content_hash,
                    template_artifact_public_id, template_configuration_id,
                    template_platform_version, template_schema_key,
                    template_payload_digest, template_scope_digest, template_content_hash,
                    request_title, authorization_principal_public_id,
                    create_command_receipt_id, open_command_receipt_id,
                    close_command_receipt_id, aggregation_command_receipt_id,
                    request_state, anonymity_mode, requested_minimum_release_count,
                    privacy_minimum_release_count, effective_minimum_release_count,
                    privacy_policy_public_id, threshold_policy_revision,
                    threshold_policy_digest, audience_policy_public_id,
                    audience_policy_revision, audience_policy_digest, opens_at, closes_at,
                    audience_scope_digest, execution_binding_digest,
                    scope_decision_revision, authorization_decision_revision, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?, ?, ?, ?, 1,
                        'dwp.hris.performance.review-form.v1', ?, ?, ?,
                        'PER-008 request', ?, ?, ?, ?, ?, ?, 'IDENTIFIED', ?, ?, ?,
                        ?, 'threshold-v1', ?, ?, 'audience-v1', ?, ?, ?, ?, ?,
                        'scope-v1', 'authorization-v1', ?)
                """,
                tenantId, requestId, aggregateVersion, id(requestId + "-cycle-version"),
                id(requestId + "-subject-worker"), id(requestId + "-subject-assignment"),
                id(requestId + "-projection"), Timestamp.from(opensAt.minusSeconds(60)),
                HEX_A, HEX_B, id(requestId + "-artifact"), id(requestId + "-configuration"),
                HEX_A, HEX_B, HEX_A, id(requestId + "-principal"), createReceipt,
                openReceipt, closeReceipt, aggregationReceipt,
                state, effectiveThreshold, effectiveThreshold, effectiveThreshold,
                id(requestId + "-privacy-policy"), HEX_C,
                id(requestId + "-audience-policy"), HEX_B,
                Timestamp.from(opensAt), Timestamp.from(closesAt), HEX_A, HEX_B,
                id(requestId + "-creator"));
    }

    static void insertAssignment(
            JdbcTemplate jdbc,
            long tenantId,
            UUID requestId,
            UUID assignmentId,
            String state,
            Instant submittedAt) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_assignments (
                    tenant_id, public_id, feedback_request_public_id,
                    provider_worker_public_id, provider_assignment_public_id,
                    workforce_projection_head_id, workforce_local_revision,
                    relationship_type, assignment_state, submitted_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, 'PEER', ?, ?)
                """, tenantId, assignmentId, requestId,
                id(assignmentId + "-provider-worker"),
                id(assignmentId + "-provider-assignment"),
                id(requestId + "-projection"), state,
                submittedAt == null ? null : Timestamp.from(submittedAt));
    }

    static void insertResponse(
            JdbcTemplate jdbc,
            long tenantId,
            UUID requestId,
            UUID assignmentId,
            UUID receiptId,
            Instant submittedAt,
            UUID responseId) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_responses (
                    tenant_id, public_id, feedback_request_public_id,
                    feedback_assignment_public_id, response_schema_key,
                    template_content_hash, submit_command_receipt_id,
                    encryption_envelope_version, encryption_algorithm, encrypted_content,
                    encryption_nonce, authentication_tag, encryption_key_version_public_id,
                    canonical_content_hmac, digest_key_version_public_id, submitted_at)
                VALUES (?, ?, ?, ?, 'PerformanceFeedbackResponse.v1', ?, ?, 1,
                        'AES_256_GCM', ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, responseId, requestId, assignmentId, HEX_A, receiptId,
                new byte[29], new byte[12], new byte[16], id(responseId + "-key"),
                HEX_B, id(responseId + "-digest-key"), Timestamp.from(submittedAt));
    }

    static void submitAssignment(
            JdbcTemplate jdbc, long tenantId, UUID assignmentId, Instant submittedAt) {
        assertThat(jdbc.update("""
                UPDATE hris_performance.prf_feedback_assignments
                   SET assignment_state = 'SUBMITTED', submitted_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND public_id = ?
                """, Timestamp.from(submittedAt), Timestamp.from(submittedAt),
                tenantId, assignmentId)).isEqualTo(1);
    }

    static void insertSubmissionEvent(
            JdbcTemplate jdbc,
            long tenantId,
            UUID eventId,
            UUID receiptId,
            UUID requestId,
            UUID assignmentId,
            long aggregateVersion,
            Instant submittedAt,
            boolean addForbiddenField) {
        String payload = addForbiddenField
                ? "jsonb_build_object('feedbackRequestId', ?::text, "
                + "'feedbackAssignmentId', ?::text, "
                + "'submittedAt', ?::timestamptz, 'extra', true)"
                : "jsonb_build_object('feedbackRequestId', ?::text, "
                + "'feedbackAssignmentId', ?::text, 'submittedAt', ?::timestamptz)";
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload)
                VALUES (?, ?, ?, 'PERFORMANCE_FEEDBACK_ASSIGNMENT', ?, ?,
                        'PerformanceFeedbackSubmitted.v1', %s)
                """.formatted(payload), eventId, tenantId, receiptId, assignmentId,
                aggregateVersion, requestId.toString(), assignmentId.toString(),
                Timestamp.from(submittedAt));
    }

    static void insertSnapshot(
            JdbcTemplate jdbc,
            long tenantId,
            UUID requestId,
            UUID receiptId,
            int submittedCount,
            Instant aggregatedAt,
            UUID snapshotId) {
        insertSnapshot(jdbc, tenantId, requestId, receiptId, submittedCount,
                HEX_A, "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, snapshotId);
    }

    static void insertSnapshot(
            JdbcTemplate jdbc,
            long tenantId,
            UUID requestId,
            UUID receiptId,
            int submittedCount,
            String sourceSetDigest,
            String algorithm,
            Instant aggregatedAt,
            UUID snapshotId) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_feedback_release_snapshots (
                    tenant_id, public_id, feedback_request_public_id,
                    aggregate_schema_key, threshold_policy_revision,
                    privacy_policy_public_id, threshold_policy_digest,
                    distinct_submitted_count, source_set_digest,
                    aggregation_algorithm_version, aggregation_command_receipt_id,
                    encryption_envelope_version, encryption_algorithm, encrypted_content,
                    encryption_nonce, authentication_tag, encryption_key_version_public_id,
                    canonical_content_hmac, digest_key_version_public_id, aggregated_at)
                VALUES (?, ?, ?, 'PerformanceFeedbackAggregate.v1', 'threshold-v1',
                        ?, ?, ?, ?, ?, ?, 1, 'AES_256_GCM', ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, snapshotId, requestId, id(requestId + "-privacy-policy"),
                HEX_C, submittedCount, sourceSetDigest, algorithm, receiptId,
                new byte[29], new byte[12],
                new byte[16], id(snapshotId + "-key"), HEX_B,
                id(snapshotId + "-digest-key"), Timestamp.from(aggregatedAt));
    }

    static String sourceSetDigest(
            JdbcTemplate jdbc, long tenantId, UUID requestId) {
        return jdbc.queryForObject("""
                SELECT encode(sha256(convert_to(COALESCE(string_agg(
                           response.public_id::TEXT || ':'
                           || response.feedback_assignment_public_id::TEXT || ':'
                           || response.template_content_hash || ':'
                           || response.canonical_content_hmac || ':'
                           || response.digest_key_version_public_id::TEXT,
                           '|' ORDER BY response.public_id::TEXT), ''), 'UTF8')), 'hex')
                  FROM hris_performance.prf_feedback_assignments assignment
                  JOIN hris_performance.prf_feedback_responses response
                    ON response.tenant_id = assignment.tenant_id
                   AND response.feedback_request_public_id
                       = assignment.feedback_request_public_id
                   AND response.feedback_assignment_public_id = assignment.public_id
                 WHERE assignment.tenant_id = ?
                   AND assignment.feedback_request_public_id = ?
                   AND assignment.assignment_state = 'SUBMITTED'
                """, String.class, tenantId, requestId);
    }

    static void insertSucceededReceipt(
            JdbcTemplate jdbc,
            long tenantId,
            UUID receiptId,
            String action,
            UUID subjectPrincipal,
            UUID aggregateId,
            long expectedVersion,
            long appliedVersion,
            UUID resultRef,
            Instant completedAt) {
        boolean lifecycle = action.startsWith(
                "owner.people.performance-feedback.lifecycle.v1:");
        String commandType = lifecycle ? "FEEDBACK_REQUEST_LIFECYCLE"
                : "PEP-PER-015".equals(action) ? "FEEDBACK_REQUEST_CREATE"
                : "PEP-PER-022".equals(action) ? "FEEDBACK_RESPONSE_SUBMIT"
                : "PERFORMANCE_FEEDBACK";
        String purpose = lifecycle
                ? "PERFORMANCE_FEEDBACK_LIFECYCLE" : "PERFORMANCE_FEEDBACK";
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, applied_aggregate_version,
                    request_hash, population_scope_digest, field_policy_revision,
                    purpose_code, authorization_revision, receipt_state, result_ref,
                    completed_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 1,
                        ?, 1, 'SUCCEEDED', ?, ?)
                """, receiptId, tenantId, "per008-" + receiptId, commandType, action,
                subjectPrincipal, aggregateId, expectedVersion, appliedVersion,
                HEX_A, HEX_B, purpose, resultRef, Timestamp.from(completedAt));
    }

    static void insertSucceededReceipt(
            JdbcTemplate jdbc,
            long tenantId,
            UUID receiptId,
            String action,
            UUID aggregateId,
            long expectedVersion,
            long appliedVersion,
            UUID resultRef,
            Instant completedAt) {
        insertSucceededReceipt(jdbc, tenantId, receiptId, action,
                id(receiptId + "-principal"), aggregateId, expectedVersion,
                appliedVersion, resultRef, completedAt);
    }

    static void assertSafeRuntimeRoleAndAcl(JdbcTemplate jdbc, String role) {
        Map<String, Object> attributes = jdbc.queryForMap("""
                SELECT rolsuper, rolcreatedb, rolcreaterole, rolinherit, rolbypassrls
                  FROM pg_catalog.pg_roles WHERE rolname = ?
                """, role);
        assertThat(attributes.values()).containsOnly(false);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_catalog.pg_class relation_record
                  JOIN pg_catalog.pg_namespace namespace_record
                    ON namespace_record.oid = relation_record.relnamespace
                  JOIN pg_catalog.pg_roles owner_role
                    ON owner_role.oid = relation_record.relowner
                 WHERE namespace_record.nspname = 'hris_performance'
                   AND relation_record.relname IN (%s)
                   AND owner_role.rolname = ?
                """.formatted(sqlNames(PROTECTED_TABLES)), Integer.class, role))
                .isZero();
        for (String table : PROTECTED_TABLES) {
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'SELECT')",
                    Boolean.class, role, qualified(table))).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'TRUNCATE')",
                    Boolean.class, role, qualified(table))).isFalse();
        }
        for (String table : CREATED_TABLES) {
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'INSERT')",
                    Boolean.class, role, qualified(table))).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'DELETE') "
                            + "OR has_table_privilege(?, ?, 'TRUNCATE')",
                    Boolean.class, role, qualified(table), role, qualified(table))).isFalse();
        }
        for (String table : List.of(
                "prf_feedback_responses", "prf_feedback_release_snapshots")) {
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'UPDATE')",
                    Boolean.class, role, qualified(table))).isFalse();
        }
        for (String table : List.of(
                "prf_feedback_requests", "prf_feedback_assignments")) {
            assertThat(jdbc.queryForObject(
                    "SELECT has_table_privilege(?, ?, 'UPDATE')",
                    Boolean.class, role, qualified(table))).isTrue();
        }
        for (String table : HARDENED_TABLES) {
            assertThat(jdbc.queryForObject("""
                    SELECT has_table_privilege(?, ?, 'INSERT')
                       AND has_table_privilege(?, ?, 'UPDATE')
                       AND has_table_privilege(?, ?, 'DELETE')
                    """, Boolean.class,
                    role, qualified(table), role, qualified(table),
                    role, qualified(table))).isTrue();
        }
    }

    static int countAs(
            DataSource source, String role, String table, Long tenantId) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE " + role);
            }
            if (tenantId != null) {
                tenant(connection, tenantId);
            }
            try (Statement query = connection.createStatement();
                 var rows = query.executeQuery(
                         "SELECT COUNT(*) FROM hris_performance." + table)) {
                rows.next();
                return rows.getInt(1);
            } finally {
                connection.rollback();
            }
        }
    }

    static int updateAs(
            DataSource source, String role, long tenantId, String sql) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL ROLE " + role);
            }
            tenant(connection, tenantId);
            try (Statement update = connection.createStatement()) {
                return update.executeUpdate(sql);
            } finally {
                connection.rollback();
            }
        }
    }

    static void tenant(Connection connection, long tenantId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('dwp.tenant_id', ?, TRUE)")) {
            statement.setString(1, Long.toString(tenantId));
            statement.execute();
        }
    }

    static void withReplica(DataSource source, Consumer<JdbcTemplate> work)
            throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = 'replica'");
            }
            JdbcTemplate scoped = new JdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            try {
                work.accept(scoped);
                connection.commit();
            } catch (RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    static void inTransaction(DataSource source, Consumer<JdbcTemplate> work)
            throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            JdbcTemplate transaction = new JdbcTemplate(
                    new SingleConnectionDataSource(connection, true));
            try {
                work.accept(transaction);
                connection.commit();
            } catch (RuntimeException | java.sql.SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    static Map<String, Integer> historyChecksums(JdbcTemplate jdbc) {
        Map<String, Integer> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT version, checksum
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE success AND version IS NOT NULL
                 ORDER BY installed_rank
                """).forEach(row -> result.put(
                String.valueOf(row.get("version")),
                ((Number) row.get("checksum")).intValue()));
        return Map.copyOf(result);
    }

    static String sqlNames(List<String> values) {
        return values.stream()
                .map(value -> "'" + value + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    static BaseRows baseRows(long tenantId, String prefix) {
        String key = prefix + "-" + tenantId;
        return new BaseRows(
                tenantId,
                key,
                id(key + "-cycle"),
                id(key + "-cycle-version"),
                id(key + "-receipt"),
                id(key + "-event"));
    }

    static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static String qualified(String table) {
        return "hris_performance." + table;
    }

    static void reset(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        jdbc.execute("CREATE SCHEMA hris_performance");
    }

    static Flyway flyway(DataSource source, String target) {
        var configuration = Flyway.configure(Per008V20MigrationFixture.class.getClassLoader())
                .dataSource(source)
                .locations("classpath:db/performance-migration")
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .table(HISTORY)
                .validateOnMigrate(true)
                .outOfOrder(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    record BaseRows(
            long tenantId,
            String key,
            UUID cycleId,
            UUID cycleVersionId,
            UUID receiptId,
            UUID eventId) {
    }

    record RequestInsertFixture(
            long tenantId,
            UUID requestId,
            UUID createReceiptId) {
    }
}
