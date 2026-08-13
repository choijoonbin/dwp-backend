package com.dwp.services.platform.savedview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class SavedViewRepository {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SavedViewRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<Row> visible(
            Long tenantId,
            Long actorId,
            Set<UUID> groupRefs,
            String surfaceKey) {
        return jdbc.query("""
                SELECT view.tenant_id, view.saved_view_id, view.surface_key, view.name, view.scope,
                       view.owner_user_id, view.owner_group_ref, view.lifecycle_state,
                       view.retention_until, view.configuration, view.version,
                       COALESCE(preference.favorite, FALSE) AS favorite,
                       COALESCE(preference.is_default, FALSE) AS is_default,
                       preference.last_used_at, view.created_at, view.updated_at
                  FROM usr_saved_views view
                  LEFT JOIN usr_saved_view_preferences preference
                    ON preference.tenant_id = view.tenant_id
                   AND preference.user_id = :actorId
                   AND preference.surface_key = view.surface_key
                   AND preference.saved_view_id = view.saved_view_id
                 WHERE view.tenant_id = :tenantId
                   AND view.surface_key = :surfaceKey
                   AND view.lifecycle_state = 'ACTIVE'
                   AND (view.owner_user_id = :actorId OR view.scope = 'TENANT'
                     OR (view.scope = 'TEAM' AND view.owner_group_ref IN (:groupRefs)))
                 ORDER BY COALESCE(preference.is_default, FALSE) DESC,
                          COALESCE(preference.favorite, FALSE) DESC,
                          (view.owner_user_id = :actorId) DESC,
                          view.updated_at DESC, LOWER(view.name)
                """, parameters(tenantId, actorId, surfaceKey)
                        .addValue("groupRefs", databaseGroupRefs(groupRefs)),
                (result, ignored) -> row(
                result.getObject("saved_view_id", UUID.class),
                result.getString("surface_key"),
                result.getString("name"),
                result.getString("scope"),
                result.getObject("owner_user_id", Long.class),
                result.getObject("owner_group_ref", UUID.class),
                result.getString("lifecycle_state"),
                result.getObject("retention_until", OffsetDateTime.class),
                jsonMap(result.getString("configuration")),
                result.getLong("version"),
                result.getBoolean("favorite"),
                result.getBoolean("is_default"),
                result.getObject("last_used_at", OffsetDateTime.class),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class)));
    }

    public Optional<Row> find(Long tenantId, Long actorId, UUID savedViewId) {
        return jdbc.query("""
                SELECT view.saved_view_id, view.surface_key, view.name, view.scope,
                       view.owner_user_id, view.owner_group_ref, view.lifecycle_state,
                       view.retention_until, view.configuration, view.version,
                       COALESCE(preference.favorite, FALSE) AS favorite,
                       COALESCE(preference.is_default, FALSE) AS is_default,
                       preference.last_used_at, view.created_at, view.updated_at
                  FROM usr_saved_views view
                  LEFT JOIN usr_saved_view_preferences preference
                    ON preference.tenant_id = view.tenant_id
                   AND preference.user_id = :actorId
                   AND preference.surface_key = view.surface_key
                   AND preference.saved_view_id = view.saved_view_id
                 WHERE view.tenant_id = :tenantId
                   AND view.saved_view_id = :savedViewId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("savedViewId", savedViewId), (result, ignored) -> row(
                result.getObject("saved_view_id", UUID.class),
                result.getString("surface_key"),
                result.getString("name"),
                result.getString("scope"),
                result.getObject("owner_user_id", Long.class),
                result.getObject("owner_group_ref", UUID.class),
                result.getString("lifecycle_state"),
                result.getObject("retention_until", OffsetDateTime.class),
                jsonMap(result.getString("configuration")),
                result.getLong("version"),
                result.getBoolean("favorite"),
                result.getBoolean("is_default"),
                result.getObject("last_used_at", OffsetDateTime.class),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class))).stream().findFirst();
    }

    public UUID create(
            Long tenantId,
            Long actorId,
            String surfaceKey,
            String name,
            String scope,
            UUID ownerGroupRef,
            Map<String, Object> configuration) {
        return jdbc.queryForObject("""
                INSERT INTO usr_saved_views (
                    tenant_id, surface_key, owner_user_id, owner_group_ref, name, scope, configuration,
                    created_by, updated_by)
                VALUES (
                    :tenantId, :surfaceKey, :actorId, :ownerGroupRef, :name, :scope,
                    CAST(:configuration AS jsonb), :actorId, :actorId)
                RETURNING saved_view_id
                """, parameters(tenantId, actorId, surfaceKey)
                .addValue("name", name)
                .addValue("scope", scope)
                .addValue("ownerGroupRef", ownerGroupRef)
                .addValue("configuration", json(configuration)), UUID.class);
    }

    public boolean update(
            Long tenantId,
            Long actorId,
            UUID savedViewId,
            String name,
            String scope,
            UUID ownerGroupRef,
            Map<String, Object> configuration,
            long version) {
        return jdbc.update("""
                UPDATE usr_saved_views
                   SET name = :name,
                       scope = :scope,
                       owner_group_ref = :ownerGroupRef,
                       configuration = CAST(:configuration AS jsonb),
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND saved_view_id = :savedViewId
                   AND version = :version
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("savedViewId", savedViewId)
                .addValue("name", name)
                .addValue("scope", scope)
                .addValue("ownerGroupRef", ownerGroupRef)
                .addValue("configuration", json(configuration))
                .addValue("version", version)) == 1;
    }

    public boolean archive(Long tenantId, Long actorId, UUID savedViewId) {
        jdbc.update("""
                DELETE FROM usr_saved_view_preferences
                 WHERE tenant_id = :tenantId AND saved_view_id = :savedViewId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("savedViewId", savedViewId));
        return jdbc.update("""
                UPDATE usr_saved_views
                   SET lifecycle_state = 'ARCHIVED', retention_until = NULL,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId AND saved_view_id = :savedViewId
                   AND lifecycle_state = 'ACTIVE'
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("savedViewId", savedViewId)) == 1;
    }

    public List<Row> ownedActiveForUpdate(Long tenantId, Long ownerUserId) {
        return jdbc.query("""
                SELECT view.saved_view_id, view.surface_key, view.name, view.scope,
                       view.owner_user_id, view.owner_group_ref, view.lifecycle_state,
                       view.retention_until, view.configuration, view.version,
                       FALSE AS favorite, FALSE AS is_default, NULL::TIMESTAMPTZ AS last_used_at,
                       view.created_at, view.updated_at
                  FROM usr_saved_views view
                 WHERE view.tenant_id = :tenantId
                   AND view.owner_user_id = :ownerUserId
                   AND view.lifecycle_state = 'ACTIVE'
                 ORDER BY view.saved_view_id
                 FOR UPDATE
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("ownerUserId", ownerUserId), (result, ignored) -> row(result));
    }

    public List<SavedViewDtos.OrphanedView> orphaned(Long tenantId) {
        return jdbc.query("""
                SELECT saved_view_id, surface_key, name, scope, owner_group_ref,
                       retention_until, updated_at
                  FROM usr_saved_views
                 WHERE tenant_id = :tenantId AND lifecycle_state = 'ORPHANED'
                 ORDER BY retention_until, updated_at DESC
                """, new MapSqlParameterSource("tenantId", tenantId), (result, ignored) ->
                new SavedViewDtos.OrphanedView(
                        result.getObject("saved_view_id", UUID.class),
                        result.getString("surface_key"), result.getString("name"),
                        result.getString("scope"),
                        result.getObject("owner_group_ref", UUID.class),
                        result.getObject("retention_until", OffsetDateTime.class),
                        result.getObject("updated_at", OffsetDateTime.class)));
    }

    public Optional<SavedViewDtos.OwnershipTransfer> transferByIdempotency(
            Long tenantId, String idempotencyKey) {
        return jdbc.query("""
                SELECT transfer_batch_id, idempotency_key, source_owner_user_id,
                       target_owner_user_id, disposition, reason_code, source_reference,
                       retention_until, transferred_count, ownership_fingerprint,
                       request_fingerprint, created_at, created_by
                  FROM usr_saved_view_transfer_batches
                 WHERE tenant_id = :tenantId AND idempotency_key = :idempotencyKey
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("idempotencyKey", idempotencyKey), (result, ignored) -> transfer(result))
                .stream().findFirst();
    }

    public List<SavedViewDtos.OwnershipTransferSummary> transfers(Long tenantId, int limit) {
        return jdbc.query("""
                SELECT transfer_batch_id, source_owner_user_id, target_owner_user_id,
                       disposition, reason_code, source_reference, retention_until,
                       transferred_count, created_at, created_by
                  FROM usr_saved_view_transfer_batches
                 WHERE tenant_id = :tenantId
                 ORDER BY created_at DESC, transfer_batch_id DESC
                 LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("limit", limit), (result, ignored) ->
                new SavedViewDtos.OwnershipTransferSummary(
                        result.getObject("transfer_batch_id", UUID.class),
                        result.getObject("source_owner_user_id", Long.class),
                        result.getObject("target_owner_user_id", Long.class),
                        result.getString("disposition"), result.getString("reason_code"),
                        result.getString("source_reference"),
                        result.getObject("retention_until", OffsetDateTime.class),
                        result.getInt("transferred_count"),
                        result.getObject("created_at", OffsetDateTime.class),
                        result.getObject("created_by", Long.class)));
    }

    public SavedViewDtos.OwnershipTransfer transfer(
            Long tenantId,
            Long actorId,
            UUID batchId,
            SavedViewDtos.OwnershipTransferRequest request,
            String requestFingerprint,
            List<Row> views) {
        jdbc.update("""
                INSERT INTO usr_saved_view_transfer_batches (
                    transfer_batch_id, tenant_id, idempotency_key, source_owner_user_id,
                    target_owner_user_id, disposition, reason_code, reason, source_reference,
                    retention_until, ownership_fingerprint, request_fingerprint,
                    expected_count, transferred_count, created_by)
                VALUES (
                    :batchId, :tenantId, :idempotencyKey, :sourceOwnerUserId,
                    :targetOwnerUserId, :disposition, :reasonCode, :reason, :sourceReference,
                    :retentionUntil, :ownershipFingerprint, :requestFingerprint,
                    :expectedCount, :expectedCount, :actorId)
                """, transferParameters(tenantId, actorId, batchId, request, requestFingerprint));
        for (Row view : views) {
            String nextState = "TRANSFER".equals(request.disposition()) ? "ACTIVE" : "ORPHANED";
            Long nextOwner = "TRANSFER".equals(request.disposition())
                    ? request.targetOwnerUserId() : null;
            int changed = jdbc.update("""
                    UPDATE usr_saved_views
                       SET owner_user_id = :nextOwner,
                           lifecycle_state = :nextState,
                           retention_until = :retentionUntil,
                           version = version + 1,
                           updated_at = CURRENT_TIMESTAMP,
                           updated_by = :actorId
                     WHERE tenant_id = :tenantId AND saved_view_id = :savedViewId
                       AND owner_user_id = :sourceOwnerUserId
                       AND lifecycle_state = 'ACTIVE' AND version = :version
                    """, new MapSqlParameterSource("tenantId", tenantId)
                    .addValue("actorId", actorId)
                    .addValue("savedViewId", view.id())
                    .addValue("sourceOwnerUserId", request.sourceOwnerUserId())
                    .addValue("nextOwner", nextOwner)
                    .addValue("nextState", nextState)
                    .addValue("retentionUntil", request.retentionUntil())
                    .addValue("version", view.version()));
            if (changed != 1) {
                throw new OptimisticLockingFailureException(
                        "Saved-view ownership changed during transfer.");
            }
            jdbc.update("""
                    DELETE FROM usr_saved_view_preferences
                     WHERE tenant_id = :tenantId AND saved_view_id = :savedViewId
                       AND (:orphaned OR user_id = :sourceOwnerUserId)
                    """, new MapSqlParameterSource("tenantId", tenantId)
                    .addValue("savedViewId", view.id())
                    .addValue("sourceOwnerUserId", request.sourceOwnerUserId())
                    .addValue("orphaned", "RETAIN_ORPHANED".equals(request.disposition())));
            jdbc.update("""
                    INSERT INTO usr_saved_view_ownership_transfers (
                        transfer_batch_id, tenant_id, saved_view_id,
                        previous_owner_user_id, new_owner_user_id,
                        previous_lifecycle_state, new_lifecycle_state,
                        owner_group_ref, actor_user_id)
                    VALUES (
                        :batchId, :tenantId, :savedViewId,
                        :previousOwner, :newOwner, :previousState, :newState,
                        :ownerGroupRef, :actorId)
                    """, new MapSqlParameterSource("batchId", batchId)
                    .addValue("tenantId", tenantId)
                    .addValue("savedViewId", view.id())
                    .addValue("previousOwner", view.ownerUserId())
                    .addValue("newOwner", nextOwner)
                    .addValue("previousState", view.lifecycleState())
                    .addValue("newState", nextState)
                    .addValue("ownerGroupRef", view.ownerGroupRef())
                    .addValue("actorId", actorId));
        }
        return transferByIdempotency(tenantId, request.idempotencyKey()).orElseThrow();
    }

    public void idempotencyLock(Long tenantId, String idempotencyKey) {
        jdbc.getJdbcTemplate().query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, tenantId + ":" + idempotencyKey),
                result -> null);
    }

    public List<RetentionRow> expiredOrphansForUpdate(OffsetDateTime now) {
        return jdbc.query("""
                SELECT view.tenant_id, view.saved_view_id, view.surface_key, view.name, view.scope,
                       view.owner_user_id, view.owner_group_ref, view.lifecycle_state,
                       view.retention_until, view.configuration, view.version,
                       FALSE AS favorite, FALSE AS is_default, NULL::TIMESTAMPTZ AS last_used_at,
                       view.created_at, view.updated_at
                  FROM usr_saved_views view
                 WHERE view.lifecycle_state = 'ORPHANED' AND view.retention_until <= :now
                 ORDER BY view.saved_view_id
                 FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource("now", now), (result, ignored) ->
                new RetentionRow(result.getLong("tenant_id"), row(result)));
    }

    public boolean archiveOrphan(
            Long tenantId, UUID savedViewId, long version, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE usr_saved_views
                   SET lifecycle_state = 'ARCHIVED', retention_until = NULL,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND saved_view_id = :savedViewId
                   AND lifecycle_state = 'ORPHANED'
                   AND version = :version AND retention_until <= :now
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("savedViewId", savedViewId)
                .addValue("version", version).addValue("now", now)) == 1;
    }

    public void preference(
            Long tenantId,
            Long actorId,
            String surfaceKey,
            UUID savedViewId,
            boolean favorite,
            boolean defaultView) {
        if (defaultView) {
            jdbc.update("""
                    UPDATE usr_saved_view_preferences
                       SET is_default = FALSE, updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenantId AND user_id = :actorId
                       AND surface_key = :surfaceKey AND is_default = TRUE
                    """, parameters(tenantId, actorId, surfaceKey));
        }
        jdbc.update("""
                INSERT INTO usr_saved_view_preferences (
                    tenant_id, user_id, surface_key, saved_view_id,
                    favorite, is_default, last_used_at)
                VALUES (
                    :tenantId, :actorId, :surfaceKey, :savedViewId,
                    :favorite, :defaultView, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, user_id, surface_key, saved_view_id) DO UPDATE
                   SET favorite = EXCLUDED.favorite,
                       is_default = EXCLUDED.is_default,
                       updated_at = CURRENT_TIMESTAMP
                """, parameters(tenantId, actorId, surfaceKey)
                .addValue("savedViewId", savedViewId)
                .addValue("favorite", favorite)
                .addValue("defaultView", defaultView));
    }

    public void markUsed(Long tenantId, Long actorId, String surfaceKey, UUID savedViewId) {
        jdbc.update("""
                INSERT INTO usr_saved_view_preferences (
                    tenant_id, user_id, surface_key, saved_view_id, last_used_at)
                VALUES (:tenantId, :actorId, :surfaceKey, :savedViewId, CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, user_id, surface_key, saved_view_id) DO UPDATE
                   SET last_used_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                """, parameters(tenantId, actorId, surfaceKey)
                .addValue("savedViewId", savedViewId));
    }

    private MapSqlParameterSource parameters(Long tenantId, Long actorId, String surfaceKey) {
        return new MapSqlParameterSource("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("surfaceKey", surfaceKey);
    }

    private Row row(
            UUID id,
            String surfaceKey,
            String name,
            String scope,
            Long ownerUserId,
            UUID ownerGroupRef,
            String lifecycleState,
            OffsetDateTime retentionUntil,
            Map<String, Object> configuration,
            long version,
            boolean favorite,
            boolean defaultView,
            OffsetDateTime lastUsedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        return new Row(id, surfaceKey, name, scope, ownerUserId, ownerGroupRef,
                lifecycleState, retentionUntil, configuration, version,
                favorite, defaultView, lastUsedAt, createdAt, updatedAt);
    }

    private Row row(java.sql.ResultSet result) throws java.sql.SQLException {
        return row(result.getObject("saved_view_id", UUID.class),
                result.getString("surface_key"), result.getString("name"),
                result.getString("scope"), result.getObject("owner_user_id", Long.class),
                result.getObject("owner_group_ref", UUID.class),
                result.getString("lifecycle_state"),
                result.getObject("retention_until", OffsetDateTime.class),
                jsonMap(result.getString("configuration")), result.getLong("version"),
                result.getBoolean("favorite"), result.getBoolean("is_default"),
                result.getObject("last_used_at", OffsetDateTime.class),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class));
    }

    private List<UUID> databaseGroupRefs(Set<UUID> groupRefs) {
        return groupRefs == null || groupRefs.isEmpty()
                ? List.of(new UUID(0L, 0L)) : List.copyOf(groupRefs);
    }

    private MapSqlParameterSource transferParameters(
            Long tenantId,
            Long actorId,
            UUID batchId,
            SavedViewDtos.OwnershipTransferRequest request,
            String requestFingerprint) {
        return new MapSqlParameterSource("batchId", batchId)
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("idempotencyKey", request.idempotencyKey())
                .addValue("sourceOwnerUserId", request.sourceOwnerUserId())
                .addValue("targetOwnerUserId", request.targetOwnerUserId())
                .addValue("disposition", request.disposition())
                .addValue("reasonCode", request.reasonCode())
                .addValue("reason", request.reason())
                .addValue("sourceReference", request.sourceReference())
                .addValue("retentionUntil", request.retentionUntil())
                .addValue("ownershipFingerprint", request.ownershipFingerprint())
                .addValue("requestFingerprint", requestFingerprint)
                .addValue("expectedCount", request.expectedCount());
    }

    private SavedViewDtos.OwnershipTransfer transfer(java.sql.ResultSet result)
            throws java.sql.SQLException {
        return new SavedViewDtos.OwnershipTransfer(
                result.getObject("transfer_batch_id", UUID.class),
                result.getString("idempotency_key"),
                result.getObject("source_owner_user_id", Long.class),
                result.getObject("target_owner_user_id", Long.class),
                result.getString("disposition"), result.getString("reason_code"),
                result.getString("source_reference"),
                result.getObject("retention_until", OffsetDateTime.class),
                result.getInt("transferred_count"),
                result.getString("ownership_fingerprint"),
                result.getString("request_fingerprint"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("created_by", Long.class));
    }

    private Map<String, Object> jsonMap(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored saved-view configuration is invalid", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Saved-view configuration cannot be serialized", exception);
        }
    }

    public record Row(
            UUID id,
            String surfaceKey,
            String name,
            String scope,
            Long ownerUserId,
            UUID ownerGroupRef,
            String lifecycleState,
            OffsetDateTime retentionUntil,
            Map<String, Object> configuration,
            long version,
            boolean favorite,
            boolean defaultView,
            OffsetDateTime lastUsedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    public record RetentionRow(Long tenantId, Row view) { }
}
