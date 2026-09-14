package com.dwp.services.approval.forms;

import static com.dwp.services.approval.forms.ApprovalFormPublishedRoutePin.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Form-only DB seal. The mandatory no-write workflow verifier supplies independent current workflow authority. */
@Repository
public class ApprovalFormVersionPolicyRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalFormMaterialCodec codec;
    private final ApprovalFormVersionPolicyAuthority authority;
    public ApprovalFormVersionPolicyRepository(NamedParameterJdbcTemplate jdbc,ApprovalFormMaterialCodec codec,
            ApprovalFormVersionPolicyAuthority authority) { this.jdbc=jdbc;this.codec=codec;this.authority=authority; }
    @Transactional public <T> Optional<T> newInitiation(Actor caller,UUID formId,UUID requestedWorkflowId,
            Function<ApprovalFormPublishedRoutePin,T> verifiedWorkflow) {
        return resolve(caller,formId,null,null,requestedWorkflowId,Operation.NEW_INITIATION,verifiedWorkflow);
    }
    @Transactional public <T> Optional<T> existingRequest(Actor caller,UUID requestId,long expectedRequestVersion,
            UUID requestedFormId,UUID requestedWorkflowId,Function<ApprovalFormPublishedRoutePin,T> verifiedWorkflow) {
        return resolve(caller,requestedFormId,requestId,expectedRequestVersion,requestedWorkflowId,Operation.EXISTING_REQUEST,verifiedWorkflow);
    }
    private <T> Optional<T> resolve(Actor caller,UUID formId,UUID requestId,Long expectedVersion,UUID requestedWorkflowId,
            Operation operation,Function<ApprovalFormPublishedRoutePin,T> verifier) {
        if(verifier==null||authority==null||!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        var window=authority.require(operation,requestId);var actor=window.actor();
        if(caller==null||!actor.tenantId().equals(caller.tenantId())||!actor.userId().equals(caller.userId())
                ||operation==Operation.NEW_INITIATION&&formId==null||operation==Operation.EXISTING_REQUEST
                &&(requestId==null||expectedVersion==null||expectedVersion<0||expectedVersion>9007199254740991L)) throw conflict();
        Optional<ApprovalFormPublishedRoutePin> observed=load(actor,formId,requestId,expectedVersion,operation,window.action());
        if(observed.isEmpty()) { authority.unchanged(window,operation,requestId);return Optional.empty(); }
        var pin=observed.get();
        if(requestedWorkflowId!=null&&!requestedWorkflowId.equals(pin.workflow().workflowId())) throw conflict();
        T result=verifier.apply(pin);if(result==null) throw unavailable();
        var fresh=load(actor,formId,requestId,expectedVersion,operation,window.action());
        if(fresh.isEmpty()||!pin.sameObservation(fresh.get())) throw conflict();
        authority.unchanged(window,operation,requestId);return Optional.of(result);
    }
    private Optional<ApprovalFormPublishedRoutePin> load(Actor actor,UUID formId,UUID requestId,Long expectedVersion,
            Operation operation,String action) {
        boolean creating=operation==Operation.NEW_INITIATION;
        String from=creating?"""
                FROM apr_forms f JOIN apr_form_versions v ON v.tenant_id=f.tenant_id AND v.form_id=f.form_id
                 AND v.version_number=f.current_version
                """:"""
                FROM apr_requests request JOIN apr_form_versions v ON v.tenant_id=request.tenant_id
                 AND v.form_version_id=request.form_version_id
                JOIN apr_forms f ON f.tenant_id=v.tenant_id AND f.form_id=v.form_id
                """;
        String requestColumns=creating?"NULL::uuid AS request_uuid,NULL::bigint AS request_revision,NULL::uuid AS request_workflow,NULL::text AS request_class,"
                :"request.request_id AS request_uuid,request.version AS request_revision,request.workflow_version_id AS request_workflow,request.data_classification AS request_class,";
        String where=creating?"f.form_id=:form":"request.request_id=:request AND request.requester_user_id=:actor AND request.deleted_at IS NULL "
                +"AND request.version=:expected AND request.status=:status AND request.management_resource_set_key=f.management_resource_set_key";
        var rows=jdbc.query("SELECT "+requestColumns+"""
                f.form_id,f.version AS form_revision,f.lifecycle_state AS form_state,f.management_resource_set_key,
                v.form_version_id,v.lifecycle_state AS version_state,v.schema_payload::text,v.schema_sha256,
                w.workspace_version,w.published_form_version_id,w.catalog_availability,
                m.schema_sha256 AS material_schema,m.metadata_payload::text,m.route_payload::text,
                m.material_sha256,m.provenance,m.captured_at,
                live.form_version_id AS live_uuid
                """+from+"""
                JOIN apr_form_versions live ON live.tenant_id=f.tenant_id AND live.form_id=f.form_id AND live.version_number=f.current_version
                LEFT JOIN apr_form_workspaces w ON w.tenant_id=f.tenant_id AND w.form_id=f.form_id
                LEFT JOIN apr_form_version_material m ON m.tenant_id=v.tenant_id AND m.form_id=v.form_id AND m.form_version_id=v.form_version_id
                WHERE f.tenant_id=:tenant AND
                """+where+(formId!=null&&!creating?" AND f.form_id=:form":"")
                +(creating?" FOR SHARE OF f,v,live":" FOR UPDATE OF request FOR SHARE OF f,v,live"),
                new MapSqlParameterSource().addValue("tenant",actor.tenantId()).addValue("actor",actor.userId()).addValue("form",formId)
                        .addValue("request",requestId).addValue("expected",expectedVersion).addValue("status",action.equals("INFORMATION")?"NEEDS_INFO":"DRAFT"),
                (row,number)->{
                    Long workspace=row.getObject("workspace_version",Long.class);
                    if(workspace==null) return Optional.<ApprovalFormPublishedRoutePin>empty();
                    long formRevision=revision(row.getObject("form_revision"));revision(workspace);
                    if(!Set.of("DRAFT","PUBLISHED").contains(row.getString("form_state"))||!"PUBLISHED".equals(row.getString("version_state"))) throw conflict();
                    UUID version=row.getObject("form_version_id",UUID.class);
                    if(creating&&(!"PUBLISHED".equals(row.getString("form_state"))||!"ACTIVE".equals(row.getString("catalog_availability"))
                            ||!version.equals(row.getObject("published_form_version_id",UUID.class))||!version.equals(row.getObject("live_uuid",UUID.class)))) throw conflict();
                    String raw=row.getString("schema_payload"),hash=hash(row.getString("schema_sha256"));codec.stored(raw,hash);
                    if(!hash.equals(row.getString("material_schema"))||row.getString("metadata_payload")==null||row.getString("route_payload")==null) throw unavailable();
                    var metadata=codec.object(row.getString("metadata_payload"));var route=codec.object(row.getString("route_payload"));
                    String digest=hash(row.getString("material_sha256"));
                    if(!digest.equals(codec.material(hash,metadata,route))) throw unavailable();
                    String provenance=row.getString("provenance");
                    if(!Set.of("PUBLISH_SNAPSHOT","LEGACY_CAPTURE_TIME").contains(provenance==null?"":provenance)) throw unavailable();
                    UUID category=uuid(metadata.get("categoryId"));String resourceSet=row.getString("management_resource_set_key");
                    if(resourceSet==null||!resourceSet.matches("[A-Z][A-Z0-9_]{2,79}")) throw unavailable();
                    long categoryRevision=currentCategory(actor,category,resourceSet);
                    WorkflowRoute workflow=workflow(route);
                    if(!creating&&(!workflow.workflowVersionId().equals(row.getObject("request_workflow",UUID.class))
                            ||!workflow.dataClassification().equals(row.getString("request_class")))) throw conflict();
                    var captured=row.getObject("captured_at",OffsetDateTime.class);if(captured==null) throw unavailable();
                    return Optional.of(verified(new Observation(actor.tenantId(),row.getObject("form_id",UUID.class),version,hash,raw,digest,
                            formRevision,workspace,resourceSet,category,categoryRevision,row.getObject("request_uuid",UUID.class),
                            row.getObject("request_revision",Long.class),operation,workflow,metadata,provenance,captured.toInstant())));
                });
        if(rows.size()!=1) throw conflict();return rows.getFirst();
    }
    private long currentCategory(Actor actor,UUID category,String resourceSet) {
        var rows=jdbc.query("SELECT version FROM apr_form_categories WHERE tenant_id=:tenant AND category_id=:id "
                +"AND management_resource_set_key=:scope AND lifecycle_state='ACTIVE' FOR SHARE",
                new MapSqlParameterSource().addValue("tenant",actor.tenantId()).addValue("id",category).addValue("scope",resourceSet),
                (row,number)->revision(row.getObject("version")));
        if(rows.size()!=1) throw conflict();return rows.getFirst();
    }
    static WorkflowRoute workflow(Map<String,Object> value) {
        if(!value.keySet().equals(Set.of("workflowId","workflowVersionId","workflowRevision","workflowVersionNumber","definitionSha256",
                "dataClassification","slaMinutes","effectiveFrom","effectiveTo"))) throw unavailable();
        long version=revision(value.get("workflowVersionNumber")),sla=revision(value.get("slaMinutes"));
        if(version<1||version>Integer.MAX_VALUE||sla<1||sla>Integer.MAX_VALUE) throw unavailable();
        String classification=value.get("dataClassification") instanceof String text?text:null;
        if(classification==null||!classification.matches("[A-Z_]{1,40}")) throw unavailable();
        Instant from=instant(value.get("effectiveFrom")),to=instant(value.get("effectiveTo"));
        if(from!=null&&to!=null&&!from.isBefore(to)) throw unavailable();
        return new WorkflowRoute(uuid(value.get("workflowId")),uuid(value.get("workflowVersionId")),revision(value.get("workflowRevision")),
                (int)version,hash(value.get("definitionSha256")),classification,(int)sla,from,to);
    }
    private static long revision(Object value) {
        if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<0||((Number)value).longValue()>9007199254740991L) throw unavailable();
        return ((Number)value).longValue();
    }
    private static UUID uuid(Object value) {
        if(!(value instanceof String text)||!text.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw unavailable();
        return UUID.fromString(text);
    }
    private static String hash(Object value) { if(!(value instanceof String text)||!text.matches("[a-f0-9]{64}")) throw unavailable();return text; }
    private static Instant instant(Object value) {
        if(value==null) return null;
        if(!(value instanceof String text)||text.length()>50) throw unavailable();
        try { return Instant.parse(text); } catch(RuntimeException error) { throw unavailable(); }
    }
    private static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"The published form route cannot be sealed."); }
    private static BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT,"The published form or owned immutable request binding changed."); }
}
