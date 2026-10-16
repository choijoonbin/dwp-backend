package com.dwp.services.payroll.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class PayrollHomePorts {

private PayrollHomePorts() {
}

interface PayrollHomeSigningKeySource
        extends HomeMaterializationWorkloadProofV1.SigningKeyResolver {
}

interface PayrollHomeReplayLedger
        extends HomeMaterializationWorkloadProofV1.ReplayStore {
}

@FunctionalInterface
interface PayrollHomeCurrentAuthority {
    ResolvedPayrollHomeAuthority revalidate(
            HomeMaterializationWorkloadProofV1.ProofClaims signedContext);
}

@FunctionalInterface
interface PayrollHomeProjectionSource {
    PayrollHomeSourceSnapshot load(ResolvedPayrollHomeAuthority authority);
}

record PayrollPopulation(UUID legalEntityId, UUID payrollGroupId) {
    PayrollPopulation {
        Objects.requireNonNull(legalEntityId, "legalEntityId");
        Objects.requireNonNull(payrollGroupId, "payrollGroupId");
    }
}

record ResolvedPayrollHomeAuthority(
        long tenantId,
        UUID subjectPrincipalPublicId,
        String capability,
        HrisHomeContributionMaterializationV1.BrowserAudience browserAudience,
        HrisHomeContributionMaterializationV1.OwnerPopulationScope ownerPopulationScope,
        long scopeRevision,
        String populationScopeDigest,
        long effectivePermissionRevision,
        long fieldPolicyRevision,
        String widgetKey,
        Optional<UUID> workerId,
        Set<PayrollPopulation> populations,
        Map<String, HrisHomeContributionMaterializationV1.Decision> fieldDecisions) {

    private static final Set<String> FIELDS = Set.of(
            "payPeriodLabel", "grossPay", "netPay", "payslipAvailable");

    ResolvedPayrollHomeAuthority {
        if (tenantId < 1 || scopeRevision < 1 || effectivePermissionRevision < 1
                || fieldPolicyRevision < 1) {
            throw new IllegalArgumentException("Payroll Home authority revisions must be positive.");
        }
        Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
        if (!"hcm.operations.payroll-support.view".equals(capability)) {
            throw new IllegalArgumentException("Payroll Home capability is not currently sealed.");
        }
        Objects.requireNonNull(browserAudience, "browserAudience");
        Objects.requireNonNull(ownerPopulationScope, "ownerPopulationScope");
        if (populationScopeDigest == null
                || !populationScopeDigest.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("populationScopeDigest must be canonical.");
        }
        if (widgetKey == null || !widgetKey.matches("^payroll-[a-z0-9-]{1,30}$")) {
            throw new IllegalArgumentException("Payroll Home widget key is invalid.");
        }
        workerId = workerId == null ? Optional.empty() : workerId;
        populations = populations == null ? Set.of() : Set.copyOf(populations);
        fieldDecisions = fieldDecisions == null ? Map.of() : Map.copyOf(fieldDecisions);
        if (!fieldDecisions.keySet().equals(FIELDS)
                || fieldDecisions.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Payroll Home field policy must decide every field.");
        }
        if (ownerPopulationScope == HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF) {
            if (browserAudience != HrisHomeContributionMaterializationV1.BrowserAudience.SELF
                    || workerId.isEmpty() || !populations.isEmpty()) {
                throw new IllegalArgumentException("SELF Payroll authority must resolve one worker only.");
            }
        } else if (ownerPopulationScope
                == HrisHomeContributionMaterializationV1.OwnerPopulationScope.PAY_GROUP) {
            if (browserAudience != HrisHomeContributionMaterializationV1.BrowserAudience.OPERATIONS
                    || populations.isEmpty() || workerId.isPresent()) {
                throw new IllegalArgumentException("PAY_GROUP authority must resolve owner populations.");
            }
        } else if (ownerPopulationScope
                == HrisHomeContributionMaterializationV1.OwnerPopulationScope.LEGAL_ENTITY) {
            if (browserAudience != HrisHomeContributionMaterializationV1.BrowserAudience.EXECUTIVE
                    || populations.isEmpty() || workerId.isPresent()) {
                throw new IllegalArgumentException("LEGAL_ENTITY authority must resolve owner populations.");
            }
        } else {
            throw new IllegalArgumentException("Unsupported Payroll Home population scope.");
        }
    }

    boolean exactlyMatches(HomeMaterializationWorkloadProofV1.ProofClaims claims) {
        return tenantId == claims.tenantId()
                && subjectPrincipalPublicId.equals(claims.subjectPrincipalPublicId())
                && capability.equals(claims.capability())
                && browserAudience == claims.browserAudience()
                && ownerPopulationScope == claims.ownerPopulationScope()
                && scopeRevision == claims.scopeRevision()
                && populationScopeDigest.equals(claims.populationScopeDigest())
                && effectivePermissionRevision == claims.effectivePermissionRevision()
                && fieldPolicyRevision == claims.fieldPolicyRevision()
                && widgetKey.equals(claims.widgetKey());
    }
}

record PayrollHomeSourceSnapshot(
        long sourceVersion,
        Instant asOf,
        Optional<String> payPeriodLabel,
        Optional<MoneyValue> grossPay,
        Optional<MoneyValue> netPay,
        Optional<Boolean> payslipAvailable) {

    PayrollHomeSourceSnapshot {
        if (sourceVersion < 1) throw new IllegalArgumentException("sourceVersion must be positive.");
        Objects.requireNonNull(asOf, "asOf");
        payPeriodLabel = payPeriodLabel == null ? Optional.empty() : payPeriodLabel;
        grossPay = grossPay == null ? Optional.empty() : grossPay;
        netPay = netPay == null ? Optional.empty() : netPay;
        payslipAvailable = payslipAvailable == null ? Optional.empty() : payslipAvailable;
    }
}

record MoneyValue(String amount, String currency) {
    MoneyValue {
        if (amount == null || !amount.matches("^(0|[1-9][0-9]{0,14})(?:\\.[0-9]{1,4})?$")
                || currency == null || !currency.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Money value is not canonical.");
        }
    }
}
}
