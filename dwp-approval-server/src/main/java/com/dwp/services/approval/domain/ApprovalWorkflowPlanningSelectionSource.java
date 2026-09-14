package com.dwp.services.approval.domain;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningSelection;
import com.dwp.services.approval.workflowplanning.WorkflowPlanningSelectionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact current authoring pins only. Private snapshots are not TASK eligibility or runtime admissions. */
public final class ApprovalWorkflowPlanningSelectionSource {
    private record Snapshot(WorkflowPlanningSelection result,String digest) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalWorkflowQuorumRuntimeStore store;
    private final TransactionTemplate coherent,current;
    public ApprovalWorkflowPlanningSelectionSource(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,PlatformTransactionManager manager) {
        this.jdbc=jdbc;store=new ApprovalWorkflowQuorumRuntimeStore(jdbc,mapper);
        coherent=readOnly(manager,TransactionDefinition.ISOLATION_REPEATABLE_READ);
        current=readOnly(manager,TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private static TransactionTemplate readOnly(PlatformTransactionManager manager,int isolation) {
        var tx=new TransactionTemplate(manager);tx.setReadOnly(true);tx.setIsolationLevel(isolation);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);return tx;
    }
    public WorkflowPlanningSelection evaluate(WorkflowPlanningSelectionContext.Seal owner,Runnable requireOwner) {
        requireOwner.run();
        return coherent.execute(status->{
            var snapshot=read(owner);requireOwner.run();requireCurrent(owner,snapshot,requireOwner);
            requireOwner.run();requireCurrent(owner,snapshot,requireOwner);requireOwner.run();return snapshot.result();
        });
    }
    private void requireCurrent(WorkflowPlanningSelectionContext.Seal owner,Snapshot snapshot,Runnable guard) {
        current.execute(status->{guard.run();var fresh=read(owner);guard.run();
            if(!snapshot.digest().equals(fresh.digest())) throw conflict();return null;});
    }
    private Snapshot read(WorkflowPlanningSelectionContext.Seal owner) {
        var p=new MapSqlParameterSource("tenant",owner.tenantId()).addValue("workflow",owner.workflowId()).addValue("scope",owner.resourceSetKey());
        var rows=jdbc.queryForList("""
                SELECT workflow.version revision,version.workflow_version_id,version.definition::text definition,
                       version.definition_sha256 sha,version.lifecycle_state state,version.effective_from,version.effective_to
                  FROM apr_tenants tenant JOIN apr_workflow_definitions workflow ON workflow.tenant_id=tenant.tenant_id
                  JOIN apr_workflow_versions version ON version.tenant_id=workflow.tenant_id AND version.workflow_id=workflow.workflow_id
                       AND version.version_number=workflow.current_version
                 WHERE tenant.tenant_id=:tenant AND tenant.lifecycle_state='ACTIVE' AND workflow.workflow_id=:workflow
                   AND workflow.management_resource_set_key=:scope AND workflow.lifecycle_state IN ('DRAFT','PUBLISHED')
                   AND version.lifecycle_state IN ('DRAFT','PUBLISHED')
                """,p);
        if(rows.size()!=1) throw new BaseException(ErrorCode.NOT_FOUND);
        var row=rows.getFirst();Instant now=store.now();
        if("PUBLISHED".equals(row.get("state"))) {
            Instant from=instant(row.get("effective_from")),to=instant(row.get("effective_to"));
            if(from!=null && now.isBefore(from) || to!=null && !now.isBefore(to)) throw conflict();
        }
        var definition=ApprovalWorkflowQuorumDefinition.compile((String)row.get("definition"));
        if(!definition.sha256().equals(((String)row.get("sha")).strip())) throw conflict();
        var forms=jdbc.queryForList("""
                SELECT form.form_id,form.version revision,schema.form_version_id,schema.version_number,
                       schema.schema_payload::text schema,schema.schema_sha256 sha
                  FROM apr_forms form JOIN apr_form_versions schema ON schema.tenant_id=form.tenant_id AND schema.form_id=form.form_id
                       AND schema.version_number=form.current_version
                 WHERE form.tenant_id=:tenant AND form.management_resource_set_key=:scope
                   AND form.lifecycle_state='PUBLISHED' AND schema.lifecycle_state='PUBLISHED'
                   AND schema.schema_payload->>'schemaContract'=:schemaContract
                 ORDER BY form.form_id LIMIT 101
                """,p.addValue("schemaContract",ApprovalFormSchemaV2.CONTRACT));
        if(forms.size()>100) throw unavailable();
        var pins=forms.stream().map(form->{
            var compiled=new ApprovalFormSchemaV2Compiler().compile(store.object((String)form.get("schema")));
            if(!compiled.sha256().equals(((String)form.get("sha")).strip())) throw conflict();
            return new WorkflowPlanningSelection.FormPin((UUID)form.get("form_id"),(UUID)form.get("form_version_id"),
                    ((Number)form.get("revision")).longValue(),((Number)form.get("version_number")).intValue(),compiled.sha256());
        }).toList();
        if(owner.selectedFormId()!=null && pins.stream().noneMatch(form->owner.selectedFormId().equals(form.formId()))) throw new BaseException(ErrorCode.NOT_FOUND);
        var policy=policy(p);
        var result=new WorkflowPlanningSelection(owner.workflowId(),(UUID)row.get("workflow_version_id"),((Number)row.get("revision")).longValue(),
                definition.sha256(),owner.resourceSetKey(),policy,pins,owner.selectedFormId(),now);
        var material=new java.util.TreeMap<String,Object>();material.put("workflowVersionId",result.workflowVersionId().toString());
        material.put("workflowRevision",result.workflowRevision());material.put("workflowSha256",result.workflowSha256());
        material.put("policyVersion",policy.version());material.put("policySha256",policy.sha256());
        material.put("forms",pins.stream().map(form->Map.of("id",form.formId().toString(),"versionId",form.formVersionId().toString(),
                "revision",form.formRevision(),"version",form.formVersion(),"sha256",form.formSchemaSha256())).toList());
        material.put("state",row.get("state"));material.put("from",instantText(row.get("effective_from")));material.put("to",instantText(row.get("effective_to")));
        return new Snapshot(result,ApprovalFormSchemaV2Canonical.sha256(ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(material))));
    }
    private WorkflowPlanningSelection.PolicyPin policy(MapSqlParameterSource p) {
        var rows=jdbc.queryForList("""
                SELECT policy_id,policy_key,version,enforcement_mode,lifecycle_state,rule_payload::text rule
                  FROM apr_policy_rules WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND policy_key IN ('BLOCK_SELF_APPROVAL','REQUIRE_REJECT_REASON','SLA_ESCALATION') ORDER BY policy_key
                """,p);
        if(rows.size()!=3 || rows.stream().anyMatch(row->!"ACTIVE".equals(row.get("lifecycle_state")))) throw unavailable();
        var reject=rows.stream().filter(row->"REQUIRE_REJECT_REASON".equals(row.get("policy_key"))).findFirst().orElseThrow();
        var sla=rows.stream().filter(row->"SLA_ESCALATION".equals(row.get("policy_key"))).findFirst().orElseThrow();
        integer(store.object((String)reject.get("rule")).get("minimumLength"),4,1000);
        var rule=store.object((String)sla.get("rule"));int warning=integer(rule.get("warningPercent"),1,99);integer(rule.get("breachPercent"),warning,100);
        var refs=rows.stream().map(row->Map.<String,Object>of("policyId",row.get("policy_id").toString(),"key",row.get("policy_key"),
                "rowVersion",row.get("version"),"enforcement",row.get("enforcement_mode"),"rule",store.object((String)row.get("rule")))).toList();
        return new WorkflowPlanningSelection.PolicyPin(Math.addExact(((Number)sla.get("version")).longValue(),1),
                store.hash(ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(Map.of("references",refs)))));
    }
    private static int integer(Object value,int min,int max) {
        if(!(value instanceof Integer number) || number<min || number>max) throw unavailable();return number;
    }
    private static Instant instant(Object value) {
        if(value==null) return null;if(value instanceof java.sql.Timestamp time) return time.toInstant();
        if(value instanceof java.time.OffsetDateTime time) return time.toInstant();throw conflict();
    }
    private static String instantText(Object value) {var instant=instant(value);return instant==null?null:instant.toString();}
    private static BaseException conflict() {return new BaseException(ErrorCode.RESOURCE_CONFLICT);}
}
