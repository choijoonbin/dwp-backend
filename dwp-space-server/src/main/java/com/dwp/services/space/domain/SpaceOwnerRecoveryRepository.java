package com.dwp.services.space.domain;

import com.dwp.services.space.security.SpaceRequestContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class SpaceOwnerRecoveryRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public SpaceOwnerRecoveryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void recover(
            SpaceRequestContext.Subject subject,
            UUID spaceId,
            UUID personPublicId) {
        jdbc.update("""
                INSERT INTO spc_memberships (
                    membership_id, tenant_id, space_id, principal_type,
                    principal_ref, member_role, membership_source,
                    lifecycle_state, valid_until, approved_by)
                VALUES (:membershipId, :tenantId, :spaceId, 'USER',
                    :principalRef, 'OWNER', 'RECOVERY', 'ACTIVE', NULL, :userId)
                ON CONFLICT (tenant_id, space_id, principal_type, principal_ref)
                DO UPDATE SET member_role = 'OWNER',
                              membership_source = 'RECOVERY',
                              lifecycle_state = 'ACTIVE',
                              valid_from = CURRENT_TIMESTAMP,
                              valid_until = NULL,
                              approved_by = EXCLUDED.approved_by,
                              version = spc_memberships.version + 1,
                              updated_at = CURRENT_TIMESTAMP
                """, new MapSqlParameterSource()
                .addValue("membershipId", UUID.randomUUID())
                .addValue("tenantId", subject.tenantId())
                .addValue("spaceId", spaceId)
                .addValue("principalRef", personPublicId.toString())
                .addValue("userId", subject.userId()));
    }
}
