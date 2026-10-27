package com.dwp.services.space.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.space.security.SpaceRequestContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

final class SpaceMembershipWriteGuard {

    private final NamedParameterJdbcTemplate jdbc;

    SpaceMembershipWriteGuard(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lockScope(SpaceRequestContext.Subject subject, UUID spaceId) {
        // Owner changes and access decisions share this lock within the service transaction.
        List<UUID> spaces = jdbc.queryForList("""
                SELECT space_id FROM spc_spaces
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                 FOR UPDATE
                """, params(subject, spaceId), UUID.class);
        if (spaces.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND, "Space was not found.");
    }

    String requireAccessRequest(SpaceRequestContext.Subject subject, UUID spaceId) {
        lockScope(subject, spaceId);
        Integer activeMemberships = jdbc.queryForObject("""
                SELECT COUNT(*) FROM spc_memberships
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                   AND ((principal_type = 'USER' AND principal_ref IN (:userRefs))
                        OR (:hasGroups AND principal_type = 'GROUP' AND principal_ref IN (:groups)))
                   AND lifecycle_state = 'ACTIVE'
                   AND valid_from <= CURRENT_TIMESTAMP
                   AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """, params(subject, spaceId)
                .addValue("userRefs", subject.personPublicId() == null
                        ? List.of(Long.toString(subject.userId()))
                        : List.of(Long.toString(subject.userId()), subject.personPublicId().toString()))
                .addValue("hasGroups", !subject.groupRefs().isEmpty())
                .addValue("groups", subject.groupRefs().isEmpty()
                        ? List.of("__NO_GROUP__") : List.copyOf(subject.groupRefs())), Integer.class);
        if (activeMemberships != null && activeMemberships > 0) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The requester already has an active Space membership.");
        }
        List<String> spaces = jdbc.queryForList("""
                SELECT visibility FROM spc_spaces
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                   AND lifecycle_state = 'ACTIVE'
                """, params(subject, spaceId), String.class);
        if (spaces.isEmpty() || !List.of("OPEN", "REQUEST").contains(spaces.getFirst())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "This Space does not accept membership requests.");
        }
        return spaces.getFirst();
    }

    void requireAnotherOwner(SpaceRequestContext.Subject subject, UUID spaceId, UUID membershipId) {
        Integer soleOwner = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER FROM spc_memberships target
                 WHERE target.tenant_id = :tenantId AND target.space_id = :spaceId
                   AND target.membership_id = :membershipId
                   AND target.member_role = 'OWNER'
                   AND target.lifecycle_state = 'ACTIVE'
                   AND target.valid_from <= CURRENT_TIMESTAMP
                   AND (target.valid_until IS NULL OR target.valid_until > CURRENT_TIMESTAMP)
                   AND NOT EXISTS (SELECT 1 FROM spc_memberships owner_record
                                    WHERE owner_record.tenant_id = target.tenant_id
                                      AND owner_record.space_id = target.space_id
                                      AND owner_record.membership_id <> target.membership_id
                                      AND owner_record.member_role = 'OWNER'
                                      AND owner_record.lifecycle_state = 'ACTIVE'
                                      AND owner_record.valid_from <= CURRENT_TIMESTAMP
                                      AND (owner_record.valid_until IS NULL
                                           OR owner_record.valid_until > CURRENT_TIMESTAMP))
                """, params(subject, spaceId).addValue("membershipId", membershipId), Integer.class);
        if (Integer.valueOf(1).equals(soleOwner)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The final active Space owner cannot be removed or demoted.");
        }
    }

    void prepareUpsert(SpaceRequestContext.Subject subject, UUID spaceId,
                       String principalType, String principalRef, String memberRole) {
        lockScope(subject, spaceId);
        if ("OWNER".equals(memberRole)) return;
        List<UUID> existing = jdbc.queryForList("""
                SELECT membership_id FROM spc_memberships
                 WHERE tenant_id = :tenantId AND space_id = :spaceId
                   AND principal_type = :principalType AND principal_ref = :principalRef
                """, params(subject, spaceId)
                .addValue("principalType", principalType)
                .addValue("principalRef", principalRef), UUID.class);
        for (UUID membershipId : existing) requireAnotherOwner(subject, spaceId, membershipId);
    }

    private MapSqlParameterSource params(SpaceRequestContext.Subject subject, UUID spaceId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId);
    }
}
