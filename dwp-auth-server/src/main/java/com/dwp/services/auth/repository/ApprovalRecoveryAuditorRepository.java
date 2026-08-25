package com.dwp.services.auth.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads mutable user and explicit permission evidence for scoped-duty candidates. */
@Repository
public class ApprovalRecoveryAuditorRepository {

    static final String EFFECTIVE_ROLES_CTE = """
            WITH effective_roles AS (
                SELECT member.user_id, member.role_id, 'DIRECT' AS source_type,
                       member.role_member_id::text AS source_ref,
                       CONCAT_WS(':', 'member', member.role_member_id::text,
                                 EXTRACT(EPOCH FROM member.updated_at)::text)
                           AS source_revision
                  FROM com_role_members member
                 WHERE member.tenant_id = :tenantId
                UNION ALL
                SELECT membership.user_id, assignment.role_id, 'GROUP' AS source_type,
                       CONCAT_WS(':', membership.group_member_id::text,
                                 assignment.group_role_assignment_id::text) AS source_ref,
                       CONCAT_WS(':', 'group',
                                 EXTRACT(EPOCH FROM membership.updated_at)::text,
                                 access_group.revision::text, access_group.version::text,
                                 assignment.version::text,
                                 COALESCE(EXTRACT(EPOCH FROM assignment.valid_from)::text,
                                          'null'),
                                 COALESCE(EXTRACT(EPOCH FROM assignment.valid_to)::text,
                                          'null')) AS source_revision
                  FROM com_group_role_assignments assignment
                  JOIN com_group_members membership
                    ON membership.tenant_id = assignment.tenant_id
                   AND membership.group_id = assignment.group_id
                  JOIN com_groups access_group
                    ON access_group.tenant_id = membership.tenant_id
                   AND access_group.group_id = membership.group_id
                   AND access_group.status = 'ACTIVE'
                 WHERE assignment.tenant_id = :tenantId
                   AND assignment.lifecycle_state = 'ACTIVE'
                   AND assignment.assignment_type = 'ACTIVE'
                   AND assignment.scope_type = 'TENANT'
                   AND (assignment.valid_from IS NULL
                        OR assignment.valid_from <= CURRENT_TIMESTAMP)
                   AND (assignment.valid_to IS NULL
                        OR assignment.valid_to > CURRENT_TIMESTAMP)
                UNION ALL
                SELECT active_grant.user_id, active_grant.role_id, 'JIT' AS source_type,
                       active_grant.active_privileged_grant_id::text AS source_ref,
                       CONCAT_WS(':', 'jit',
                                 EXTRACT(EPOCH FROM active_grant.updated_at)::text,
                                 EXTRACT(EPOCH FROM active_grant.activated_at)::text,
                                 EXTRACT(EPOCH FROM active_grant.expires_at)::text)
                           AS source_revision
                  FROM com_active_privileged_grants active_grant
                 WHERE active_grant.tenant_id = :tenantId
                   AND active_grant.scope_type = 'TENANT'
                   AND active_grant.revoked_at IS NULL
                   AND active_grant.activated_at <= CURRENT_TIMESTAMP
                   AND active_grant.expires_at > CURRENT_TIMESTAMP
            )
            """;

    static final String CANDIDATE_USERS_SQL = """
            SELECT user_record.user_id, user_record.access_revision
              FROM com_users user_record
              JOIN com_tenants tenant
                ON tenant.tenant_id = user_record.tenant_id
               AND tenant.status = 'ACTIVE'
             WHERE user_record.tenant_id = :tenantId
               AND user_record.user_id IN (:userIds)
               AND user_record.status = 'ACTIVE'
             ORDER BY user_record.user_id
            """;

