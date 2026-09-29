package com.dwp.services.time.absence.ports;

import static org.junit.jupiter.api.Assertions.*;
import static com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4.*;
import static com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4.JSON;
import static com.dwp.services.time.absence.contracts.v4.AbsOwnerResponseV4Test.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.dwp.platform.contracts.hris.identity.v2.VerifiedCurrentHrisAuthorizationV2;
import com.dwp.services.time.absence.ports.AbsOwnerSnapshotRefetchPortV4.*;
import org.junit.jupiter.api.Test;

public class AbsOwnerSnapshotRefetchPortV4Test {
    public static AbsOwnerSnapshotRefetchPortV4 port(){return new AbsOwnerSnapshotRefetchPortV4(engine(),CLOCK);}
    public static ObjectNode rawOwner(OwnerKind kind,UUID snapshot,VerifiedCurrentHrisAuthorizationV2 a,ObjectNode payload) {
        ObjectNode raw=JSON.createObjectNode();raw.put("wireContractId",WIRE_ID);
        raw.put("ownerContractId",kind.contractId);raw.put("sourceStreamKey",kind.logicalSourceAlias);
        raw.put("deploymentStreamKey",kind.deploymentStreamKey);ObjectNode h=raw.putObject("header");
        h.put("tenantId",Long.toString(a.actor().tenantId()));h.put("snapshotPublicId",snapshot.toString());h.put("businessRevision","1");
        h.put("asOf",NOW.toString());h.put("validUntil",NOW.plusSeconds(200).toString());
        h.put("purposeCode",a.requirements().purpose());h.put("selectedScopeKey",SCOPE);
        h.put("populationBindingRecipeId",BINDING_RECIPE_ID);h.put("populationScopeDigest",candidateTargetBindingDigest(a));h.put("contentSha256",digest(payload));
        ObjectNode actor=h.putObject("actorAuthority");actor.put("actorPrincipalPublicId",a.actor().principalPublicId().toString());
        actor.put("actorUserRowVersion",Long.toString(a.actor().userRowVersion()));actor.put("actorAccessRevision",Long.toString(a.actor().accessRevision()));
        actor.put("authRevision",a.authRevision().value());actor.put("policyRevision",a.policyRevision().value());
        actor.put("contextKey",a.contextKey().value());actor.put("decisionRevision",a.decisionRevision().value());
        raw.set("payload",payload);return raw;
    }
    public static VerifiedOwner owner(OwnerKind kind,UUID snapshot,VerifiedCurrentHrisAuthorizationV2 a,ObjectNode payload) {
        ObjectNode raw=rawOwner(kind,snapshot,a,payload);
        return port().owner(kind,true,q->raw,new Lookup(snapshot,1,NOW,a)).orElseThrow();
    }
    public static VerifiedPolicy policy(ObjectNode input,VerifiedCurrentHrisAuthorizationV2 a,String treatment) {
        ObjectNode ref=(ObjectNode)input.path("policyRef").deepCopy(),content=(ObjectNode)input.path("policyContent").deepCopy();
        if(treatment!=null)content.put("terminationTreatment",treatment);
        ref.put("asOf",NOW.toString());ref.put("validUntil",NOW.plusSeconds(200).toString());
        ref.put("purposeCode",a.requirements().purpose());ref.put("populationScopeDigest",candidateTargetBindingDigest(a));ref.put("contentSha256",digest(content));
        UUID id=uuid(ref,"snapshotPublicId");
        return port().policy(q->new RawPolicy(new Parent("abs_leave_plan_versions",41,42,id),ref,content),new Lookup(id,1,NOW,a));
    }
    public static ObjectNode employment(VerifiedCurrentHrisAuthorizationV2 a,ObjectNode input) {
        var t=a.target();ObjectNode p=JSON.createObjectNode();
        p.put("personPublicId",t.personPublicId().toString());p.put("workerPublicId",t.workerPublicId().toString());
        p.put("workRelationshipPublicId",t.workRelationshipPublicId().toString());p.put("assignmentPublicId",t.assignmentPublicId().toString());
        p.put("legalEmployerPublicId","d0000000-0000-4000-8000-000000000049");
        p.put("personVersion",Long.toString(t.personVersion()));p.put("workerVersion",Long.toString(t.workerVersion()));
        p.put("workRelationshipVersion",Long.toString(t.workRelationshipVersion()));p.put("assignmentVersion",Long.toString(t.assignmentVersion()));
        p.put("employmentStartedOn","2026-10-17");p.putNull("employmentEndedOn");
        p.putArray("sourceRefs").add(input.path("policyRef"));return p;
    }
    public static ObjectNode calendar() throws Exception {
        JsonNode f=JSON.readTree(resource("fixtures"));
        return wire(f.path("importEnvironment").path("owner").path("calendar").path("payload"));
    }
    public static ObjectNode configuration(ObjectNode input) {
        return configuration(input,null);
    }
    public static ObjectNode configuration(ObjectNode input,VerifiedPolicy currentPolicy) {
        JsonNode policyRef=currentPolicy==null?input.path("policyRef"):currentPolicy.reference();
        ObjectNode p=JSON.createObjectNode();String id=text(input.path("selectedItems").get(0).path("configurationRef"),"snapshotPublicId");
        p.put("snapshotPublicId",id);p.put("configurationVersionPublicId","d0000000-0000-4000-8000-000000000050");
        p.put("revision","1");p.put("configurationKind","TIM_LEAVE_POLICY");p.put("timeArtifactPublicId",text(policyRef,"snapshotPublicId"));
        p.put("timeArtifactRevision",text(policyRef,"revision"));p.put("timeArtifactSha256",text(policyRef,"contentSha256"));p.put("priority",1);
        p.put("effectiveFrom",NOW.toString());p.putNull("effectiveTo");p.put("status","PUBLISHED");p.put("contentSha256","1".repeat(64));
        p.put("approvalReceiptPublicId","d0000000-0000-4000-8000-000000000051");p.put("authorPublicId",ACTOR.toString());
        p.put("approverPublicId","d0000000-0000-4000-8000-000000000052");p.put("publishedAt",NOW.toString());
        p.put("ownerStreamKey","platform-hris-configuration");p.putArray("sourceRefs").add(input.path("policyRef"));
        ObjectNode scope=p.putObject("scope");scope.put("kind","TENANT");
        p.put("asOf",NOW.toString());p.put("validUntil",NOW.plusSeconds(200).toString());return p;
    }
    @Test void disabledZeroCallsAndRequiredMissingDenies() throws Exception {
        AtomicInteger calls=new AtomicInteger();ObjectNode input=wire(fixture("inputsAB",0));var a=proof(input);
        var q=new Lookup(uuid(input.path("policyRef"),"snapshotPublicId"),1,NOW,a);
        assertTrue(port().owner(OwnerKind.EMPLOYMENT,false,lookup->{calls.incrementAndGet();return null;},q).isEmpty());
        assertEquals(0,calls.get());
        assertThrows(IllegalArgumentException.class,()->port().owner(OwnerKind.EMPLOYMENT,true,null,q));
        assertThrows(IllegalArgumentException.class,()->new AbsOwnerSnapshotRefetchPortV4(null,CLOCK).owner(OwnerKind.EMPLOYMENT,true,lookup->null,q));
    }
    @Test void fullNativeTargetWithNoTargetAuthAndActorAboveSafeInteger() throws Exception {
        ObjectNode input=wire(fixture("inputsAB",0));var a=proof(input);UUID snapshot=uuid(input.path("selectedItems").get(0).path("employmentRef"),"snapshotPublicId");
        VerifiedOwner o=owner(OwnerKind.EMPLOYMENT,snapshot,a,employment(a,input));
        assertNull(a.actor().personPublicId());assertNotEquals(a.actor().principalPublicId(),a.target().personPublicId());
        assertEquals("people-main",o.reference().path("streamKey").asText());
        ObjectNode clone=o.payload();clone.put("workerVersion","24");assertEquals("23",o.payload().path("workerVersion").asText());
    }
    @Test void rejectsWrongTenantStaleActorOpaqueTargetAndChangedPayload() throws Exception {
        ObjectNode input=wire(fixture("inputsAB",0));var a=proof(input);UUID id=uuid(input.path("selectedItems").get(0).path("employmentRef"),"snapshotPublicId");
        ObjectNode base=rawOwner(OwnerKind.EMPLOYMENT,id,a,employment(a,input));
        List<ObjectNode> negatives=new ArrayList<>();
        ObjectNode n=base.deepCopy();((ObjectNode)n.get("header")).put("tenantId","42");negatives.add(n);
        n=base.deepCopy();((ObjectNode)n.get("header")).put("validUntil",NOW.toString());negatives.add(n);
        n=base.deepCopy();((ObjectNode)n.path("header").path("actorAuthority")).put("actorUserRowVersion","9007199254740992");negatives.add(n);
        n=base.deepCopy();((ObjectNode)n.path("header").path("actorAuthority")).put("policyRevision","policy-other");negatives.add(n);
        n=base.deepCopy();((ObjectNode)n.get("payload")).put("assignmentVersion","32");((ObjectNode)n.get("header")).put("contentSha256",digest(n.get("payload")));negatives.add(n);
        n=base.deepCopy();((ObjectNode)n.get("payload")).put("workerVersion","24");negatives.add(n);
        n=base.deepCopy();n.put("deploymentStreamKey","people-employment");negatives.add(n);
        n=base.deepCopy();((ObjectNode)n.path("header").path("actorAuthority")).put("actorUserRowVersion","9223372036854775808");negatives.add(n);
        n=base.deepCopy();((ObjectNode)n.path("header").path("actorAuthority")).put("actorUserRowVersion",9007199254740993L);negatives.add(n);
        for(ObjectNode bad:negatives)assertThrows(IllegalArgumentException.class,()->port().owner(OwnerKind.EMPLOYMENT,true,q->bad,new Lookup(id,1,NOW,a)));
    }
}
