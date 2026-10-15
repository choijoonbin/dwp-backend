package com.dwp.services.people.hris.experience;

import java.util.Optional;
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
            long storedAuthorizationRevision) {
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
            long authorizationRevision) {
    }
}
