package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorum.*;

import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Server-only authoring source. No public dispatch or candidate readiness is enabled by this DB guard. */
public final class ApprovalWorkflowStudioSource {
    public record Selection(UUID workflowId, long workflowRevision, UUID workflowVersionId, String workflowSha256,
            UUID formVersionId, String formSchemaSha256, long policyVersion, String policySha256,
            String managementResourceSetKey, Map<String, Object> samplePayload) {
        public Selection {
            if(workflowId==null || workflowRevision<0 || workflowVersionId==null || !sha256(workflowSha256)
                    || formVersionId==null || !sha256(formSchemaSha256) || policyVersion<1 || !sha256(policySha256)
                    || managementResourceSetKey==null || !managementResourceSetKey.matches("[A-Za-z][A-Za-z0-9_.:-]{0,159}")
                    || samplePayload==null || samplePayload.size()>100 || samplePayload.containsKey("createdFrom"))
                throw invalid("Studio planning requires exact server version pins and a bounded typed sample.");
            samplePayload=ApprovalFormSchemaV2Canonical.freeze(samplePayload);
        }
    }

    /** The dedicated signed planning adapter must deny unknown evidence; fixture guards are not activation proof. */
    public interface OwnerGuard {
        void require(Actor actor, Selection selection, Snapshot serverSnapshot, Instant now);
    }

    public record StagePath(String stepKey, boolean selected, List<String> predecessors) {
        public StagePath {predecessors=List.copyOf(predecessors);}
    }

    public static final class Snapshot {
        private final Map<String,Object> material;
        private final ApprovalWorkflowQuorumDefinition definition;
        private final List<StagePath> topology;
        private final String canonicalJson;
        private final String sha256;
        private Snapshot(Map<String,Object> material, ApprovalWorkflowQuorumDefinition definition, List<StagePath> topology) {
            this.material=ApprovalFormSchemaV2Canonical.freeze(material);this.definition=definition;this.topology=List.copyOf(topology);
            canonicalJson=ApprovalFormSchemaV2Canonical.json(this.material);
            sha256=ApprovalFormSchemaV2Canonical.sha256(canonicalJson);
        }
        public Map<String,Object> material() {return material;}
        public ApprovalWorkflowQuorumDefinition definition() {return definition;}
        public List<StagePath> topology() {return topology;}
        public String canonicalJson() {return canonicalJson;}
        public String sha256() {return sha256;}
        public Instant authorityDeadline() {
            Object end=material.get("effectiveTo");
            return "PUBLISHED".equals(material.get("workflow_state")) && end!=null ? Instant.parse((String)end) : null;
        }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalWorkflowQuorumRuntimeStore store;
    private final TransactionTemplate coherent, current;
    private final OwnerGuard owner;

