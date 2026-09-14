package com.dwp.services.auth.approvalsignatures;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dwp.core.exception.BaseException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.util.*;
import org.junit.jupiter.api.*;

class SignatureAuthorityProtocolTest {
    static SignatureAuthorityTestFixture f;
    @BeforeAll static void keys() { f=new SignatureAuthorityTestFixture(); }
    @Test void actualIndependentOwnerAndTransportProofsBindEveryClosedSourceField() {
        var b=f.binding(SignatureAuthorityProtocol.Operation.CREATE); var e=f.exchange(b);
        var proof=f.verifier.verify(e.bytes(),e.token());
        assertThat(proof.bindings()).isEqualTo(f.node(b)); assertThat(proof.binding().source().size()).isEqualTo(25);
        assertThat(proof.binding().source().path("ownerUserId").longValue()).isEqualTo(99L);
        var copy=proof.bindings(); ((com.fasterxml.jackson.databind.node.ObjectNode)copy).put("actorId",100);
        assertThat(proof.binding().actorId()).isEqualTo(99L);
    }
    @Test void rejectsBorrowedPurposeUnsignedAndMutatedPrivateBody() {
        var b=f.binding(SignatureAuthorityProtocol.Operation.CREATE);
        for (var e:List.of(f.exchange(b,"DWP_POLICY_IMPACT_OWNER_SOURCE_V1",SignatureAuthorityProtocol.TRANSPORT,SignatureAuthorityTestFixture.NOW.plusSeconds(30)),
                f.exchange(b,SignatureAuthorityProtocol.OWNER,"DWP_APPROVAL_WORKFLOW_RUNTIME_TRANSPORT_V1",SignatureAuthorityTestFixture.NOW.plusSeconds(30))))
            assertThatThrownBy(()->f.verifier.verify(e.bytes(),e.token())).isInstanceOf(BaseException.class);
        var e=f.exchange(b); assertThatThrownBy(()->f.verifier.verify(e.bytes(),"unsigned")).isInstanceOf(BaseException.class);
        byte[] changed=new String(e.bytes(),java.nio.charset.StandardCharsets.UTF_8).replace("original-key","changed-key").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(()->f.verifier.verify(changed,e.token())).isInstanceOf(BaseException.class);
    }
    @Test void strictDuplicatesFractionsOverflowAndSourceActorMismatchCannotReachAuthority() {
        var e=f.exchange(f.binding(SignatureAuthorityProtocol.Operation.CREATE));
        byte[] duplicate=("{\"sourceProof\":\"first\",\"sourceProof\":\"second\",\"bindings\":{}} ").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(()->f.verifier.verify(duplicate,e.token())).isInstanceOf(BaseException.class);
        for (Object value:List.of(42.5,new java.math.BigInteger("9007199254740992"),0L)) {
            var b=f.binding(SignatureAuthorityProtocol.Operation.CREATE); b.put("tenantId",value); var bad=f.exchange(b);
            assertThatThrownBy(()->f.verifier.verify(bad.bytes(),bad.token())).isInstanceOf(BaseException.class);
        }
        var b=f.binding(SignatureAuthorityProtocol.Operation.CREATE); b.put("actorId",100L); var bad=f.exchange(b);
        assertThatThrownBy(()->f.verifier.verify(bad.bytes(),bad.token())).isInstanceOf(BaseException.class);
    }
    @Test void rejectsExpiredOrOverlongProofAndOpaqueScopeMismatch() {
        var b=f.binding(SignatureAuthorityProtocol.Operation.CREATE);
        for (var until:List.of(SignatureAuthorityTestFixture.NOW,SignatureAuthorityTestFixture.NOW.plusSeconds(31))) {
            var e=f.exchange(b,SignatureAuthorityProtocol.OWNER,SignatureAuthorityProtocol.TRANSPORT,until);
            assertThatThrownBy(()->f.verifier.verify(e.bytes(),e.token())).isInstanceOf(BaseException.class);
        }
        var e=f.exchange(b); b.put("contextScopeKey","retargeted");
        assertThatThrownBy(()->f.verifier.verify(f.json.bytes(Map.of("sourceProof",f.json.parse(e.bytes()).path("sourceProof").textValue(),"bindings",b)),e.token())).isInstanceOf(BaseException.class);
    }
    @Test void differentlyNamedSameKeyOrMissingInventoryIsNotASeparatePurpose() {
        var renamed=new RSAKey.Builder(f.owner).keyID("approval-signature-transport:renamed").build();
        assertThatThrownBy(()->new SignatureAuthorityKeys(f.json,SignatureAuthorityTestFixture.jwks(f.owner),SignatureAuthorityTestFixture.jwks(renamed),
                f.authority.toJSONString(),SignatureAuthorityTestFixture.jwks(f.authority),List.of(SignatureAuthorityTestFixture.jwks(f.mfa)),true)).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->new SignatureAuthorityKeys(f.json,SignatureAuthorityTestFixture.jwks(f.owner),SignatureAuthorityTestFixture.jwks(f.transport),
                f.authority.toJSONString(),SignatureAuthorityTestFixture.jwks(f.authority),List.of(SignatureAuthorityTestFixture.jwks(f.mfa)),false)).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->new SignatureAuthorityKeys(f.json,SignatureAuthorityTestFixture.jwks(f.owner),SignatureAuthorityTestFixture.jwks(f.transport),
                f.authority.toJSONString(),SignatureAuthorityTestFixture.jwks(f.authority),List.of(SignatureAuthorityTestFixture.jwks(f.owner)),true)).isInstanceOf(BaseException.class);
    }
    @Test void currentAuthorityVectorIsRereadAndDriftCannotProduceSignedAuthority() throws Exception {
        var e=f.exchange(f.binding(SignatureAuthorityProtocol.Operation.CREATE)); var proof=f.verifier.verify(e.bytes(),e.token());
        var current=mock(SignatureCurrentAuthority.class);
        var original=new SignatureCurrentAuthority.Observation("auth-v1","policy-v10","b".repeat(64),"c".repeat(64),SignatureAuthorityTestFixture.NOW.plusSeconds(30),false);
        when(current.requireCurrent(proof)).thenReturn(original);
        var service=new SignatureAuthorityService(true,()->f.verifier,current,()->new SignatureAuthorityIssuer(f.keys,SignatureAuthorityTestFixture.CLOCK));
        var jwt=SignedJWT.parse(service.evaluate(proof)); assertThat(jwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(f.authority.toPublicJWK()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getClaim("sourceKind")).isEqualTo("ARTIFACT"); verify(current,times(3)).requireCurrent(proof);
        reset(current); when(current.requireCurrent(proof)).thenReturn(original,new SignatureCurrentAuthority.Observation("auth-v2","policy-v10","b".repeat(64),"d".repeat(64),original.expiresAt(),false));
        assertThatThrownBy(()->service.evaluate(proof)).isInstanceOf(BaseException.class);
    }
    @Test void featureDisabledHasZeroDependencyReadsProvisionOrWrites() {
        var current=mock(SignatureCurrentAuthority.class);
        var service=new SignatureAuthorityService(false,()->{throw new AssertionError("key access");},current,()->{throw new AssertionError("issuer access");});
        assertThatThrownBy(()->service.preverify(new byte[]{1},"anything")).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->service.evaluate(null)).isInstanceOf(BaseException.class); verifyNoInteractions(current);
    }
}
