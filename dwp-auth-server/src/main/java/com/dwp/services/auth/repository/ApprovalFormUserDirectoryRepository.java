package com.dwp.services.auth.repository;

import com.dwp.services.auth.dto.ApprovalFormUserDirectoryDtos.ResolvedPerson;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The purpose-specific query never selects or searches HCM attributes or identity email. */
@Repository
public class ApprovalFormUserDirectoryRepository {
    private static final String SOURCE = """
            SELECT person.tenant_id, person.user_id, person.person_public_id,
                   person.display_name, person.identity_plane, person.status
              FROM com_users person
              JOIN com_tenants tenant ON tenant.tenant_id = person.tenant_id
                                     AND tenant.status = 'ACTIVE'
             WHERE person.tenant_id = :tenantId
               AND person.identity_plane = 'TENANT' AND person.status = 'ACTIVE'
               AND person.person_public_id IS NOT NULL
            """;
    private final NamedParameterJdbcTemplate jdbc;

    public ApprovalFormUserDirectoryRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ResolvedPerson> search(long tenantId, String query, int size) {
        String literal = query.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return read(SOURCE + " AND lower(person.display_name) LIKE lower(:query) ESCAPE '!'"
                + " ORDER BY person.display_name, person.user_id LIMIT :size",
                new MapSqlParameterSource("tenantId", tenantId).addValue("query", '%' + literal + '%').addValue("size", size));
    }

    public List<ResolvedPerson> resolve(long tenantId, List<UUID> people) {
        return read(SOURCE + " AND person.person_public_id IN (:people) ORDER BY person.user_id",
                new MapSqlParameterSource("tenantId", tenantId).addValue("people", people));
    }

    private List<ResolvedPerson> read(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (row, index) -> new ResolvedPerson(row.getLong("tenant_id"),
                row.getLong("user_id"), row.getObject("person_public_id", UUID.class), row.getString("display_name"),
                row.getString("identity_plane"), row.getString("status")));
    }
}
