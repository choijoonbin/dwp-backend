package com.dwp.services.platform.workplace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceResourceFavoriteDtos.*;

@Repository
class WorkplaceResourceFavoriteRepository {
    private final JdbcTemplate jdbc;

    WorkplaceResourceFavoriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lock(long tenantId, long userId, UUID resourceId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1,
                        "workplace-favorite:" + tenantId + ":" + userId + ":" + resourceId),
                result -> null);
    }

    boolean resourceExists(long tenantId, UUID resourceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM wp_resources
                 WHERE tenant_id=? AND resource_id=? AND lifecycle_state<>'RETIRED')
                """, Boolean.class, tenantId, resourceId));
    }

    List<FavoriteView> favorites(long tenantId, long userId, List<UUID> resourceIds) {
        if (resourceIds.isEmpty()) {
            return jdbc.query("""
                    SELECT resource_id, favorite, version, updated_at
                      FROM wp_resource_favorites
                     WHERE tenant_id=? AND user_id=? AND favorite
                     ORDER BY updated_at DESC, resource_id
                    """, (rs, row) -> favorite(rs), tenantId, userId);
        }
        return jdbc.query("""
                SELECT requested.resource_id,
                       COALESCE(favorite.favorite, FALSE) AS favorite,
                       COALESCE(favorite.version, 0) AS version,
                       favorite.updated_at
                  FROM unnest(?::uuid[]) WITH ORDINALITY requested(resource_id, ordinal)
             LEFT JOIN wp_resource_favorites favorite
                    ON favorite.tenant_id=? AND favorite.user_id=?
                   AND favorite.resource_id=requested.resource_id
                 ORDER BY requested.ordinal
                """, (rs, row) -> favorite(rs), resourceIds.toArray(UUID[]::new), tenantId, userId);
    }

    Optional<FavoriteView> favorite(long tenantId, long userId, UUID resourceId) {
        return jdbc.query("""
                SELECT resource_id, favorite, version, updated_at
                  FROM wp_resource_favorites
                 WHERE tenant_id=? AND user_id=? AND resource_id=?
                """, (rs, row) -> favorite(rs), tenantId, userId, resourceId)
                .stream().findFirst();
    }

    void save(long tenantId, long userId, UUID resourceId, boolean favorite,
              long expectedVersion, OffsetDateTime now) {
        if (expectedVersion == 0) {
            jdbc.update("""
                    INSERT INTO wp_resource_favorites
                        (tenant_id, user_id, resource_id, favorite, version, updated_at)
                    VALUES (?, ?, ?, ?, 1, ?)
                    """, tenantId, userId, resourceId, favorite, now);
            return;
        }
        int changed = jdbc.update("""
                UPDATE wp_resource_favorites
                   SET favorite=?, version=version+1, updated_at=?
                 WHERE tenant_id=? AND user_id=? AND resource_id=? AND version=?
                """, favorite, now, tenantId, userId, resourceId, expectedVersion);
        if (changed != 1) throw new IllegalStateException("Favorite CAS failed after scope lock.");
    }

    Optional<CommandRow> command(long tenantId, long userId, String key) {
        return jdbc.query("""
                SELECT command_id, resource_id, request_fingerprint, result_favorite,
                       result_version, result_updated_at, audit_event_id, correlation_id
                  FROM wp_resource_favorite_commands
                 WHERE tenant_id=? AND user_id=? AND idempotency_key=?
                """, (rs, row) -> new CommandRow(
                rs.getObject("command_id", UUID.class),
                rs.getObject("resource_id", UUID.class),
                rs.getString("request_fingerprint"),
                rs.getBoolean("result_favorite"),
                rs.getLong("result_version"),
                rs.getObject("result_updated_at", OffsetDateTime.class),
                rs.getObject("audit_event_id", UUID.class),
                rs.getString("correlation_id")), tenantId, userId, key).stream().findFirst();
    }

    UUID audit(long tenantId, long userId, UUID resourceId, boolean favorite,
               long version, String correlationId, OffsetDateTime now) {
        UUID auditId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_audit_events
                    (audit_event_id, tenant_id, action, aggregate_type, aggregate_id,
                     actor_user_id, correlation_id, snapshot, occurred_at)
                VALUES (?, ?, ?, 'WP_RESOURCE', ?, ?, ?,
                        jsonb_build_object('favorite', ?, 'version', ?), ?)
                """, auditId, tenantId,
                favorite ? "workplace.resource.favorite.set" : "workplace.resource.favorite.cleared",
                resourceId, userId, correlationId, favorite, version, now);
        return auditId;
    }

    UUID insertCommand(long tenantId, long userId, UUID resourceId, String key,
                       String fingerprint, FavoriteView result, UUID auditEventId,
                       String correlationId, OffsetDateTime now) {
        UUID commandId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_resource_favorite_commands
                    (command_id, tenant_id, user_id, resource_id, idempotency_key,
                     request_fingerprint, result_favorite, result_version,
                     result_updated_at, audit_event_id, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, commandId, tenantId, userId, resourceId, key, fingerprint,
                result.favorite(), result.version(), result.updatedAt(), auditEventId,
                correlationId, now);
        return commandId;
    }

    private FavoriteView favorite(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FavoriteView(rs.getObject("resource_id", UUID.class),
                rs.getBoolean("favorite"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    record CommandRow(UUID commandId, UUID resourceId, String fingerprint,
                      boolean favorite, long version, OffsetDateTime updatedAt,
                      UUID auditEventId, String correlationId) {
        FavoriteReceipt receipt() {
            return new FavoriteReceipt(commandId,
                    new FavoriteView(resourceId, favorite, version, updatedAt),
                    auditEventId, correlationId, updatedAt);
        }
    }
}
