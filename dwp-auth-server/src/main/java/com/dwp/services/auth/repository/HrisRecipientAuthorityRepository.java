package com.dwp.services.auth.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/** Owns the single exact native query used by the HRIS recipient authority. */
@Repository
public class HrisRecipientAuthorityRepository {

    private static final String EXACT_AUTHORITY_SQL = """
            SELECT tenant.tenant_id,
                   tenant.public_id AS tenant_public_id,
                   tenant.status AS tenant_status,
                   tenant.version AS tenant_version,
                   subject.user_id,
                   subject.public_id AS principal_public_id,
                   subject.person_public_id,
                   subject.status AS user_status,
                   subject.identity_plane,
                   subject.version AS user_row_version,
                   subject.access_revision
              FROM com_tenants tenant
              JOIN com_users subject ON subject.tenant_id = tenant.tenant_id
             WHERE tenant.tenant_id = :tenantId
               AND tenant.status = 'ACTIVE'
               AND subject.tenant_id = tenant.tenant_id
               AND subject.person_public_id = :personPublicId
               AND subject.status = 'ACTIVE'
               AND subject.identity_plane = 'TENANT'
             ORDER BY subject.user_id
             LIMIT 2
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public HrisRecipientAuthorityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuthorityRow> resolve(long tenantId, UUID personPublicId) {
        return jdbc.query(
                EXACT_AUTHORITY_SQL,
                new MapSqlParameterSource("tenantId", tenantId)
                        .addValue("personPublicId", personPublicId),
                (row, index) -> new AuthorityRow(
                        row.getObject("tenant_id", Long.class),
                        row.getObject("tenant_public_id", UUID.class),
                        row.getString("tenant_status"),
                        row.getObject("tenant_version", Long.class),
                        row.getObject("user_id", Long.class),
                        row.getObject("principal_public_id", UUID.class),
                        row.getObject("person_public_id", UUID.class),
                        row.getString("user_status"),
                        row.getString("identity_plane"),
                        row.getObject("user_row_version", Long.class),
                        row.getObject("access_revision", Long.class)));
    }

    public record AuthorityRow(
            Long tenantId,
            UUID tenantPublicId,
            String tenantStatus,
            Long tenantVersion,
            Long userId,
            UUID principalPublicId,
            UUID personPublicId,
            String userStatus,
            String identityPlane,
            Long userRowVersion,
            Long accessRevision) {
    }
}
