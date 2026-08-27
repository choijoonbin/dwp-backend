package com.dwp.services.platform.savedview;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
class SavedViewLifecycleHistoryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    SavedViewLifecycleHistoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<SavedViewDtos.OrphanLifecycleResult> latest(Long tenantId, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.query("""
                SELECT command_id, idempotency_key, saved_view_id,
                       saved_view_name, surface_key, scope, action,
                       target_owner_user_id, target_owner_display_name,
                       previous_lifecycle_state, new_lifecycle_state,
                       previous_retention_until, next_retention_until,
                       reason_code, reason, source_reference, request_fingerprint,
                       previous_version, resulting_version, created_at, created_by
                  FROM usr_saved_view_lifecycle_commands
                 WHERE tenant_id = :tenantId
                 ORDER BY created_at DESC, command_id DESC
                 LIMIT :limit
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("limit", boundedLimit), (result, ignored) ->
                new SavedViewDtos.OrphanLifecycleResult(
                        result.getObject("command_id", UUID.class),
                        result.getString("idempotency_key"),
                        result.getObject("saved_view_id", UUID.class),
                        result.getString("saved_view_name"),
                        result.getString("surface_key"),
                        result.getString("scope"),
                        result.getString("action"),
                        result.getObject("target_owner_user_id", Long.class),
                        result.getString("target_owner_display_name"),
                        result.getString("previous_lifecycle_state"),
                        result.getString("new_lifecycle_state"),
                        result.getObject("previous_retention_until", OffsetDateTime.class),
                        result.getObject("next_retention_until", OffsetDateTime.class),
                        result.getString("reason_code"), result.getString("reason"),
                        result.getString("source_reference"),
                        result.getString("request_fingerprint"),
                        result.getLong("previous_version"),
                        result.getLong("resulting_version"),
                        result.getObject("created_at", OffsetDateTime.class),
                        result.getObject("created_by", Long.class)));
    }
}
