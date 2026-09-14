package com.dwp.services.approval.forms;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.*;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalFormWorkspaceRepository implements ApprovalFormWorkspaceReadPort {
    final NamedParameterJdbcTemplate jdbc;
    final ApprovalFormMaterialCodec codec;
    public ApprovalFormWorkspaceRepository(NamedParameterJdbcTemplate jdbc, ApprovalFormMaterialCodec codec) {
        this.jdbc=jdbc; this.codec=codec;
    }
    public MapSqlParameterSource params(Actor actor,UUID formId) {
        if(actor==null||actor.tenantId()==null||actor.userId()==null||actor.tenantId()<=0||actor.userId()<=0||formId==null) throw unavailable();
        String scope=ApprovalManagementScopeContext.current().orElseThrow(ApprovalFormWorkspaceRepository::unavailable).resourceSetKey();
        return new MapSqlParameterSource().addValue("tenant",actor.tenantId()).addValue("actor",actor.userId())
                .addValue("form",formId).addValue("scope",scope);
    }
    public Head head(Actor actor,UUID formId,boolean lock) {
        var rows=jdbc.query("""
            SELECT f.*,v.form_version_id AS current_uuid,v.lifecycle_state AS current_state,
                   w.workspace_version,w.published_form_version_id,w.draft_form_version_id,w.catalog_availability,
                   w.updated_by AS draft_editor
              FROM apr_forms f JOIN apr_form_versions v ON v.tenant_id=f.tenant_id AND v.form_id=f.form_id
               AND v.version_number=f.current_version
              LEFT JOIN apr_form_workspaces w ON w.tenant_id=f.tenant_id AND w.form_id=f.form_id
             WHERE f.tenant_id=:tenant AND f.form_id=:form AND f.management_resource_set_key=:scope
            """+(lock?" FOR UPDATE OF f":""),params(actor,formId),(row,n)->{
                Long ws=row.getObject("workspace_version",Long.class);
                String state=row.getString("lifecycle_state"),versionState=row.getString("current_state");
                UUID current=row.getObject("current_uuid",UUID.class);
                UUID pub=ws==null?("PUBLISHED".equals(versionState)?current:null):row.getObject("published_form_version_id",UUID.class);
                UUID draft=ws==null?("DRAFT".equals(versionState)?current:null):row.getObject("draft_form_version_id",UUID.class);
                if (ws!=null && (pub==null&&draft==null || pub!=null&&(!pub.equals(current)||!"PUBLISHED".equals(state))
                        || pub==null&&(!"DRAFT".equals(state)||!current.equals(draft)))) throw unavailable();
                long revision=row.getLong("version");
                if (revision<0||revision>9007199254740991L||ws!=null&&(ws<0||ws>9007199254740991L)) throw unavailable();
                return new Head(formId,revision,ws,state,pub,draft,ws==null?("RETIRED".equals(state)?CatalogAvailability.RETIRED:CatalogAvailability.ACTIVE)
                        :CatalogAvailability.valueOf(row.getString("catalog_availability")),row.getObject("draft_editor",Long.class),
                        new MetadataInput(row.getObject("category_id",UUID.class),row.getString("name_ko"),row.getString("name_en"),
                            row.getString("description_ko"),row.getString("description_en"),row.getString("owner_group_ref"),row.getString("form_kind")));
            });
        if(rows.size()!=1) throw new BaseException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    @Override public Workspace read(Actor actor,UUID formId) {
        Head h=head(actor,formId,false);
        Version pub=h.published()==null?null:version(actor,formId,h.published());
        Version draft=h.draft()==null?null:version(actor,formId,h.draft());
        if(pub!=null&&!"PUBLISHED".equals(pub.lifecycleState())||draft!=null&&!"DRAFT".equals(draft.lifecycleState())) throw unavailable();
        boolean eligible=pub!=null&&"PUBLISHED".equals(h.state())&&h.availability()==CatalogAvailability.ACTIVE;
        if(eligible) {
            try {
                publishedPolicy(actor,formId,pub,false);
            } catch(BaseException exception) { eligible=false; }
            catch(IllegalArgumentException|NullPointerException exception) { throw unavailable(); }
        }
        unchanged(actor,h);
        return new Workspace(formId,h.revision(),h.workspaceRevision(),h.availability(),pub,draft,h.editor(),eligible,OffsetDateTime.now());
    }
    public void unchanged(Actor actor,Head before) {
        if(!before.equals(head(actor,before.formId(),false))) throw conflict();
    }
    public Version version(Actor actor,UUID formId,UUID id) {
        var rows=jdbc.query("""
            SELECT v.*,m.metadata_payload::text,m.route_payload::text,m.schema_sha256 AS material_schema,
                   m.material_sha256,m.provenance,m.captured_at,m.captured_by,
                   l.source_form_version_id,l.base_published_form_version_id
              FROM apr_form_versions v JOIN apr_forms f ON f.tenant_id=v.tenant_id AND f.form_id=v.form_id
              LEFT JOIN apr_form_version_material m ON m.tenant_id=v.tenant_id AND m.form_version_id=v.form_version_id
              LEFT JOIN apr_form_version_lineage l ON l.tenant_id=v.tenant_id AND l.form_version_id=v.form_version_id
             WHERE f.tenant_id=:tenant AND f.form_id=:form AND v.form_version_id=:id AND f.management_resource_set_key=:scope
            """,params(actor,formId).addValue("id",id),(row,n)->{
                String raw=row.getString("schema_payload"),sha=row.getString("schema_sha256"); codec.stored(raw,sha);
                Map<String,Object> meta=row.getString("metadata_payload")==null?Map.of():codec.object(row.getString("metadata_payload"));
                Map<String,Object> route=row.getString("route_payload")==null?Map.of():codec.object(row.getString("route_payload"));
                String digest=row.getString("material_sha256");
                if(digest!=null&&(!sha.equals(row.getString("material_schema"))||!digest.equals(codec.material(sha,meta,route)))) throw unavailable();
                if(digest!=null&&!"LEGACY_CAPTURE_TIME".equals(row.getString("provenance"))&&!codec.object(raw).containsKey("schemaContract")
                        &&!sha.equals(codec.sha(codec.json(codec.object(raw))))) throw unavailable();
                return new Version(id,row.getInt("version_number"),row.getString("lifecycle_state"),
                        row.getObject("source_form_version_id",UUID.class),row.getObject("base_published_form_version_id",UUID.class),
                        codec.object(raw),sha,meta,route,digest,row.getString("provenance")==null?"UNRECORDED_HISTORICAL_METADATA":row.getString("provenance"),
                        row.getObject("captured_at",OffsetDateTime.class),row.getObject("captured_by",Long.class),
                        row.getObject("created_at",OffsetDateTime.class),row.getObject("created_by",Long.class),
                        row.getObject("published_at",OffsetDateTime.class),row.getObject("published_by",Long.class));
            });
        if(rows.size()!=1) throw new BaseException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    public History history(Actor actor,UUID formId,int size) {
        if(size<1||size>100) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        head(actor,formId,false);
        var ids=jdbc.query("SELECT form_version_id FROM apr_form_versions WHERE tenant_id=:tenant AND form_id=:form ORDER BY version_number DESC LIMIT :size",
                params(actor,formId).addValue("size",size+1),(row,n)->row.getObject(1,UUID.class));
        return new History(ids.stream().limit(size).map(id->version(actor,formId,id)).toList(),ids.size()>size);
    }
    public Map<String,Object> legacyRoute(Actor actor,UUID formId) {
        var ids=jdbc.query("""
            SELECT workflow_id FROM apr_form_workflow_bindings WHERE tenant_id=:tenant AND form_id=:form
             AND binding_type='DEFAULT' AND lifecycle_state='ACTIVE'
             AND (effective_from IS NULL OR effective_from<=clock_timestamp())
             AND (effective_to IS NULL OR effective_to>clock_timestamp())
            """,params(actor,formId),(row,n)->row.getObject(1,UUID.class));
        if(ids.size()!=1) throw conflict();
        return policy(actor,formId,head(actor,formId,false).metadata(),ids.getFirst(),false).route();
    }
    public Policy policy(Actor actor,UUID formId,MetadataInput metadata,UUID workflowId,boolean lock) {
        var values=jdbc.query("""
            SELECT c.category_id,c.version AS category_revision,c.name_ko AS category_name_ko,c.name_en AS category_name_en,
                   w.workflow_id,w.version AS workflow_revision,w.data_classification,w.sla_minutes,
                   v.workflow_version_id,v.version_number,v.definition_sha256,v.effective_from,v.effective_to
              FROM apr_form_categories c JOIN apr_workflow_definitions w ON w.tenant_id=c.tenant_id
              JOIN apr_workflow_versions v ON v.tenant_id=w.tenant_id AND v.workflow_id=w.workflow_id AND v.version_number=w.current_version
             WHERE c.tenant_id=:tenant AND c.category_id=:category AND w.workflow_id=:workflow
               AND c.management_resource_set_key=:scope AND w.management_resource_set_key=:scope
               AND c.lifecycle_state='ACTIVE' AND w.lifecycle_state='PUBLISHED' AND v.lifecycle_state='PUBLISHED'
               AND (v.effective_from IS NULL OR v.effective_from<=clock_timestamp())
               AND (v.effective_to IS NULL OR v.effective_to>clock_timestamp())
            """+(lock?" FOR SHARE OF c,w,v":""),params(actor,formId).addValue("category",metadata.categoryId()).addValue("workflow",workflowId),(row,n)->{
                var meta=new LinkedHashMap<>(codec.map(metadata));
                meta.put("categoryRevision",row.getLong("category_revision"));
                meta.put("categoryNameKo",row.getString("category_name_ko"));meta.put("categoryNameEn",row.getString("category_name_en"));
                var route=new LinkedHashMap<String,Object>();
                route.put("workflowId",workflowId.toString());route.put("workflowVersionId",row.getObject("workflow_version_id",UUID.class).toString());
                route.put("workflowRevision",row.getLong("workflow_revision"));route.put("workflowVersionNumber",row.getInt("version_number"));
                route.put("definitionSha256",row.getString("definition_sha256"));route.put("dataClassification",row.getString("data_classification"));
                route.put("slaMinutes",row.getInt("sla_minutes"));
                var from=row.getObject("effective_from",OffsetDateTime.class);var to=row.getObject("effective_to",OffsetDateTime.class);
                route.put("effectiveFrom",from==null?null:from.toInstant().toString());route.put("effectiveTo",to==null?null:to.toInstant().toString());
                return new Policy(codec.map(meta),codec.map(route));
            });
        if(values.size()!=1) throw conflict();
        return values.getFirst();
    }
    public Policy publishedPolicy(Actor actor,UUID formId,Version published,boolean lock) {
        if(!"PUBLISHED".equals(published.lifecycleState())) throw conflict();
        if(published.route().isEmpty()) return policy(actor,formId,head(actor,formId,false).metadata(),
                UUID.fromString((String)legacyRoute(actor,formId).get("workflowId")),lock);
        var route=ApprovalFormVersionPolicyRepository.workflow(published.route());
        UUID category=UUID.fromString((String)published.metadata().get("categoryId"));
        var rows=jdbc.query("""
            SELECT v.effective_from,v.effective_to
              FROM apr_form_categories c JOIN apr_workflow_definitions w ON w.tenant_id=c.tenant_id
              JOIN apr_workflow_versions v ON v.tenant_id=w.tenant_id AND v.workflow_id=w.workflow_id
             WHERE c.tenant_id=:tenant AND c.category_id=:category AND c.lifecycle_state='ACTIVE'
               AND c.management_resource_set_key=:scope AND w.management_resource_set_key=:scope
               AND w.workflow_id=:workflow AND w.lifecycle_state='PUBLISHED'
               AND v.workflow_version_id=:version AND v.version_number=:number AND v.definition_sha256=:sha
               AND v.lifecycle_state='PUBLISHED'
               AND (v.effective_from IS NULL OR v.effective_from<=clock_timestamp())
               AND (v.effective_to IS NULL OR v.effective_to>clock_timestamp())
            """+(lock?" FOR SHARE OF c,w,v":""),params(actor,formId).addValue("category",category)
                .addValue("workflow",route.workflowId()).addValue("version",route.workflowVersionId())
                .addValue("number",route.workflowVersionNumber()).addValue("sha",route.definitionSha256()),(row,number)->{
            var from=row.getObject("effective_from",OffsetDateTime.class);var to=row.getObject("effective_to",OffsetDateTime.class);
            return java.util.Objects.equals(route.effectiveFrom(),from==null?null:from.toInstant())
                    &&java.util.Objects.equals(route.effectiveTo(),to==null?null:to.toInstant());
        });
        if(rows.size()!=1||!rows.getFirst()) throw conflict();
        return new Policy(published.metadata(),published.route());
    }
    static BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT,"The exact form workspace or effective policy changed."); }
    static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"The immutable form workspace cannot be verified."); }
    public record Head(UUID formId,long revision,Long workspaceRevision,String state,UUID published,UUID draft,
            CatalogAvailability availability,Long editor,MetadataInput metadata) { }
    public record Policy(Map<String,Object> metadata,Map<String,Object> route) { }
}
