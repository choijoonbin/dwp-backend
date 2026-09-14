package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Closed result shapes are checked before signing, independently of the caller's verifier. */
final class WorkflowRuntimeResults {
    private static final Set<String> SUBJECT = Set.of("tenantId", "userId", "personPublicId", "identityPlane", "status", "roleIds", "canApprove");
    private WorkflowRuntimeResults() { }
    static void require(WorkflowRuntimeJson json, WorkflowRuntimeProofVerifier.Verified proof, JsonNode result) {
        if (proof.operation() == Operation.INFORMATION_ADMISSION) {
            exact(result, WorkflowRuntimeBindings.INFO_RESULT_FIELDS);
            var command = proof.caller().sealed().get("command");
            for (String field : WorkflowRuntimeBindings.INFO_RESULT_FIELDS) if (!json.canonical(result.get(field)).equals(json.canonical(command.get(field)))) throw denied();
        } else if (proof.operation() == Operation.CANDIDATES) {
            exact(result, Set.of("role", "complete", "truncated", "members", "memberSetSha256"));
            var role = result.get("role"); exact(role, Set.of("roleCode", "roleId", "roleVersion"));
            if (!text(role, "roleCode").equals(text(proof.caller().sealed().get("stage"), "candidateRole"))) throw denied();
            long roleId = number(role, "roleId", 1, Long.MAX_VALUE); number(role, "roleVersion", 0, Long.MAX_VALUE);
            if (!result.get("complete").isBoolean() || !result.get("complete").booleanValue()
                    || !result.get("truncated").isBoolean() || result.get("truncated").booleanValue()
                    || !result.get("members").isArray() || result.get("members").size() > 1000) throw denied();
            long previous = 0; var persons = new java.util.HashSet<java.util.UUID>();
            for (var member : result.get("members")) {
                subject(member, proof.caller().tenantId(), roleId, true);
                long user = number(member, "userId", 1, Long.MAX_VALUE);
                if (user <= previous || !persons.add(uuid(member, "personPublicId"))) throw denied(); previous = user;
            }
            if (!sha(json.canonical(result.get("members"))).equals(hash(result, "memberSetSha256"))) throw denied();
        } else {
            exact(result, Set.of("target", "actor", "principal", "delegation", "ownerAdmissionDigest"));
            var bindings = proof.caller().sealed(); var target = bindings.get("target");
            if (!json.canonical(result.get("target")).equals(json.canonical(target))
                    || !json.canonical(result.get("delegation")).equals(json.canonical(target.get("delegation")))
                    || !json.canonical(result.get("ownerAdmissionDigest")).equals(json.canonical(bindings.get("stage").get("admissionAttestationSha256")))) throw denied();
            subject(result.get("actor"), proof.caller().tenantId(), 0, false); subject(result.get("principal"), proof.caller().tenantId(), 0, true);
            for (String participant : Set.of("actor", "principal")) {
                if (number(result.get(participant), "userId", 1, Long.MAX_VALUE) != number(target, participant + "Id", 1, Long.MAX_VALUE)
                        || !uuid(result.get(participant), "personPublicId").equals(uuid(target, participant + "PersonPublicId"))) throw denied();
            }
        }
    }
    private static void subject(JsonNode value, long tenant, long roleId, boolean principal) {
        exact(value, SUBJECT);
        if (number(value, "tenantId", 1, Long.MAX_VALUE) != tenant || !text(value, "identityPlane").equals("TENANT")
                || !text(value, "status").equals("ACTIVE") || !value.get("canApprove").isBoolean() || !value.get("canApprove").booleanValue()) throw denied();
        number(value, "userId", 1, Long.MAX_VALUE); uuid(value, "personPublicId"); var roles = value.get("roleIds");
        if (!roles.isArray() || roles.size() > 1 || principal && roles.size() != 1) throw denied();
        if (roles.size() == 1 && (!roles.get(0).isIntegralNumber() || !roles.get(0).canConvertToLong() || roles.get(0).longValue() < 1
                || roleId > 0 && roles.get(0).longValue() != roleId)) throw denied();
    }
}
