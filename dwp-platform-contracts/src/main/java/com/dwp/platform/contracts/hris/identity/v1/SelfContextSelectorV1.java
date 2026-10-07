package com.dwp.platform.contracts.hris.identity.v1;

import java.util.UUID;

/** Untrusted selector: all three IDs must match the owner-returned native graph. */
public record SelfContextSelectorV1(UUID workerPublicId, UUID workRelationshipPublicId,
                                   UUID assignmentPublicId) {
}
