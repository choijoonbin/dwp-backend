package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed, fail-closed boundary to the current control-plane service grant.
 *
 * <p>No production provider is bound in the HRM-013 prerequisite phase. A
 * protocol-fence row, configuration property, cache or static map is not a
 * substitute for this authority.</p>
 */
public interface Hrm013ServiceAuthorityPort {

    Resolution resolve(Query query);

    /**
     * Cryptographic projection used by the runtime. Persistence/configuration-only
     * implementations intentionally cannot satisfy this method.
     */
    default SigningAuthority requireSigningAuthority(Query query) {
        CurrentGrant grant = requireCurrentAdmit(query);
        throw new AuthorityUnavailableException(new IllegalStateException(
                "No cryptographic control-plane adapter is installed for "
                        + grant.bindingId()));
    }

    default boolean remainsCurrent(SigningAuthority authority) {
        Objects.requireNonNull(authority, "authority");
        try {
            SigningAuthority current = requireSigningAuthority(authority.query());
            return authority.sameAuthority(current);
        } catch (RuntimeException unavailableOrChanged) {
            return false;
        }
    }

    default CurrentGrant requireCurrentAdmit(Query query) {
        Objects.requireNonNull(query, "query");
        final Resolution resolution;
        try {
            resolution = resolve(query);
        } catch (RuntimeException exception) {
            throw new AuthorityUnavailableException(exception);
        }
        if (resolution == null || resolution.status() != Status.ADMIT
                || resolution.grant() == null) {
            throw new AuthorityRejectedException(resolution == null
                    ? Status.UNAVAILABLE : resolution.status());
        }
        CurrentGrant grant = resolution.grant();
        if (grant.tenantId() != query.tenantId()
                || !grant.callerWorkloadId().equals(query.callerWorkloadId())
                || !grant.bindingId().equals(query.bindingId())
                || !grant.purpose().equals(query.purpose())
                || grant.tenantGrantRevision() != query.signedTenantGrantRevision()
                || grant.authorizationRevision() != query.signedAuthorizationRevision()) {
            throw new AuthorityRejectedException(Status.REVISION_MISMATCH);
        }
        return grant;
    }

    record Query(
            long tenantId,
            String callerWorkloadId,
            String bindingId,
            String purpose,
            long signedTenantGrantRevision,
            long signedAuthorizationRevision,
            HrisServiceGrantAuthorityV1.ResolutionMode resolutionMode,
            HrisServiceGrantAuthorityV1.DrainFields drainFields) {
        public Query(
                long tenantId, String callerWorkloadId, String bindingId,
                String purpose, long signedTenantGrantRevision,
                long signedAuthorizationRevision) {
            this(tenantId, callerWorkloadId, bindingId, purpose,
                    signedTenantGrantRevision, signedAuthorizationRevision,
                    HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK, null);
        }

        public Query {
            if (tenantId < 1 || signedTenantGrantRevision < 1
                    || signedAuthorizationRevision < 1) {
                throw new IllegalArgumentException("HRM-013 authority revisions must be positive");
            }
            callerWorkloadId = required(callerWorkloadId, "callerWorkloadId");
            bindingId = required(bindingId, "bindingId");
            purpose = required(purpose, "purpose");
            Objects.requireNonNull(resolutionMode, "resolutionMode");
            if (resolutionMode == HrisServiceGrantAuthorityV1.ResolutionMode.PROBE_MISSING
                    || (resolutionMode == HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING)
                    != (drainFields != null)) {
                throw new IllegalArgumentException("HRM-013 authority mode is invalid");
            }
        }
    }

    record CurrentGrant(
            long tenantId,
            String callerWorkloadId,
            String bindingId,
            String purpose,
            long tenantGrantRevision,
            long authorizationRevision) {
        public CurrentGrant {
            if (tenantId < 1 || tenantGrantRevision < 1 || authorizationRevision < 1) {
                throw new IllegalArgumentException("HRM-013 current grant is invalid");
            }
            callerWorkloadId = required(callerWorkloadId, "callerWorkloadId");
            bindingId = required(bindingId, "bindingId");
            purpose = required(purpose, "purpose");
        }
    }

