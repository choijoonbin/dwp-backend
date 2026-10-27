package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.GrantBinding;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantRotationReadinessVerifier.ReadinessItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.auth.hrisservicegrant.HrisServiceGrantAuthorityRepositoryPostgresTest.*;

/** Signed rotation readiness evidence for the V271 authority database tests. */
final class HrisServiceGrantReadinessPostgresFixture {
    private HrisServiceGrantReadinessPostgresFixture() {
    }

    static List<ReadinessItem> signedReadinessItems(long tenantId, UUID grantSetId,
            UUID fenceId, Instant effectiveAt,
            List<HrisServiceGrantAuthorityV1.ReadinessObservation> observations,
            KeyPair callerKey, KeyPair ownerKey, GrantBinding binding) {
        return signedReadinessItems(tenantId, grantSetId, fenceId, effectiveAt, observations,
                callerKey, ownerKey, binding,
                Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.SECONDS), 1);
    }

    static List<ReadinessItem> signedReadinessItems(long tenantId, UUID grantSetId,
            UUID fenceId, Instant effectiveAt,
            List<HrisServiceGrantAuthorityV1.ReadinessObservation> observations,
            KeyPair callerKey, KeyPair ownerKey, GrantBinding binding,
            Instant observedAt, long issueDelaySeconds) {
        return signedReadinessItems(tenantId, grantSetId, fenceId, effectiveAt,
                observations, callerKey, ownerKey, binding, observedAt,
                issueDelaySeconds, 180);
    }

    static List<ReadinessItem> signedReadinessItems(long tenantId, UUID grantSetId,
            UUID fenceId, Instant effectiveAt,
            List<HrisServiceGrantAuthorityV1.ReadinessObservation> observations,
            KeyPair callerKey, KeyPair ownerKey, GrantBinding binding,
            Instant observedAt, long issueDelaySeconds,
            long receiptLifetimeSeconds) {
        return observations.stream().map(observation -> {
            var role = observation.attestingServiceRole();
            return signedReadinessItem(tenantId, grantSetId, fenceId, effectiveAt,
                    observation, role == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                            ? callerKey : ownerKey,
                    role == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                            ? binding.callerSigningKeyId() : binding.ownerSigningKeyId(),
                    observedAt, issueDelaySeconds, receiptLifetimeSeconds);
        }).toList();
    }

    static ReadinessItem signedReadinessItem(long tenantId, UUID grantSetId,
            UUID fenceId, Instant effectiveAt,
            HrisServiceGrantAuthorityV1.ReadinessObservation observation,
            KeyPair signer, String keyId, Instant observedAt, long issueDelaySeconds,
            long receiptLifetimeSeconds) {
        try {
            var role = observation.attestingServiceRole();
            String workload = role == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                    ? observation.callerWorkloadId() : observation.ownerWorkloadId();
            Instant expiresAt = observedAt.plusSeconds(receiptLifetimeSeconds);
            UUID correlation = UUID.randomUUID();
            ObjectNode receipt = new ObjectMapper().createObjectNode();
            receipt.put("schemaName", HrisServiceGrantRotationReadinessVerifier.SCHEMA);
            receipt.put("schemaVersion", 1); receipt.put("tenantId", tenantId);
            receipt.put("bindingId", observation.bindingId());
            receipt.put("callerWorkloadId", observation.callerWorkloadId());
            receipt.put("ownerWorkloadId", observation.ownerWorkloadId());
            receipt.put("bindingPurpose", observation.purpose());
            receipt.put("serviceWorkloadId", workload);
            receipt.put("attestingServiceRole", role.name());
            receipt.put("oldGrantSetId", grantSetId.toString());
            receipt.put("quiesceFenceId", fenceId.toString());
            receipt.put("quiesceRevision", observation.quiesceRevision());
            receipt.put("quiesceEffectiveAt", effectiveAt.toString());
            receipt.put("observedDrainActivityRevision", observation.drainActivityRevision());
            receipt.put("drainQuietSince", observation.drainQuietSince().toString());
            receipt.put("readinessEligibleAt", observation.readinessEligibleAt().toString());
            receipt.put("readinessObservationSha256", observation.readinessObservationSha256());
            receipt.put("observedGrantRevision", 1);
            receipt.put("tenantGrantRevision", observation.tenantGrantRevision());
            receipt.put("authorizationRevision", observation.authorizationRevision());
            receipt.put("pendingCount", 0); receipt.putNull("oldestPendingAt");
            receipt.put("observedAt", observedAt.toString());
            receipt.put("expiresAt", expiresAt.toString());
            receipt.put("correlationId", correlation.toString());
            receipt.put("evidenceSha256", Hrm013WorkloadProofV1.sha256(
                    HrisServiceGrantRotationReadinessVerifier.canonicalBytes(receipt)));
            byte[] receiptBytes = HrisServiceGrantRotationReadinessVerifier.canonicalBytes(receipt);

            ObjectNode claims = new ObjectMapper().createObjectNode();
            Instant issuedAt = observedAt.plusSeconds(issueDelaySeconds);
            claims.put("iss", workload); claims.put("aud", "dwp-auth-server");
            claims.put("sub", workload); claims.put("iat", issuedAt.getEpochSecond());
            claims.put("nbf", issuedAt.getEpochSecond());
            claims.put("exp", issuedAt.plusSeconds(300).getEpochSecond());
            claims.put("jti", UUID.randomUUID().toString());
            claims.put("requestNonce", UUID.randomUUID().toString());
            for (String field : List.of("tenantId", "bindingId", "callerWorkloadId",
                    "ownerWorkloadId", "bindingPurpose", "serviceWorkloadId",
                    "attestingServiceRole", "oldGrantSetId", "quiesceFenceId",
                    "quiesceRevision", "quiesceEffectiveAt", "observedDrainActivityRevision",
                    "drainQuietSince", "readinessEligibleAt", "readinessObservationSha256",
                    "observedGrantRevision", "tenantGrantRevision", "authorizationRevision",
                    "evidenceSha256", "correlationId")) claims.set(field, receipt.get(field));
            claims.put("proofPurpose", HrisServiceGrantRotationReadinessVerifier.PROOF_PURPOSE);
            byte[] claimBytes = HrisServiceGrantRotationReadinessVerifier.canonicalBytes(claims);
            ObjectNode header = new ObjectMapper().createObjectNode();
            header.put("alg", "RS256");
            header.put("typ", HrisServiceGrantRotationReadinessVerifier.PROOF_TYPE);
            header.put("kid", keyId);
            String encodedHeader = encode(HrisServiceGrantRotationReadinessVerifier
                    .canonicalBytes(header));
            String signingInput = encodedHeader + "." + encode(claimBytes);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(signer.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return new ReadinessItem(receiptBytes, claimBytes,
                    encodedHeader + ".." + encode(signature.sign()));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static String readinessDigest(List<ReadinessItem> items) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> receipts = items.stream().map(item -> {
            try { return (ObjectNode) mapper.readTree(item.receiptBytes()); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }).sorted(java.util.Comparator.comparing(value -> String.join("\n",
                value.path("bindingId").asText(), value.path("callerWorkloadId").asText(),
                value.path("ownerWorkloadId").asText(), value.path("bindingPurpose").asText(),
                value.path("attestingServiceRole").asText(),
                value.path("serviceWorkloadId").asText(),
                value.path("evidenceSha256").asText()))).toList();
        ArrayNode evidence = mapper.createArrayNode();
        receipts.forEach(value -> evidence.add(value.path("evidenceSha256").asText()));
        return Hrm013WorkloadProofV1.sha256(
                HrisServiceGrantRotationReadinessVerifier.canonicalBytes(evidence));
    }

    static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2_048); return generator.generateKeyPair();
    }

    static HrisServiceGrantAuthorityV1.RsaPublicJwk jwk(
            KeyPair pair, String keyId) {
        RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        return new HrisServiceGrantAuthorityV1.RsaPublicJwk("RS256",
                encode(unsigned(key.getPublicExponent().toByteArray())), keyId, "RSA",
                encode(unsigned(key.getModulus().toByteArray())), "sig");
    }

    static byte[] unsigned(byte[] value) {
        return value.length > 1 && value[0] == 0
                ? Arrays.copyOfRange(value, 1, value.length) : value;
    }

    static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String jwkJson(String keyId) {
        return jwkJson(HrisServiceGrantAuthorityRepositoryPostgresTest.jwk(keyId));
    }

    static String jwkJson(HrisServiceGrantAuthorityV1.RsaPublicJwk value) {
        return """
                {"alg":"RS256","e":"AQAB","kid":"%s","kty":"RSA","n":"%s","use":"sig"}
                """.formatted(value.kid(), value.n()).strip();
    }
}
