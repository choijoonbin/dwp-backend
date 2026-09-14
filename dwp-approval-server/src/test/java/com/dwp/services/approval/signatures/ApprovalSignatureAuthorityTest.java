package com.dwp.services.approval.signatures;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;

class ApprovalSignatureAuthorityTest {
    final ApprovalSignatureCanonical json=new ApprovalSignatureCanonical(new ObjectMapper());
    final Clock clock=Clock.systemUTC();
    ApprovalSignatureTestAuthority port;
    @BeforeEach void setup() { ApprovalRequestContext.set(99L,42L,UUID.randomUUID(),Set.of("WORKSPACE_USER"),Set.of()); port=new ApprovalSignatureTestAuthority(clock); }
    @AfterEach void clear() { ApprovalRequestContext.clear(); }
    ApprovalSignatureAuthority.Binding binding() { return new ApprovalSignatureAuthority.Binding(ApprovalSignatureAuthority.Operation.SIGN,UUID.randomUUID(),1L,"a".repeat(64),"original-key"); }
    @Test void exactSignedBoundAuthorityIsRequiredAndHasNoPublicVerifiedFactory() {
        var proof=port.verifier(json).require(binding()); assertThat(proof.actorId()).isEqualTo(99);
        assertThat(ApprovalSignatureAuthority.Verified.class.getConstructors()).isEmpty();
        assertThatThrownBy(() -> new ApprovalSignatureAuthority((ignored,nonce) -> "{\"installed\":true}",new com.nimbusds.jose.jwk.JWKSet(port.key.toPublicJWK()),clock,json).require(binding())).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
    @Test void everyBindingSubstitutionAndUnsignedHighRiskAreDenied() {
        for (var field:java.util.List.of("nonce","purpose","method","path","routeContractKey","objectId","bodySha256","idempotencyKey","personPublicId","resourceSetKey","decisionRevision","registrySha256","grantSha256","signerKind","accessMode","sourceKind","sourceSha256")) {
            port.change=b -> b.claim(field,"tampered"); assertThatThrownBy(() -> port.verifier(json).require(binding())).as(field).isInstanceOf(com.dwp.core.exception.BaseException.class);
        }
        port.change=b -> b.claim("tenantId",43L); assertThatThrownBy(() -> port.verifier(json).require(binding())).isInstanceOf(com.dwp.core.exception.BaseException.class);
        port.change=b -> b.claim("objectVersion",1.0); assertThatThrownBy(() -> port.verifier(json).require(binding())).isInstanceOf(com.dwp.core.exception.BaseException.class);
        port.change=b -> b.claim("extra",true); assertThatThrownBy(() -> port.verifier(json).require(binding())).isInstanceOf(com.dwp.core.exception.BaseException.class);
        port.change=b -> { }; port.highRisk=false; assertThatThrownBy(() -> port.verifier(json).require(binding())).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
    @Test void missingUninstalledExpiredAndRevokedSourcesFailBeforeAnySql() {
        var sources=mock(ApprovalSignatureSourceRepository.class); var ledger=mock(ApprovalSignatureRepository.class);
        var a=port.verifier(json);
        var service=new ApprovalSignatureService(true,a,sources,ledger,null,null,json,null,null,clock);
        port.installed=false; assertThatThrownBy(() -> service.context(UUID.randomUUID(),"ko")).isInstanceOf(com.dwp.core.exception.BaseException.class); verifyNoInteractions(sources,ledger);
        port.installed=true; port.change=b -> b.expirationTime(java.util.Date.from(clock.instant().minusSeconds(1)));
        assertThatThrownBy(() -> a.require(binding())).isInstanceOf(com.dwp.core.exception.BaseException.class);
        port.change=b -> { }; var proof=a.require(binding()); port.grant="c".repeat(64); assertThatThrownBy(() -> a.unchanged(proof)).isInstanceOf(com.dwp.core.exception.BaseException.class);
        var disabled=new ApprovalSignatureService(false,a,sources,ledger,null,null,json,null,null,clock);
        assertThatThrownBy(() -> disabled.get(UUID.randomUUID())).isInstanceOf(com.dwp.core.exception.BaseException.class); verifyNoInteractions(sources,ledger);
    }
    @Test void providerModeAndCrossActorCannotBorrowSelfAttestation() {
        ApprovalRequestContext.set(99L,42L,UUID.randomUUID(),Set.of("PROVIDER_ADMIN"),Set.of());
        assertThatThrownBy(() -> port.verifier(json).require(binding())).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
    @Test void previouslySignedAssertionCannotBeReplayedAsFreshAuthorityEvenWithinTtl() {
        var binding=binding(); String[] cached={null};
        var verifier=new ApprovalSignatureAuthority((original,nonce) -> {
            if (cached[0]==null) cached[0]=port.token(original,nonce,b -> { }); return cached[0];
        },new com.nimbusds.jose.jwk.JWKSet(port.key.toPublicJWK()),clock,json);
        var proof=verifier.require(binding);
        assertThatThrownBy(() -> verifier.unchanged(proof)).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
}