    public ApprovalWorkflowStudioSource(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, TransactionTemplate transactions, OwnerGuard owner) {
        if(owner==null) throw unavailable("Dedicated Studio owner authority is unavailable.");
        this.jdbc=jdbc;this.store=new ApprovalWorkflowQuorumRuntimeStore(jdbc,mapper);this.owner=owner;
        coherent=readOnly(transactions,TransactionDefinition.ISOLATION_REPEATABLE_READ);
        current=readOnly(transactions,TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private static TransactionTemplate readOnly(TransactionTemplate source,int isolation) {
        var result=new TransactionTemplate(java.util.Objects.requireNonNull(source.getTransactionManager()));
        result.setReadOnly(true);result.setIsolationLevel(isolation);result.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);return result;
    }

    public <T> T evaluate(Actor actor, Selection selection, Function<Snapshot,T> planningExchange) {
        if(actor==null || actor.userId()==null || actor.userId()<1 || actor.tenantId()==null || actor.tenantId()<1
                || actor.personPublicId()==null || selection==null || planningExchange==null
                || !actor.equals(serverActor())) throw new BaseException(ErrorCode.FORBIDDEN);
        owner.require(actor,selection,null,Instant.now());
        return coherent.execute(status->{
            Snapshot snapshot=read(actor,selection);owner.require(actor,selection,snapshot,store.now());
            requireCurrent(actor,selection,snapshot);
            T result=planningExchange.apply(snapshot);
            requireCurrent(actor,selection,snapshot);
            owner.require(actor,selection,snapshot,store.now());return result;
        });
    }

    private void requireCurrent(Actor actor,Selection selection,Snapshot snapshot) {
        // A repeatable-read snapshot cannot see a concurrent head/policy change; recheck through a fresh transaction.
        current.execute(status->{Snapshot fresh=read(actor,selection);owner.require(actor,selection,fresh,store.now());
            if(!snapshot.sha256().equals(fresh.sha256())) throw ApprovalWorkflowQuorumRuntimeStore.conflict();return null;});
    }
    private static Actor serverActor() {
        try {return com.dwp.services.approval.security.ApprovalRequestContext.require();}
        catch(IllegalStateException missing) {throw new BaseException(ErrorCode.FORBIDDEN);}
    }

    private Snapshot read(Actor actor,Selection selected) {
        var p=new MapSqlParameterSource("tenant",actor.tenantId()).addValue("workflow",selected.workflowId())
                .addValue("version",selected.workflowVersionId()).addValue("formVersion",selected.formVersionId())
                .addValue("scope",selected.managementResourceSetKey());
        var rows=jdbc.queryForList("""
                SELECT workflow.version workflow_revision,workflow.current_version workflow_head,workflow.lifecycle_state workflow_parent_state,
                       version.version_number workflow_version,version.lifecycle_state workflow_state,version.definition::text definition,
                       version.definition_sha256 workflow_sha,version.effective_from,version.effective_to,
                       form.form_id,form.version form_revision,form.current_version form_head,form.lifecycle_state form_parent_state,
                       schema.version_number form_version,schema.lifecycle_state form_state,schema.schema_payload::text schema,
                       schema.schema_sha256 schema_sha
                  FROM apr_tenants tenant JOIN apr_workflow_definitions workflow ON workflow.tenant_id=tenant.tenant_id
                  JOIN apr_workflow_versions version ON version.tenant_id=workflow.tenant_id AND version.workflow_id=workflow.workflow_id
                  JOIN apr_form_versions schema ON schema.tenant_id=workflow.tenant_id AND schema.form_version_id=:formVersion
                  JOIN apr_forms form ON form.tenant_id=schema.tenant_id AND form.form_id=schema.form_id
                 WHERE tenant.tenant_id=:tenant AND tenant.lifecycle_state='ACTIVE' AND workflow.workflow_id=:workflow
                   AND version.workflow_version_id=:version AND workflow.management_resource_set_key=:scope
                   AND form.management_resource_set_key=:scope
                   AND workflow.lifecycle_state IN ('DRAFT','PUBLISHED') AND form.lifecycle_state IN ('DRAFT','PUBLISHED')
                   AND version.lifecycle_state IN ('DRAFT','PUBLISHED') AND schema.lifecycle_state IN ('DRAFT','PUBLISHED')
                   AND (version.lifecycle_state='PUBLISHED' OR version.version_number=workflow.current_version)
                   AND (schema.lifecycle_state='PUBLISHED' OR schema.version_number=form.current_version)
                """,p);
        if(rows.size()!=1) throw new BaseException(ErrorCode.NOT_FOUND);
        var row=rows.getFirst();if(((Number)row.get("workflow_revision")).longValue()!=selected.workflowRevision()) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        if("PUBLISHED".equals(row.get("workflow_state"))) {
            Instant now=store.now();String from=instant(row.get("effective_from")),to=instant(row.get("effective_to"));
            if(from!=null && now.isBefore(Instant.parse(from)) || to!=null && !now.isBefore(Instant.parse(to)))
                throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        }
        var definition=ApprovalWorkflowQuorumDefinition.compile((String)row.get("definition"));
        var schema=new ApprovalFormSchemaV2Compiler().compile(store.object((String)row.get("schema")));
        if(!definition.sha256().equals(((String)row.get("workflow_sha")).strip()) || !definition.sha256().equals(selected.workflowSha256())
                || !schema.sha256().equals(((String)row.get("schema_sha")).strip()) || !schema.sha256().equals(selected.formSchemaSha256()))
            throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var sample=new ApprovalFormSchemaV2Evaluator().evaluate(schema,selected.samplePayload(),true).payload();
        var policy=policy(p);if(policy.version()!=selected.policyVersion() || !policy.sha256().equals(selected.policySha256())) throw ApprovalWorkflowQuorumRuntimeStore.conflict();
        var skipped=new java.util.HashSet<String>();var topology=new java.util.ArrayList<StagePath>();
        for(var stage:definition.topologicalStages()) {
            boolean active=(stage.predecessors().isEmpty() || !skipped.containsAll(stage.predecessors()))
                    && ApprovalWorkflowStageCondition.matches(schema.canonicalJson(),sample,stage.routeCondition());
            if(!active) skipped.add(stage.key());topology.add(new StagePath(stage.key(),active,stage.predecessors()));
        }
        var material=new java.util.TreeMap<String,Object>();material.put("tenantId",actor.tenantId());material.put("workflowId",selected.workflowId().toString());
        material.put("workflowVersionId",selected.workflowVersionId().toString());material.put("formVersionId",selected.formVersionId().toString());
        for(String key:List.of("workflow_revision","workflow_head","workflow_parent_state","workflow_version","workflow_state","form_revision","form_head","form_parent_state","form_version","form_state")) material.put(key,row.get(key));
        material.put("formId",row.get("form_id").toString());material.put("workflowDefinitionSha256",definition.sha256());material.put("formSchemaSha256",schema.sha256());
        material.put("managementResourceSetKey",selected.managementResourceSetKey());material.put("policyVersion",policy.version());material.put("policySha256",policy.sha256());
        material.put("policyReferences",policy.references());material.put("samplePayload",sample);material.put("samplePayloadSha256",store.hash(ApprovalFormSchemaV2Canonical.json(sample)));
        material.put("effectiveFrom",instant(row.get("effective_from")));material.put("effectiveTo",instant(row.get("effective_to")));
        return new Snapshot(material,definition,topology);
    }
    private static String instant(Object value) {
        if(value==null) return null;if(value instanceof java.sql.Timestamp time) return time.toInstant().toString();
        if(value instanceof java.time.OffsetDateTime time) return time.toInstant().toString();throw ApprovalWorkflowQuorumRuntimeStore.conflict();
    }

    private ApprovalWorkflowQuorumRuntimeStore.Policy policy(MapSqlParameterSource p) {
        var rows=jdbc.queryForList("""
                SELECT policy_id,policy_key,version,enforcement_mode,lifecycle_state,rule_payload::text rule
                  FROM apr_policy_rules WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND policy_key IN ('BLOCK_SELF_APPROVAL','REQUIRE_REJECT_REASON','SLA_ESCALATION') ORDER BY policy_key
                """,p);
        if(rows.size()!=3 || rows.stream().anyMatch(row->!"ACTIVE".equals(row.get("lifecycle_state")))) throw unavailable("The Studio policy scope is incomplete.");
        var reject=rows.stream().filter(row->"REQUIRE_REJECT_REASON".equals(row.get("policy_key"))).findFirst().orElseThrow();
        var sla=rows.stream().filter(row->"SLA_ESCALATION".equals(row.get("policy_key"))).findFirst().orElseThrow();
        var rejectRule=store.object((String)reject.get("rule"));var slaRule=store.object((String)sla.get("rule"));
        int minimum=integer(rejectRule.get("minimumLength"),4,1000),warning=integer(slaRule.get("warningPercent"),1,99),breach=integer(slaRule.get("breachPercent"),warning,100);
        var refs=rows.stream().map(row->Map.<String,Object>of("policyId",row.get("policy_id").toString(),"key",row.get("policy_key"),
                "rowVersion",row.get("version"),"enforcement",row.get("enforcement_mode"),"rule",store.object((String)row.get("rule")))).toList();
        return new ApprovalWorkflowQuorumRuntimeStore.Policy(Math.addExact(((Number)sla.get("version")).longValue(),1),
                store.hash(ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(Map.of("references",refs)))),minimum,warning,breach,refs);
    }
    private static int integer(Object value,int min,int max) {
        if(!(value instanceof Integer number) || number<min || number>max) throw unavailable("Studio policy integers are incomplete.");return number;
    }
}
