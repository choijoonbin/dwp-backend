package com.dwp.services.platform.workplace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** Executes current native tenant retention policy; no external deletion guarantee. */
@Service
public class WorkplaceExperienceFacilitiesRetention {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public WorkplaceExperienceFacilitiesRetention(JdbcTemplate jdbc) {
        this.jdbc = jdbc; this.named = new NamedParameterJdbcTemplate(jdbc);
    }
    public record Purged(int requests, int closures) { }

    @Transactional
    public Purged purge(int batchSize) {
        return purgeScoped(null,batchSize);
    }

    @Transactional
    public Purged purgeTenant(Long tenant, int batchSize) {
        if(tenant==null || tenant<=0) throw new IllegalArgumentException("A native tenant scope is required");
        return purgeScoped(tenant,batchSize);
    }

    private Purged purgeScoped(Long tenant,int batchSize) {
        if (batchSize < 1 || batchSize > 5000) throw new IllegalArgumentException("Invalid facility retention batch size");
        int requests = purgeKind("REQUEST",tenant,batchSize), closures = purgeKind("CLOSURE",tenant,batchSize);
        return new Purged(requests,closures);
    }

    private int purgeKind(String kind, Long tenant,int batchSize) {
        boolean request = "REQUEST".equals(kind);
        String table = request ? "wp_experience_facility_requests" : "wp_experience_facility_closures";
        String idColumn = request ? "request_id" : "closure_id";
        String eligible = request ? "wp_experience_facility_retention_eligible_requests" : "wp_experience_facility_retention_eligible_closures";
        var candidates = named.query("SELECT x."+idColumn+", x.tenant_id, x.resource_id FROM "+table+" x "
                + "JOIN "+eligible+" e ON e.tenant_id=x.tenant_id AND e."+idColumn+"=x."+idColumn
                + (tenant==null?"":" WHERE x.tenant_id=:tenant")
                + " ORDER BY x.updated_at,x."+idColumn+" FOR UPDATE OF x SKIP LOCKED LIMIT :batch",
                new MapSqlParameterSource("tenant",tenant).addValue("batch",batchSize),
                (rs,i) -> new Candidate(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class)));
        int purged = 0;
        for (Candidate c : candidates) {
            if (!request) lockClosureEvidence(c);
            List<UUID> audits = lockAuditEvidence(c,"FACILITY_"+kind);
            // Re-evaluate after row/delivery/booking locks: status, hold and lease
            // changes committed while waiting must be observed before deletion.
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+eligible
                    +" WHERE tenant_id=? AND "+idColumn+"=?)",Boolean.class,c.tenant,c.id))) continue;
            redactAudit(c,audits);
            int deleted = jdbc.update("DELETE FROM "+table+" WHERE tenant_id=? AND "+idColumn+"=?",c.tenant,c.id);
            if (deleted == 1) {
                jdbc.update("INSERT INTO wp_experience_facility_retention_counters(tenant_id,requests_purged,closures_purged,last_purged_at) "
                        +"VALUES(?,?,?,CURRENT_TIMESTAMP) ON CONFLICT(tenant_id) DO UPDATE SET requests_purged="
                        +"wp_experience_facility_retention_counters.requests_purged+EXCLUDED.requests_purged,closures_purged="
                        +"wp_experience_facility_retention_counters.closures_purged+EXCLUDED.closures_purged,last_purged_at=CURRENT_TIMESTAMP",
                        c.tenant,request?1:0,request?0:1);
                purged++;
            }
        }
        return purged;
    }

    private void lockClosureEvidence(Candidate c) {
        List<UUID> calendar = jdbc.query("SELECT calendar_resource_id FROM wp_resources WHERE tenant_id=? AND resource_id=? AND resource_type='ROOM' AND calendar_resource_id IS NOT NULL",
                (rs,i)->rs.getObject(1,UUID.class),c.tenant,c.resource);
        calendar.forEach(id -> jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},c.tenant+":"+id));
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{},"workplace-resource:"+c.tenant+":"+c.resource);
        jdbc.query("SELECT b.booking_id FROM wp_bookings b JOIN wp_experience_facility_closures x ON x.tenant_id=b.tenant_id AND x.resource_id=b.resource_id "
                +"WHERE x.tenant_id=? AND x.closure_id=? AND b.starts_at<x.ends_at AND b.ends_at>x.starts_at FOR UPDATE OF b",
                (rs,i)->rs.getObject(1,UUID.class),c.tenant,c.id);
    }

    private List<UUID> lockAuditEvidence(Candidate c,String kind) {
        List<UUID> ids = jdbc.query("SELECT audit_event_id FROM wp_audit_events WHERE tenant_id=? AND aggregate_type=? AND aggregate_id=? FOR UPDATE",
                (rs,i)->rs.getObject(1,UUID.class),c.tenant,kind,c.id);
        if (!ids.isEmpty()) named.query("SELECT event_id FROM sys_audit_outbox WHERE tenant_id=:tenant AND event_id IN (:ids) FOR UPDATE",
                new MapSqlParameterSource("tenant",c.tenant).addValue("ids",ids),(rs,i)->rs.getObject(1,UUID.class));
        return ids;
    }

    private void redactAudit(Candidate c,List<UUID> audits) {
        if(audits.isEmpty()) return;
        var p = new MapSqlParameterSource("tenant",c.tenant).addValue("ids",audits);
        jdbc.execute("SELECT set_config('dwp.facility_retention_redaction','on',true)");
        jdbc.execute("SELECT set_config('dwp.audit_retention_bypass','on',true)");
        named.update("UPDATE wp_audit_events SET actor_user_id=0,correlation_id=NULL,snapshot='{\"retentionAction\":\"FACILITY_PERSONAL_DATA_REDACTED\"}'::jsonb WHERE tenant_id=:tenant AND audit_event_id IN (:ids)",p);
        named.update("UPDATE sys_platform_audit_events SET actor_type='SERVICE',actor_id=NULL,correlation_id=NULL,before_snapshot=NULL,after_snapshot='{\"retentionAction\":\"FACILITY_PERSONAL_DATA_REDACTED\"}' WHERE tenant_id=:tenant AND audit_event_id IN (:ids)",p);
        named.update("""
                UPDATE sys_audit_outbox o SET payload=jsonb_build_object(
                  'eventId',a.audit_event_id,'eventVersion','1.0','occurredAt',a.occurred_at,'tenantId',a.tenant_id,
                  'category','ADMIN_CHANGE','action',a.action,'outcome',a.outcome,'severity','INFO','riskScore',10,
                  'actorType','SERVICE','actorId',NULL,'actorRoles','[]'::jsonb,'sourceService','dwp-platform-server',
                  'sourceModule','platform-administration','environment',COALESCE(o.payload->>'environment','local'),
                  'targetType',a.target_type,'targetId',a.target_id,'targetDisplayName',a.target_id,
                  'beforeState','{}'::jsonb,'afterState',a.after_snapshot::jsonb,
                  'metadata',jsonb_build_object('legacyAuditEventId',a.audit_event_id),'retentionClass','STANDARD'),
                  last_error=NULL,updated_at=CURRENT_TIMESTAMP
                FROM sys_platform_audit_events a WHERE a.tenant_id=:tenant AND a.audit_event_id IN (:ids)
                  AND o.tenant_id=a.tenant_id AND o.event_id=a.audit_event_id
                """,p);
        named.update("DELETE FROM sys_audit_events WHERE tenant_id=:tenant AND event_id IN (:ids)",p);
        jdbc.execute("SELECT set_config('dwp.facility_retention_redaction','off',true)");
        jdbc.execute("SELECT set_config('dwp.audit_retention_bypass','off',true)");
    }
    private record Candidate(UUID id,long tenant,UUID resource) { }
}