    static final String PERMISSION_EVIDENCE_SQL = EFFECTIVE_ROLES_CTE + """
            SELECT evidence.user_id, resource.key AS resource_key,
                   permission.code AS permission_code, assignment.effect,
                   'ROLE' AS source_type,
                   CONCAT_WS(':', evidence.source_type, evidence.source_ref,
                             assignment.role_permission_id::text) AS source_ref,
                   CONCAT_WS(':', evidence.source_revision, 'role-permission',
                             EXTRACT(EPOCH FROM assignment.updated_at)::text,
                             EXTRACT(EPOCH FROM resource.updated_at)::text)
                       AS source_revision
              FROM effective_roles evidence
              JOIN com_roles role
                ON role.tenant_id = :tenantId
               AND role.role_id = evidence.role_id
               AND role.status = 'ACTIVE'
              JOIN com_role_permissions assignment
                ON assignment.tenant_id = :tenantId
               AND assignment.role_id = evidence.role_id
              JOIN com_resources resource
                ON resource.resource_id = assignment.resource_id
               AND (resource.tenant_id IS NULL OR resource.tenant_id = :tenantId)
               AND resource.enabled = TRUE
              JOIN com_permissions permission
                ON permission.permission_id = assignment.permission_id
             WHERE evidence.user_id IN (:userIds)
               AND resource.key = 'ADMIN.APPROVAL_OPERATIONS'
               AND permission.code = 'VIEW'
            UNION ALL
            SELECT subject.user_id, resource.key, permission.code, grant_record.effect,
                   'PRINCIPAL' AS source_type,
                   grant_record.principal_resource_grant_id::text AS source_ref,
                   CONCAT_WS(':', 'principal-grant', grant_record.version::text,
                             EXTRACT(EPOCH FROM grant_record.updated_at)::text,
                             EXTRACT(EPOCH FROM grant_record.valid_from)::text,
                             COALESCE(EXTRACT(EPOCH FROM grant_record.valid_to)::text,
                                      'null')) AS source_revision
              FROM com_principal_resource_grants grant_record
              JOIN com_resources resource
                ON resource.resource_id = grant_record.resource_id
               AND resource.tenant_id = grant_record.tenant_id
               AND resource.enabled = TRUE
              JOIN com_permissions permission
                ON permission.permission_id = grant_record.permission_id
              JOIN LATERAL (
                   SELECT grant_record.principal_ref::BIGINT AS user_id
                    WHERE grant_record.principal_type = 'USER'
                      AND grant_record.principal_ref ~ '^[0-9]+$'
                   UNION ALL
                   SELECT membership.user_id
                     FROM com_group_members membership
                     JOIN com_groups access_group
                       ON access_group.tenant_id = membership.tenant_id
                      AND access_group.group_id = membership.group_id
                      AND access_group.status = 'ACTIVE'
                    WHERE grant_record.principal_type = 'GROUP'
                      AND grant_record.principal_ref ~ '^[0-9]+$'
                      AND membership.tenant_id = grant_record.tenant_id
                      AND membership.group_id = grant_record.principal_ref::BIGINT
              ) subject ON TRUE
             WHERE grant_record.tenant_id = :tenantId
               AND subject.user_id IN (:userIds)
               AND grant_record.lifecycle_state = 'ACTIVE'
               AND grant_record.valid_from <= CURRENT_TIMESTAMP
               AND (grant_record.valid_to IS NULL
                    OR grant_record.valid_to > CURRENT_TIMESTAMP)
               AND resource.key = 'ADMIN.APPROVAL_OPERATIONS'
               AND permission.code = 'VIEW'
             ORDER BY user_id, effect, source_type, source_ref
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ApprovalRecoveryAuditorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CandidateEvidence> findAuthoritativeCandidates(
            Long tenantId,
            List<Long> scopedCandidateUserIds) {
        List<Long> userIds = scopedCandidateUserIds == null ? List.of()
                : scopedCandidateUserIds.stream().filter(java.util.Objects::nonNull)
                        .distinct().sorted().toList();
        if (userIds.isEmpty()) return List.of();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userIds", userIds);
        List<CandidateRow> candidates = jdbc.query(
                CANDIDATE_USERS_SQL, parameters,
                (result, ignored) -> new CandidateRow(
                        result.getLong("user_id"), result.getLong("access_revision")));
        List<PermissionEvidence> permissions = jdbc.query(
                PERMISSION_EVIDENCE_SQL, parameters,
                (result, ignored) -> new PermissionEvidence(
                        result.getString("resource_key"),
                        result.getString("permission_code"), result.getString("effect"),
                        result.getString("source_type"), result.getString("source_ref"),
                        result.getString("source_revision"), result.getLong("user_id")));
        Map<Long, List<PermissionEvidence>> byUser = new LinkedHashMap<>();
        permissions.forEach(value -> byUser.computeIfAbsent(
                value.userId(), ignored -> new ArrayList<>()).add(value));
        return candidates.stream().map(candidate -> new CandidateEvidence(
                candidate.userId(), candidate.accessRevision(),
                byUser.getOrDefault(candidate.userId(), List.of()))).toList();
    }

    private record CandidateRow(Long userId, long accessRevision) {
    }

    public record CandidateEvidence(
            Long userId,
            long accessRevision,
            List<PermissionEvidence> permissions) {
        public CandidateEvidence {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }

    public record PermissionEvidence(
            String resourceKey,
            String permissionCode,
            String effect,
            String sourceType,
            String sourceRef,
            String sourceRevision,
            Long userId) {
    }
}
