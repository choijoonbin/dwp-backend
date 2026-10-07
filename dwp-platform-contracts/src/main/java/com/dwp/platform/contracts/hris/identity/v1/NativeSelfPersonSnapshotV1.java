package com.dwp.platform.contracts.hris.identity.v1;

import java.time.Instant;
import java.util.UUID;

/** Untrusted current People owner metadata. A native version/lease is not a signed current proof. */
public record NativeSelfPersonSnapshotV1(long tenantId, UUID personPublicId, long personVersion,
                                       NativeSelfContextSetV1.PersonState personState,
                                       Instant capturedAt, Instant expiresAt) {
}
