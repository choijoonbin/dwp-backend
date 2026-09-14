package com.dwp.services.approval.documentretention;

import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.UUID;

public final class ApprovalRetentionObjectJobs {
    public record Job(UUID id,String key,String version,String sha256,long size,long generation,UUID token,String locator,boolean presenceVerified) { }
    private final JdbcTemplate jdbc;
    public ApprovalRetentionObjectJobs(DataSource executor){jdbc=new JdbcTemplate(executor);}
    public Job claim(UUID claimId) {
        return jdbc.query("SELECT * FROM apr_retention_internal.claim_object(?)",r->r.next()
                ?new Job(r.getObject("object_intent_id",UUID.class),r.getString("object_key"),r.getString("version_id"),r.getString("content_sha256"),
                    r.getLong("size_bytes"),r.getLong("generation"),r.getObject("lease_token",UUID.class),r.getString("storage_locator_sha256"),r.getBoolean("presence_verified")):null,claimId);
    }
    public void bind(Job job,String version,String locator) {
        jdbc.query("SELECT apr_retention_internal.bind_object_presence(?,?,?,?,?)",r->null,job.id(),job.generation(),job.token(),version,locator);
    }
    public void finish(Job job,String locator,boolean absent) {
        jdbc.query("SELECT apr_retention_internal.finish_object(?,?,?,?,?)",r->null,job.id(),job.generation(),job.token(),locator,absent);
    }
}
