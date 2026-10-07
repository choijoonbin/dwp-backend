package com.dwp.services.payroll.foundation;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Payroll-owner boundary for resolving an opaque Gateway scope key into exact legal entities.
 *
 * <p>The Gateway key proves which scope was selected. It intentionally does not carry business
 * membership. Only an owner-local, current projection for the tenant, actor, derived scope and
 * rollout revision may supply that membership. Route-specific decision revisions remain enforced
 * by the Gateway and {@link PayrollFoundationSecurityFilter}; they are audit evidence, not part of
 * the owner membership identity.</p>
 */
interface PayrollLegalEntityScopeResolver {

    Resolution resolve(PayrollFoundationRequestContext.VerifiedSubject subject);

    record Resolution(String projectionRevision, Set<UUID> legalEntityIds) {

        public Resolution {
            if (projectionRevision == null
                    || !projectionRevision.strip().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,239}")) {
                throw new IllegalArgumentException("A canonical scope projection revision is required");
            }
            if (legalEntityIds == null || legalEntityIds.isEmpty()
                    || legalEntityIds.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("At least one legal entity membership is required");
            }
            projectionRevision = projectionRevision.strip();
            legalEntityIds = Set.copyOf(new LinkedHashSet<>(legalEntityIds));
        }

        PayrollFoundationAccess.Scope scope() {
            return new PayrollFoundationAccess.Scope(false, legalEntityIds);
        }

        String evidenceDigest(String contextScopeKey) {
            String canonicalMembers = legalEntityIds.stream()
                    .sorted()
                    .map(UUID::toString)
                    .collect(java.util.stream.Collectors.joining(","));
            return PayrollFoundationCanonical.textDigest(
                    contextScopeKey + "|" + projectionRevision + "|" + canonicalMembers);
        }
    }
}
