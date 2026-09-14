package com.dwp.services.approval.documentretention.management.receipt;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionErrors;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Capture is enlisted in the original command transaction; replay never calls it. */
@Repository
public class ApprovalRetentionCommandWitnessRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;
    private final ObjectMapper mapper;
    private final ApprovalRetentionCommandProfile profile;

    public ApprovalRetentionCommandWitnessRepository(NamedParameterJdbcTemplate jdbc,ApprovalDocumentCanonical canonical,
            ObjectMapper mapper,ApprovalRetentionCommandProfile profile) {
        this.jdbc=jdbc;this.canonical=canonical;this.mapper=mapper;this.profile=profile;
    }

    public record Captured(ApprovalRequestContext.Actor actor,String scope,ApprovalRetentionCommandProfile.Prepared command,
            Map<String,Object> source,int retentionDays) {}

    public void validateText(Object originalBody) {profile.validateText(originalBody);}

    public Captured prepare(ApprovalRequestContext.Actor actor,String scope,ApprovalRetentionCommandProfile.Operation operation,
            UUID target,Object originalBody,Long originalMaker,Integer publicationRevision,
            ApprovalStepUpVerifier.VerifiedChallenge high,int retentionDays) {
        enlisted();
        var command=profile.prepare(operation,target,originalBody);
        if(retentionDays<1 || retentionDays>3650 || actor.userId()<1 || actor.tenantId()<1
                || actor.userId()>9_007_199_254_740_991L || actor.tenantId()>9_007_199_254_740_991L
                || scope==null || !scope.matches("[A-Z][A-Z0-9_]{2,79}")) throw ApprovalRetentionErrors.invalid();
        var evidence=ApprovalDecisionRevisionContext.current();
        if(operation.high()) {
            if(high==null || evidence.isEmpty() || !Set.of("110","111").contains(evidence.get().rolloutState())) throw ApprovalRetentionErrors.forbidden();
            var binding=high.binding();
            if(!actor.userId().equals(binding.actorUserId()) || !actor.tenantId().equals(binding.tenantId())
                    || !target.toString().equals(binding.targetId()) || !operation.capability().equals(binding.capabilityContractKey())
                    || !command.idempotencyKey().equals(binding.idempotencyKey()) || !command.requestBodySha256().equals(binding.payloadSha256())
                    || !command.originalExpectedVersion().equals(binding.targetVersion())
                    || !("/api/approvals"+command.nativePath()).equals(binding.commandPath())) throw ApprovalRetentionErrors.forbidden();
        }
        if(operation==ApprovalRetentionCommandProfile.Operation.PUBLISH_POLICY
                && (originalMaker==null || originalMaker.equals(actor.userId()) || publicationRevision==null || publicationRevision<1)) {
            throw ApprovalRetentionErrors.forbidden();
        }
        var source=new LinkedHashMap<String,Object>();
        source.put("algorithm",command.algorithm());source.put("operation",operation.name());
        source.put("permission",operation.permission());source.put("capability",operation.capability());
        source.put("tenantId",actor.tenantId());source.put("actorUserId",actor.userId());source.put("resourceSetKey",scope);
        source.put("idempotencyKey",command.idempotencyKey());source.put("requestBodySha256",command.requestBodySha256());
        source.put("originalTargetId",nullable(target));source.put("originalExpectedVersion",nullable(command.originalExpectedVersion()));
        source.put("originPlane",evidence.map(ApprovalDecisionRevisionContext.Evidence::rolloutState).orElse("TRUSTED_COMPAT"));
        source.put("decisionRevision",nullable(evidence.map(ApprovalDecisionRevisionContext.Evidence::revision).orElse(null)));
        source.put("originalPublication",operation==ApprovalRetentionCommandProfile.Operation.PUBLISH_POLICY
                ?Map.of("makerUserId",originalMaker,"revision",publicationRevision):NullNode.instance);
        source.put("verifiedHighBinding",high==null?NullNode.instance:mapper.valueToTree(high.binding()));
        return new Captured(actor,scope,command,Collections.unmodifiableMap(source),retentionDays);
    }

    private Object nullable(Object value) {return value==null?NullNode.instance:value;}

    public void commit(Captured captured,UUID resultReference,long resultVersion) {
        enlisted();selectors(captured.actor());
        if(resultReference==null || resultVersion<0 || resultVersion>9_007_199_254_740_991L) throw ApprovalRetentionErrors.invalid();
        var command=captured.command();var operation=command.operation();
        UUID request=operation==ApprovalRetentionCommandProfile.Operation.CLAIM_RECORD?command.originalTargetId():null;
        UUID policy=operation==ApprovalRetentionCommandProfile.Operation.CLAIM_RECORD?null:
                operation==ApprovalRetentionCommandProfile.Operation.INITIALIZE_POLICY?resultReference:command.originalTargetId();
        UUID commandId=UUID.randomUUID();
        var args=new MapSqlParameterSource().addValue("id",commandId).addValue("tenant",captured.actor().tenantId())
                .addValue("actor",captured.actor().userId()).addValue("operation",operation.name()).addValue("route",command.nativePath())
                .addValue("key",command.idempotencyKey()).addValue("scope",captured.scope()).addValue("target",command.originalTargetId())
                .addValue("result",resultReference).addValue("request",request).addValue("policy",policy)
                .addValue("body",command.requestBodySha256()).addValue("algorithm",command.algorithm())
                .addValue("expected",command.originalExpectedVersion()).addValue("version",resultVersion)
                .addValue("source",canonical.json(captured.source())).addValue("days",captured.retentionDays());
        int rows=jdbc.update("""
            INSERT INTO apr_retention_original_command_witnesses(command_id,tenant_id,actor_user_id,operation,route,
                idempotency_key,resource_set_key,original_target_id,result_reference_id,request_id,policy_id,request_body_sha256,
                canonical_algorithm,parent_command_fingerprint,original_expected_version,result_version,origin_authority_profile,
                source_profile_sha256,committed_at,retain_until)
            SELECT :id,:tenant,:actor,:operation,:route,:key,:scope,:target,:result,:request,:policy,:body,:algorithm,
                parent.fingerprint,:expected,:version,source,encode(sha256(convert_to(source::text,'UTF8')),'hex'),stamp,
                stamp+make_interval(days=>:days)
            FROM apr_retention_management_commands parent CROSS JOIN(SELECT CAST(:source AS jsonb) source,clock_timestamp() stamp) original
            WHERE parent.tenant_id=:tenant AND parent.actor_user_id=:actor AND parent.route=:route AND parent.idempotency_key=:key
                AND parent.resource_set_key=:scope AND parent.target_id=:result AND parent.result_version=:version
            """,args);
        if(rows!=1) throw ApprovalRetentionErrors.unavailable();
        if(operation==ApprovalRetentionCommandProfile.Operation.CLAIM_RECORD) {
            int sealed=jdbc.update("UPDATE apr_retention_dispatch_intents SET receipt_command_id=:id WHERE intent_id=:result AND tenant_id=:tenant AND actor_user_id=:actor AND request_id=:request AND version=0 AND state='QUEUED' AND receipt_command_id IS NULL",args);
            if(sealed!=1) throw ApprovalRetentionErrors.unavailable();
        }
    }

    void selectors(ApprovalRequestContext.Actor actor) {
        jdbc.queryForObject("SELECT set_config('dwp.approval.retention.receipt.tenant',:tenant,true)",Map.of("tenant",actor.tenantId().toString()),String.class);
        jdbc.queryForObject("SELECT set_config('dwp.approval.retention.receipt.actor',:actor,true)",Map.of("actor",actor.userId().toString()),String.class);
    }
    void enlisted() {
        var source=jdbc.getJdbcTemplate().getDataSource();
        if(source==null || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly() || !TransactionSynchronizationManager.hasResource(source)) {
            throw ApprovalRetentionErrors.unavailable();
        }
        var connection=DataSourceUtils.getConnection(source);
        try {
            if(!DataSourceUtils.isConnectionTransactional(connection,source) || connection.getAutoCommit()
                    || connection.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED) throw ApprovalRetentionErrors.unavailable();
        } catch(SQLException failure) {throw ApprovalRetentionErrors.unavailable();}
        finally {DataSourceUtils.releaseConnection(connection,source);}
    }
}
