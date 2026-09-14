package com.dwp.services.approval.attachment.binding;

import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.security.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import static com.dwp.services.approval.document.ApprovalDocumentOwnerRepository.params;

@Component
public class ApprovalAttachmentLifecycleBinding {
    public enum Intent { UPDATE_DRAFT, RECOVER_DRAFT, INFO_RESPONSE }
    public record Target(UUID formVersionId,UUID workflowVersionId,String resourceSetKey,String formSchemaSha256,int payloadRevision,String payloadSha256) { }
    private record Source(UUID form,UUID workflow,String schema) { }
    private record Selection(long version,List<UUID> ids) { }
    private record Manifest(String sha,List<Map<String,Object>> items) { }
    public static final class Pin {
        private final Object transaction;
        private final long tenant,actor,version,selectionVersion;
        private final UUID request;
        private final Intent intent;
        private final int revision;
        private final String payloadSha,scope,classification,manifestSha;
        private final List<UUID> ids;
        private final ApprovalAttachmentDtos.Prepared prepared;
        private final boolean material;
        private boolean consumed;
        private Pin(Object transaction,ApprovalRequestContext.Actor actor,ApprovalDocumentOwnerRepository.Owner owner,
                Intent intent,Selection selection,String manifestSha,ApprovalAttachmentDtos.Prepared prepared,boolean material) {
            this.transaction=transaction;tenant=actor.tenantId();this.actor=actor.userId();request=owner.requestId();version=owner.requestVersion();
            revision=owner.payloadRevision();payloadSha=owner.payloadSha256();scope=owner.resourceSetKey();classification=owner.classification();
            this.intent=intent;selectionVersion=selection.version();ids=selection.ids();this.manifestSha=manifestSha;this.prepared=prepared;this.material=material;
        }
        public boolean materialChange(){return material;}
        public int sourcePayloadRevision(){return revision;}
        public String sourcePayloadSha256(){return payloadSha;}
    }
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalWorkAuthority authority;
    private final ApprovalDocumentOwnerRepository owners;
    private final ApprovalAttachmentPolicyRepository policies;
    private final ApprovalAttachmentManifestFacade facade;
    private final ApprovalAttachmentProviderGate provider;
    private final ApprovalDocumentCanonical canonical;
    private final Object transactionKey=new Object();
    public ApprovalAttachmentLifecycleBinding(NamedParameterJdbcTemplate jdbc,ApprovalWorkAuthority authority,ApprovalDocumentOwnerRepository owners,
            ApprovalAttachmentPolicyRepository policies,ApprovalAttachmentManifestFacade facade,ApprovalAttachmentProviderGate provider,ApprovalDocumentCanonical canonical) {
        this.jdbc=jdbc;this.authority=authority;this.owners=owners;this.policies=policies;this.facade=facade;this.provider=provider;this.canonical=canonical;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void initializeCreated(UUID id,long expectedVersion) {
        transaction();var actor=actor("CREATE");var owner=owned(actor,id,expectedVersion);
        if(expectedVersion!=0 || owner.payloadRevision()!=1 || !"DRAFT".equals(owner.status())) throw ApprovalDocumentCanonical.conflict();
        jdbc.update("INSERT INTO apr_attachment_selections(tenant_id,request_id) VALUES(:tenant,:request) ON CONFLICT DO NOTHING",params(actor).addValue("request",id));
        Selection selection=selection(actor,id);if(selection.version()!=0 || !selection.ids().isEmpty()) throw ApprovalDocumentCanonical.conflict();
        empty(actor,owner);actor("CREATE");
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Pin prepare(UUID id,long expectedVersion,Intent intent) {
        if(intent==null) throw ApprovalDocumentCanonical.forbidden();
        Object transaction=transaction();var actor=actor("UPDATE");var owner=owned(actor,id,expectedVersion);
        if(!(intent==Intent.INFO_RESPONSE?"NEEDS_INFO":"DRAFT").equals(owner.status())) throw ApprovalDocumentCanonical.conflict();
        Selection selection=selection(actor,id);Manifest previous=manifest(actor,id,owner.payloadRevision(),owner.payloadSha256());
        ApprovalAttachmentDtos.Prepared prepared=null;String digest=ApprovalDocumentCanonical.sha("[]");
        if(!selection.ids().isEmpty()) {
            var policy=policies.current(actor,owner.resourceSetKey(),null,false);
            validateItems(actor,owner,selection.ids(),policy,null);
            prepared=facade.prepare(id,expectedVersion,owner.payloadRevision(),selection.version(),policy.policyId(),policy.version());digest=prepared.manifestSha256();
        }
        actor("UPDATE");return new Pin(transaction,actor,owner,intent,selection,digest,prepared,!previous.sha().equals(digest));
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Pin prepareRecovery(UUID id,long expectedVersion,int historicalRevision) {
        transaction();var actor=actor("UPDATE");var owner=owned(actor,id,expectedVersion);
        if(!"DRAFT".equals(owner.status()) || historicalRevision<1) throw ApprovalDocumentCanonical.conflict();
        var p=params(actor).addValue("request",id).addValue("revision",historicalRevision);
        String hash=jdbc.query("SELECT payload_sha256 FROM apr_request_payload_versions WHERE tenant_id=:tenant AND request_id=:request AND revision_number=:revision FOR SHARE",p,r->r.next()?r.getString(1):null);
        if(hash==null) throw ApprovalDocumentOwnerRepository.hidden();
        Manifest historical=manifest(actor,id,historicalRevision,hash);List<UUID> ids=ids(historical.items());
        if(!ids.isEmpty()) validateItems(actor,owner,ids,policies.current(actor,owner.resourceSetKey(),null,false),historical.items());
        Selection current=selection(actor,id);actor("UPDATE");
        jdbc.update("INSERT INTO apr_attachment_selections(tenant_id,request_id) VALUES(:tenant,:request) ON CONFLICT DO NOTHING",p);
        if(jdbc.update("UPDATE apr_attachment_selections SET attachment_ids=CAST(:ids AS jsonb),version=:next WHERE tenant_id=:tenant AND request_id=:request AND version=:version",
                p.addValue("ids",canonical.json(ids)).addValue("version",current.version()).addValue("next",next(current.version())))!=1) throw ApprovalDocumentCanonical.conflict();
        return prepare(id,expectedVersion,Intent.RECOVER_DRAFT);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Target target(UUID id) {
        transaction();var actor=actor("UPDATE");var owner=owners.lock(actor,ApprovalDocumentDtos.OwnerType.REQUEST,id);
        if(owner.requesterUserId()!=actor.userId()) throw ApprovalDocumentOwnerRepository.hidden();owners.requireImmutablePayload(actor,owner);
        Source source=source(actor,id);return new Target(source.form(),source.workflow(),owner.resourceSetKey(),source.schema(),owner.payloadRevision(),owner.payloadSha256());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void seal(Pin pin,Target expected) {
        if(pin==null || expected==null || transaction()!=pin.transaction || pin.consumed) throw ApprovalDocumentCanonical.conflict();
        var actor=actor("UPDATE");if(actor.tenantId()!=pin.tenant || actor.userId()!=pin.actor) throw ApprovalDocumentCanonical.forbidden();
        var owner=owned(actor,pin.request,next(pin.version));Source current=source(actor,pin.request);
        Target actual=new Target(current.form(),current.workflow(),owner.resourceSetKey(),current.schema(),owner.payloadRevision(),owner.payloadSha256());
        boolean material=pin.material || !pin.payloadSha.equals(owner.payloadSha256());
        if(!actual.equals(expected) || owner.payloadRevision()!=(pin.intent==Intent.INFO_RESPONSE && !material?pin.revision:next(pin.revision))) throw ApprovalDocumentCanonical.conflict();
        String state=pin.intent==Intent.INFO_RESPONSE?"IN_REVIEW":"DRAFT";if(!state.equals(owner.status())) throw ApprovalDocumentCanonical.conflict();
        Selection selection=selection(actor,pin.request);
        if(selection.version()!=pin.selectionVersion || !selection.ids().equals(pin.ids)) throw ApprovalDocumentCanonical.conflict();
        if(pin.prepared!=null) {
            if(!pin.scope.equals(owner.resourceSetKey()) || !pin.classification.equals(owner.classification())) throw ApprovalDocumentCanonical.conflict();
            var policy=policies.current(actor,owner.resourceSetKey(),pin.prepared.policyId(),false);
            if(policy.version()!=pin.prepared.policyVersion()) throw ApprovalDocumentCanonical.conflict();
            validateItems(actor,owner,pin.ids,policy,null);facade.seal(pin.prepared,owner.payloadRevision(),owner.payloadSha256());
        } else empty(actor,owner);
        if(!manifest(actor,pin.request,owner.payloadRevision(),owner.payloadSha256()).sha().equals(pin.manifestSha)) throw ApprovalDocumentCanonical.conflict();
        actor("UPDATE");pin.consumed=true;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void requireSealedForSubmit(UUID id,long expectedVersion) {
        transaction();var actor=actor("CREATE");var owner=owned(actor,id,expectedVersion);
        if(!"DRAFT".equals(owner.status())) throw ApprovalDocumentCanonical.conflict();
        Selection selected=selection(actor,id);Manifest sealed=manifest(actor,id,owner.payloadRevision(),owner.payloadSha256());
        if(!ids(sealed.items()).equals(selected.ids())) throw new com.dwp.core.exception.BaseException(com.dwp.core.common.ErrorCode.RESOURCE_CONFLICT,"ATTACHMENTS_REQUIRE_DRAFT_SAVE");
        if(!selected.ids().isEmpty()) validateItems(actor,owner,selected.ids(),policies.current(actor,owner.resourceSetKey(),null,false),sealed.items());actor("CREATE");
    }
    private Object transaction() {
        var dataSource=jdbc.getJdbcTemplate().getDataSource();
        if(!TransactionSynchronizationManager.isActualTransactionActive() || dataSource==null || !TransactionSynchronizationManager.hasResource(dataSource)) throw ApprovalDocumentCanonical.conflict();
        Object current=TransactionSynchronizationManager.getResource(transactionKey);
        if(current!=null) return current;Object token=new Object();TransactionSynchronizationManager.bindResource(transactionKey,token);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){TransactionSynchronizationManager.unbindResourceIfPossible(transactionKey);}});return token;
    }
    private ApprovalRequestContext.Actor actor(String action) {
        if(ApprovalDecisionRevisionContext.current().isPresent() && ApprovalPilotAuthorizationContext.current().isEmpty()) throw ApprovalDocumentCanonical.forbidden();
        return authority.requireCurrent("ACTION.APPROVAL_REQUEST:"+action);
    }
    private ApprovalDocumentOwnerRepository.Owner owned(ApprovalRequestContext.Actor actor,UUID id,long version) {
        var owner=owners.lock(actor,ApprovalDocumentDtos.OwnerType.REQUEST,id);
        if(owner.requesterUserId()!=actor.userId()) throw ApprovalDocumentOwnerRepository.hidden();
        if(owner.requestVersion()!=version) throw ApprovalDocumentCanonical.conflict();owners.requireImmutablePayload(actor,owner);
        String retention=jdbc.query("SELECT state FROM apr_record_retention_heads WHERE tenant_id=:tenant AND request_id=:request FOR UPDATE",params(actor).addValue("request",id),r->r.next()?r.getString(1):null);
        if(retention!=null && !"LIVE".equals(retention)) throw ApprovalDocumentCanonical.conflict();return owner;
    }
    private Source source(ApprovalRequestContext.Actor actor,UUID id) {
        return jdbc.query("SELECT r.form_version_id,r.workflow_version_id,v.schema_payload::text FROM apr_requests r JOIN apr_form_versions v ON v.tenant_id=r.tenant_id AND v.form_version_id=r.form_version_id WHERE r.tenant_id=:tenant AND r.request_id=:request FOR SHARE OF v",
                params(actor).addValue("request",id),r->{if(!r.next()) throw ApprovalDocumentOwnerRepository.hidden();return new Source(r.getObject(1,UUID.class),r.getObject(2,UUID.class),canonical.fingerprint(canonical.read(r.getString(3),Map.class)));});
    }
    private Selection selection(ApprovalRequestContext.Actor actor,UUID id) {
        var p=params(actor).addValue("request",id);
        return jdbc.query("SELECT version,attachment_ids::text FROM apr_attachment_selections WHERE tenant_id=:tenant AND request_id=:request FOR UPDATE",p,r->{if(!r.next()) return new Selection(0,List.of());var values=canonical.read(r.getString(2),UUID[].class);var ids=Arrays.stream(values).sorted().toList();if(ids.size()>10 || new HashSet<>(ids).size()!=ids.size()) throw ApprovalDocumentCanonical.conflict();return new Selection(r.getLong(1),ids);});
    }
    private Manifest manifest(ApprovalRequestContext.Actor actor,UUID id,int revision,String payloadSha) {
        return jdbc.query("SELECT manifest_sha256,items::text,payload_sha256 FROM apr_attachment_manifests WHERE tenant_id=:tenant AND request_id=:request AND payload_revision=:revision FOR SHARE",
                params(actor).addValue("request",id).addValue("revision",revision),r->{
            if(!r.next()) {
                // Legacy empty records are not applicable only when no attachment or prior seal ever exists.
                if(notApplicable(actor,id)) return new Manifest(ApprovalDocumentCanonical.sha("[]"),List.of());
                throw ApprovalDocumentCanonical.unavailable("Missing immutable attachment manifest.");
            }
            List<Map<String,Object>> items=items(r.getString(2));if(!payloadSha.equals(r.getString(3)) || !canonical.fingerprint(items).equals(r.getString(1))) throw ApprovalDocumentCanonical.unavailable("Attachment manifest binding failed.");
            return new Manifest(r.getString(1),items);
        });
    }
    private boolean notApplicable(ApprovalRequestContext.Actor actor,UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT (
                  EXISTS(SELECT 1 FROM apr_attachment_uploads WHERE tenant_id=:tenant AND request_id=:request)
                  OR EXISTS(SELECT 1 FROM apr_attachment_manifests WHERE tenant_id=:tenant AND request_id=:request)
                  OR EXISTS(SELECT 1 FROM apr_attachment_preparations WHERE tenant_id=:tenant AND request_id=:request)
                  OR EXISTS(SELECT 1 FROM apr_attachment_download_grants WHERE tenant_id=:tenant AND request_id=:request)
                  OR EXISTS(SELECT 1 FROM apr_attachment_selections WHERE tenant_id=:tenant AND request_id=:request AND attachment_ids<>'[]'::jsonb))
                """,params(actor).addValue("request",id),Boolean.class));
    }
    private void empty(ApprovalRequestContext.Actor actor,ApprovalDocumentOwnerRepository.Owner owner) {
        if(!selection(actor,owner.requestId()).ids().isEmpty()) throw ApprovalDocumentCanonical.conflict();
        var p=params(actor).addValue("request",owner.requestId()).addValue("revision",owner.payloadRevision()).addValue("payload",owner.payloadSha256()).addValue("manifest",ApprovalDocumentCanonical.sha("[]"));
        jdbc.update("INSERT INTO apr_attachment_manifests(tenant_id,request_id,payload_revision,payload_sha256,manifest_sha256,items) VALUES(:tenant,:request,:revision,:payload,:manifest,'[]'::jsonb) ON CONFLICT DO NOTHING",p);
        if(!manifest(actor,owner.requestId(),owner.payloadRevision(),owner.payloadSha256()).items().isEmpty()) throw ApprovalDocumentCanonical.conflict();
    }
    private void validateItems(ApprovalRequestContext.Actor actor,ApprovalDocumentOwnerRepository.Owner owner,List<UUID> ids,ApprovalAttachmentPolicyRepository.Head policy,List<Map<String,Object>> historical) {
        provider.requireIngestion();
        if(!policy.rules().allowUpload() || ids.size()>policy.rules().maxFiles()) throw ApprovalDocumentCanonical.forbidden();long sum=0;var current=new ArrayList<Map<String,Object>>();
        for(UUID id:ids) {
            var item=jdbc.query("SELECT * FROM apr_attachment_uploads WHERE tenant_id=:tenant AND request_id=:request AND attachment_id=:attachment AND policy_id=:policy AND state='AVAILABLE' AND av_state='AV_CLEAR' AND passive_content_state='PASSIVE_ALLOWED' AND object_version IS NOT NULL AND engine_version IS NOT NULL AND definitions_at IS NOT NULL AND scanned_at IS NOT NULL AND parser_version IS NOT NULL AND retain_until>clock_timestamp() FOR SHARE",
                    params(actor).addValue("request",owner.requestId()).addValue("attachment",id).addValue("policy",policy.policyId()),r->{
                if(!r.next()) throw ApprovalDocumentCanonical.conflict();var row=new TreeMap<String,Object>();row.put("attachmentId",r.getString("attachment_id"));row.put("fileName",r.getString("file_name"));row.put("mediaType",r.getString("media_type"));row.put("sizeBytes",r.getLong("size_bytes"));row.put("sha256",r.getString("content_sha256"));row.put("objectVersion",r.getString("object_version"));row.put("objectKey",r.getString("object_key"));return row;
            });
            long size=((Number)item.get("sizeBytes")).longValue();if(size<1 || size>policy.rules().maxFileBytes() || !policy.rules().allowedMediaTypes().contains(item.get("mediaType"))) throw ApprovalDocumentCanonical.forbidden();
            var stored=new ApprovalAttachmentStorage.Stored((String)item.get("objectKey"),(String)item.get("objectVersion"),size,(String)item.get("sha256"));
            byte[] bytes;
            try{bytes=provider.storage().load(stored);}catch(RuntimeException unknown){throw ApprovalDocumentCanonical.unavailable("Current exact attachment object is unavailable.");}
            if(bytes==null) throw ApprovalDocumentCanonical.unavailable("Current exact attachment object is unavailable.");
            ApprovalAttachmentIntegrity.require(bytes,size,stored.sha256());
            try {sum=Math.addExact(sum,size);}catch(ArithmeticException overflow){throw ApprovalDocumentCanonical.conflict();}current.add(item);
        }
        if(sum>policy.rules().maxRequestBytes()) throw ApprovalDocumentCanonical.forbidden();
        if(historical!=null && !canonical.fingerprint(historical).equals(canonical.fingerprint(current))) throw ApprovalDocumentCanonical.conflict();
    }
    private List<Map<String,Object>> items(String json) {
        try {
            var items=new ObjectMapper().readValue(json,new TypeReference<List<Map<String,Object>>>(){});
            if(items.size()>10) throw new IllegalArgumentException();
            var keys=Set.of("attachmentId","fileName","mediaType","sizeBytes","sha256","objectKey","objectVersion");
            for(var item:items) if(!item.keySet().equals(keys) || keys.stream().filter(key->!key.equals("sizeBytes")).anyMatch(key->!(item.get(key) instanceof String))
                    || !(item.get("sizeBytes") instanceof Integer || item.get("sizeBytes") instanceof Long)
                    || ((Number)item.get("sizeBytes")).longValue()<1) throw new IllegalArgumentException();
            ids(items);return List.copyOf(items);
        }catch(Exception malformed){throw ApprovalDocumentCanonical.unavailable("Malformed immutable attachment items.");}
    }
    private List<UUID> ids(List<Map<String,Object>> items) {
        var ids=items.stream().map(item->UUID.fromString((String)item.get("attachmentId"))).sorted().toList();if(new HashSet<>(ids).size()!=ids.size()) throw ApprovalDocumentCanonical.conflict();return ids;
    }
    private long next(long value) {try{return Math.addExact(value,1);}catch(ArithmeticException overflow){throw ApprovalDocumentCanonical.conflict();}}
    private int next(int value) {try{return Math.addExact(value,1);}catch(ArithmeticException overflow){throw ApprovalDocumentCanonical.conflict();}}
}
