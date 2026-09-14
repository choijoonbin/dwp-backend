package com.dwp.services.approval.forms;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.*;
import com.dwp.services.approval.forms.ApprovalFormWorkspaceRepository.Head;
import com.dwp.services.approval.forms.ApprovalFormWorkspaceRepository.Policy;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalFormLifecycleStore {
    private final ApprovalFormWorkspaceRepository repo;
    public ApprovalFormLifecycleStore(ApprovalFormWorkspaceRepository repo) { this.repo=repo; }
    public void expected(Head head,Long formRevision,Long workspaceRevision) {
        if(formRevision==null||head.revision()!=formRevision||!java.util.Objects.equals(head.workspaceRevision(),workspaceRevision)) throw conflict();
    }
    private void adopt(Actor actor,Head head) {
        if(head.workspaceRevision()!=null) return;
        if(!java.util.Set.of("DRAFT","PUBLISHED").contains(head.state())) throw conflict();
        repo.jdbc.update("""
            INSERT INTO apr_form_workspaces(tenant_id,form_id,published_form_version_id,draft_form_version_id,
                catalog_availability,created_by,updated_by) VALUES(:tenant,:form,:published,:draft,:availability,:actor,:actor)
            """,repo.params(actor,head.formId()).addValue("published",head.published()).addValue("draft",head.draft())
                        .addValue("availability",head.availability().name()));
    }
    public UUID branch(Actor actor,Head head,Version source) {
        if(!"PUBLISHED".equals(source.lifecycleState())||head.draft()!=null||!"PUBLISHED".equals(head.state())) throw conflict();
        Policy policy=source.route().isEmpty()?repo.policy(actor,head.formId(),head.metadata(),
                UUID.fromString((String)repo.legacyRoute(actor,head.formId()).get("workflowId")),true)
                :repo.policy(actor,head.formId(),metadata(source.metadata()),UUID.fromString((String)source.route().get("workflowId")),true);
        captureCurrentPublished(actor,head,null);
        adopt(actor,head);
        Integer max=repo.jdbc.queryForObject("SELECT MAX(version_number) FROM apr_form_versions WHERE tenant_id=:tenant AND form_id=:form",
                repo.params(actor,head.formId()),Integer.class);
        if(max==null||max==Integer.MAX_VALUE) throw conflict();
        UUID id=UUID.randomUUID();
        String schema=repo.codec.json(source.schema());
        String hash=repo.codec.sha(schema);
        if(source.schema().containsKey("schemaContract")) {
            var typed=repo.codec.authoring(source.schema());schema=typed.json();hash=typed.sha256();
        }
        var params=repo.params(actor,head.formId()).addValue("id",id).addValue("number",max+1)
                .addValue("schema",schema).addValue("sha",hash)
                .addValue("source",source.formVersionId()).addValue("base",head.published());
        repo.jdbc.update("""
            INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,lifecycle_state,created_by)
              VALUES(:id,:tenant,:form,:number,CAST(:schema AS jsonb),:sha,'DRAFT',:actor)
            """,params);
        material(params,hash,policy);
        repo.jdbc.update("""
            INSERT INTO apr_form_version_lineage(tenant_id,form_id,form_version_id,source_form_version_id,
                base_published_form_version_id,recorded_by) VALUES(:tenant,:form,:id,:source,:base,:actor)
            """,params);
        advance(actor,head.formId(),head.workspaceRevision()==null?0:head.workspaceRevision(),"draft_form_version_id=:id",params);
        return id;
    }
    public void update(Actor actor,Head head,UpdateWorkingDraft input) {
        if(!input.draftFormVersionId().equals(head.draft())) throw conflict();
        boolean initial=head.workspaceRevision()==null;
        if(initial&&(!"DRAFT".equals(head.state())||head.published()!=null)) throw conflict();
        var schema=repo.codec.authoring(input.schema());
        Policy policy=repo.policy(actor,head.formId(),input.metadata(),input.defaultWorkflowId(),true);
        if(initial) adopt(actor,head);
        var params=repo.params(actor,head.formId()).addValue("id",head.draft()).addValue("schema",schema.json()).addValue("sha",schema.sha256());
        int count=repo.jdbc.update("""
            UPDATE apr_form_versions SET schema_payload=CAST(:schema AS jsonb),schema_sha256=:sha
             WHERE tenant_id=:tenant AND form_id=:form AND form_version_id=:id AND lifecycle_state='DRAFT'
            """,params);
        if(count!=1) throw conflict();
        params.addValue("metadata",repo.codec.json(policy.metadata())).addValue("route",repo.codec.json(policy.route()))
                .addValue("digest",repo.codec.material(schema.sha256(),policy.metadata(),policy.route()));
        if(initial) material(params,schema.sha256(),policy);
        else if(repo.jdbc.update("""
            UPDATE apr_form_version_material SET schema_sha256=:sha,metadata_payload=CAST(:metadata AS jsonb),
                route_payload=CAST(:route AS jsonb),material_sha256=:digest,captured_at=clock_timestamp(),captured_by=:actor
             WHERE tenant_id=:tenant AND form_id=:form AND form_version_id=:id AND provenance='AUTHORING_SNAPSHOT'
            """,params)!=1) throw conflict();
        advance(actor,head.formId(),initial?0:head.workspaceRevision(),"draft_form_version_id=:id",params);
    }
    public void availability(Actor actor,Head head,CatalogAvailability target) {
        if(head.published()==null||!"PUBLISHED".equals(head.state())) throw conflict();
        if(target==CatalogAvailability.ACTIVE) {
            var version=repo.version(actor,head.formId(),head.published());
            Policy current=repo.publishedPolicy(actor,head.formId(),version,true);
            captureCurrentPublished(actor,head,current);
        }
        adopt(actor,head);
        var params=repo.params(actor,head.formId()).addValue("availability",target.name());
        advance(actor,head.formId(),head.workspaceRevision()==null?0:head.workspaceRevision(),"catalog_availability=:availability",params);
    }
    public void publish(Actor actor,Head head,Version draft) {
        Policy current=repo.policy(actor,head.formId(),metadata(draft.metadata()),UUID.fromString((String)draft.route().get("workflowId")),true);
        if(!current.metadata().equals(draft.metadata())||!current.route().equals(draft.route())) throw conflict();
        var validated=repo.codec.authoring(draft.schema());
        if(!validated.sha256().equals(draft.schemaSha256())) throw conflict();
        var params=repo.params(actor,head.formId()).addValue("id",draft.formVersionId()).addValue("number",draft.versionNumber())
                .addValue("expected",head.revision());
        var meta=metadata(draft.metadata());
        params.addValue("category",meta.categoryId()).addValue("nameKo",meta.nameKo()).addValue("nameEn",meta.nameEn())
                .addValue("descriptionKo",meta.descriptionKo()).addValue("descriptionEn",meta.descriptionEn())
                .addValue("owner",meta.ownerGroupRef()).addValue("kind",meta.formKind());
        if(repo.jdbc.update("""
            UPDATE apr_form_version_material SET provenance='PUBLISH_SNAPSHOT',captured_at=clock_timestamp(),captured_by=:actor
             WHERE tenant_id=:tenant AND form_version_id=:id AND provenance='AUTHORING_SNAPSHOT'
            """,params)!=1) throw conflict();
        if(repo.jdbc.update("""
            UPDATE apr_form_versions SET lifecycle_state='PUBLISHED',published_at=clock_timestamp(),published_by=:actor
             WHERE tenant_id=:tenant AND form_id=:form AND form_version_id=:id AND lifecycle_state='DRAFT'
            """,params)!=1) throw conflict();
        if(repo.jdbc.update("""
            UPDATE apr_forms SET lifecycle_state='PUBLISHED',current_version=:number,version=version+1,
                category_id=:category,name_ko=:nameKo,name_en=:nameEn,description_ko=:descriptionKo,description_en=:descriptionEn,
                owner_group_ref=:owner,form_kind=:kind,updated_at=clock_timestamp(),updated_by=:actor
             WHERE tenant_id=:tenant AND form_id=:form AND management_resource_set_key=:scope AND version=:expected
            """,params)!=1) throw conflict();
        advance(actor,head.formId(),head.workspaceRevision(),"published_form_version_id=:id,draft_form_version_id=NULL",params);
        // Legacy active bindings remain exactly as they were. Managed initiation reads the immutable version route.
    }
    private void material(MapSqlParameterSource params,String hash,Policy policy) {
        material(params,hash,policy,"AUTHORING_SNAPSHOT");
    }
    private void material(MapSqlParameterSource params,String hash,Policy policy,String provenance) {
        params.addValue("metadata",repo.codec.json(policy.metadata())).addValue("route",repo.codec.json(policy.route()))
                .addValue("digest",repo.codec.material(hash,policy.metadata(),policy.route())).addValue("provenance",provenance);
        repo.jdbc.update("""
            INSERT INTO apr_form_version_material(tenant_id,form_id,form_version_id,schema_sha256,
                metadata_payload,route_payload,material_sha256,provenance,captured_by)
             VALUES(:tenant,:form,:id,:sha,CAST(:metadata AS jsonb),CAST(:route AS jsonb),:digest,:provenance,:actor)
            """,params);
    }
    private void captureCurrentPublished(Actor actor,Head head,Policy knownCurrent) {
        if(head.published()==null) return;
        var version=repo.version(actor,head.formId(),head.published());
        if(version.materialDigest()!=null) return;
        var locked=repo.jdbc.query("SELECT schema_payload::text,schema_sha256 FROM apr_form_versions "
                +"WHERE tenant_id=:tenant AND form_id=:form AND form_version_id=:id AND lifecycle_state='PUBLISHED' FOR SHARE",
                repo.params(actor,head.formId()).addValue("id",head.published()),(row,number)->
                        java.util.Map.entry(repo.codec.object(row.getString(1)),row.getString(2)));
        if(locked.size()!=1||!version.schemaSha256().equals(locked.getFirst().getValue())||!version.schema().equals(locked.getFirst().getKey())) throw conflict();
        Policy current=knownCurrent==null?repo.policy(actor,head.formId(),head.metadata(),
                UUID.fromString((String)repo.legacyRoute(actor,head.formId()).get("workflowId")),true):knownCurrent;
        material(repo.params(actor,head.formId()).addValue("id",head.published()).addValue("sha",version.schemaSha256()),
                version.schemaSha256(),current,"LEGACY_CAPTURE_TIME");
    }
    private void advance(Actor actor,UUID form,long expected,String assignment,MapSqlParameterSource params) {
        params.addValue("workspaceExpected",expected);
        if(repo.jdbc.update("UPDATE apr_form_workspaces SET "+assignment+",workspace_version=workspace_version+1,updated_at=clock_timestamp(),updated_by=:actor "
                +"WHERE tenant_id=:tenant AND form_id=:form AND workspace_version=:workspaceExpected",params)!=1) throw conflict();
    }
    public Map<String,Object> prior(Actor actor,UUID form,String scope,String route,String key,String digest) {
        var rows=repo.jdbc.query("""
            SELECT request_sha256,result::text FROM apr_form_command_receipts WHERE tenant_id=:tenant AND actor_user_id=:actor
             AND context_scope_key=:context AND route_key=:route AND idempotency_key=:key
            """,repo.params(actor,form).addValue("context",scope).addValue("route",route).addValue("key",key),(row,n)->{
                if(!digest.equals(row.getString(1))) throw conflict();return repo.codec.object(row.getString(2));
            });
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void receipt(Actor actor,UUID form,String scope,String route,String key,String digest,Workspace outcome) {
        repo.jdbc.update("""
            INSERT INTO apr_form_command_receipts(tenant_id,actor_user_id,context_scope_key,route_key,idempotency_key,
                form_id,management_resource_set_key,request_sha256,result)
             VALUES(:tenant,:actor,:context,:route,:key,:form,:scope,:digest,CAST(:result AS jsonb))
            """,repo.params(actor,form).addValue("context",scope).addValue("route",route).addValue("key",key)
                    .addValue("digest",digest).addValue("result",repo.codec.json(outcome)));
    }
    public void journal(Actor actor,UUID form,String action,UUID version,Map<String,Object> material) {
        var head=repo.head(actor,form,false);
        repo.jdbc.update("""
            INSERT INTO apr_form_lifecycle_events(event_id,tenant_id,form_id,form_version_id,actor_user_id,management_resource_set_key,
                action,form_revision,workspace_revision,material) VALUES(:event,:tenant,:form,:id,:actor,:scope,:action,:revision,:workspace,CAST(:material AS jsonb))
            """,repo.params(actor,form).addValue("event",UUID.randomUUID()).addValue("id",version).addValue("action",action)
                    .addValue("revision",head.revision()).addValue("workspace",head.workspaceRevision()).addValue("material",repo.codec.json(material)));
    }
    public MetadataInput metadata(Map<String,Object> value) {
        try { return new MetadataInput(UUID.fromString((String)value.get("categoryId")),(String)value.get("nameKo"),(String)value.get("nameEn"),
                (String)value.get("descriptionKo"),(String)value.get("descriptionEn"),(String)value.get("ownerGroupRef"),(String)value.get("formKind")); }
        catch(Exception exception) { throw new BaseException(ErrorCode.INVALID_STATE); }
    }
    private BaseException conflict() { return ApprovalFormWorkspaceRepository.conflict(); }
}
