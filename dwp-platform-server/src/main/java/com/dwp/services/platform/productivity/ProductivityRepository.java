package com.dwp.services.platform.productivity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.productivity.ProductivityTypes.*;

@Repository
public class ProductivityRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final String CONNECTOR_COLUMNS = """
            productivity_connector_id, tenant_id, connector_key, display_name,
            provider_type, auth_mode, provider_tenant_id, client_id, credential_reference,
            redirect_uri, requested_scopes, capabilities, lifecycle_state, health_state,
            policy_state, safe_error_code, last_configuration_check_at,
            last_successful_sync_at, consecutive_failures, version
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ProductivityRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<ConnectorRecord> connectors(Long tenantId) {
        return jdbc.query(
                "SELECT " + CONNECTOR_COLUMNS + " FROM int_productivity_connectors "
                        + "WHERE tenant_id = :tenantId AND lifecycle_state <> 'RETIRED' "
                        + "ORDER BY display_name, connector_key",
                new MapSqlParameterSource("tenantId", tenantId),
                this::connector);
    }

    public Optional<ConnectorRecord> connector(Long tenantId, UUID connectorId) {
        return jdbc.query(
                "SELECT " + CONNECTOR_COLUMNS + " FROM int_productivity_connectors "
                        + "WHERE tenant_id = :tenantId AND productivity_connector_id = :connectorId",
                new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("connectorId", connectorId),
                this::connector).stream().findFirst();
    }

    public ConnectorRecord createConnector(Long tenantId, Long actorId, ConnectorDraft draft) {
        UUID connectorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO int_productivity_connectors (
                    productivity_connector_id, tenant_id, connector_key, display_name,
                    provider_type, auth_mode, provider_tenant_id, client_id,
                    credential_reference, redirect_uri, requested_scopes, capabilities,
                    lifecycle_state, health_state, policy_state, safe_error_code,
                    created_by, updated_by)
                VALUES (
                    :connectorId, :tenantId, :connectorKey, :displayName,
                    :providerType, :authMode, :providerTenantId, :clientId,
                    :credentialReference, :redirectUri, CAST(:requestedScopes AS jsonb),
                    CAST(:capabilities AS jsonb), 'DRAFT', 'CONFIGURATION_REQUIRED',
                    :policyState, 'CONFIGURATION_NOT_CHECKED', :actorId, :actorId)
                """, connectorParameters(tenantId, actorId, connectorId, draft));
        return connector(tenantId, connectorId).orElseThrow();
    }

    public Optional<ConnectorRecord> updateConnector(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            long expectedVersion,
            ConnectorDraft draft) {
        int updated = jdbc.update("""
                UPDATE int_productivity_connectors SET
                    connector_key = :connectorKey,
                    display_name = :displayName,
                    provider_type = :providerType,
                    auth_mode = :authMode,
                    provider_tenant_id = :providerTenantId,
                    client_id = :clientId,
                    credential_reference = :credentialReference,
                    redirect_uri = :redirectUri,
                    requested_scopes = CAST(:requestedScopes AS jsonb),
                    capabilities = CAST(:capabilities AS jsonb),
                    policy_state = :policyState,
                    health_state = 'CONFIGURATION_REQUIRED',
                    safe_error_code = 'CONFIGURATION_CHANGED',
                    last_configuration_check_at = NULL,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = :actorId
                WHERE tenant_id = :tenantId
                  AND productivity_connector_id = :connectorId
                  AND version = :expectedVersion
                  AND lifecycle_state IN ('DRAFT', 'SUSPENDED')
                """, connectorParameters(tenantId, actorId, connectorId, draft)
                .addValue("expectedVersion", expectedVersion));
        return updated == 1 ? connector(tenantId, connectorId) : Optional.empty();
    }

    public void configurationResult(
            Long tenantId,
            UUID connectorId,
            ConnectorHealth health,
            String errorCode,
            Instant checkedAt) {
        jdbc.update("""
                UPDATE int_productivity_connectors SET
                    health_state = :health,
                    safe_error_code = :errorCode,
                    last_configuration_check_at = :checkedAt,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenantId
                  AND productivity_connector_id = :connectorId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("connectorId", connectorId)
                .addValue("health", health.name())
                .addValue("errorCode", errorCode)
                .addValue("checkedAt", timestamp(checkedAt)));
    }

    public boolean changeLifecycle(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            long expectedVersion,
            ConnectorLifecycle lifecycle) {
        return jdbc.update("""
                UPDATE int_productivity_connectors SET
                    lifecycle_state = :lifecycle,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = :actorId
                WHERE tenant_id = :tenantId
                  AND productivity_connector_id = :connectorId
                  AND version = :expectedVersion
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("connectorId", connectorId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("lifecycle", lifecycle.name())) == 1;
    }

    public List<SubjectRecord> subjects(Long tenantId, int limit) {
        return jdbc.query("""
                SELECT productivity_subject_id, tenant_id, productivity_connector_id,
                       user_id, provider_subject_ref_hash, encrypted_refresh_token,
                       granted_scopes, consent_state, token_expires_at,
                       last_successful_sync_at, last_error_code, version
                  FROM int_productivity_subjects
                 WHERE tenant_id = :tenantId
                 ORDER BY updated_at DESC
                 LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("limit", Math.min(500, Math.max(1, limit))), this::subject);
    }

    public Optional<SubjectRecord> subject(Long tenantId, UUID connectorId, Long userId) {
        return jdbc.query("""
                SELECT productivity_subject_id, tenant_id, productivity_connector_id,
                       user_id, provider_subject_ref_hash, encrypted_refresh_token,
                       granted_scopes, consent_state, token_expires_at,
                       last_successful_sync_at, last_error_code, version
                  FROM int_productivity_subjects
                 WHERE tenant_id = :tenantId
                   AND productivity_connector_id = :connectorId
                   AND user_id = :userId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("connectorId", connectorId)
                .addValue("userId", userId), this::subject).stream().findFirst();
    }

    public SubjectRecord connectSubject(
            Long tenantId,
            Long userId,
            UUID connectorId,
            String providerSubjectHash,
            String encryptedRefreshToken,
            List<String> grantedScopes,
            Instant tokenExpiresAt) {
        UUID subjectId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO int_productivity_subjects (
                    productivity_subject_id, tenant_id, productivity_connector_id,
                    user_id, provider_subject_ref_hash, encrypted_refresh_token,
                    granted_scopes, consent_state, token_expires_at, created_by, updated_by)
                VALUES (
                    :subjectId, :tenantId, :connectorId, :userId, :providerSubjectHash,
                    :refreshToken, CAST(:grantedScopes AS jsonb), 'CONNECTED',
                    :tokenExpiresAt, :userId, :userId)
                ON CONFLICT (tenant_id, productivity_connector_id, user_id) DO UPDATE SET
                    provider_subject_ref_hash = EXCLUDED.provider_subject_ref_hash,
                    encrypted_refresh_token = EXCLUDED.encrypted_refresh_token,
                    granted_scopes = EXCLUDED.granted_scopes,
                    consent_state = 'CONNECTED',
                    token_expires_at = EXCLUDED.token_expires_at,
                    last_error_code = NULL,
                    version = int_productivity_subjects.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                """, new MapSqlParameterSource("subjectId", subjectId)
                .addValue("tenantId", tenantId)
                .addValue("connectorId", connectorId)
                .addValue("userId", userId)
                .addValue("providerSubjectHash", providerSubjectHash)
                .addValue("refreshToken", encryptedRefreshToken)
                .addValue("grantedScopes", json(grantedScopes))
                .addValue("tokenExpiresAt", timestamp(tokenExpiresAt)));
        return subject(tenantId, connectorId, userId).orElseThrow();
    }

    public void updateSubjectToken(
            UUID subjectId,
            String encryptedRefreshToken,
            Instant tokenExpiresAt,
            List<String> grantedScopes) {
        jdbc.update("""
                UPDATE int_productivity_subjects SET
                    encrypted_refresh_token = COALESCE(:refreshToken, encrypted_refresh_token),
                    token_expires_at = :tokenExpiresAt,
                    granted_scopes = CAST(:grantedScopes AS jsonb),
                    consent_state = 'CONNECTED',
                    last_error_code = NULL,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE productivity_subject_id = :subjectId
                """, new MapSqlParameterSource("subjectId", subjectId)
                .addValue("refreshToken", encryptedRefreshToken)
                .addValue("tokenExpiresAt", timestamp(tokenExpiresAt))
                .addValue("grantedScopes", json(grantedScopes)));
    }

    public void subjectFailure(UUID subjectId, ConsentState state, String errorCode) {
        jdbc.update("""
                UPDATE int_productivity_subjects SET
                    consent_state = :state,
                    last_error_code = :errorCode,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE productivity_subject_id = :subjectId
                """, new MapSqlParameterSource("subjectId", subjectId)
                .addValue("state", state.name())
                .addValue("errorCode", errorCode));
    }

    public OAuthTransaction createOAuthTransaction(
            Long tenantId,
            Long userId,
            UUID connectorId,
            String stateHash,
            String encryptedPkce,
            Instant expiresAt) {
        UUID transactionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO int_productivity_oauth_transactions (
                    oauth_transaction_id, tenant_id, productivity_connector_id,
                    user_id, state_hash, encrypted_pkce_verifier, expires_at)
                VALUES (
                    :transactionId, :tenantId, :connectorId, :userId,
                    :stateHash, :encryptedPkce, :expiresAt)
                """, new MapSqlParameterSource("transactionId", transactionId)
                .addValue("tenantId", tenantId)
                .addValue("connectorId", connectorId)
                .addValue("userId", userId)
                .addValue("stateHash", stateHash)
                .addValue("encryptedPkce", encryptedPkce)
                .addValue("expiresAt", timestamp(expiresAt)));
        return new OAuthTransaction(transactionId, tenantId, connectorId, userId,
                stateHash, encryptedPkce, expiresAt);
    }

    public Optional<OAuthTransaction> consumeOAuthTransaction(
            Long tenantId,
            Long userId,
            String stateHash,
            Instant now) {
        List<OAuthTransaction> matches = jdbc.query("""
                UPDATE int_productivity_oauth_transactions SET consumed_at = :now
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND state_hash = :stateHash
                   AND consumed_at IS NULL
                   AND expires_at > :now
                RETURNING oauth_transaction_id, tenant_id, productivity_connector_id,
                          user_id, state_hash, encrypted_pkce_verifier, expires_at
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("userId", userId)
                .addValue("stateHash", stateHash)
                .addValue("now", timestamp(now)), (rs, row) -> new OAuthTransaction(
                        rs.getObject("oauth_transaction_id", UUID.class),
                        rs.getLong("tenant_id"),
                        rs.getObject("productivity_connector_id", UUID.class),
                        rs.getLong("user_id"),
                        rs.getString("state_hash"),
                        rs.getString("encrypted_pkce_verifier"),
                        instant(rs, "expires_at")));
        return matches.stream().findFirst();
    }

    public StreamRecord ensureStream(
            Long tenantId,
            Long userId,
            UUID subjectId,
            ResourceKind resourceKind,
            Instant windowStart,
            Instant windowEnd) {
        UUID streamId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO int_productivity_sync_streams (
                    productivity_sync_stream_id, tenant_id, productivity_subject_id,
                    resource_kind, calendar_window_start, calendar_window_end,
                    created_by, updated_by)
                VALUES (
                    :streamId, :tenantId, :subjectId, :resourceKind,
                    :windowStart, :windowEnd, :userId, :userId)
                ON CONFLICT (tenant_id, productivity_subject_id, resource_kind) DO NOTHING
                """, new MapSqlParameterSource("streamId", streamId)
                .addValue("tenantId", tenantId)
                .addValue("subjectId", subjectId)
                .addValue("resourceKind", resourceKind.name())
                .addValue("windowStart", timestamp(windowStart))
                .addValue("windowEnd", timestamp(windowEnd))
                .addValue("userId", userId));
        return stream(tenantId, subjectId, resourceKind).orElseThrow();
    }

    public Optional<StreamRecord> stream(Long tenantId, UUID subjectId, ResourceKind resourceKind) {
        return jdbc.query("""
                SELECT productivity_sync_stream_id, tenant_id, productivity_subject_id,
                       resource_kind, encrypted_cursor, cursor_fingerprint,
                       calendar_window_start, calendar_window_end, stream_state,
                       last_attempt_at, last_success_at, last_error_code, version
                  FROM int_productivity_sync_streams
                 WHERE tenant_id = :tenantId
                   AND productivity_subject_id = :subjectId
                   AND resource_kind = :resourceKind
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("subjectId", subjectId)
                .addValue("resourceKind", resourceKind.name()), this::stream).stream().findFirst();
    }

    public boolean startStream(UUID streamId, boolean reset) {
        return jdbc.update("""
                UPDATE int_productivity_sync_streams SET
                    stream_state = 'SYNCING',
                    encrypted_cursor = CASE WHEN :reset THEN NULL ELSE encrypted_cursor END,
                    cursor_fingerprint = CASE WHEN :reset THEN NULL ELSE cursor_fingerprint END,
                    last_attempt_at = CURRENT_TIMESTAMP,
                    last_error_code = NULL,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE productivity_sync_stream_id = :streamId
                  AND stream_state <> 'SYNCING'
                """, new MapSqlParameterSource("streamId", streamId).addValue("reset", reset)) > 0;
    }

    public void completeStream(
            UUID streamId,
            String encryptedCursor,
            String cursorFingerprint,
            StreamState state,
            String errorCode,
            boolean success) {
        jdbc.update("""
                UPDATE int_productivity_sync_streams SET
                    encrypted_cursor = COALESCE(:cursor, encrypted_cursor),
                    cursor_fingerprint = COALESCE(:cursorFingerprint, cursor_fingerprint),
                    stream_state = :state,
                    last_success_at = CASE WHEN :success THEN CURRENT_TIMESTAMP ELSE last_success_at END,
                    last_error_code = :errorCode,
                    version = version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE productivity_sync_stream_id = :streamId
                """, new MapSqlParameterSource("streamId", streamId)
                .addValue("cursor", encryptedCursor)
                .addValue("cursorFingerprint", cursorFingerprint)
                .addValue("state", state.name())
                .addValue("errorCode", errorCode)
                .addValue("success", success));
    }

    public UUID startRun(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            UUID subjectId,
            ResourceKind resourceKind,
            SyncMode syncMode,
            String correlationId,
            Instant startedAt) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO int_productivity_sync_runs (
                    productivity_sync_run_id, tenant_id, productivity_connector_id,
                    productivity_subject_id, resource_kind, sync_mode, run_state,
                    started_at, correlation_id, initiated_by)
                VALUES (
                    :runId, :tenantId, :connectorId, :subjectId, :resourceKind,
                    :syncMode, 'RUNNING', :startedAt, :correlationId, :actorId)
                """, new MapSqlParameterSource("runId", runId)
                .addValue("tenantId", tenantId)
                .addValue("connectorId", connectorId)
                .addValue("subjectId", subjectId)
                .addValue("resourceKind", resourceKind.name())
                .addValue("syncMode", syncMode.name())
                .addValue("startedAt", timestamp(startedAt))
                .addValue("correlationId", correlationId)
                .addValue("actorId", actorId));
        return runId;
    }

    public void completeRun(
            UUID runId,
            SyncRunState state,
            int upserts,
            int deletes,
            int skips,
            int errors,
            boolean partial,
            Instant retryAfterAt,
            String errorCode) {
        jdbc.update("""
                UPDATE int_productivity_sync_runs SET
                    run_state = :state,
                    completed_at = CURRENT_TIMESTAMP,
                    upsert_count = :upserts,
                    delete_count = :deletes,
                    skip_count = :skips,
                    error_count = :errors,
                    partial_result = :partial,
                    retry_after_at = :retryAfterAt,
                    safe_error_code = :errorCode
                WHERE productivity_sync_run_id = :runId
                """, new MapSqlParameterSource("runId", runId)
                .addValue("state", state.name())
                .addValue("upserts", upserts)
                .addValue("deletes", deletes)
                .addValue("skips", skips)
                .addValue("errors", errors)
                .addValue("partial", partial)
                .addValue("retryAfterAt", timestamp(retryAfterAt))
                .addValue("errorCode", errorCode));
    }

    public void addRunError(
            Long tenantId,
            UUID runId,
            String itemReferenceHash,
            String errorCode,
            String safeMessage,
            boolean retryable) {
        jdbc.update("""
                INSERT INTO int_productivity_sync_errors (
                    productivity_sync_error_id, productivity_sync_run_id, tenant_id,
                    item_reference_hash, error_code, safe_message, retryable)
                VALUES (
                    :errorId, :runId, :tenantId, :itemReferenceHash,
                    :errorCode, :safeMessage, :retryable)
                """, new MapSqlParameterSource("errorId", UUID.randomUUID())
                .addValue("runId", runId)
                .addValue("tenantId", tenantId)
                .addValue("itemReferenceHash", itemReferenceHash)
                .addValue("errorCode", errorCode)
                .addValue("safeMessage", safeMessage)
                .addValue("retryable", retryable));
    }

    public void upsertItem(ItemRecord item) {
        jdbc.update("""
                INSERT INTO wrk_productivity_items (
                    productivity_item_id, tenant_id, user_id, productivity_connector_id,
                    resource_kind, source_id_hash, encrypted_title, encrypted_source_url,
                    occurred_at, ends_at, importance, read_state, cancelled,
                    classification, permission_reference_hash, source_version)
                VALUES (
                    :itemId, :tenantId, :userId, :connectorId, :resourceKind,
                    :sourceIdHash, :encryptedTitle, :encryptedSourceUrl,
                    :occurredAt, :endsAt, :importance, :readState, :cancelled,
                    :classification, :permissionReferenceHash, :sourceVersion)
                ON CONFLICT (
                    tenant_id, user_id, productivity_connector_id,
                    resource_kind, source_id_hash) DO UPDATE SET
                    encrypted_title = EXCLUDED.encrypted_title,
                    encrypted_source_url = EXCLUDED.encrypted_source_url,
                    occurred_at = EXCLUDED.occurred_at,
                    ends_at = EXCLUDED.ends_at,
                    importance = EXCLUDED.importance,
                    read_state = EXCLUDED.read_state,
                    cancelled = EXCLUDED.cancelled,
                    classification = EXCLUDED.classification,
                    permission_reference_hash = EXCLUDED.permission_reference_hash,
                    source_version = EXCLUDED.source_version,
                    tombstoned_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """, item.parameters());
    }

    public int tombstoneItem(
            Long tenantId,
            Long userId,
            UUID connectorId,
            ResourceKind resourceKind,
            String sourceIdHash) {
        return jdbc.update("""
                UPDATE wrk_productivity_items SET
                    tombstoned_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenantId
                  AND user_id = :userId
                  AND productivity_connector_id = :connectorId
                  AND resource_kind = :resourceKind
                  AND source_id_hash = :sourceIdHash
                  AND tombstoned_at IS NULL
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("userId", userId)
                .addValue("connectorId", connectorId)
                .addValue("resourceKind", resourceKind.name())
                .addValue("sourceIdHash", sourceIdHash));
    }

    public ItemResult items(
            Long tenantId,
            Long userId,
            ResourceKind resourceKind,
            int page,
            int size) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("tenantId", tenantId)
                .addValue("userId", userId)
                .addValue("resourceKind", resourceKind == null ? null : resourceKind.name())
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wrk_productivity_items
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND tombstoned_at IS NULL
                   AND (:resourceKind IS NULL OR resource_kind = :resourceKind)
                """, parameters, Long.class);
        List<ItemRecord> content = jdbc.query("""
                SELECT productivity_item_id, tenant_id, user_id,
                       productivity_connector_id, resource_kind, source_id_hash,
                       encrypted_title, encrypted_source_url, occurred_at, ends_at,
                       importance, read_state, cancelled, classification,
                       permission_reference_hash, source_version
                  FROM wrk_productivity_items
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND tombstoned_at IS NULL
                   AND (:resourceKind IS NULL OR resource_kind = :resourceKind)
                 ORDER BY occurred_at DESC, productivity_item_id
                 LIMIT :limit OFFSET :offset
                """, parameters, this::item);
        return new ItemResult(content, total);
    }

    public List<RunRecord> runs(Long tenantId, int limit) {
        return jdbc.query("""
                SELECT run.productivity_sync_run_id, run.productivity_connector_id,
                       subject.user_id, run.resource_kind, run.sync_mode, run.run_state,
                       run.started_at, run.completed_at, run.upsert_count, run.delete_count,
                       run.skip_count, run.error_count, run.partial_result,
                       run.retry_after_at, run.safe_error_code, run.correlation_id
                  FROM int_productivity_sync_runs run
                  JOIN int_productivity_subjects subject
                    ON subject.productivity_subject_id = run.productivity_subject_id
                 WHERE run.tenant_id = :tenantId
                 ORDER BY run.started_at DESC
                 LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("limit", Math.min(500, Math.max(1, limit))), this::run);
    }

    public Metrics metrics(Long tenantId) {
        var values = jdbc.queryForMap("""
                SELECT
                    (SELECT COUNT(*) FROM int_productivity_connectors
                      WHERE tenant_id = :tenantId AND lifecycle_state <> 'RETIRED') connectors,
                    (SELECT COUNT(*) FROM int_productivity_connectors
                      WHERE tenant_id = :tenantId AND lifecycle_state = 'ACTIVE') active_connectors,
                    (SELECT COUNT(*) FROM int_productivity_subjects
                      WHERE tenant_id = :tenantId AND consent_state = 'CONNECTED') connected_subjects,
                    (SELECT COUNT(*) FROM int_productivity_sync_streams
                      WHERE tenant_id = :tenantId
                        AND stream_state IN ('STALE', 'RESET_REQUIRED', 'AUTHENTICATION_REQUIRED')) stale_streams,
                    (SELECT COUNT(*) FROM int_productivity_sync_runs
                      WHERE tenant_id = :tenantId AND run_state IN ('FAILED', 'BLOCKED')
                        AND started_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours') failed_runs,
                    (SELECT MAX(last_successful_sync_at) FROM int_productivity_connectors
                      WHERE tenant_id = :tenantId) last_success
                """, new MapSqlParameterSource("tenantId", tenantId));
        Object timestamp = values.get("last_success");
        return new Metrics(
                number(values.get("connectors")),
                number(values.get("active_connectors")),
                number(values.get("connected_subjects")),
                number(values.get("stale_streams")),
                number(values.get("failed_runs")),
                timestamp instanceof Timestamp value ? value.toInstant() : null);
    }

    public void recordSyncSuccess(Long tenantId, UUID connectorId, UUID subjectId) {
        jdbc.update("""
                UPDATE int_productivity_connectors SET
                    health_state = 'HEALTHY', safe_error_code = NULL,
                    last_successful_sync_at = CURRENT_TIMESTAMP,
                    consecutive_failures = 0, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenantId AND productivity_connector_id = :connectorId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("connectorId", connectorId));
        jdbc.update("""
                UPDATE int_productivity_subjects SET
                    last_successful_sync_at = CURRENT_TIMESTAMP, last_error_code = NULL,
                    consent_state = 'CONNECTED', updated_at = CURRENT_TIMESTAMP
                WHERE productivity_subject_id = :subjectId
                """, new MapSqlParameterSource("subjectId", subjectId));
    }

    public void recordConnectorFailure(
            Long tenantId,
            UUID connectorId,
            ConnectorHealth health,
            String errorCode) {
        jdbc.update("""
                UPDATE int_productivity_connectors SET
                    health_state = :health,
                    safe_error_code = :errorCode,
                    consecutive_failures = consecutive_failures + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenantId AND productivity_connector_id = :connectorId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("connectorId", connectorId)
                .addValue("health", health.name())
                .addValue("errorCode", errorCode));
    }

    private MapSqlParameterSource connectorParameters(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            ConnectorDraft draft) {
        return new MapSqlParameterSource("connectorId", connectorId)
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("connectorKey", draft.connectorKey())
                .addValue("displayName", draft.displayName())
                .addValue("providerType", draft.providerType().name())
                .addValue("authMode", draft.authMode().name())
                .addValue("providerTenantId", draft.providerTenantId())
                .addValue("clientId", draft.clientId())
                .addValue("credentialReference", draft.credentialReference())
                .addValue("redirectUri", draft.redirectUri())
                .addValue("requestedScopes", json(draft.requestedScopes()))
                .addValue("capabilities", json(draft.capabilities()))
                .addValue("policyState", draft.policyState().name());
    }

    private ConnectorRecord connector(ResultSet rs, int row) throws SQLException {
        return new ConnectorRecord(
                rs.getObject("productivity_connector_id", UUID.class),
                rs.getLong("tenant_id"),
                rs.getString("connector_key"),
                rs.getString("display_name"),
                ProviderType.valueOf(rs.getString("provider_type")),
                AuthMode.valueOf(rs.getString("auth_mode")),
                rs.getString("provider_tenant_id"),
                rs.getString("client_id"),
                rs.getString("credential_reference"),
                rs.getString("redirect_uri"),
                strings(rs.getString("requested_scopes")),
                strings(rs.getString("capabilities")),
                ConnectorLifecycle.valueOf(rs.getString("lifecycle_state")),
                ConnectorHealth.valueOf(rs.getString("health_state")),
                PolicyState.valueOf(rs.getString("policy_state")),
                rs.getString("safe_error_code"),
                instant(rs, "last_configuration_check_at"),
                instant(rs, "last_successful_sync_at"),
                rs.getInt("consecutive_failures"),
                rs.getLong("version"));
    }

    private SubjectRecord subject(ResultSet rs, int row) throws SQLException {
        return new SubjectRecord(
                rs.getObject("productivity_subject_id", UUID.class),
                rs.getLong("tenant_id"),
                rs.getObject("productivity_connector_id", UUID.class),
                rs.getLong("user_id"),
                rs.getString("provider_subject_ref_hash"),
                rs.getString("encrypted_refresh_token"),
                strings(rs.getString("granted_scopes")),
                ConsentState.valueOf(rs.getString("consent_state")),
                instant(rs, "token_expires_at"),
                instant(rs, "last_successful_sync_at"),
                rs.getString("last_error_code"),
                rs.getLong("version"));
    }

    private StreamRecord stream(ResultSet rs, int row) throws SQLException {
        return new StreamRecord(
                rs.getObject("productivity_sync_stream_id", UUID.class),
                rs.getLong("tenant_id"),
                rs.getObject("productivity_subject_id", UUID.class),
                ResourceKind.valueOf(rs.getString("resource_kind")),
                rs.getString("encrypted_cursor"),
                rs.getString("cursor_fingerprint"),
                instant(rs, "calendar_window_start"),
                instant(rs, "calendar_window_end"),
                StreamState.valueOf(rs.getString("stream_state")),
                instant(rs, "last_attempt_at"),
                instant(rs, "last_success_at"),
                rs.getString("last_error_code"),
                rs.getLong("version"));
    }

    private RunRecord run(ResultSet rs, int row) throws SQLException {
        return new RunRecord(
                rs.getObject("productivity_sync_run_id", UUID.class),
                rs.getObject("productivity_connector_id", UUID.class),
                rs.getLong("user_id"),
                ResourceKind.valueOf(rs.getString("resource_kind")),
                SyncMode.valueOf(rs.getString("sync_mode")),
                SyncRunState.valueOf(rs.getString("run_state")),
                instant(rs, "started_at"),
                instant(rs, "completed_at"),
                rs.getInt("upsert_count"),
                rs.getInt("delete_count"),
                rs.getInt("skip_count"),
                rs.getInt("error_count"),
                rs.getBoolean("partial_result"),
                instant(rs, "retry_after_at"),
                rs.getString("safe_error_code"),
                rs.getString("correlation_id"));
    }

    private ItemRecord item(ResultSet rs, int row) throws SQLException {
        return new ItemRecord(
                rs.getObject("productivity_item_id", UUID.class),
                rs.getLong("tenant_id"),
                rs.getLong("user_id"),
                rs.getObject("productivity_connector_id", UUID.class),
                ResourceKind.valueOf(rs.getString("resource_kind")),
                rs.getString("source_id_hash"),
                rs.getString("encrypted_title"),
                rs.getString("encrypted_source_url"),
                instant(rs, "occurred_at"),
                instant(rs, "ends_at"),
                rs.getString("importance"),
                nullableBoolean(rs, "read_state"),
                rs.getBoolean("cancelled"),
                rs.getString("classification"),
                rs.getString("permission_reference_hash"),
                rs.getString("source_version"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Productivity metadata is invalid.", exception);
        }
    }

    private List<String> strings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored productivity metadata is invalid.", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    public record ConnectorDraft(
            String connectorKey,
            String displayName,
            ProviderType providerType,
            AuthMode authMode,
            String providerTenantId,
            String clientId,
            String credentialReference,
            String redirectUri,
            List<String> requestedScopes,
            List<String> capabilities,
            PolicyState policyState) {
    }

    public record ConnectorRecord(
            UUID connectorId,
            Long tenantId,
            String connectorKey,
            String displayName,
            ProviderType providerType,
            AuthMode authMode,
            String providerTenantId,
            String clientId,
            String credentialReference,
            String redirectUri,
            List<String> requestedScopes,
            List<String> capabilities,
            ConnectorLifecycle lifecycleState,
            ConnectorHealth healthState,
            PolicyState policyState,
            String safeErrorCode,
            Instant lastConfigurationCheckAt,
            Instant lastSuccessfulSyncAt,
            int consecutiveFailures,
            long version) {
    }

    public record SubjectRecord(
            UUID subjectId,
            Long tenantId,
            UUID connectorId,
            Long userId,
            String providerSubjectRefHash,
            String encryptedRefreshToken,
            List<String> grantedScopes,
            ConsentState consentState,
            Instant tokenExpiresAt,
            Instant lastSuccessfulSyncAt,
            String lastErrorCode,
            long version) {
    }

    public record OAuthTransaction(
            UUID transactionId,
            Long tenantId,
            UUID connectorId,
            Long userId,
            String stateHash,
            String encryptedPkceVerifier,
            Instant expiresAt) {
    }

    public record StreamRecord(
            UUID streamId,
            Long tenantId,
            UUID subjectId,
            ResourceKind resourceKind,
            String encryptedCursor,
            String cursorFingerprint,
            Instant windowStart,
            Instant windowEnd,
            StreamState streamState,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            String lastErrorCode,
            long version) {
    }

    public record RunRecord(
            UUID runId,
            UUID connectorId,
            long userId,
            ResourceKind resourceKind,
            SyncMode syncMode,
            SyncRunState runState,
            Instant startedAt,
            Instant completedAt,
            int upsertCount,
            int deleteCount,
            int skipCount,
            int errorCount,
            boolean partialResult,
            Instant retryAfterAt,
            String safeErrorCode,
            String correlationId) {
    }

    public record ItemRecord(
            UUID itemId,
            Long tenantId,
            Long userId,
            UUID connectorId,
            ResourceKind resourceKind,
            String sourceIdHash,
            String encryptedTitle,
            String encryptedSourceUrl,
            Instant occurredAt,
            Instant endsAt,
            String importance,
            Boolean readState,
            boolean cancelled,
            String classification,
            String permissionReferenceHash,
            String sourceVersion) {

        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource("itemId", itemId)
                    .addValue("tenantId", tenantId)
                    .addValue("userId", userId)
                    .addValue("connectorId", connectorId)
                    .addValue("resourceKind", resourceKind.name())
                    .addValue("sourceIdHash", sourceIdHash)
                    .addValue("encryptedTitle", encryptedTitle)
                    .addValue("encryptedSourceUrl", encryptedSourceUrl)
                    .addValue("occurredAt", timestamp(occurredAt))
                    .addValue("endsAt", timestamp(endsAt))
                    .addValue("importance", importance)
                    .addValue("readState", readState)
                    .addValue("cancelled", cancelled)
                    .addValue("classification", classification)
                    .addValue("permissionReferenceHash", permissionReferenceHash)
                    .addValue("sourceVersion", sourceVersion);
        }
    }

    public record ItemResult(List<ItemRecord> content, long total) {
    }

    public record Metrics(
            long connectors,
            long activeConnectors,
            long connectedSubjects,
            long staleStreams,
            long failedRuns24h,
            Instant lastSuccessfulSyncAt) {
    }
}
