package com.dwp.services.platform.savedview;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
class SavedViewOwnershipConflictRepository {
    private final NamedParameterJdbcTemplate jdbc;

    SavedViewOwnershipConflictRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<SavedViewDtos.OwnershipNameConflict> transferConflicts(
            Long tenantId, Long sourceOwnerUserId, Long targetOwnerUserId) {
        return jdbc.query("""
                SELECT incoming.saved_view_id AS incoming_id,
                       incoming.name AS incoming_name, incoming.surface_key,
                       existing.saved_view_id AS existing_id,
                       existing.name AS existing_name
                  FROM usr_saved_views incoming
                  JOIN usr_saved_views existing
                    ON existing.tenant_id = incoming.tenant_id
                   AND existing.owner_user_id = :targetOwnerUserId
                   AND existing.scope = 'PERSONAL'
                   AND existing.lifecycle_state = 'ACTIVE'
                   AND existing.surface_key = incoming.surface_key
                   AND LOWER(existing.name) = LOWER(incoming.name)
                   AND existing.saved_view_id <> incoming.saved_view_id
                 WHERE incoming.tenant_id = :tenantId
                   AND incoming.owner_user_id = :sourceOwnerUserId
                   AND incoming.scope = 'PERSONAL'
                   AND incoming.lifecycle_state = 'ACTIVE'
                 ORDER BY incoming.surface_key, LOWER(incoming.name), incoming.saved_view_id
                """, transferParameters(tenantId, targetOwnerUserId)
                .addValue("sourceOwnerUserId", sourceOwnerUserId),
                (result, ignored) -> conflict(result));
    }

    List<OrphanReassignConflict> orphanReassignConflicts(
            Long tenantId, UUID savedViewId, Collection<Long> targetOwnerUserIds) {
        if (targetOwnerUserIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT incoming.scope AS incoming_scope,
                       existing.owner_user_id AS target_owner_user_id,
                       incoming.saved_view_id AS incoming_id,
                       incoming.name AS incoming_name, incoming.surface_key,
                       existing.saved_view_id AS existing_id,
                       existing.name AS existing_name
                  FROM usr_saved_views incoming
                  JOIN usr_saved_views existing
                    ON existing.tenant_id = incoming.tenant_id
                   AND existing.scope = incoming.scope
                   AND existing.lifecycle_state = 'ACTIVE'
                   AND existing.surface_key = incoming.surface_key
                   AND LOWER(existing.name) = LOWER(incoming.name)
                   AND existing.saved_view_id <> incoming.saved_view_id
                   AND ((incoming.scope = 'PERSONAL'
                            AND existing.owner_user_id IN (:targetOwnerUserIds))
                        OR (incoming.scope = 'TEAM'
                            AND existing.owner_group_ref = incoming.owner_group_ref)
                        OR incoming.scope = 'TENANT')
                 WHERE incoming.tenant_id = :tenantId
                   AND incoming.saved_view_id = :savedViewId
                   AND incoming.lifecycle_state = 'ORPHANED'
                 ORDER BY existing.saved_view_id
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("savedViewId", savedViewId)
                .addValue("targetOwnerUserIds", targetOwnerUserIds),
                (result, ignored) -> new OrphanReassignConflict(
                        result.getString("incoming_scope"),
                        result.getLong("target_owner_user_id"),
                        conflict(result)));
    }

    private MapSqlParameterSource transferParameters(Long tenantId, Long targetOwnerUserId) {
        return new MapSqlParameterSource("tenantId", tenantId)
                .addValue("targetOwnerUserId", targetOwnerUserId);
    }

    private SavedViewDtos.OwnershipNameConflict conflict(java.sql.ResultSet result)
            throws java.sql.SQLException {
        return new SavedViewDtos.OwnershipNameConflict(
                result.getObject("incoming_id", UUID.class),
                result.getString("incoming_name"), result.getString("surface_key"),
                result.getObject("existing_id", UUID.class),
                result.getString("existing_name"));
    }

    record OrphanReassignConflict(
            String scope,
            Long existingOwnerUserId,
            SavedViewDtos.OwnershipNameConflict evidence) { }
}
