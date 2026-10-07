package com.dwp.services.platform.productivity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.productivity.ProductivityTypes.*;

abstract class ProductivityRepositorySupport {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    ProductivityRepositorySupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    protected MapSqlParameterSource connectorParameters(
            Long tenantId,
            Long actorId,
            UUID connectorId,
            ProductivityConnectorDraftView draft) {
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

    protected <T> T connector(ResultSet rs, int row, ConnectorFactory<T> factory)
            throws SQLException {
        return factory.create(
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

    protected <T> T subject(ResultSet rs, int row, SubjectFactory<T> factory)
            throws SQLException {
        return factory.create(
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

    protected <T> T stream(ResultSet rs, int row, StreamFactory<T> factory)
            throws SQLException {
        return factory.create(
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

    protected <T> T run(ResultSet rs, int row, RunFactory<T> factory) throws SQLException {
        return factory.create(
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

    protected <T> T item(ResultSet rs, int row, ItemFactory<T> factory) throws SQLException {
        return factory.create(
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

    protected String json(Object value) {
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

    protected static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    protected static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    protected static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    protected static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    @FunctionalInterface
    protected interface ConnectorFactory<T> {
        T create(
                UUID connectorId, Long tenantId, String connectorKey, String displayName,
                ProviderType providerType, AuthMode authMode, String providerTenantId,
                String clientId, String credentialReference, String redirectUri,
                List<String> requestedScopes, List<String> capabilities,
                ConnectorLifecycle lifecycleState, ConnectorHealth healthState,
                PolicyState policyState, String safeErrorCode,
                Instant lastConfigurationCheckAt, Instant lastSuccessfulSyncAt,
                int consecutiveFailures, long version);
    }

    @FunctionalInterface
    protected interface SubjectFactory<T> {
        T create(
                UUID subjectId, Long tenantId, UUID connectorId, Long userId,
                String providerSubjectRefHash, String encryptedRefreshToken,
                List<String> grantedScopes, ConsentState consentState,
                Instant tokenExpiresAt, Instant lastSuccessfulSyncAt,
                String lastErrorCode, long version);
    }

    @FunctionalInterface
    protected interface StreamFactory<T> {
        T create(
                UUID streamId, Long tenantId, UUID subjectId, ResourceKind resourceKind,
                String encryptedCursor, String cursorFingerprint, Instant windowStart,
                Instant windowEnd, StreamState streamState, Instant lastAttemptAt,
                Instant lastSuccessAt, String lastErrorCode, long version);
    }

    @FunctionalInterface
    protected interface RunFactory<T> {
        T create(
                UUID runId, UUID connectorId, long userId, ResourceKind resourceKind,
                SyncMode syncMode, SyncRunState runState, Instant startedAt,
                Instant completedAt, int upsertCount, int deleteCount, int skipCount,
                int errorCount, boolean partialResult, Instant retryAfterAt,
                String safeErrorCode, String correlationId);
    }

    @FunctionalInterface
    protected interface ItemFactory<T> {
        T create(
                UUID itemId, Long tenantId, Long userId, UUID connectorId,
                ResourceKind resourceKind, String sourceIdHash, String encryptedTitle,
                String encryptedSourceUrl, Instant occurredAt, Instant endsAt,
                String importance, Boolean readState, boolean cancelled,
                String classification, String permissionReferenceHash, String sourceVersion);
    }
}
