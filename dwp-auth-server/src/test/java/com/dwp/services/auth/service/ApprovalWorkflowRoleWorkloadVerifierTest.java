package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovalWorkflowRoleWorkloadVerifierTest {
    ApprovalWorkflowRoleProofVerifier verifier;
    String body;
    String owner;
    String transport;

    @BeforeAll static void keys() throws Exception { ApprovalWorkflowRoleBindingServiceTest.keys(); }
    @BeforeEach void initialize() throws Exception {
        var binding = ApprovalWorkflowRoleProofVerifierTest.binding();
        owner = ApprovalWorkflowRoleProofVerifierTest.sign(ApprovalWorkflowRoleProofVerifierTest.claims(binding), ApprovalWorkflowRoleProofVerifierTest.roleKey);
        body = ApprovalWorkflowRoleProofVerifierTest.body(owner, binding);
        transport = ApprovalWorkflowRoleProofVerifierTest.transport(body);
        verifier = configured(ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.transportKey));
    }

    private ApprovalWorkflowRoleProofVerifier configured(String workloadKeys) {
        return new ApprovalWorkflowRoleProofVerifier(ApprovalWorkflowRoleProofVerifierTest.JSON,
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.roleKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.userKey),
                workloadKeys, ApprovalWorkflowRoleProofVerifierTest.CLOCK);
    }

    private void denied(String header, String raw) {
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BaseException.class, () -> verifier.verify(header, raw)).getErrorCode());
    }

    @Test void compactTokenBindsBothProofAndWholeBodyWithMinimumEvidenceExpiry() throws Exception {
        var claims = ApprovalWorkflowRoleProofVerifierTest.workloadClaims(body);
        claims.put("exp", ApprovalWorkflowRoleProofVerifierTest.NOW.plusSeconds(12).getEpochSecond());
        var proof = verifier.verify(ApprovalWorkflowRoleProofVerifierTest.sign(claims, ApprovalWorkflowRoleProofVerifierTest.transportKey), body);
        assertEquals(ApprovalWorkflowRoleProofVerifierTest.NOW.plusSeconds(12), proof.expiresAt());
        assertEquals(claims.get("bodySha256"), proof.bodySha256());
        assertEquals(claims.get("jti"), proof.transportId().toString());
        assertTrue(transport.length() <= 2048);
        assertEquals(12, claims.size());
    }

    @Test void missingOversizedAndOwnerOrUserHeaderTokensCannotReplaceWorkloadPurpose() throws Exception {
        denied(null, body); denied("a".repeat(2049), body); denied(owner, body);
        denied(ApprovalWorkflowRoleProofVerifierTest.sign(ApprovalWorkflowRoleProofVerifierTest.workloadClaims(body),
                ApprovalWorkflowRoleProofVerifierTest.userKey), body);
    }

    @Test void bodyTamperAndAnotherOtherwiseValidOwnerProofInvalidateExistingTransport() throws Exception {
        denied(transport, body.replace("\"requestVersion\":0", "\"requestVersion\":1"));
        var binding = ApprovalWorkflowRoleProofVerifierTest.binding();
        String another = ApprovalWorkflowRoleProofVerifierTest.sign(ApprovalWorkflowRoleProofVerifierTest.claims(binding), ApprovalWorkflowRoleProofVerifierTest.roleKey);
        denied(transport, ApprovalWorkflowRoleProofVerifierTest.body(another, binding));
    }

    @Test void workloadTtlExactHttpIdentityPurposeAndDigestCannotBeChanged() throws Exception {
        for (var change : List.of(Map.of("exp", ApprovalWorkflowRoleProofVerifierTest.NOW.plusSeconds(31).getEpochSecond()),
                Map.of("exp", ApprovalWorkflowRoleProofVerifierTest.NOW.getEpochSecond()),
                Map.of("iat", ApprovalWorkflowRoleProofVerifierTest.NOW.plusSeconds(1).getEpochSecond()),
                Map.of("nbf", ApprovalWorkflowRoleProofVerifierTest.NOW.minusSeconds(1).getEpochSecond()),
                Map.of("httpMethod", "HEAD"), Map.of("httpPath", "/internal/approvals/form-users"),
                Map.of("purpose", ApprovalWorkflowRoleProofVerifier.PURPOSE), Map.of("operation", "USER_DIRECTORY"),
                Map.of("bodySha256", "a".repeat(64)), Map.of("sourceProofSha256", "b".repeat(64)))) {
            var claims = ApprovalWorkflowRoleProofVerifierTest.workloadClaims(body); claims.putAll(change);
            denied(ApprovalWorkflowRoleProofVerifierTest.sign(claims, ApprovalWorkflowRoleProofVerifierTest.transportKey), body);
        }
    }

    @Test void strictClaimsAndCanonicalSignatureRejectExtraValuesCoercionAndAlternateEncoding() throws Exception {
        var claims = ApprovalWorkflowRoleProofVerifierTest.workloadClaims(body); claims.put("roleIds", List.of(7));
        denied(ApprovalWorkflowRoleProofVerifierTest.sign(claims, ApprovalWorkflowRoleProofVerifierTest.transportKey), body);
        claims = ApprovalWorkflowRoleProofVerifierTest.workloadClaims(body); claims.put("iat", "1");
        denied(ApprovalWorkflowRoleProofVerifierTest.sign(claims, ApprovalWorkflowRoleProofVerifierTest.transportKey), body);
        denied(transport + "=", body);
    }

    @Test void ownerAndUserKeysOrMissingWorkloadTrustFailClosedAsUnavailable() {
        for (String keys : List.of("", ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.roleKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.userKey))) {
            var wrong = configured(keys);
            assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, assertThrows(BaseException.class, () -> wrong.verify(transport, body)).getErrorCode());
        }
    }

    @Test void bodyObjectOrderCanCanonicalizeButDuplicateAndTrailingBodyTokensAreForbidden() throws Exception {
        var json = ApprovalWorkflowRoleProofVerifierTest.JSON;
        var original = json.readTree(body); var reordered = new LinkedHashMap<String, Object>();
        reordered.put("sourceProof", original.get("sourceProof")); reordered.put("operation", original.get("operation"));
        reordered.put("bindings", original.get("bindings"));
        assertEquals(verifier.verify(transport, body).bodySha256(), verifier.verify(transport, json.writeValueAsString(reordered)).bodySha256());
        denied(transport, body.replaceFirst("\\{", "{\"operation\":\"ROLE_BINDINGS\","));
        denied(transport, body + " true");
    }

    @Test void mappingSignerCannotReuseTransportKeyEvenAfterValidTwoProofVerification() {
        var issuer = new ApprovalWorkflowRoleAttestationIssuer(ApprovalWorkflowRoleProofVerifierTest.JSON,
                ApprovalWorkflowRoleProofVerifierTest.transportKey.toJSONString(),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.roleKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.userKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.transportKey), ApprovalWorkflowRoleProofVerifierTest.CLOCK);
        var proof = verifier.verify(transport, body);
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, assertThrows(BaseException.class, () -> issuer.sign(proof,
                List.of(new ApprovalWorkflowRoleMapping("APPROVAL_REVIEWER", 7, 3)),
                new ApprovalWorkflowRoleCurrentAuthority.Evidence("auth-1", "policy-1", proof.expiresAt()))).getErrorCode());
    }
}
