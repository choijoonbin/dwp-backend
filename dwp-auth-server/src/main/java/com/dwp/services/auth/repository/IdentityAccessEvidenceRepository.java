package com.dwp.services.auth.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class IdentityAccessEvidenceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IdentityAccessEvidenceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, List<EffectiveAccessRow>> effectiveAccess(
            Long tenantId,
            Collection<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        List<EffectiveAccessRow> rows = jdbc.query("""
                SELECT member.user_id, role.role_id, role.code AS role_code,
                       role.name AS role_name, role.privileged,
                       'DIRECT' AS source_type, member.role_member_id AS source_id,
                       NULL::VARCHAR AS source_key, NULL::VARCHAR AS source_name,
                       'ACTIVE' AS assignment_type, 'TENANT' AS scope_type,
                       NULL::VARCHAR AS scope_ref, NULL::TIMESTAMPTZ AS valid_from,
                       NULL::TIMESTAMPTZ AS valid_to, member.created_at AS assigned_at
                  FROM com_role_members member
                  JOIN com_roles role
                    ON role.tenant_id = member.tenant_id
                   AND role.role_id = member.role_id
                 WHERE member.tenant_id = :tenantId
                   AND member.user_id IN (:userIds)
                   AND role.status = 'ACTIVE'
                UNION ALL
                SELECT membership.user_id, role.role_id, role.code AS role_code,
                       role.name AS role_name, role.privileged,
                       'GROUP' AS source_type,
                       assignment.group_role_assignment_id AS source_id,
                       access_group.group_key AS source_key,
                       access_group.display_name AS source_name,
                       assignment.assignment_type, assignment.scope_type,
                       assignment.scope_ref, assignment.valid_from, assignment.valid_to,
                       assignment.created_at AS assigned_at
                  FROM com_group_role_assignments assignment
                  JOIN com_group_members membership
                    ON membership.tenant_id = assignment.tenant_id
                   AND membership.group_id = assignment.group_id
                  JOIN com_groups access_group
                    ON access_group.tenant_id = assignment.tenant_id
                   AND access_group.group_id = assignment.group_id
                  JOIN com_roles role
                    ON role.tenant_id = assignment.tenant_id
                   AND role.role_id = assignment.role_id
                 WHERE assignment.tenant_id = :tenantId
                   AND membership.user_id IN (:userIds)
                   AND assignment.lifecycle_state = 'ACTIVE'
                   AND assignment.assignment_type = 'ACTIVE'
                   AND access_group.status = 'ACTIVE'
                   AND role.status = 'ACTIVE'
                   AND (assignment.valid_from IS NULL
                        OR assignment.valid_from <= CURRENT_TIMESTAMP)
                   AND (assignment.valid_to IS NULL
                        OR assignment.valid_to > CURRENT_TIMESTAMP)
                 ORDER BY user_id, privileged DESC, role_code, source_type, source_name
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("userIds", userIds),
                this::effectiveAccessRow);
        Map<Long, List<EffectiveAccessRow>> result = new LinkedHashMap<>();
        for (EffectiveAccessRow row : rows) {
            result.computeIfAbsent(row.userId(), ignored -> new java.util.ArrayList<>()).add(row);
        }
        return result;
    }

    public Map<Long, SessionEvidence> sessionEvidence(
            Long tenantId,
            Collection<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        List<SessionEvidence> rows = jdbc.query("""
                SELECT session.user_id, MAX(session.session_started_at) AS last_sign_in_at,
                       COUNT(*) FILTER (
                           WHERE session.revoked_at IS NULL
                             AND session.expires_at > CURRENT_TIMESTAMP
                             AND session.idle_expires_at > CURRENT_TIMESTAMP
                             AND (session.superseded_at IS NULL
                                  OR session.superseded_expires_at > CURRENT_TIMESTAMP)
                       ) AS active_session_count
                  FROM sys_auth_sessions session
                 WHERE session.tenant_id = :tenantId
                   AND session.user_id IN (:userIds)
                 GROUP BY session.user_id
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("userIds", userIds),
                (resultSet, rowNumber) -> new SessionEvidence(
                        resultSet.getLong("user_id"),
                        instant(resultSet, "last_sign_in_at"),
                        resultSet.getLong("active_session_count")));
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                SessionEvidence::userId,
                value -> value,
                (left, ignored) -> left,
                LinkedHashMap::new));
    }

    private EffectiveAccessRow effectiveAccessRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new EffectiveAccessRow(
                resultSet.getLong("user_id"),
                resultSet.getLong("role_id"),
                resultSet.getString("role_code"),
                resultSet.getString("role_name"),
                resultSet.getBoolean("privileged"),
                resultSet.getString("source_type"),
                resultSet.getLong("source_id"),
                resultSet.getString("source_key"),
                resultSet.getString("source_name"),
                resultSet.getString("assignment_type"),
                resultSet.getString("scope_type"),
                resultSet.getString("scope_ref"),
                instant(resultSet, "valid_from"),
                instant(resultSet, "valid_to"),
                instant(resultSet, "assigned_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record EffectiveAccessRow(
            Long userId,
            Long roleId,
            String roleCode,
            String roleName,
            boolean privileged,
            String sourceType,
            Long sourceId,
            String sourceKey,
            String sourceName,
            String assignmentType,
            String scopeType,
            String scopeRef,
            Instant validFrom,
            Instant validTo,
            Instant assignedAt) {
    }

    public record SessionEvidence(
            Long userId,
            Instant lastSignInAt,
            long activeSessionCount) {
    }
}
