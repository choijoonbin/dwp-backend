package com.dwp.platform.contracts.hris.identity.v1;

import java.time.Instant;
import java.util.UUID;

/** Untrusted Auth-owner data; construction is deliberately available to owner adapters. */
public record AuthPersonBindingV1(long tenantId, long userId, UUID principalPublicId,
                                  UUID personPublicId, IdentityPlane identityPlane, Status status,
                                  long userRowVersion, long accessRevision,
                                  Instant capturedAt, Instant expiresAt) {
    public enum IdentityPlane { TENANT, PLATFORM }
    public enum Status { ACTIVE, INVITED, SUSPENDED, INACTIVE }
}
