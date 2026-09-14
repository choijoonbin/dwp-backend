package com.dwp.services.auth.workflowruntime;

import static org.assertj.core.api.Assertions.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProofFixture.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowRuntimeProofTest {
    private final WorkflowRuntimeProofVerifier verifier = new WorkflowRuntimeProofVerifier(JSON, keys());
    private ObjectNode candidate(PoolMode mode) { return WorkflowRuntimeProofFixture.candidate(10, 11, new UUID(0, 11), 12, new UUID(0, 12), "REVIEWER", mode, 1); }
    @Test void exactFieldCountsAreStructuralAndNeverPadding() {
        assertThat(OWNER_CLAIMS).hasSize(10); assertThat(TRANSPORT_CLAIMS).hasSize(13); assertThat(ATTESTATION_CLAIMS).hasSize(15);
        assertThat(AUTHORITY_FIELDS).hasSize(6); assertThat(WorkflowRuntimeBindings.OWNER_FIELDS).hasSize(25);
        assertThat(WorkflowRuntimeBindings.STAGE_FIELDS).hasSize(15); assertThat(WorkflowRuntimeBindings.TARGET_FIELDS).hasSize(11).contains("stepKey");
        assertThat(WorkflowRuntimeBindings.INFO_RESULT_FIELDS).hasSize(15);
    }
    @Test void allFourExplicitCandidateProfilesKeepTheirOwnPinShape() {
        for (PoolMode mode : PoolMode.values()) {
            var envelope = proof(Operation.CANDIDATES, candidate(mode));
            assertThat(verifier.verify(envelope.transport(), envelope.body()).caller().sealed().get("stage").get("poolMode").asText()).isEqualTo(mode.name());
        }
    }
    @Test void sealedReadRecheckRequiresEveryFrozenPinAndNeverAcceptsNullPool() {
        for (String field : java.util.List.of("snapshotSha256", "candidateSetSha256", "candidateCount", "requiredVotes")) {
            var binding = candidate(PoolMode.SEALED); ((ObjectNode) binding.get("stage")).putNull(field); denied(Operation.CANDIDATES, binding);
            binding = candidate(PoolMode.SEALED); ((ObjectNode) binding.get("stage")).remove(field); denied(Operation.CANDIDATES, binding);
        }
        var binding = candidate(PoolMode.SEALED); ((ObjectNode) binding.get("stage")).put("requiredVotes", 3); denied(Operation.CANDIDATES, binding);
        binding = candidate(PoolMode.SEALED); ((ObjectNode) binding.get("stage")).put("generation", 0); denied(Operation.CANDIDATES, binding);
    }
    @Test void profileDiscriminatorCannotBeInferredOrUnknown() {
        var binding = candidate(PoolMode.SEALED); ((ObjectNode) binding.get("stage")).remove("poolMode"); denied(Operation.CANDIDATES, binding);
        for (String mode : java.util.List.of("FRESH", "sealed", "SYSTEM", "SIMULATION")) {
            binding = candidate(PoolMode.SEALED); ((ObjectNode) binding.get("stage")).put("poolMode", mode); denied(Operation.CANDIDATES, binding);
        }
    }
    @Test void targetStepKeyBindsAnActualStageAndVoterOnlyAcceptsSealed() {
        var binding = candidate(PoolMode.SEALED); binding.set("target", target(11, new UUID(0, 11), 11, new UUID(0, 11), 7));
        var envelope = proof(Operation.VOTER, binding); assertThat(verifier.verify(envelope.transport(), envelope.body()).operation()).isEqualTo(Operation.VOTER);
        ((ObjectNode) binding.get("target")).put("stepKey", "OTHER_STEP"); denied(Operation.VOTER, binding);
        binding = candidate(PoolMode.INITIAL); binding.set("target", target(11, new UUID(0, 11), 11, new UUID(0, 11), 7)); denied(Operation.VOTER, binding);
    }
    @Test void signedTransportRejectsBodyTamperBeforeAuthorityAndNoUserOrFrozenRolePurposeBorrow() {
        var binding = candidate(PoolMode.SEALED); var envelope = proof(Operation.CANDIDATES, binding);
        String changed = new String(envelope.body(), StandardCharsets.UTF_8).replace("\"requestVersion\":4", "\"requestVersion\":5");
        assertThatThrownBy(() -> verifier.verify(envelope.transport(), changed.getBytes(StandardCharsets.UTF_8))).isInstanceOf(BaseException.class);
        for (String purpose : java.util.List.of("APPROVAL_FORM_TENANT_PEOPLE_V1", "APPROVAL_FORM_REFERENCE_RESOLVE_V1", "APPROVAL_WORKFLOW_ROLE_BINDINGS_V1")) {
            var claims = new java.util.TreeMap<>(envelope.transportClaims()); claims.put("purpose", purpose);
            assertThatThrownBy(() -> verifier.verify(sign(claims, TRANSPORT), envelope.body())).isInstanceOf(BaseException.class);
        }
    }
    @Test void duplicateFieldsMissingBindingsAndNonCanonicalJwtPartsAreRejected() {
        var envelope = proof(Operation.CANDIDATES, candidate(PoolMode.SEALED));
        String body = new String(envelope.body(), StandardCharsets.UTF_8);
        String duplicate = body.replace("\"operation\":\"CANDIDATES\"", "\"operation\":\"CANDIDATES\",\"operation\":\"VOTER\"");
        assertThatThrownBy(() -> verifier.verify(envelope.transport(), duplicate.getBytes(StandardCharsets.UTF_8))).isInstanceOf(BaseException.class);
        var altered = envelope.json().deepCopy(); altered.remove("bindings");
        assertThatThrownBy(() -> verifier.verify(envelope.transport(), JSON.canonical(altered).getBytes(StandardCharsets.UTF_8))).isInstanceOf(BaseException.class);
        String padded = envelope.transport().replaceFirst("\\.", "=.");
        assertThatThrownBy(() -> verifier.verify(padded, envelope.body())).isInstanceOf(BaseException.class);
    }
    @Test void unknownAliasCannotBecomeAProductionAction() {
        var binding = candidate(PoolMode.SEALED); ((ObjectNode) binding.get("owner")).put("routeContractKey", "route.approvals.work.drafts.recover.action"); denied(Operation.CANDIDATES, binding);
    }
    @Test void runtimeKeyMaterialCannotReuseUserOrFrozenRoleKeys() {
        var reused = new WorkflowRuntimeKeys(JSON, publicKeys(OWNER), publicKeys(TRANSPORT), ATTESTATION.toJSONString(), publicKeys(ATTESTATION), publicKeys(OWNER));
        assertThat(reused.owners()).isEmpty(); assertThatThrownBy(reused::signer).isInstanceOf(BaseException.class);
        var privateTrust = new WorkflowRuntimeKeys(JSON, OWNER.toJSONString(), publicKeys(TRANSPORT), ATTESTATION.toJSONString(), publicKeys(ATTESTATION));
        assertThat(privateTrust.owners()).isEmpty();
    }
    @Test void sixtyFourStagePublishedDefinitionUsesBodyProofNotOversizedHeaders() {
        var binding = WorkflowRuntimeProofFixture.candidate(10, 11, new UUID(0, 11), 12, new UUID(0, 12), "REVIEWER", PoolMode.SEALED, 64);
        var envelope = proof(Operation.CANDIDATES, binding);
        assertThat(envelope.transport()).hasSizeLessThanOrEqualTo(2048); assertThat(envelope.sourceProof().length()).isGreaterThan(8192);
        assertThat(verifier.verify(envelope.transport(), envelope.body()).operation()).isEqualTo(Operation.CANDIDATES);
        ((ObjectNode) binding.get("owner")).put("workflowDefinitionSha256", "0".repeat(64)); denied(Operation.CANDIDATES, binding);
    }
    private void denied(Operation operation, ObjectNode binding) {
        var envelope = proof(operation, binding);
        assertThatThrownBy(() -> verifier.verify(envelope.transport(), envelope.body())).isInstanceOf(BaseException.class);
    }
}
