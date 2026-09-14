package com.dwp.services.approval.signatures;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApprovalSignatureEvidenceSignerTest {
    final ApprovalSignatureCanonical json=new ApprovalSignatureCanonical(new ObjectMapper());
    RSAKey key() throws Exception { return new RSAKeyGenerator(2048).keyID("approval-self-attestation:test").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate(); }
    @Test void actualCryptoReceiptBindsArtifactConsentActorAndPurpose() throws Exception {
        RSAKey key=key(); var signer=new ApprovalSignatureEvidenceSigner(key,Set.of(),true,json);
        UUID ceremony=UUID.randomUUID(),consent=UUID.randomUUID();
        var result=signer.sign(ceremony,99,"a".repeat(64),"b".repeat(64),consent,"c".repeat(64),Instant.now());
        var proof=JWSObject.parse(result.compactJws()); assertThat(proof.verify(new RSASSAVerifier(key.toPublicJWK()))).isTrue();
        assertThat(proof.getPayload().toString()).contains(ceremony.toString(),consent.toString(),"SELF_ATTESTATION","b".repeat(64),"99");
        assertThat(proof.verify(new RSASSAVerifier(key().toPublicJWK()))).isFalse();
        assertThat(result.publicKeyJson()).doesNotContain("\"d\":","\"p\":");
    }
    @Test void unprovisionedOrUnknownInventoryDoesNotSignAndProhibitedRuntimeMaterialIsRejected() throws Exception {
        var empty=new ApprovalSignatureEvidenceSigner(null,Set.of(),true,json); assertThat(empty.readiness()).isEqualTo("NOT_VERIFIED");
        assertThatThrownBy(() -> empty.sign(UUID.randomUUID(),99,"a","b",UUID.randomUUID(),"c",Instant.now())).isInstanceOf(com.dwp.core.exception.BaseException.class);
        RSAKey key=key(); assertThat(new ApprovalSignatureEvidenceSigner(key,Set.of(),false,json).readiness()).isEqualTo("NOT_VERIFIED");
        assertThatThrownBy(() -> new ApprovalSignatureEvidenceSigner(key,Set.of(key.toPublicJWK().computeThumbprint().toString()),true,json)).isInstanceOf(com.dwp.core.exception.BaseException.class);
        RSAKey runtime=new RSAKey.Builder(key).keyID("runtime:test").build();
        assertThatThrownBy(() -> new ApprovalSignatureEvidenceSigner(runtime,Set.of(),true,json)).isInstanceOf(com.dwp.core.exception.BaseException.class);
    }
    @Test void consentDefaultsFalseAndUnknownAssignmentCannotBeDeserialized() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        var consent=mapper.readValue("{\"expectedVersion\":0,\"sourceDigest\":\"a\",\"termsId\":\"t\",\"termsVersion\":1,\"termsSha256\":\"b\",\"locale\":\"ko\",\"idempotencyKey\":\"k\"}",ApprovalSignatureDtos.Consent.class);
        assertThat(consent.accepted()).isFalse();
        assertThatThrownBy(() -> mapper.readValue("{\"signerUserId\":100}",ApprovalSignatureDtos.Create.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue("{\"signerKind\":\"DELEGATED\"}",ApprovalSignatureDtos.Create.class)).isInstanceOf(Exception.class);
    }
    @Test void finalizedArtifactSnapshotAlwaysReturnsAnIndependentByteCopy() {
        byte[] bytes="finalized".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var snapshot=new ApprovalSignatureSourceRepository.Snapshot(null,bytes); bytes[0]=0;
        byte[] returned=snapshot.bytes(); returned[0]=0;
        assertThat(new String(snapshot.bytes(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("finalized");
    }
}