    record SigningAuthority(
            Query query,
            CurrentGrant grant,
            UUID grantSetId,
            long grantRevision,
            String callerSpiffeId,
            String ownerWorkloadId,
            String ownerSpiffeId,
            String callerSigningKeyId,
            PublicKey callerSigningPublicKey,
            HrisServiceGrantAuthorityV1.RsaPublicJwk callerSigningPublicJwk,
            String ownerSigningKeyId,
            PublicKey ownerSigningPublicKey,
            HrisServiceGrantAuthorityV1.RsaPublicJwk ownerSigningPublicJwk,
            Instant recordedAt,
            HrisServiceGrantAuthorityV1.DrainAdmission drainAdmission) {
        public SigningAuthority(
                Query query, CurrentGrant grant, UUID grantSetId,
                long grantRevision, String callerSpiffeId,
                String ownerWorkloadId, String ownerSpiffeId,
                String callerSigningKeyId, PublicKey callerSigningPublicKey,
                Instant recordedAt) {
            this(query, grant, grantSetId, grantRevision, callerSpiffeId,
                    ownerWorkloadId, ownerSpiffeId, callerSigningKeyId,
                    callerSigningPublicKey, null, null, null, null,
                    recordedAt, null);
        }

        public SigningAuthority {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(grant, "grant");
            Objects.requireNonNull(grantSetId, "grantSetId");
            if (grantRevision < 1) throw new IllegalArgumentException("grantRevision");
            callerSpiffeId = required(callerSpiffeId, "callerSpiffeId");
            ownerWorkloadId = required(ownerWorkloadId, "ownerWorkloadId");
            ownerSpiffeId = required(ownerSpiffeId, "ownerSpiffeId");
            callerSigningKeyId = required(callerSigningKeyId, "callerSigningKeyId");
            Objects.requireNonNull(callerSigningPublicKey, "callerSigningPublicKey");
            if ((callerSigningPublicJwk == null) != (ownerSigningPublicJwk == null)
                    || (ownerSigningKeyId == null) != (ownerSigningPublicJwk == null)
                    || (ownerSigningPublicKey == null) != (ownerSigningPublicJwk == null)) {
                throw new IllegalArgumentException("complete caller/owner JWK projection required");
            }
            if (callerSigningPublicJwk != null
                    && (!callerSigningKeyId.equals(callerSigningPublicJwk.kid())
                    || !required(ownerSigningKeyId, "ownerSigningKeyId")
                    .equals(ownerSigningPublicJwk.kid()))) {
                throw new IllegalArgumentException("signing key projection mismatch");
            }
            Objects.requireNonNull(recordedAt, "recordedAt");
            if ((query.resolutionMode()
                    == HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING)
                    != (drainAdmission != null)) {
                throw new IllegalArgumentException("drain admission does not match query");
            }
            if (grant.tenantId() != query.tenantId()
                    || !grant.bindingId().equals(query.bindingId())
                    || !grant.callerWorkloadId().equals(query.callerWorkloadId())
                    || !grant.purpose().equals(query.purpose())) {
                throw new IllegalArgumentException("signing authority tuple mismatch");
            }
        }

        public boolean sameAuthority(SigningAuthority other) {
            return other != null
                    && query.equals(other.query)
                    && grantSetId.equals(other.grantSetId)
                    && grantRevision == other.grantRevision
                    && grant.equals(other.grant)
                    && callerSigningKeyId.equals(other.callerSigningKeyId)
                    && callerSigningPublicKey.equals(other.callerSigningPublicKey)
                    && Objects.equals(ownerSigningKeyId, other.ownerSigningKeyId)
                    && Objects.equals(ownerSigningPublicKey, other.ownerSigningPublicKey)
                    && sameDrainFence(drainAdmission, other.drainAdmission);
        }

        public String signingKeyId(Hrm013CredentialRole role) {
            return role == Hrm013CredentialRole.CALLER
                    ? callerSigningKeyId : required(ownerSigningKeyId, "ownerSigningKeyId");
        }

        public PublicKey signingPublicKey(Hrm013CredentialRole role) {
            PublicKey result = role == Hrm013CredentialRole.CALLER
                    ? callerSigningPublicKey : ownerSigningPublicKey;
            return Objects.requireNonNull(result, "ownerSigningPublicKey");
        }

        public HrisServiceGrantAuthorityV1.RsaPublicJwk signingPublicJwk(
                Hrm013CredentialRole role) {
            HrisServiceGrantAuthorityV1.RsaPublicJwk result =
                    role == Hrm013CredentialRole.CALLER
                            ? callerSigningPublicJwk : ownerSigningPublicJwk;
            return Objects.requireNonNull(result, "signingPublicJwk");
        }

        private static boolean sameDrainFence(
                HrisServiceGrantAuthorityV1.DrainAdmission left,
                HrisServiceGrantAuthorityV1.DrainAdmission right) {
            if (left == null || right == null) return left == right;
            return left.quiesceFenceId().equals(right.quiesceFenceId())
                    && left.quiesceRevision() == right.quiesceRevision()
                    && left.quiesceEffectiveAt().equals(right.quiesceEffectiveAt())
                    && left.attestingServiceRole() == right.attestingServiceRole();
        }
    }

    record Resolution(Status status, CurrentGrant grant) {
        public Resolution {
            status = Objects.requireNonNull(status, "status");
            if (status == Status.ADMIT && grant == null) {
                throw new IllegalArgumentException("ADMIT requires one current grant");
            }
            if (status != Status.ADMIT && grant != null) {
                throw new IllegalArgumentException("Only ADMIT may expose a grant");
            }
        }

        public static Resolution admit(CurrentGrant grant) {
            return new Resolution(Status.ADMIT, Objects.requireNonNull(grant, "grant"));
        }

        public static Resolution deny(Status status) {
            if (status == Status.ADMIT) {
                throw new IllegalArgumentException("Use admit for an ADMIT resolution");
            }
            return new Resolution(status, null);
        }
    }

    enum Status {
        ADMIT,
        REVOKE,
        MISSING,
        AMBIGUOUS,
        STALE,
        REVISION_MISMATCH,
        UNAVAILABLE
    }

    final class AuthorityRejectedException extends RuntimeException {
        private final Status status;

        public AuthorityRejectedException(Status status) {
            super("HRM-013 service authority rejected the request");
            this.status = Objects.requireNonNull(status, "status");
        }

        public Status status() {
            return status;
        }
    }

    final class AuthorityUnavailableException extends RuntimeException {
        public AuthorityUnavailableException(Throwable cause) {
            super("HRM-013 service authority is unavailable", cause);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
