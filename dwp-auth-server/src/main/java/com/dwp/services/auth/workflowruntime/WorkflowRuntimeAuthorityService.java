package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProofVerifier.minimum;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

@Service
public final class WorkflowRuntimeAuthorityService {
    private final WorkflowRuntimeProofVerifier verifier;
    private final WorkflowRuntimeIdentityPort identities;
    private final WorkflowRuntimeAdmissionLink admission;
    private final WorkflowRuntimeSourceRepository sources;
    private final WorkflowRuntimeReplayStore replay;
    private final WorkflowRuntimeAttestationIssuer issuer;
    private final WorkflowRuntimeJson json;
    public WorkflowRuntimeAuthorityService(WorkflowRuntimeProofVerifier verifier, WorkflowRuntimeIdentityPort identities,
            WorkflowRuntimeAdmissionLink admission, WorkflowRuntimeSourceRepository sources, WorkflowRuntimeReplayStore replay,
            WorkflowRuntimeAttestationIssuer issuer, WorkflowRuntimeJson json) {
        this.verifier = verifier; this.identities = identities; this.admission = admission; this.sources = sources;
        this.replay = replay; this.issuer = issuer; this.json = json;
    }
    public WorkflowRuntimeAttestationIssuer.Response resolve(String transportToken, byte[] rawBody) {
        var proof = verifier.verify(transportToken, rawBody);
        issuer.requireReady();
        JsonNode parent = admission.require(proof);
        var owner = identities.requireOwner(proof); admission.current(parent, owner);
        var bindings = proof.caller().sealed(); long tenant = proof.caller().tenantId();
        if (proof.operation() == Operation.INFORMATION_ADMISSION) return information(proof, owner);
        var stage = bindings.get("stage");
        String code = WorkflowRuntimePublishedDefinition.role(json, bindings.get("owner"), stage);
        var role = sources.currentRole(tenant, code);
        var members = proof.operation() == Operation.CANDIDATES ? sources.completeMembers(tenant, role) : java.util.List.<Long>of();
        var requested = new TreeSet<Long>(members); requested.add(proof.caller().actorId()); requested.add(number(stage, "requesterUserId", 1, Long.MAX_VALUE));
        if (proof.operation() == Operation.VOTER) {
            requested.add(number(bindings.get("target"), "actorId", 1, Long.MAX_VALUE));
            requested.add(number(bindings.get("target"), "principalId", 1, Long.MAX_VALUE));
        }
        var snapshot = sources.snapshot(tenant, requested);
        var requester = snapshot.subjects().get(number(stage, "requesterUserId", 1, Long.MAX_VALUE));
        if (!requester.subject().personPublicId().equals(uuid(stage, "requesterPersonPublicId"))) throw denied();
        assertOwnerSources(proof, snapshot);
        JsonNode result;
        if (proof.operation() == Operation.CANDIDATES) {
            var people = new ArrayList<JsonNode>(); var persons = new TreeSet<String>();
            for (long id : members) {
                var subject = snapshot.subjects().get(id);
                if (!subject.roles().contains(role.roleId()) || !persons.add(subject.subject().personPublicId().toString())) throw changed();
                if (!subject.canApprove()) throw denied();
                people.add(subject.minimal(json, tenant, role.roleId(), true));
            }
            result = json.tree(Map.of("role", role, "complete", true, "truncated", false, "members", people,
                    "memberSetSha256", sha(json.canonical(json.tree(people)))));
        } else result = voter(proof, snapshot, role);
        var refreshed = identities.requireOwner(proof); admission.current(parent, refreshed);
        if (!owner.stableVector().equals(refreshed.stableVector()) || !owner.authRevision().equals(refreshed.authRevision())
                || !owner.policyRevision().equals(refreshed.policyRevision()) || !role.equals(sources.currentRole(tenant, code))) throw changed();
        if (proof.operation() == Operation.CANDIDATES && !members.equals(sources.completeMembers(tenant, role))) throw changed();
        var post = sources.snapshot(tenant, requested);
        if (!snapshot.vector().equals(post.vector())) throw changed();
        Instant expiry = minimum(owner.expiresAt(), refreshed.expiresAt());
        if (parent != null) expiry = minimum(expiry, Instant.ofEpochSecond(number(parent, "exp", 1, Long.MAX_VALUE)));
        for (var subject : snapshot.subjects().values()) expiry = minimum(expiry, subject.expiresAt());
        for (var subject : post.subjects().values()) expiry = minimum(expiry, subject.expiresAt());
        if (proof.operation() == Operation.VOTER && !bindings.get("target").get("delegation").isNull()) {
            expiry = minimum(expiry, Instant.ofEpochSecond(number(bindings.get("target").get("delegation"), "endsAt", 1, Long.MAX_VALUE)));
        }
        expiry = Instant.ofEpochSecond(expiry.getEpochSecond());
        replay.consume(proof, expiry);
        return issuer.sign(proof, refreshed, json.tree(Map.of("owner", refreshed.stableVector(), "role", role, "subjects", post.vector())), result, expiry);
    }
    private WorkflowRuntimeAttestationIssuer.Response information(WorkflowRuntimeProofVerifier.Verified proof, WorkflowRuntimeIdentityPort.OwnerEvidence owner) {
        // Admission has no Approval owner DB dependency and never queries candidate membership.
        var refreshed = identities.requireOwner(proof);
        if (!owner.stableVector().equals(refreshed.stableVector()) || !owner.authRevision().equals(refreshed.authRevision())
                || !owner.policyRevision().equals(refreshed.policyRevision())) throw changed();
        var command = proof.caller().sealed().get("command");
        var result = json.tree(Map.of()).deepCopy();
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) result;
        WorkflowRuntimeBindings.INFO_RESULT_FIELDS.forEach(field -> object.set(field, command.get(field).deepCopy()));
        exact(result, WorkflowRuntimeBindings.INFO_RESULT_FIELDS);
        Instant expiry = Instant.ofEpochSecond(minimum(owner.expiresAt(), refreshed.expiresAt()).getEpochSecond());
        replay.consume(proof, expiry);
        return issuer.sign(proof, refreshed, refreshed.stableVector(), result, expiry);
    }
    private JsonNode voter(WorkflowRuntimeProofVerifier.Verified proof, WorkflowRuntimeSourceRepository.Snapshot snapshot, WorkflowRuntimeSourceRepository.Role role) {
        var bindings = proof.caller().sealed(); var target = bindings.get("target"); var stage = bindings.get("stage");
        long actorId = number(target, "actorId", 1, Long.MAX_VALUE), principalId = number(target, "principalId", 1, Long.MAX_VALUE);
        var actor = snapshot.subjects().get(actorId); var principal = snapshot.subjects().get(principalId);
        if (!actor.subject().personPublicId().equals(uuid(target, "actorPersonPublicId"))
                || !principal.subject().personPublicId().equals(uuid(target, "principalPersonPublicId")) || !actor.canApprove() || !principal.canApprove()
                || !principal.roles().contains(role.roleId())) throw denied();
        long requester = number(stage, "requesterUserId", 1, Long.MAX_VALUE); var person = uuid(stage, "requesterPersonPublicId");
        if (actorId == requester || principalId == requester || person.equals(actor.subject().personPublicId()) || person.equals(principal.subject().personPublicId())
                || actorId != principalId && actor.subject().personPublicId().equals(principal.subject().personPublicId())) throw denied();
        var delegation = target.get("delegation");
        if (!delegation.isNull()) {
            long now = Instant.now().getEpochSecond();
            if (number(delegation, "authorityRoleId", 1, Long.MAX_VALUE) != role.roleId()
                    || number(delegation, "startsAt", 1, Long.MAX_VALUE) > now || number(delegation, "endsAt", 1, Long.MAX_VALUE) <= now) throw denied();
        }
        var result = json.tree(Map.of("target", target, "actor", actor.minimal(json, proof.caller().tenantId(), role.roleId(), false),
                "principal", principal.minimal(json, proof.caller().tenantId(), role.roleId(), true), "delegation", delegation));
        ((com.fasterxml.jackson.databind.node.ObjectNode) result).set("ownerAdmissionDigest", stage.get("admissionAttestationSha256"));
        return result;
    }
    private static void assertOwnerSources(WorkflowRuntimeProofVerifier.Verified proof, WorkflowRuntimeSourceRepository.Snapshot snapshot) {
        var owner = snapshot.subjects().get(proof.caller().actorId());
        if (!owner.subject().personPublicId().equals(proof.caller().personPublicId()) || !owner.permissions().containsAll(java.util.Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_FORM:VIEW"))) throw denied();
        if (proof.caller().routeContractKey().equals("route.approvals.work.task-decision.action")) { if (!owner.canApprove()) throw denied(); }
        else if (!owner.permissions().contains("ACTION.APPROVAL_REQUEST:UPDATE")) throw denied();
    }
}
