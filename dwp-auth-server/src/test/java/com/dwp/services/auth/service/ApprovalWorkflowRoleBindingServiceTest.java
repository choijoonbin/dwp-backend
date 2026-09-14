package com.dwp.services.auth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.entity.Role;
import com.dwp.services.auth.repository.RoleRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApprovalWorkflowRoleBindingServiceTest {
    static RSAKey mappingKey;
    ApprovalWorkflowRoleCurrentAuthority authority;
    ApprovalWorkflowRoleReplayStore replay;
    RoleRepository roles;
    ApprovalWorkflowRoleBindingService service;
    String token;
    String body;

    @BeforeAll static void keys() throws Exception {
        ApprovalWorkflowRoleProofVerifierTest.keys();
        mappingKey = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).keyID("auth-workflow-mapping-1").generate();
    }
    @BeforeEach void initialize() throws Exception {
        var proof = new ApprovalWorkflowRoleProofVerifier(ApprovalWorkflowRoleProofVerifierTest.JSON,
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.roleKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.userKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.transportKey), ApprovalWorkflowRoleProofVerifierTest.CLOCK);
        authority = mock(ApprovalWorkflowRoleCurrentAuthority.class); replay = mock(ApprovalWorkflowRoleReplayStore.class); roles = mock(RoleRepository.class);
        var issuer = new ApprovalWorkflowRoleAttestationIssuer(ApprovalWorkflowRoleProofVerifierTest.JSON, mappingKey.toJSONString(),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.roleKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.userKey),
                ApprovalWorkflowRoleProofVerifierTest.publicKeys(ApprovalWorkflowRoleProofVerifierTest.transportKey), ApprovalWorkflowRoleProofVerifierTest.CLOCK);
        service = new ApprovalWorkflowRoleBindingService(proof, authority, replay, roles, issuer, ApprovalWorkflowRoleProofVerifierTest.CLOCK);
        var binding = ApprovalWorkflowRoleProofVerifierTest.binding();
        token = ApprovalWorkflowRoleProofVerifierTest.sign(ApprovalWorkflowRoleProofVerifierTest.claims(binding), ApprovalWorkflowRoleProofVerifierTest.roleKey);
        body = ApprovalWorkflowRoleProofVerifierTest.body(token, binding);
        token = ApprovalWorkflowRoleProofVerifierTest.transport(body);
        when(authority.require(any())).thenReturn(evidence("auth-1"));
        when(roles.findByTenantIdAndCodeIn(42L, List.of("APPROVAL_REVIEWER"))).thenReturn(List.of(role(7L, 3L)));
    }

    ApprovalWorkflowRoleCurrentAuthority.Evidence evidence(String revision) {
        return new ApprovalWorkflowRoleCurrentAuthority.Evidence(revision, "policy-1", ApprovalWorkflowRoleProofVerifierTest.NOW.plusSeconds(20));
    }
    Role role(long id, long version) {
        return Role.builder().roleId(id).tenantId(42L).code("APPROVAL_REVIEWER").version(version).status("ACTIVE").build();
    }
    void fails(ErrorCode code) {
        assertEquals(code, assertThrows(BaseException.class, () -> service.bind(token, body)).getErrorCode());
    }

    @Test void signsOnlyRequeriedStableRoleMappingWithExactPinsAndMinExpiry() throws Exception {
        var response = service.bind(token, body); var signed = SignedJWT.parse(response.attestationToken());
        assertTrue(signed.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(mappingKey.toPublicJWK())));
        var claims = signed.getJWTClaimsSet();
        assertEquals(ApprovalWorkflowRoleAttestationIssuer.PURPOSE, claims.getStringClaim("purpose"));
        assertEquals(ApprovalWorkflowRoleProofVerifierTest.NOW.plusSeconds(20), claims.getExpirationTime().toInstant());
        assertEquals(1, assertInstanceOf(List.class, claims.getClaim("mapping")).size());
        assertEquals(14, claims.getClaims().size());
        verify(authority, times(2)).require(any()); verify(replay).consume(any());
        verify(roles, times(2)).findByTenantIdAndCodeIn(42L, List.of("APPROVAL_REVIEWER"));
        verifyNoMoreInteractions(roles);
    }

    @Test void invalidSignatureOrHeaderBodyMismatchHasZeroAuthorityReplayAndRoleReads() {
        failsWithWrongToken(); verifyNoInteractions(authority, replay, roles);
    }
    private void failsWithWrongToken() {
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BaseException.class, () -> service.bind("borrowed", body)).getErrorCode());
    }

    @Test void revokedOrUnknownCurrentAuthorityHasZeroReplayAndRoleReads() {
        when(authority.require(any())).thenThrow(new BaseException(ErrorCode.FORBIDDEN)); fails(ErrorCode.FORBIDDEN);
        verifyNoInteractions(replay, roles);
    }

    @Test void replayOrUnavailableRedisFailsBeforeAnyRoleRead() {
        doThrow(new BaseException(ErrorCode.FORBIDDEN)).when(replay).consume(any()); fails(ErrorCode.FORBIDDEN); verifyNoInteractions(roles);
    }

    @Test void identityPolicyOrRoleMappingDriftNeverReturnsAnAttestation() {
        when(authority.require(any())).thenReturn(evidence("auth-1"), evidence("auth-2")); fails(ErrorCode.DECISION_REVISION_CONFLICT);
        initializeRoleMappingDrift(); fails(ErrorCode.DECISION_REVISION_CONFLICT);
    }
    private void initializeRoleMappingDrift() {
        when(authority.require(any())).thenReturn(evidence("auth-1"));
        when(roles.findByTenantIdAndCodeIn(anyLong(), any())).thenReturn(List.of(role(7L, 3L))).thenReturn(List.of(role(7L, 4L)));
    }

    @Test void disabledMissingDuplicateAndCrossTenantRolesFailClosedWithoutPopulationLookup() {
        when(roles.findByTenantIdAndCodeIn(anyLong(), any())).thenReturn(List.of()); fails(ErrorCode.FORBIDDEN);
        Role foreign = role(7L, 3L); foreign.setTenantId(43L);
        when(roles.findByTenantIdAndCodeIn(anyLong(), any())).thenReturn(List.of(foreign)); fails(ErrorCode.FORBIDDEN);
        Role disabled = role(7L, 3L); disabled.setStatus("DISABLED");
        when(roles.findByTenantIdAndCodeIn(anyLong(), any())).thenReturn(List.of(disabled)); fails(ErrorCode.FORBIDDEN);
    }
}
