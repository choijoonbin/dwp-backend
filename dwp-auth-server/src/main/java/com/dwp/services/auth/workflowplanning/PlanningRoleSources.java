package com.dwp.services.auth.workflowplanning;

import static com.dwp.services.auth.workflowplanning.PlanningProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Minimal membership provenance and next time transition, never directory attributes. */
@Repository
public class PlanningRoleSources {
    private final JdbcTemplate jdbc;
    private final PlanningJson json;
    public PlanningRoleSources(JdbcTemplate jdbc,PlanningJson json) { this.jdbc=jdbc; this.json=json; }
    public Snapshot current(long tenant,long role) {
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();
        var rows=jdbc.query("""
                WITH source AS (
                  SELECT 'DIRECT' AS kind,m.role_member_id::text AS id,m.user_id,
                    jsonb_build_object('kind','DIRECT','id',m.role_member_id,'userId',m.user_id,'updatedAt',m.updated_at) AS vector,
                    NULL::timestamptz AS valid_from,NULL::timestamptz AS valid_to
                  FROM com_role_members m WHERE m.tenant_id=? AND m.role_id=?
                  UNION ALL
                  SELECT 'GROUP',a.group_role_assignment_id::text||':'||m.group_member_id,m.user_id,
                    jsonb_build_object('kind','GROUP','id',a.group_role_assignment_id,'version',a.version,
                      'memberId',m.group_member_id,'memberUpdatedAt',m.updated_at,'userId',m.user_id,
                      'groupId',g.group_id,'groupRevision',g.revision,'groupVersion',g.version,
                      'validFrom',a.valid_from,'validTo',a.valid_to),a.valid_from,a.valid_to
                  FROM com_group_role_assignments a
                  JOIN com_group_members m ON m.tenant_id=a.tenant_id AND m.group_id=a.group_id
                  JOIN com_groups g ON g.tenant_id=m.tenant_id AND g.group_id=m.group_id
                  WHERE a.tenant_id=? AND a.role_id=? AND g.status='ACTIVE'
                    AND a.assignment_type='ACTIVE' AND a.lifecycle_state='ACTIVE' AND a.scope_type='TENANT'
                    AND (a.valid_to IS NULL OR a.valid_to>CURRENT_TIMESTAMP)
                  UNION ALL
                  SELECT 'PRIVILEGED',g.active_privileged_grant_id::text,g.user_id,
                    jsonb_build_object('kind','PRIVILEGED','id',g.active_privileged_grant_id,'userId',g.user_id,
                      'updatedAt',g.updated_at,'validFrom',g.activated_at,'validTo',g.expires_at),g.activated_at,g.expires_at
                  FROM com_active_privileged_grants g WHERE g.tenant_id=? AND g.role_id=?
                    AND g.scope_type='TENANT' AND g.revoked_at IS NULL AND g.expires_at>CURRENT_TIMESTAMP
                )
                SELECT s.vector::text,s.valid_from,s.valid_to FROM source s
                JOIN com_users u ON u.tenant_id=? AND u.user_id=s.user_id AND u.status='ACTIVE' AND u.identity_plane='TENANT'
                ORDER BY s.kind,s.id LIMIT 50001
                """,(r,n)->new Row(json.parse(r.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8),4096),
                        r.getObject(2,OffsetDateTime.class),r.getObject(3,OffsetDateTime.class)),tenant,role,tenant,role,tenant,role,tenant);
        if(rows.size()>50000) throw unavailable();
        Instant deadline=null; var vector=new ArrayList<JsonNode>();
        for(var row:rows) {
            vector.add(row.vector());
            for(var boundary:new OffsetDateTime[]{row.from(),row.until()}) if(boundary!=null && boundary.toInstant().isAfter(now)
                    && (deadline==null || boundary.toInstant().isBefore(deadline))) deadline=boundary.toInstant();
        }
        return new Snapshot(json.tree(vector),deadline);
    }
    private record Row(JsonNode vector,OffsetDateTime from,OffsetDateTime until) { }
    public record Snapshot(JsonNode vector,Instant deadline) {
        public Snapshot { vector=vector.deepCopy(); }
        @Override public JsonNode vector() { return vector.deepCopy(); }
    }
}
