package com.dwp.services.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.dwp.services.auth.service.ApprovalWorkflowRoleProofVerifierTest.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ApprovalWorkflowRoleProtocolTest {
    @BeforeAll static void signingKeys() throws Exception { keys(); }

    ApprovalWorkflowRoleProofVerifier verifier() {
        return new ApprovalWorkflowRoleProofVerifier(JSON, publicKeys(roleKey), publicKeys(userKey), publicKeys(transportKey), CLOCK);
    }

    Map<String, Object> fixedBinding() {
        var value = binding();
        value.put("personPublicId", "11111111-1111-4111-8111-111111111111");
        value.put("workflowVersionId", "22222222-2222-4222-8222-222222222222");
        value.put("formVersionId", "33333333-3333-4333-8333-333333333333");
        return value;
    }

    @Test void publicAliasAndDisjointOwnerTransportProfilesRetainExactWireValues() {
        assertThat(ApprovalWorkflowRoleProtocol.ROLE_BINDINGS).isEqualTo("ROLE_BINDINGS");
        assertThat(ApprovalWorkflowRoleProofVerifier.OPERATION).isEqualTo(ApprovalWorkflowRoleProtocol.ROLE_BINDINGS);
        assertThat(ApprovalWorkflowRoleProofVerifier.ISSUER).isEqualTo("dwp-approval-server:workflow-role-authority:v1");
        assertThat(ApprovalWorkflowRoleProofVerifier.AUDIENCE).isEqualTo("dwp-auth-server:approval-role-authority:v1");
        assertThat(ApprovalWorkflowRoleProofVerifier.PURPOSE).isEqualTo("APPROVAL_WORKFLOW_ROLE_AUTHORITY_V1");
        assertThat(ApprovalWorkflowRoleWorkloadVerifier.ISSUER).isEqualTo("dwp-approval-server:workflow-role-transport:v1");
        assertThat(ApprovalWorkflowRoleWorkloadVerifier.AUDIENCE).isEqualTo("dwp-auth-server:workflow-role-transport:v1");
        assertThat(ApprovalWorkflowRoleWorkloadVerifier.PURPOSE).isEqualTo("APPROVAL_WORKFLOW_ROLE_TRANSPORT_V1");
    }

    @Test void fixedPublishedBindingPreservesOriginalRequestAndFullBodyDigestGoldens() throws Exception {
        var value = fixedBinding();
        var parsed = ApprovalWorkflowRoleBinding.parse(JSON.valueToTree(value), JSON);
        assertThat(verifier().digest(parsed)).isEqualTo("3f48aca9946b4a569a68b225f31b63396d1e3b9c3cd7ac025455aacc0ffac67a");
        var body = JSON.readTree(ApprovalWorkflowRoleProofVerifierTest.body("fixed.owner.signature", value));
        assertThat(ApprovalWorkflowRoleBinding.sha256(ApprovalWorkflowRoleBinding.json(JSON,
                ApprovalWorkflowRoleBinding.canonical(body))))
                .isEqualTo("8f1dc8defdc82c86d6429f03b16b1a98e3d681e64ae6f14b927ba5f9a6faa5a0");
        assertThat(value.get("workflowDefinitionSha256"))
                .isEqualTo("3817e323c93a0267c15966fcfcd8561f6e9d94af7de1aad721f211f7b75b4b8e");
    }

    @Test void actualSignedOwnerAndCompactTransportKeepClosedShapesAndMinimumExpiry() throws Exception {
        var value = fixedBinding();
        var owner = claims(value);
        String source = sign(owner, roleKey);
        String raw = ApprovalWorkflowRoleProofVerifierTest.body(source, value);
        var transport = workloadClaims(raw);
        transport.put("exp", NOW.plusSeconds(12).getEpochSecond());
        assertThat(owner.keySet()).isEqualTo(Set.of("iss", "aud", "purpose", "jti", "iat", "nbf", "exp",
                "operation", "requestDigest", "bindings"));
        assertThat(transport.keySet()).isEqualTo(Set.of("iss", "aud", "purpose", "jti", "iat", "nbf", "exp",
                "operation", "httpMethod", "httpPath", "bodySha256", "sourceProofSha256"));
        var actual = verifier().verify(sign(transport, transportKey), raw);
        assertThat(JSON.writeValueAsString(actual.binding().sealed())).isEqualTo(JSON.writeValueAsString(value));
        assertThat(actual.requestDigest()).isEqualTo("3f48aca9946b4a569a68b225f31b63396d1e3b9c3cd7ac025455aacc0ffac67a");
        assertThat(actual.proofId()).isEqualTo(UUID.fromString(owner.get("jti").toString()));
        assertThat(actual.transportId()).isEqualTo(UUID.fromString(transport.get("jti").toString()));
        assertThat(actual.bodySha256()).isEqualTo(transport.get("bodySha256"));
        assertThat(actual.expiresAt()).isEqualTo(NOW.plusSeconds(12));
    }
}
