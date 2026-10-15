package com.dwp.services.people.hris.experience;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for a server-verified, tenant-bound People subject to active DWP user
 * mapping. HRM-002 deliberately ships without an inferred implementation.
 */
interface HrisExperienceRecipientAuthority {

    Optional<VerifiedRecipient> resolve(
            long tenantId,
            UUID targetPersonPublicId,
            Instant at);

    record VerifiedRecipient(
            long tenantId,
            UUID personPublicId,
            long userId,
            String evidenceRevision,
            Instant validUntil) {
    }
}

@Component
final class NoVerifiedHrisExperienceRecipientAuthority
        implements HrisExperienceRecipientAuthority {

    @Override
    public Optional<VerifiedRecipient> resolve(
            long tenantId,
            UUID targetPersonPublicId,
            Instant at) {
        // People currently has no authoritative person-to-active-DWP-user mapping.
        // Never infer this identity from created_by, actor ids, or person keys.
        return Optional.empty();
    }
}
