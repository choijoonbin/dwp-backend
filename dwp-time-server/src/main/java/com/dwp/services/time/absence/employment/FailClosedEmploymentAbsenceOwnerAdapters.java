package com.dwp.services.time.absence.employment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Production-safe defaults while the trusted owner issuer/control-plane gate remains open. */
public final class FailClosedEmploymentAbsenceOwnerAdapters {
    private FailClosedEmploymentAbsenceOwnerAdapters() {
    }

    @Component
    @ConditionalOnMissingBean(EmploymentAbsenceContract.OwnerGrantAuthority.class)
    static final class Grants implements EmploymentAbsenceContract.OwnerGrantAuthority {
        @Override
        public Optional<EmploymentAbsenceContract.OwnerGrant> current(long tenantId) {
            return Optional.empty();
        }
    }

    @Component
    @ConditionalOnMissingBean(EmploymentAbsenceContract.TenantContextAuthority.class)
    static final class Contexts implements EmploymentAbsenceContract.TenantContextAuthority {
        @Override
        public Optional<EmploymentAbsenceContract.TenantContext> current(long tenantId) {
            return Optional.empty();
        }
    }

    @Component
    @ConditionalOnMissingBean(EmploymentAbsenceContract.OwnerProofIssuer.class)
    static final class Issuer implements EmploymentAbsenceContract.OwnerProofIssuer {
        @Override
        public Optional<EmploymentAbsenceContract.OwnerRequestProof> issue(
                EmploymentAbsenceContract.OwnerGrant grant,
                UUID relationshipPublicId,
                long minimumRelationshipRevision,
                LocalDate effectiveOn,
                UUID correlationId) {
            return Optional.empty();
        }
    }

    @Component
    @ConditionalOnMissingBean(EmploymentAbsenceContract.OwnerTransport.class)
    static final class Transport implements EmploymentAbsenceContract.OwnerTransport {
        @Override
        public EmploymentAbsenceContract.OwnerWireResult exchange(
                EmploymentAbsenceContract.OwnerRequestProof proof) {
            return new EmploymentAbsenceContract.WireFailure(
                    "OWNER_TRANSPORT_UNAVAILABLE");
        }
    }

    @Component
    @ConditionalOnMissingBean(EmploymentAbsenceContract.ResponseKeyAuthority.class)
    static final class Keys implements EmploymentAbsenceContract.ResponseKeyAuthority {
        @Override
        public Optional<EmploymentAbsenceContract.ResponseSigningKey> current(String keyId) {
            return Optional.empty();
        }
    }

    @Component
    @ConditionalOnMissingBean(EmploymentAbsenceContract.OwnerAudit.class)
    static final class Audit implements EmploymentAbsenceContract.OwnerAudit {
        @Override
        public boolean append(
                long tenantId,
                UUID relationshipPublicId,
                UUID correlationId,
                String outcome,
                String digest,
                Map<String, Object> evidence) {
            return false;
        }
    }

    @Component
    @ConditionalOnMissingBean(EmploymentAbsenceProjectionRecoveryAuthority.class)
    static final class ProjectionRecovery
            implements EmploymentAbsenceProjectionRecoveryAuthority {
        @Override
        public boolean authorized(
                long tenantId,
                String actor,
                String capability,
                UUID correlationId) {
            return false;
        }
    }
}
