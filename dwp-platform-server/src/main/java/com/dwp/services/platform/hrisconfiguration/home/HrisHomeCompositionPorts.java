package com.dwp.services.platform.hrisconfiguration.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Activation ports for the SYS-owned HRIS Home composition core.
 *
 * <p>R4 deliberately ships no event consumer, HTTP client, proof signer, controller, route or
 * tenant enrollment. Production adapters must be supplied by a later activation increment. This
 * leaves the core executable in targeted tests while an accidental property flip still fails
 * startup instead of falling back to browser headers or unsigned owner calls.
 */
public final class HrisHomeCompositionPorts {

    private HrisHomeCompositionPorts() {
    }

    @FunctionalInterface
    public interface CandidateSource {
        List<HrisHomeContributionCandidate> latest(CandidateQuery query);
    }

    @FunctionalInterface
    public interface OwnerMaterializationClient {
        HrisHomeContributionMaterializationV1 materialize(OwnerRequest request);
    }

    /** Returns an exact currently authorized PAGE path, or {@code null} to deny the deep link. */
    @FunctionalInterface
    public interface DeepLinkPolicy {
        String authorize(
                HrisHomeCompositionDtos.CurrentViewAuthority authority,
                HrisHomeCompositionDtos.OwnerAuthority owner,
                String ownerDeepLink);
    }

    @FunctionalInterface
    public interface CurrentAuthorityGuard {
        /** Re-resolves and exactly matches the complete trusted snapshot or throws. */
        void requireCurrent(CurrentAuthoritySnapshot expected);
    }

    public record CurrentAuthoritySnapshot(
            long tenantId,
            UUID subjectPrincipalPublicId,
            HrisHomeContributionMaterializationV1.BrowserAudience browserAudience,
            long browserScopeRevision,
            String browserPopulationScopeDigest,
            long browserFieldPolicyRevision,
            long authorizationRevision,
            long compositionRevision,
            Map<HrisHomeContributionMaterializationV1.Module,
                    HrisHomeCompositionDtos.OwnerAuthority> owners) {

        public CurrentAuthoritySnapshot {
            if (tenantId < 1 || browserScopeRevision < 1 || browserFieldPolicyRevision < 1
                    || authorizationRevision < 1 || compositionRevision < 1) {
                throw new IllegalArgumentException("authority snapshot revisions must be positive");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(browserAudience, "browserAudience");
            if (browserPopulationScopeDigest == null
                    || !browserPopulationScopeDigest.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("browserPopulationScopeDigest is invalid");
            }
            owners = Map.copyOf(Objects.requireNonNull(owners, "owners"));
        }

        static CurrentAuthoritySnapshot from(
                HrisHomeCompositionDtos.CurrentViewAuthority authority) {
            return new CurrentAuthoritySnapshot(
                    authority.tenantId(), authority.subjectPrincipalPublicId(),
                    authority.browserAudience(), authority.browserScopeRevision(),
                    authority.browserPopulationScopeDigest(),
                    authority.browserFieldPolicyRevision(), authority.authorizationRevision(),
                    authority.compositionRevision(), authority.owners());
        }
    }

    public record CandidateQuery(
            long tenantId,
            UUID subjectPrincipalPublicId,
            Instant asOf,
            HrisHomeCompositionDtos.OwnerAuthority ownerAuthority) {

        public CandidateQuery {
            if (tenantId < 1) throw new IllegalArgumentException("tenantId must be positive");
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(ownerAuthority, "ownerAuthority");
        }
    }

    /**
     * Trusted outbound context. A future adapter must turn this context into the exact RS256
     * {@link com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1} proof. It
     * must not send browser cookies, user headers or a caller-selected tenant header.
     */
    public record OwnerRequest(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String capability,
            long effectivePermissionRevision,
            long fieldPolicyRevision,
            String populationScopeDigest,
            HrisHomeContributionCandidate candidate,
            HrisHomeContributionMaterializationV1.BrowserAudience browserAudience,
            UUID correlationId,
            HomeMaterializationWorkloadProofV1.Endpoint endpoint,
            String exactPath) {

        public OwnerRequest {
            if (tenantId < 1 || effectivePermissionRevision < 1 || fieldPolicyRevision < 1) {
                throw new IllegalArgumentException("owner request revisions must be positive");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            if (capability == null || capability.isBlank()) {
                throw new IllegalArgumentException("capability is required");
            }
            if (populationScopeDigest == null
                    || !populationScopeDigest.matches("^[0-9a-f]{64}$")) {
                throw new IllegalArgumentException("populationScopeDigest is invalid");
            }
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(browserAudience, "browserAudience");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(endpoint, "endpoint");
            if (!endpoint.module().equals(candidate.module())
                    || !endpoint.path(candidate.payloadRef()).equals(exactPath)) {
                throw new IllegalArgumentException("owner endpoint does not match its candidate");
            }
        }
    }

    /** Stable owner failure safe to expose only as a typed browser partial failure. */
    public static final class OwnerMaterializationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final HrisHomeContributionMaterializationV1.FailureCode code;
        private final boolean retryable;

        public OwnerMaterializationException(
                HrisHomeContributionMaterializationV1.FailureCode code,
                boolean retryable) {
            super(Objects.requireNonNull(code, "code").name());
            this.code = code;
            this.retryable = retryable;
        }

        public HrisHomeContributionMaterializationV1.FailureCode code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
