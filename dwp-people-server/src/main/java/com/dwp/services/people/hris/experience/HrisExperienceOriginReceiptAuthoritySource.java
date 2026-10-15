package com.dwp.services.people.hris.experience;

import com.dwp.services.people.hr.HcmPopulationScopeService;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Central-owned bridge for re-evaluating an originating command authority.
 *
 * <p>The generic receipt GET decision is deliberately not reused as the origin
 * decision. Until exactly one authoritative bridge is registered, receipt
 * projection fails closed.</p>
 */
public interface HrisExperienceOriginReceiptAuthoritySource {

    Optional<CurrentOriginAuthority> resolve(OriginAuthorityRequest request);

    record OriginAuthorityRequest(
            long tenantId,
            long actorUserId,
            UUID actorPersonPublicId,
            String commandType,
            String action,
            String targetType,
            String purposeCode,
            String expectedRouteContractKey,
            String expectedCapabilityContractKey,
            String expectedMethod,
            boolean expectedReadOnly,
            long storedAuthorizationRevision,
            String storedContextDigest,
            UUID subjectPublicId) {
    }

    record CurrentOriginAuthority(
            long tenantId,
            long actorUserId,
            UUID actorPersonPublicId,
            String commandType,
            String action,
            String targetType,
            String purposeCode,
            String routeContractKey,
            String capabilityContractKey,
            String method,
            boolean readOnly,
            long authorizationRevision,
            String contextDigest,
            CurrentOriginScope scope) {
    }

    /**
     * The single owner-materialized scope used by both the V54 evidence check
     * and the final receipt population/field checks.
     */
    record CurrentOriginScope(
            HcmPopulationScopeService.ResolvedPopulation population,
            String scopeRevision,
            Set<String> fieldGroups,
            String sourceScopeKey,
            String derivedScopeKey,
            String productRelationshipRevision,
            String targetPopulationRevision,
            OffsetDateTime freshUntil) {

        public CurrentOriginScope {
            fieldGroups = fieldGroups == null ? Set.of() : Set.copyOf(fieldGroups);
        }

        public boolean self() {
            return population == null;
        }
    }
}
