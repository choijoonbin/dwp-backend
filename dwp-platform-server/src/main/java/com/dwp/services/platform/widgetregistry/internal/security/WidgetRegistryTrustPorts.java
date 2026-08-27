package com.dwp.services.platform.widgetregistry.internal.security;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Cryptographic trust ports for the Provider-to-Platform Widget Registry plane.
 *
 * <p>Implementations must validate the compact JWS signature against the separately pinned JWKS before
 * returning claims. They must never return unverified, decoded JWT payloads. The ingress filter deliberately
 * remains active without implementations and fails closed until all three ports are wired.</p>
 */
public final class WidgetRegistryTrustPorts {

    private WidgetRegistryTrustPorts() {
    }

    @FunctionalInterface
    public interface ServiceTokenVerifier {
        ServiceTokenClaims verify(String compactToken) throws VerificationException;
    }

    @FunctionalInterface
    public interface ProviderAssertionVerifier {
        ProviderAssertionClaims verify(String compactAssertion, AssertionKind kind)
                throws VerificationException;
    }

    @FunctionalInterface
    public interface AssertionReplayStore {
        ReplayDecision claim(ReplayKey key, Instant retainUntil);
    }

    public enum AssertionKind {
        WIDGET,
        RECONCILE
    }

    public enum ReplayDecision {
        ACCEPTED,
        REPLAYED,
        UNAVAILABLE
    }

    public enum VerificationFailure {
        INVALID,
        TRUST_UNAVAILABLE
    }

    public record JoseProof(String algorithm, String keyId, String keyFingerprint) {
    }

    public record JwtIdentity(
            String issuer,
            String subject,
            String audience,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            String jwtId) {
    }

    public record ServiceTokenClaims(
            JoseProof proof,
            JwtIdentity identity,
            String authorizedParty,
            Set<String> scopes) {

        public ServiceTokenClaims {
            scopes = scopes == null ? null : Set.copyOf(scopes);
        }

        @Override
        public String toString() {
            return "ServiceTokenClaims[REDACTED]";
        }
    }

    public record SignedRequestBinding(
            String method,
            String pathTemplate,
            String actualPath,
            String requestTargetSha256,
            String bodySha256,
            String idempotencyKey,
            String correlationId) {
    }

    public record ProviderAssertionClaims(
            JoseProof proof,
            JwtIdentity identity,
            String serviceTokenJti,
            SignedRequestBinding request,
            List<String> permissionCodes,
            List<String> ownerProductKeys,
            String actorRef,
            String sessionRef,
            String providerAuthorityRevision,
            Instant authenticatedAt,
            CommandBinding command,
            ReconcileBinding reconcile,
            String purpose,
            String operationId,
            String commandType) {

        public ProviderAssertionClaims {
            permissionCodes = permissionCodes == null ? null : List.copyOf(permissionCodes);
            ownerProductKeys = ownerProductKeys == null ? null : List.copyOf(ownerProductKeys);
        }

        @Override
        public String toString() {
            return "ProviderAssertionClaims[REDACTED]";
        }
    }

    public record CommandBinding(
            String commandId,
            CommandTargetBinding target,
            Long expectedVersion,
            String publicRequestFingerprint,
            String reasonDigest,
            List<String> sodArtifactIds) {

        public CommandBinding {
            sodArtifactIds = sodArtifactIds == null ? null : List.copyOf(sodArtifactIds);
        }
    }

    /**
     * Full closed command target. Field presence is signed because omitted and explicit-null JSON members are not
     * interchangeable at this trust boundary.
     */
    public record CommandTargetBinding(
            Set<String> fields,
            String targetType,
            String targetId,
            String definitionId,
            String versionId,
            String evidenceId,
            String controlId,
            String channel,
            String controlScope,
            String runtimeTargetType,
            String runtimeTargetId) {

        public CommandTargetBinding {
            fields = fields == null ? null : Set.copyOf(fields);
        }
    }

    public record ReconcileBinding(
            String commandId,
            String publicRequestFingerprint,
            String actorRefSha256,
            String operationId,
            String targetType,
            String targetId,
            Instant providerReceiptCreatedAt,
            OriginalArtifactBinding originalArtifacts) {
    }

    public record OriginalArtifactBinding(
            String serviceTokenSha256,
            String serviceTokenJti,
            Instant serviceTokenExpiresAt,
            String widgetAssertionSha256,
            String widgetAssertionJti,
            Instant widgetAssertionExpiresAt) {
    }

    public record ReplayKey(String issuer, String subject, String jwtId) {
    }

    public static final class VerificationException extends Exception {
        private static final long serialVersionUID = 1L;
        private final VerificationFailure failure;

        public VerificationException(VerificationFailure failure) {
            super(failure == VerificationFailure.TRUST_UNAVAILABLE
                    ? "Widget Registry trust authority is unavailable."
                    : "Widget Registry proof is invalid.");
            this.failure = failure;
        }

        public VerificationException(VerificationFailure failure, Throwable cause) {
            super(failure == VerificationFailure.TRUST_UNAVAILABLE
                    ? "Widget Registry trust authority is unavailable."
                    : "Widget Registry proof is invalid.", cause);
            this.failure = failure;
        }

        public VerificationFailure failure() {
            return failure;
        }
    }
}
