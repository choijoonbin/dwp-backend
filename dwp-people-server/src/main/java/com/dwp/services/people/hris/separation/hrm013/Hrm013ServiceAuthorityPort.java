package com.dwp.services.people.hris.separation.hrm013;

import java.util.Objects;

/**
 * Typed, fail-closed boundary to the current control-plane service grant.
 *
 * <p>No production provider is bound in the HRM-013 prerequisite phase. A
 * protocol-fence row, configuration property, cache or static map is not a
 * substitute for this authority.</p>
 */
public interface Hrm013ServiceAuthorityPort {

    Resolution resolve(Query query);

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
            long signedAuthorizationRevision) {
        public Query {
            if (tenantId < 1 || signedTenantGrantRevision < 1
                    || signedAuthorizationRevision < 1) {
                throw new IllegalArgumentException("HRM-013 authority revisions must be positive");
            }
            callerWorkloadId = required(callerWorkloadId, "callerWorkloadId");
            bindingId = required(bindingId, "bindingId");
            purpose = required(purpose, "purpose");
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
