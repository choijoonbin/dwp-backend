package com.dwp.services.platform.hrisconfiguration.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.BrowserAudience;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Module;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.OwnerPopulationScope;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable authority fence independent of the Home DTO and activation-port owners. */
public final class HrisHomeAuthorityContract {

    private HrisHomeAuthorityContract() {
    }

    @FunctionalInterface
    public interface CurrentAuthorityGuard {
        /** Re-resolves and exactly matches the complete trusted snapshot or throws. */
        void requireCurrent(CurrentAuthoritySnapshot expected);
    }

    public record CurrentAuthoritySnapshot(
            long tenantId,
            UUID subjectPrincipalPublicId,
            BrowserAudience browserAudience,
            long browserScopeRevision,
            String browserPopulationScopeDigest,
            long browserFieldPolicyRevision,
            long authorizationRevision,
            long compositionRevision,
            Map<Module, OwnerAuthoritySnapshot> owners) {

        public CurrentAuthoritySnapshot {
            if (tenantId < 1 || browserScopeRevision < 1 || browserFieldPolicyRevision < 1
                    || authorizationRevision < 1 || compositionRevision < 1) {
                throw new IllegalArgumentException("authority snapshot revisions must be positive");
            }
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            Objects.requireNonNull(browserAudience, "browserAudience");
            digest(browserPopulationScopeDigest, "browserPopulationScopeDigest");
            Objects.requireNonNull(owners, "owners");
            LinkedHashMap<Module, OwnerAuthoritySnapshot> copy = new LinkedHashMap<>();
            owners.forEach((module, owner) -> {
                Objects.requireNonNull(module, "owners contains null module");
                Objects.requireNonNull(owner, "owners contains null authority");
                if (module != owner.module()) {
                    throw new IllegalArgumentException("owner snapshot module key mismatch");
                }
                if (browserAudience != owner.browserAudience()) {
                    throw new IllegalArgumentException("owner snapshot audience mismatch");
                }
                copy.put(module, owner);
            });
            owners = Map.copyOf(copy);
        }
    }

    public record OwnerAuthoritySnapshot(
            Module module,
            String widgetKey,
            String capability,
            BrowserAudience browserAudience,
            OwnerPopulationScope ownerPopulationScope,
            long scopeRevision,
            String populationScopeDigest,
            long effectivePermissionRevision,
            long fieldPolicyRevision,
            Map<String, Decision> fieldCeilings) {

        public OwnerAuthoritySnapshot {
            Objects.requireNonNull(module, "module");
            if (widgetKey == null || widgetKey.isBlank()
                    || capability == null || capability.isBlank()) {
                throw new IllegalArgumentException("owner widget and capability are required");
            }
            Objects.requireNonNull(browserAudience, "browserAudience");
            Objects.requireNonNull(ownerPopulationScope, "ownerPopulationScope");
            if (!ownerPopulationScope.matches(browserAudience, module)) {
                throw new IllegalArgumentException("owner scope does not match its audience");
            }
            if (scopeRevision < 1 || effectivePermissionRevision < 1
                    || fieldPolicyRevision < 1) {
                throw new IllegalArgumentException("owner authority revisions must be positive");
            }
            digest(populationScopeDigest, "populationScopeDigest");
            fieldCeilings = Map.copyOf(
                    Objects.requireNonNull(fieldCeilings, "fieldCeilings"));
            fieldCeilings.forEach((field, decision) -> {
                if (field == null || field.isBlank() || decision == null) {
                    throw new IllegalArgumentException("field ceiling is invalid");
                }
            });
        }
    }

    private static void digest(String value, String name) {
        if (value == null || !value.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
