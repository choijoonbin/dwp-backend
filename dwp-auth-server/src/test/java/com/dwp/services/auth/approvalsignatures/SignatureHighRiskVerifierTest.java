package com.dwp.services.auth.approvalsignatures;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.nimbusds.jwt.JWTClaimsSet;
import java.util.*;
import org.junit.jupiter.api.*;

class SignatureHighRiskVerifierTest {
    static SignatureAuthorityTestFixture f;
    @BeforeAll static void keys() { f=new SignatureAuthorityTestFixture(); }
    SignatureHighRiskVerifier verifier() {
        return new SignatureHighRiskVerifier(f.json,f.mfa.toPublicJWK(),"https://auth.example.test",f.mfa.getKeyID(),"urn:dwp:acr:mfa",
                SignatureAuthorityTestFixture.CLOCK,600,900);
    }
    Map<String,Object> claims(Map<String,Object> map) {
        var b=SignatureAuthorityBindings.parse(f.node(map),f.json); var c=new LinkedHashMap<String,Object>();
        c.put("iss","https://auth.example.test"); c.put("aud","dwp-approval-server"); c.put("sub",Long.toString(b.actorId())); c.put("tenant_id",b.tenantId());
        c.put("iat",SignatureAuthorityTestFixture.NOW.getEpochSecond()); c.put("nbf",SignatureAuthorityTestFixture.NOW.getEpochSecond());
        c.put("exp",SignatureAuthorityTestFixture.NOW.plusSeconds(300).getEpochSecond()); c.put("auth_time",SignatureAuthorityTestFixture.NOW.getEpochSecond());
        c.put("acr","urn:dwp:acr:mfa"); c.put("amr",List.of("pwd","mfa")); c.put("jti","original-challenge"); c.put("nonce","original-nonce");
        c.put("owner_service_key","approval"); c.put("command_contract_key",b.operation().route()); c.put("activation_policy","STEPUP-MGMT-HIGH-V1");
        c.put("capability_contract_key",b.operation().capability()); c.put("context_key",b.contextKey()); c.put("scope_ref",b.contextScopeKey());
        c.put("target_type","APPROVAL_SIGNATURE_REQUEST"); c.put("target_id",b.objectId().toString()); c.put("target_version",b.objectVersion());
        c.put("command_method","POST"); c.put("command_path","/api/approvals"+b.operation().path(b.objectId()));
        c.put("idempotency_key",b.idempotencyKey()); c.put("payload_sha256",b.bodySha256()); c.put("decision_revision",b.decisionRevision());
        c.put("command_sha256",ProductSurfaceStepUpChallengeContract.commandSha256(new ProductSurfaceStepUpChallengeContract.CommandMaterial(
                b.operation().route(),"approval","dwp-approval-server","POST",(String)c.get("command_path"),b.contextKey(),b.contextScopeKey(),
                "APPROVAL_SIGNATURE_REQUEST",b.objectId().toString(),b.objectVersion(),b.idempotencyKey(),b.bodySha256(),b.decisionRevision())));
        return c;
    }
    SignatureAuthorityBindings withToken(Map<String,Object> b,Map<String,Object> c) throws Exception {
        b.put("stepUpToken",SignatureAuthorityTestFixture.sign(f.mfa,JWTClaimsSet.parse(c))); return SignatureAuthorityBindings.parse(f.node(b),f.json);
    }
    @Test void originalCommandBoundMfaUsesSharedDigestAndActualRsa() throws Exception {
        var b=f.binding(SignatureAuthorityProtocol.Operation.SIGN); var binding=withToken(b,claims(b));
        assertThat(verifier().verify(binding)).isEqualTo(SignatureAuthorityTestFixture.NOW.plusSeconds(300));
        var nativeVerifier=new com.dwp.services.approval.security.ApprovalStepUpVerifier(new com.fasterxml.jackson.databind.ObjectMapper(),
                "-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(f.mfa.toRSAPublicKey().getEncoded())+"\n-----END PUBLIC KEY-----",
                "https://auth.example.test","dwp-approval-server",f.mfa.getKeyID(),"urn:dwp:acr:mfa",600,900);
        assertThat(nativeVerifier.verify(binding.stepUpToken(),new com.dwp.services.approval.security.ApprovalStepUpVerifier.CommandBinding(
                binding.actorId(),binding.tenantId(),binding.operation().route(),binding.contextKey(),"STEPUP-MGMT-HIGH-V1",binding.operation().capability(),
                binding.contextScopeKey(),"APPROVAL_SIGNATURE_REQUEST",binding.objectId().toString(),binding.objectVersion(),"POST",
                "/api/approvals"+binding.operation().path(binding.objectId()),binding.idempotencyKey(),binding.bodySha256(),binding.decisionRevision())).nonce()).isEqualTo("original-nonce");
    }
    @Test void rejectsOtherPurposeTargetBodyCasContextAndExpiredMfaWithoutCallerBoolean() throws Exception {
        var b=f.binding(SignatureAuthorityProtocol.Operation.SIGN); var original=claims(b);
        for (var mismatch:Map.<String,Object>of("command_contract_key","route.approvals.admin.policy-publish.action", "target_type","APPROVAL_FORM",
                "target_version",6L,"payload_sha256","0".repeat(64),"scope_ref","different-opaque","command_path","/api/approvals/v1/other",
                "amr",List.of("pwd"),"auth_time",SignatureAuthorityTestFixture.NOW.minusSeconds(601).getEpochSecond(),"exp",SignatureAuthorityTestFixture.NOW.getEpochSecond()).entrySet()) {
            var c=new LinkedHashMap<>(original); c.put(mismatch.getKey(),mismatch.getValue()); var binding=withToken(b,c);
            assertThatThrownBy(()->verifier().verify(binding)).as(mismatch.getKey()).isInstanceOf(BaseException.class);
        }
        b.put("stepUpToken","true"); assertThatThrownBy(()->verifier().verify(SignatureAuthorityBindings.parse(f.node(b),f.json))).isInstanceOf(BaseException.class);
        var e=f.exchange(b); var proof=f.verifier.verify(e.bytes(),e.token());
        assertThatThrownBy(()->new SignatureAuthorityIssuer(f.keys,SignatureAuthorityTestFixture.CLOCK).issue(proof,
                new SignatureCurrentAuthority.Observation("auth","policy","b".repeat(64),"c".repeat(64),SignatureAuthorityTestFixture.NOW.plusSeconds(30),false))).isInstanceOf(BaseException.class);
    }
    @Test void renamedAuthorityKeyCannotSignTheOriginalMfa() throws Exception {
        var b=f.binding(SignatureAuthorityProtocol.Operation.SIGN); var c=claims(b);
        b.put("stepUpToken",SignatureAuthorityTestFixture.sign(new com.nimbusds.jose.jwk.RSAKey.Builder(f.authority).keyID(f.mfa.getKeyID()).build(),JWTClaimsSet.parse(c)));
        assertThatThrownBy(()->verifier().verify(SignatureAuthorityBindings.parse(f.node(b),f.json))).isInstanceOf(BaseException.class);
    }
}
