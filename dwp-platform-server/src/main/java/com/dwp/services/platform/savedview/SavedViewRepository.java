package com.dwp.services.platform.savedview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public List<Row> visible(Long tenantId, Long actorId, String surfaceKey) {
        return jdbc.query("""
                SELECT view.saved_view_id, view.surface_key, view.name, view.scope,
                       view.owner_user_id, view.configuration, view.version,
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
                   AND (view.owner_user_id = :actorId OR view.scope = 'TENANT')
                 ORDER BY COALESCE(preference.is_default, FALSE) DESC,
                          COALESCE(preference.favorite, FALSE) DESC,
                          (view.owner_user_id = :actorId) DESC,
                          view.updated_at DESC, LOWER(view.name)
                """, parameters(tenantId, actorId, surfaceKey), (result, ignored) -> row(
                result.getObject("saved_view_id", UUID.class),
                result.getString("surface_key"),
                result.getString("name"),
                result.getString("scope"),
                result.getLong("owner_user_id"),
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
                       view.owner_user_id, view.configuration, view.version,
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
                result.getLong("owner_user_id"),
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
            Map<String, Object> configuration) {
        return jdbc.queryForObject("""
                INSERT INTO usr_saved_views (
                    tenant_id, surface_key, owner_user_id, name, scope, configuration,
                    created_by, updated_by)
                VALUES (
                    :tenantId, :surfaceKey, :actorId, :name, :scope,
                    CAST(:configuration AS jsonb), :actorId, :actorId)
                RETURNING saved_view_id
                """, parameters(tenantId, actorId, surfaceKey)
                .addValue("name", name)
                .addValue("scope", scope)
                .addValue("configuration", json(configuration)), UUID.class);
    }

    public boolean update(
            Long tenantId,
            Long actorId,
            UUID savedViewId,
            String name,
            String scope,
            Map<String, Object> configuration,
            long version) {
        return jdbc.update("""
                UPDATE usr_saved_views
                   SET name = :name,
                       scope = :scope,
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
                .addValue("configuration", json(configuration))
                .addValue("version", version)) == 1;
    }

    public boolean delete(Long tenantId, UUID savedViewId) {
        return jdbc.update("""
                DELETE FROM usr_saved_views
                 WHERE tenant_id = :tenantId AND saved_view_id = :savedViewId
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("savedViewId", savedViewId)) == 1;
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
            Map<String, Object> configuration,
            long version,
            boolean favorite,
            boolean defaultView,
            OffsetDateTime lastUsedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        return new Row(id, surfaceKey, name, scope, ownerUserId, configuration, version,
                favorite, defaultView, lastUsedAt, createdAt, updatedAt);
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
            Map<String, Object> configuration,
            long version,
            boolean favorite,
            boolean defaultView,
            OffsetDateTime lastUsedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }
}
