package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public final class WorkflowRuntimeAdmissionLink {
    private final WorkflowRuntimeProofVerifier verifier;
    private final WorkflowRuntimeKeys keys;
    public WorkflowRuntimeAdmissionLink(WorkflowRuntimeProofVerifier verifier, WorkflowRuntimeKeys keys) { this.verifier = verifier; this.keys = keys; }
    public JsonNode require(WorkflowRuntimeProofVerifier.Verified proof) {
        if (proof.operation() == Operation.INFORMATION_ADMISSION) return null;
        var bindings = proof.caller().sealed(); var owner = bindings.get("owner"); var stage = bindings.get("stage");
        if (stage.get("admissionAttestation").isNull()) return null;
        String token = text(stage, "admissionAttestation", MAX_ATTESTATION);
        if (!sha(token).equals(hash(stage, "admissionAttestationSha256"))) throw denied();
        var claims = verifier.jwt(token, MAX_ATTESTATION, keys.attestations(), ATTESTATION_CLAIMS);
        var operation = Operation.INFORMATION_ADMISSION;
        if (!operation.attestationIssuer().equals(text(claims, "iss")) || !operation.attestationAudience().equals(text(claims, "aud"))
                || !operation.attestationPurpose().equals(text(claims, "purpose")) || !operation.name().equals(text(claims, "operation"))
                || !text(claims, "sub").equals(Long.toString(proof.caller().actorId()))) throw denied();
        uuid(claims, "sourceProofJti"); uuid(claims, "transportProofJti"); hash(claims, "bodySha256"); hash(claims, "bindingsSha256");
        var authority = claims.get("authority"); exact(authority, AUTHORITY_FIELDS);
        long expires = number(claims, "exp", 1, Long.MAX_VALUE);
        if (number(authority, "expiresAt", 1, Long.MAX_VALUE) != expires
                || number(authority, "evaluatedAt", 1, Long.MAX_VALUE) > number(claims, "iat", 1, Long.MAX_VALUE)
                || !text(authority, "sourceRevision").matches("awr-[a-f0-9]{64}")) throw denied();
        hash(authority, "sourceVectorSha256"); text(authority, "ownerAuthRevision"); text(authority, "ownerPolicyRevision");
        var result = claims.get("result"); exact(result, WorkflowRuntimeBindings.INFO_RESULT_FIELDS);
        if (number(result, "tenantId", 1, Long.MAX_VALUE) != proof.caller().tenantId()
                || !uuid(result, "personPublicId").equals(proof.caller().personPublicId())
                || !hash(result, "rawBodySha256").equals(hash(stage, "commandRawBodySha256"))) throw denied();
        for (String field : java.util.Set.of("idempotencyKey", "contextKey", "contextScopeKey", "decisionRevision", "routeContractKey", "accessMode")) {
            if (!text(result, field).equals(text(owner, field))) throw denied();
        }
        if (!java.util.Set.of("110", "111").contains(text(result, "rolloutState"))) throw denied();
        number(result, "expectedVersion", 0, 9007199254740991L);
        if (number(result, "authorityValidUntil", 1, Long.MAX_VALUE) < expires) throw denied();
        String purpose = text(result, "commandPurpose"); var target = uuid(result, "targetId");
        if (purpose.equals("TASK_INFORMATION")) {
            if (!text(owner, "routeContractKey").equals("route.approvals.work.task-decision.action")
                    || !text(owner, "path").equals("/v1/tasks/" + target + "/decisions") || !result.get("sourceGeneration").isNull()) throw denied();
        } else if (purpose.equals("REQUEST_REPLY")) {
            if (!text(owner, "routeContractKey").equals("route.approvals.work.request-information-response.action")
                    || !uuid(owner, "requestId").equals(target)) throw denied();
            long source = number(result, "sourceGeneration", 1, Long.MAX_VALUE - 1);
            boolean activation = proof.operation() == Operation.CANDIDATES && !text(stage, "poolMode").equals(PoolMode.SEALED.name());
            long expected = activation ? source + 1 : source;
            if (number(stage, "generation", 1, Long.MAX_VALUE) != expected) throw denied();
            long originalVersion = number(result, "expectedVersion", 0, 9007199254740990L);
            if (number(owner, "requestVersion", 0, 9007199254740991L) != originalVersion + (activation ? 1 : 0)) throw denied();
        } else throw denied();
        return claims.deepCopy();
    }
    public void current(JsonNode parent, WorkflowRuntimeIdentityPort.OwnerEvidence current) {
        if (parent == null) return;
        var authority = parent.get("authority");
        if (!text(authority, "ownerAuthRevision").equals(current.authRevision())
                || !text(authority, "ownerPolicyRevision").equals(current.policyRevision())
                || !Instant.now().isBefore(Instant.ofEpochSecond(number(parent, "exp", 1, Long.MAX_VALUE)))) throw changed();
    }
}
