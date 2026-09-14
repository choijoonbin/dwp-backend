package com.dwp.services.auth.workflowruntime;

import static org.assertj.core.api.Assertions.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProofFixture.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Parent cryptographic linkage only, not a mocked current-authority activation positive. */
class WorkflowRuntimeAdmissionLinkTest {
    private final WorkflowRuntimeProofVerifier verifier = new WorkflowRuntimeProofVerifier(JSON, keys());
    private final WorkflowRuntimeAdmissionLink link = new WorkflowRuntimeAdmissionLink(verifier, keys());
    private ObjectNode linked(PoolMode mode) {
        var command = command(10, 11, new UUID(0, 11)); var values = (ObjectNode) command.get("command");
        values.put("commandPurpose", "REQUEST_REPLY").put("targetId", REQUEST.toString()).put("sourceGeneration", 1)
                .put("routeContractKey", "route.approvals.work.request-information-response.action").put("path", "/v1/requests/" + REQUEST + "/information-response");
        var envelope = proof(Operation.INFORMATION_ADMISSION, command); var verified = verifier.verify(envelope.transport(), envelope.body());
        var result = JSON.tree(Map.of()); WorkflowRuntimeBindings.INFO_RESULT_FIELDS.forEach(field -> ((ObjectNode) result).set(field, values.get(field)));
        Instant expiry = Instant.now().plusSeconds(15); var current = new WorkflowRuntimeIdentityPort.OwnerEvidence("raw-auth-current", "raw-policy-current", expiry, JSON.tree(Map.of("revision", 1)));
        String parent = new WorkflowRuntimeAttestationIssuer(JSON, keys()).sign(verified, current, current.stableVector(), result, expiry).attestation();
        var bindings = candidate(10, 11, new UUID(0, 11), 12, new UUID(0, 12), "REVIEWER", mode, 1);
        var owner = (ObjectNode) bindings.get("owner"); owner.put("routeContractKey", values.get("routeContractKey").asText()).put("path", values.get("path").asText());
        owner.put("requestVersion", mode == PoolMode.SEALED ? 4 : 5);
        var stage = (ObjectNode) bindings.get("stage");
        stage.put("admissionAttestation", parent).put("admissionAttestationSha256", WorkflowRuntimeJson.sha(parent)).put("commandRawBodySha256", values.get("rawBodySha256").asText());
        return bindings;
    }
    @Test void sealedRecheckBindsOldGenerationAndOriginalReplyCasVersionWhileRestartBindsKnownNextVersion() {
        for (PoolMode mode : java.util.List.of(PoolMode.SEALED, PoolMode.REBUILD, PoolMode.RETAINED)) {
            var bindings = linked(mode); var envelope = proof(Operation.CANDIDATES, bindings);
            assertThat(link.require(verifier.verify(envelope.transport(), envelope.body())).get("result").get("expectedVersion").asLong()).isEqualTo(4);
        }
    }
    @Test void generationOrReplyVersionSubstitutionCannotHealOriginalAdmission() {
        var bindings = linked(PoolMode.SEALED); ((ObjectNode) bindings.get("stage")).put("generation", 2); denied(bindings);
        bindings = linked(PoolMode.SEALED); ((ObjectNode) bindings.get("owner")).put("requestVersion", 5); denied(bindings);
        bindings = linked(PoolMode.REBUILD); ((ObjectNode) bindings.get("owner")).put("requestVersion", 4); denied(bindings);
    }
    @Test void bodyFingerprintKeyIdentityAndPurposeAreCausallyBoundToCurrentSignedParent() {
        for (String field : java.util.List.of("idempotencyKey", "contextKey", "contextScopeKey", "decisionRevision")) {
            var bindings = linked(PoolMode.SEALED); ((ObjectNode) bindings.get("owner")).put(field, field.equals("decisionRevision") ? "psr-" + "9".repeat(64) : "changed-original"); denied(bindings);
        }
        var bindings = linked(PoolMode.SEALED); ((ObjectNode) bindings.get("stage")).put("commandRawBodySha256", "9".repeat(64)); denied(bindings);
        bindings = linked(PoolMode.SEALED); ((ObjectNode) bindings.get("owner")).put("personPublicId", new UUID(0, 99).toString()); denied(bindings);
    }
    @Test void forgedHashWithoutAuthSignedParentAndCurrentRawRevisionDriftAreRejected() {
        var bindings = linked(PoolMode.SEALED); String token = bindings.get("stage").get("admissionAttestation").asText();
        var forged = token.substring(0, token.lastIndexOf('.') + 1) + "A".repeat(342);
        ((ObjectNode) bindings.get("stage")).put("admissionAttestation", forged).put("admissionAttestationSha256", WorkflowRuntimeJson.sha(forged)); denied(bindings);
        bindings = linked(PoolMode.SEALED); var envelope = proof(Operation.CANDIDATES, bindings); JsonNode parent = link.require(verifier.verify(envelope.transport(), envelope.body()));
        assertThatThrownBy(() -> link.current(parent, new WorkflowRuntimeIdentityPort.OwnerEvidence("raw-auth-revoked", "raw-policy-current", Instant.now().plusSeconds(10), JSON.tree(Map.of())))).isInstanceOf(BaseException.class);
    }
    private void denied(ObjectNode bindings) {
        var envelope = proof(Operation.CANDIDATES, bindings);
        assertThatThrownBy(() -> link.require(verifier.verify(envelope.transport(), envelope.body()))).isInstanceOf(BaseException.class);
    }
}
