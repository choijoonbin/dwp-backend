package com.dwp.platform.contracts.hris.referenceconfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.*;

/** Short-lived outbound proofs. Keys come only from a registered workload identity provider. */
public final class HrisReferenceConfigurationProofIssuerV1 {
    public record SigningKey(String keyId, PrivateKey privateKey, String workloadId, WorkloadAuthentication authentication) { }
    @FunctionalInterface public interface SigningKeyProvider { SigningKey current(Endpoint endpoint); }
    private HrisReferenceConfigurationProofIssuerV1() { }

    public static String issue(ObjectMapper mapper, Clock clock, Endpoint endpoint, String exactPath,
            long tenantId, byte[] exactBody, SigningKey key) {
        if (key == null || key.keyId() == null || !key.keyId().matches("^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$")
                || !(key.privateKey() instanceof RSAPrivateKey rsa) || rsa.getModulus().bitLength() < 2048
                || !endpoint.callerWorkloadId().equals(key.workloadId()) || key.authentication() == null
                || !endpoint.matchesPath(exactPath) || exactPath.contains("?") || exactPath.contains("#")
                || tenantId < 1 || exactBody == null) throw unavailable();
        try {
            long now = clock.instant().getEpochSecond();
            UUID id = UUID.randomUUID(), nonce;
            do { nonce = UUID.randomUUID(); } while (id.equals(nonce));
            Map<String,Object> claims = new LinkedHashMap<>();
            claims.put("iss", key.workloadId()); claims.put("aud", endpoint.ownerWorkloadId()); claims.put("sub", key.workloadId());
            claims.put("iat", now); claims.put("nbf", now); claims.put("exp", now + 30);
            claims.put("jti", id.toString()); claims.put("requestNonce", nonce.toString());
            claims.put("bindingId", endpoint.bindingId()); claims.put("purpose", endpoint.purpose());
            claims.put("method", endpoint.method()); claims.put("path", exactPath); claims.put("tenantId", tenantId);
            claims.put("requestBodySha256", sha256(exactBody)); claims.put("authn", key.authentication().name());
            String content = encode(mapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT", "kid", key.keyId())))
                    + "." + encode(mapper.writeValueAsBytes(claims));
            Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key.privateKey());
            signer.update(content.getBytes(StandardCharsets.US_ASCII));
            return content + "." + encode(signer.sign());
        } catch (Exception exception) { throw unavailable(); }
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static IllegalStateException unavailable() { return new IllegalStateException("REFERENCE_CONFIGURATION_WORKLOAD_SIGNER_UNAVAILABLE"); }
}
