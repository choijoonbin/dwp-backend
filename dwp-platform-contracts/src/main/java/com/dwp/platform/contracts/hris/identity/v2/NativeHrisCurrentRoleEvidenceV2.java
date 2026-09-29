package com.dwp.platform.contracts.hris.identity.v2;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** UNTRUSTED native Auth carrier. No provider, HTTP proof, role grant or independent ledger. */
public record NativeHrisCurrentRoleEvidenceV2(long tenantId, long userId, UUID principalPublicId,
        long userRowVersion, long accessRevision, String authRevision, boolean complete,
        List<RoleSource> roles, Instant capturedAt, Instant expiresAt) {
    public NativeHrisCurrentRoleEvidenceV2 { roles = roles == null ? null : List.copyOf(roles); }
    public enum SourceKind { DIRECT, GROUP, PRIVILEGED }
    /**
     * roleVersion is com_roles.version. DIRECT uses com_role_members ID/updated_at (no
     * invented membership version). GROUP additionally uses assignment/group versions
     * and the native group-membership timestamp; windows belong to that assignment.
     */
    public record RoleSource(long roleId, String roleCode, long roleVersion, SourceKind sourceKind,
            Long sourceId, UUID sourcePublicId, Long sourceVersion, Instant sourceStamp, Long groupId,
            Long groupVersion, Instant membershipStamp, Instant validFrom, Instant validTo) { }
}
