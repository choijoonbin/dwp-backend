package com.dwp.services.approval.documentretention.management.receipt;

import com.dwp.services.approval.documentretention.management.ApprovalRetentionErrors;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionManagementRepository;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.dwp.services.approval.documentretention.management.receipt.ApprovalRetentionCommandProfile.Operation;

@Repository
public class ApprovalRetentionReceiptRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ApprovalRetentionReceiptProfileParser parser;
    private final ApprovalRetentionCommandWitnessRepository writes;
    private final ApprovalRetentionManagementRepository management;
    public ApprovalRetentionReceiptRepository(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,
            ApprovalRetentionReceiptProfileParser parser,ApprovalRetentionCommandWitnessRepository writes,
            ApprovalRetentionManagementRepository management) {
        this.jdbc=jdbc;this.mapper=mapper;this.parser=parser;this.writes=writes;this.management=management;
    }

    public ApprovalRetentionCommandReceipt read(ApprovalRequestContext.Actor actor,String scope,Operation operation,UUID target,String key) {
        writes.enlisted();management.tenant(actor);
        if(actor.userId()<1 || actor.userId()>9_007_199_254_740_991L || actor.tenantId()<1
                || actor.tenantId()>9_007_199_254_740_991L) throw ApprovalRetentionErrors.forbidden();
        writes.selectors(actor);
        var args=Map.<String,Object>of("tenant",actor.tenantId(),"actor",actor.userId(),"route",operation.nativePath(target),"key",key);
        var rows=jdbc.query("""
            SELECT p.target_id parent_result,p.result_version parent_version,p.resource_set_key parent_scope,p.fingerprint,
                w.*,w.origin_authority_profile::text source,
                encode(sha256(convert_to(w.origin_authority_profile::text,'UTF8')),'hex') actual_source_sha
            FROM apr_retention_management_commands p LEFT JOIN apr_retention_original_command_witnesses w
                USING(tenant_id,actor_user_id,route,idempotency_key)
            WHERE p.tenant_id=:tenant AND p.actor_user_id=:actor AND p.route=:route AND p.idempotency_key=:key
            FOR SHARE OF p
            """,args,(r,n)->map(r,actor,scope,operation,target,key));
        if(rows.isEmpty()) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM apr_retention_management_commands WHERE tenant_id=:tenant AND route=:route AND idempotency_key=:key AND actor_user_id<>:actor)",args,Boolean.class))) {
                throw ApprovalRetentionErrors.forbidden();
            }
            throw ApprovalRetentionErrors.hidden();
        }
        if(rows.size()!=1) throw ApprovalRetentionErrors.unavailable();var receipt=rows.getFirst();
        if(operation==Operation.CLAIM_RECORD) {
            management.request(actor,scope,target,false);
            var intent=management.claim(actor,scope,receipt.resultReferenceId());
            if(!target.equals(intent.requestId())) throw ApprovalRetentionErrors.unavailable();
        } else management.policy(actor,scope,operation==Operation.INITIALIZE_POLICY?receipt.resultReferenceId():target,false);
        return receipt;
    }
    private ApprovalRetentionCommandReceipt map(ResultSet r,ApprovalRequestContext.Actor actor,String scope,Operation operation,UUID target,String key) throws SQLException {
        if(!scope.equals(r.getString("parent_scope"))) throw ApprovalRetentionErrors.forbidden();
        if(r.getObject("command_id")==null) throw ApprovalRetentionReceiptErrors.metadataUnavailable();
        var result=r.getObject("result_reference_id",UUID.class);var original=r.getObject("original_target_id",UUID.class);
        Long expected=r.getObject("original_expected_version",Long.class);long version=r.getLong("result_version");
        if(!operation.name().equals(r.getString("operation")) || !java.util.Objects.equals(original,target)
                || !scope.equals(r.getString("resource_set_key")) || !result.equals(r.getObject("parent_result",UUID.class))
                || version!=r.getLong("parent_version") || !r.getString("parent_command_fingerprint").equals(r.getString("fingerprint"))
                || !ApprovalRetentionCommandProfile.ALGORITHM.equals(r.getString("canonical_algorithm"))
                || !r.getString("actual_source_sha").equals(r.getString("source_profile_sha256"))) throw ApprovalRetentionErrors.unavailable();
        String body=r.getString("request_body_sha256");if(body==null || !body.matches("[a-f0-9]{64}")) throw ApprovalRetentionErrors.unavailable();
        if((operation==Operation.INITIALIZE_POLICY)!=(expected==null) || version<0 || version>9_007_199_254_740_991L
                || (expected!=null && (expected<0 || expected>9_007_199_254_740_991L))) throw ApprovalRetentionErrors.unavailable();
        String tag;
        try {
            var source=mapper.readTree(r.getString("source"));tag=parser.validate(source,actor,scope,operation,target,key,body,expected);
            if(operation==Operation.PUBLISH_POLICY) {
                var args=Map.of("tenant",actor.tenantId(),"policy",target,"maker",source.path("originalPublication").path("makerUserId").longValue(),
                        "revision",source.path("originalPublication").path("revision").intValue(),"checker",actor.userId());
                if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM apr_retention_policy_publications WHERE tenant_id=:tenant AND policy_id=:policy AND revision=:revision AND maker_user_id=:maker AND checker_user_id=:checker AND maker_user_id<>checker_user_id)",args,Boolean.class))) throw ApprovalRetentionErrors.unavailable();
            }
        } catch(java.io.IOException invalid) {throw ApprovalRetentionErrors.unavailable();}
        var committed=r.getObject("committed_at",OffsetDateTime.class);var deadline=r.getObject("retain_until",OffsetDateTime.class);
        if(committed==null || deadline==null || committed.isAfter(OffsetDateTime.now()) || deadline.isBefore(committed)) throw ApprovalRetentionErrors.unavailable();
        return new ApprovalRetentionCommandReceipt(r.getObject("command_id",UUID.class),operation.name(),key,actor.userId(),scope,
                original,result,body,expected,version,"COMMITTED",committed,tag,ApprovalRetentionCommandProfile.VERSION);
    }
}
