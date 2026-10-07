package com.dwp.services.time.absence.ports;

import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.dwp.platform.contracts.hris.identity.v2.VerifiedCurrentHrisAuthorizationV2;
import com.dwp.platform.contracts.hris.identity.v2.CurrentHrisAuthorizationV2.TargetKind;
import com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4;
import com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4.*;

/**
 * Explicit owner SPI; no Spring/default adapter, repository, authority autoallow or connector fallback.
 * Guard result is bounded local composition, not a current signed production transport/transaction PEP.
 */
public final class AbsOwnerSnapshotRefetchPortV4 {
    public record Lookup(UUID snapshotPublicId,long expectedRevision,Instant asOf,
            VerifiedCurrentHrisAuthorizationV2 authority) {}
    @FunctionalInterface public interface OwnerLoader { ObjectNode loadCurrent(Lookup lookup); }
    @FunctionalInterface public interface PolicyLoader { RawPolicy loadCurrent(Lookup lookup); }
    @FunctionalInterface public interface TerminationLoader { ObjectNode loadCurrent(Lookup lookup); }
    public record RawPolicy(Parent localParent,ObjectNode reference,ObjectNode fullPayload) {
        public RawPolicy {
            reference=reference==null?null:reference.deepCopy();fullPayload=fullPayload==null?null:fullPayload.deepCopy();
        }
        @Override public ObjectNode reference(){return reference==null?null:reference.deepCopy();}
        @Override public ObjectNode fullPayload(){return fullPayload==null?null:fullPayload.deepCopy();}
    }
    public static final class VerifiedOwner {
        private final OwnerKind kind;private final ObjectNode raw;
        private final VerifiedCurrentHrisAuthorizationV2 authority;
        private VerifiedOwner(OwnerKind kind,ObjectNode raw,VerifiedCurrentHrisAuthorizationV2 authority) {
            this.kind=kind;this.raw=raw.deepCopy();this.authority=authority;
        }
        public OwnerKind kind(){return kind;}
        public ObjectNode payload(){return (ObjectNode)raw.get("payload").deepCopy();}
        public ObjectNode header(){return (ObjectNode)raw.get("header").deepCopy();}
        public VerifiedCurrentHrisAuthorizationV2 authority(){return authority;}
        public ObjectNode reference() {
            require(kind.referenceContractId!=null,"OWNER_HAS_NO_REFERENCE_ABI");
            ObjectNode h=header(),r=AbsOwnerResponseV4.JSON.createObjectNode();
            r.put("contractId",kind.referenceContractId);r.put("schemaVersion",1);
            for(String f:List.of("snapshotPublicId","asOf","validUntil","purposeCode","populationScopeDigest","contentSha256"))
                r.set(f,h.get(f).deepCopy());
            r.set("revision",h.get("businessRevision").deepCopy());
            r.put("ownerService",kind.ownerService);r.put("streamKey",kind.deploymentStreamKey);
            return r;
        }
    }
    public static final class VerifiedPolicy {
        private final RawPolicy source;private final VerifiedCurrentHrisAuthorizationV2 authority;
        private VerifiedPolicy(RawPolicy source,VerifiedCurrentHrisAuthorizationV2 authority){this.source=source;this.authority=authority;}
        public Parent localParent(){return source.localParent();}
        public ObjectNode reference(){return source.reference();}
        public ObjectNode fullPayload(){return source.fullPayload();}
        public VerifiedCurrentHrisAuthorizationV2 authority(){return authority;}
    }
    public static final class VerifiedTermination {
        private final ObjectNode value;private final VerifiedCurrentHrisAuthorizationV2 authority;
        private VerifiedTermination(ObjectNode value,VerifiedCurrentHrisAuthorizationV2 authority){this.value=value.deepCopy();this.authority=authority;}
        public ObjectNode payload(){return value.deepCopy();}
        public VerifiedCurrentHrisAuthorizationV2 authority(){return authority;}
    }
    private final SchemaValidator schema;private final Clock clock;
    public AbsOwnerSnapshotRefetchPortV4(SchemaValidator schema,Clock clock) {this.schema=schema;this.clock=clock;}
    public Optional<VerifiedOwner> owner(OwnerKind kind,boolean enabled,OwnerLoader loader,Lookup lookup) {
        if(!enabled)return Optional.empty(); // Optional disabled is not an empty authorized owner snapshot.
        checked(lookup);require(loader!=null&&kind!=null,"SELECTED_OWNER_ADAPTER_MISSING");
        ObjectNode raw=loader.loadCurrent(lookup);AbsOwnerResponseV4.validate(schema,"OWNER",raw);
        require(kind.contractId.equals(text(raw,"ownerContractId")),"WRONG_OWNER_CONTRACT");
        require(kind.logicalSourceAlias.equals(text(raw,"sourceStreamKey")),"WRONG_LOGICAL_SOURCE_ALIAS");
        require(kind.deploymentStreamKey.equals(text(raw,"deploymentStreamKey")),"WRONG_DEPLOYMENT_STREAM");
        ObjectNode h=(ObjectNode)raw.get("header");VerifiedCurrentHrisAuthorizationV2 a=lookup.authority;
        require(nativeLong(h.get("tenantId"))==a.actor().tenantId(),"WRONG_TENANT");
        require(uuid(h,"snapshotPublicId").equals(lookup.snapshotPublicId)
                &&nativeLong(h.get("businessRevision"))==lookup.expectedRevision,"STALE_OR_WRONG_SNAPSHOT");
        require(text(h,"purposeCode").equals(a.requirements().purpose())
                &&text(h,"selectedScopeKey").equals(a.selectedScopeKey())
                &&text(h,"populationBindingRecipeId").equals(AbsOwnerResponseV4.BINDING_RECIPE_ID)
                &&text(h,"populationScopeDigest").equals(AbsOwnerResponseV4.candidateTargetBindingDigest(a)),"WRONG_PURPOSE_OR_POPULATION");
        window(h,lookup.asOf);
        JsonNode actor=h.path("actorAuthority");
        require(uuid(actor,"actorPrincipalPublicId").equals(a.actor().principalPublicId())
                &&nativeLong(actor.get("actorUserRowVersion"))==a.actor().userRowVersion()
                &&nativeLong(actor.get("actorAccessRevision"))==a.actor().accessRevision(),"WRONG_ACTOR_NATIVE_STAMPS");
        require(text(actor,"authRevision").equals(a.authRevision().value())
                &&text(actor,"policyRevision").equals(a.policyRevision().value())
                &&text(actor,"contextKey").equals(a.contextKey().value())
                &&text(actor,"decisionRevision").equals(a.decisionRevision().value()),"WRONG_OPAQUE_AUTHORITY");
        require(text(h,"contentSha256").equals(digest(raw.get("payload"))),"OWNER_FULL_PAYLOAD_DIGEST_MISMATCH");
        if(kind==OwnerKind.EMPLOYMENT) employment(raw.get("payload"),a);
        return Optional.of(new VerifiedOwner(kind,raw,a));
    }
    public VerifiedPolicy policy(PolicyLoader loader,Lookup lookup) {
        checked(lookup);require(loader!=null,"CURRENT_ABS_POLICY_ADAPTER_MISSING");
        RawPolicy value=loader.loadCurrent(lookup);require(value!=null&&value.localParent!=null,"CURRENT_ABS_POLICY_MISSING");
        require(value.localParent.table().equals("abs_leave_plan_versions")
                &&value.localParent.tenantId()==lookup.authority.actor().tenantId(),"WRONG_LOCAL_POLICY_PARENT");
        ObjectNode ref=value.reference();AbsOwnerResponseV4.validate(schema,"POLICY_REFERENCE",ref);
        AbsOwnerResponseV4.validate(schema,"LEAVE_POLICY",value.fullPayload());
        require(uuid(ref,"snapshotPublicId").equals(lookup.snapshotPublicId)
                &&nativeLong(ref.get("revision"))==lookup.expectedRevision,"STALE_POLICY");
        require(value.localParent.publicId().equals(lookup.snapshotPublicId),"LOCAL_POLICY_UUID_RESOLUTION_MISMATCH");
        referenceWindow(ref,lookup);
        require(text(ref,"contentSha256").equals(digest(value.fullPayload())),"POLICY_CONTENT_DIGEST_MISMATCH");
        return new VerifiedPolicy(value,lookup.authority);
    }
    public VerifiedTermination termination(TerminationLoader loader,Lookup lookup) {
        checked(lookup);require(loader!=null,"HRM_TERMINATION_ADAPTER_MISSING");
        ObjectNode value=loader.loadCurrent(lookup);AbsOwnerResponseV4.validate(schema,"TERMINATION",value);
        require(uuid(value,"snapshotPublicId").equals(lookup.snapshotPublicId)
                &&nativeLong(value.get("revision"))==lookup.expectedRevision,"STALE_TERMINATION");
        window(value,lookup.asOf);
        var target=lookup.authority.target();
        require(target.kind()==TargetKind.EMPLOYMENT
                &&uuid(value,"workerPublicId").equals(target.workerPublicId()),"WRONG_TERMINATION_WORKER");
        require(value.path("assignmentPublicIds").isArray(),"MISSING_ASSIGNMENT_ARRAY");
        boolean found=false;for(JsonNode id:value.path("assignmentPublicIds"))
            if(id.isTextual()&&id.textValue().equals(target.assignmentPublicId().toString()))found=true;
        require(found&&text(value,"employmentState").equals("SEPARATED"),"WRONG_TERMINATION_ASSIGNMENT_OR_STATE");
        require(text(value,"contentSha256").equals(ownSlotDigest(value,"contentSha256")),"TERMINATION_FULL_DIGEST_MISMATCH");
        return new VerifiedTermination(value,lookup.authority);
    }
    public void current(VerifiedCurrentHrisAuthorizationV2 authority) {
        current(authority,clock);
    }
    /** A consumer cannot extend the minted lease by supplying a port with an older clock. */
    public void current(VerifiedCurrentHrisAuthorizationV2 authority,Clock consumerClock) {
        require(clock!=null&&consumerClock!=null&&schema!=null&&authority!=null,"REQUIRED_CURRENT_SOURCE_MISSING");
        Instant now=consumerClock.instant();
        require(!now.isBefore(authority.issuedAt())&&now.isBefore(authority.expiresAt()),"CURRENT_GUARD_EXPIRED");
        require(authority.requirements().mutation(),"NON_MUTATION_AUTHORITY");
        require(authority.actor().tenantId()==authority.target().tenantId(),"ACTOR_TARGET_TENANT_MISMATCH");
    }
    private void checked(Lookup lookup) {
        require(clock!=null&&schema!=null&&lookup!=null&&lookup.authority!=null,"REQUIRED_CURRENT_SOURCE_MISSING");
        require(lookup.snapshotPublicId!=null&&lookup.expectedRevision>0&&lookup.asOf!=null,"INVALID_OWNER_LOOKUP");
        Instant now=clock.instant();var a=lookup.authority;
        require(!now.isBefore(a.issuedAt())&&now.isBefore(a.expiresAt()),"CURRENT_GUARD_EXPIRED");
        require(a.requirements().mutation(),"NON_MUTATION_AUTHORITY");
        require(a.actor().tenantId()==a.target().tenantId(),"ACTOR_TARGET_TENANT_MISMATCH");
    }
    private void window(JsonNode node,Instant asOf) {
        Instant captured=Instant.parse(text(node,"asOf")),until=Instant.parse(text(node,"validUntil"));
        require(captured.equals(asOf)&&clock.instant().isBefore(until)&&asOf.isBefore(until),"STALE_OWNER_WINDOW");
    }
    private void referenceWindow(ObjectNode ref,Lookup lookup) {
        window(ref,lookup.asOf);
        require(text(ref,"purposeCode").equals(lookup.authority.requirements().purpose())
                &&text(ref,"populationScopeDigest").equals(AbsOwnerResponseV4.candidateTargetBindingDigest(lookup.authority)),"WRONG_POLICY_PURPOSE_SCOPE");
    }
    private void employment(JsonNode p,VerifiedCurrentHrisAuthorizationV2 a) {
        var t=a.target();require(t.kind()==TargetKind.EMPLOYMENT,"EMPLOYMENT_SOURCE_FOR_PERSON_TARGET");
        require(uuid(p,"personPublicId").equals(t.personPublicId())&&nativeLong(p.get("personVersion"))==t.personVersion()
                &&uuid(p,"workerPublicId").equals(t.workerPublicId())&&nativeLong(p.get("workerVersion"))==t.workerVersion()
                &&uuid(p,"workRelationshipPublicId").equals(t.workRelationshipPublicId())
                &&nativeLong(p.get("workRelationshipVersion"))==t.workRelationshipVersion()
                &&uuid(p,"assignmentPublicId").equals(t.assignmentPublicId())
                &&nativeLong(p.get("assignmentVersion"))==t.assignmentVersion(),"TARGET_PEOPLE_VERSION_VECTOR_MISMATCH");
        require(t.workerPersonPublicId().equals(t.personPublicId())
                &&t.relationshipWorkerPublicId().equals(t.workerPublicId())
                &&t.assignmentRelationshipPublicId().equals(t.workRelationshipPublicId()),"BROKEN_NATIVE_PARENT_TUPLE");
    }
    private static void require(boolean c,String code){AbsOwnerResponseV4.require(c,code);}
    private static String text(JsonNode n,String f){return AbsOwnerResponseV4.text(n,f);}
    private static UUID uuid(JsonNode n,String f){return AbsOwnerResponseV4.uuid(n,f);}
    private static long nativeLong(JsonNode n){return AbsOwnerResponseV4.nativeLong(n);}
    private static String digest(JsonNode n){return AbsOwnerResponseV4.digest(n);}
    private static String ownSlotDigest(ObjectNode n,String f){return AbsOwnerResponseV4.ownSlotDigest(n,f);}
}
