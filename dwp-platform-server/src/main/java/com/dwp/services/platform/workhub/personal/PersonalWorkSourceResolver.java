package com.dwp.services.platform.workhub.personal;

import java.util.Optional;

/**
 * Source adapters must check current tenant/user permissions on every resolution.
 * Metadata requires current source-object access. Remote sources whose owner API must
 * enforce its own route PEP may return an identity-only bookmark (all metadata null)
 * after validating source identity and app VIEW entitlement; this is REFERENCE_ONLY,
 * not an access-approved citation. Unsupported/inaccessible sources return empty.
 * An unavailable authority must not return stale metadata as authorized.
 */
public interface PersonalWorkSourceResolver {
    boolean supports(PersonalWorkDtos.SourceReference reference);

    Optional<PersonalWorkDtos.ResolvedSource> resolve(
            PersonalWorkDtos.AccessContext context, PersonalWorkDtos.SourceReference reference);
}
