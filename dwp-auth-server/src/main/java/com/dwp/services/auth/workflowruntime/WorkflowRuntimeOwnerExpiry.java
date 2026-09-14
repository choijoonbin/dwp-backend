package com.dwp.services.auth.workflowruntime;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

/** Current Auth source time bounds for the owner, including INFO admission before any Approval DB read. */
public final class WorkflowRuntimeOwnerExpiry {
    private WorkflowRuntimeOwnerExpiry() { }
    public static Evidence current(JdbcTemplate jdbc, WorkflowRuntimeJson json, long tenant, long user) {
        var rows = jdbc.query("""
                SELECT 'GROUP_ROLE' AS kind,assignment.group_role_assignment_id::text AS id,assignment.version,
                       assignment.valid_to AS expiry,assignment.valid_from AS starts
                  FROM com_group_role_assignments assignment
                  JOIN com_group_members membership ON membership.tenant_id=assignment.tenant_id AND membership.group_id=assignment.group_id
                  JOIN com_groups access_group ON access_group.tenant_id=membership.tenant_id AND access_group.group_id=membership.group_id AND access_group.status='ACTIVE'
                  JOIN com_roles role ON role.tenant_id=assignment.tenant_id AND role.role_id=assignment.role_id AND role.status='ACTIVE'
                 WHERE assignment.tenant_id=? AND membership.user_id=? AND assignment.lifecycle_state='ACTIVE'
                   AND assignment.assignment_type='ACTIVE' AND assignment.scope_type='TENANT'
                   AND (assignment.valid_from IS NULL OR assignment.valid_from<=CURRENT_TIMESTAMP)
                   AND (assignment.valid_to IS NULL OR assignment.valid_to>CURRENT_TIMESTAMP)
                UNION ALL
                SELECT 'PRIVILEGED',grant_record.active_privileged_grant_id::text,0,grant_record.expires_at,grant_record.activated_at
                  FROM com_active_privileged_grants grant_record
                  JOIN com_roles role ON role.tenant_id=grant_record.tenant_id AND role.role_id=grant_record.role_id AND role.status='ACTIVE'
                 WHERE grant_record.tenant_id=? AND grant_record.user_id=? AND grant_record.scope_type='TENANT'
                   AND grant_record.revoked_at IS NULL AND grant_record.activated_at<=CURRENT_TIMESTAMP AND grant_record.expires_at>CURRENT_TIMESTAMP
                UNION ALL
                SELECT 'PRINCIPAL',grant_record.principal_resource_grant_id::text,grant_record.version,grant_record.valid_to,grant_record.valid_from
                  FROM com_principal_resource_grants grant_record
                  JOIN com_resources resource ON resource.tenant_id=grant_record.tenant_id AND resource.resource_id=grant_record.resource_id AND resource.enabled
                 WHERE grant_record.tenant_id=? AND grant_record.lifecycle_state='ACTIVE' AND grant_record.valid_from<=CURRENT_TIMESTAMP
                   AND (grant_record.valid_to IS NULL OR grant_record.valid_to>CURRENT_TIMESTAMP)
                   AND ((grant_record.principal_type='USER' AND grant_record.principal_ref=?::text)
                    OR (grant_record.principal_type='GROUP' AND EXISTS(SELECT 1 FROM com_group_members membership
                        JOIN com_groups access_group ON access_group.tenant_id=membership.tenant_id AND access_group.group_id=membership.group_id AND access_group.status='ACTIVE'
                       WHERE membership.tenant_id=grant_record.tenant_id AND membership.user_id=? AND membership.group_id::text=grant_record.principal_ref)))
                 ORDER BY kind,id LIMIT 10001
                """, (row, index) -> {
            OffsetDateTime expiry = row.getObject("expiry", OffsetDateTime.class), starts = row.getObject("starts", OffsetDateTime.class);
            var material = new java.util.TreeMap<String, Object>(); material.put("kind", row.getString("kind"));
            material.put("id", row.getString("id")); material.put("version", row.getLong("version"));
            material.put("starts", starts == null ? null : starts.toInstant().toString()); material.put("expiry", expiry == null ? null : expiry.toInstant().toString());
            return new Source(json.tree(material), expiry == null ? null : expiry.toInstant());
        }, tenant, user, tenant, user, tenant, user, user);
        if (rows.size() > 10000) throw WorkflowRuntimeProtocol.unavailable();
        Instant expiry = rows.stream().map(Source::expiresAt).filter(java.util.Objects::nonNull).min(Instant::compareTo).orElse(null);
        return new Evidence(json.tree(rows.stream().map(Source::vector).toList()), expiry);
    }
    private record Source(JsonNode vector, Instant expiresAt) { }
    public record Evidence(JsonNode vector, Instant expiresAt) { }
}
