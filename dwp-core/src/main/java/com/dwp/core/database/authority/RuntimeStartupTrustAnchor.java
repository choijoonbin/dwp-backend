package com.dwp.core.database.authority;

import java.security.PublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/**
 * Independently deployment-pinned policy, never constructed by discovering expected values from
 * the signed seal, current database or a caller's digest. Public keys only; no endpoint factory.
 * Installing this policy/provider and its current revision is still a deployment integration task.
 */
public record RuntimeStartupTrustAnchor(String service, String topologyRevision,
        String deploymentId, String applicationInstanceId, long expectedEpoch, long expectedKeyRevision,
        String startupChallengeSha256, String expectedControlReference, String expectedSourceRevision,
        String expectedSourceArtifactSha256, String expectedManifestSha256, String expectedControlReceiptSha256,
        List<RuntimeStreamStartupSeal.StreamEvidence> expectedStreams,
        List<RuntimeStreamStartupSeal.RuntimePurpose> expectedRuntimePurposes,
        Map<String, PublicKey> trustedKeys, Duration maximumSealLifetime, Duration maximumLeaseLifetime) {
    public RuntimeStartupTrustAnchor {
        identifier(service); key(topologyRevision); uuid(deploymentId); uuid(applicationInstanceId);
        require(expectedEpoch > 0 && expectedKeyRevision > 0, "independent current epochs required");
        digest(startupChallengeSha256); controlReference(expectedControlReference);
        require(expectedSourceRevision != null && expectedSourceRevision.matches("[0-9a-f]{40}"), "source anchor missing");
        digest(expectedSourceArtifactSha256); digest(expectedManifestSha256); digest(expectedControlReceiptSha256);
        expectedStreams = ordered(expectedStreams, RuntimeStreamStartupSeal.StreamEvidence::streamKey, "expected streams");
        expectedRuntimePurposes = ordered(expectedRuntimePurposes,
                RuntimeStreamStartupSeal.RuntimePurpose::bindingKey, "expected runtime purposes");
        require(!expectedStreams.isEmpty() && !expectedRuntimePurposes.isEmpty(), "independent runtime policy missing");
        trustedKeys = Map.copyOf(trustedKeys); require(!trustedKeys.isEmpty(), "deployment public keys missing");
        trustedKeys.forEach((id, publicKey) -> {
            key(id); require(publicKey != null && (publicKey.getAlgorithm().equals("Ed25519")
                    || publicKey.getAlgorithm().equals("EdDSA")), "non-Ed25519 trust key rejected");
        });
        require(maximumSealLifetime != null && !maximumSealLifetime.isNegative()
                && !maximumSealLifetime.isZero() && maximumSealLifetime.compareTo(Duration.ofMinutes(5)) <= 0,
                "seal lifetime must be bounded to at most five minutes");
        require(maximumLeaseLifetime != null && !maximumLeaseLifetime.isNegative()
                && !maximumLeaseLifetime.isZero() && maximumLeaseLifetime.compareTo(Duration.ofSeconds(30)) <= 0,
                "freshness lease must be bounded to at most thirty seconds");
    }
}
