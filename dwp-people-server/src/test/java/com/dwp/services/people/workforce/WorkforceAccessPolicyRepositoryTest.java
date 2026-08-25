package com.dwp.services.people.workforce;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkforceAccessPolicyRepositoryTest {

    @Test
    void resolveBindsPostgresTimestampInsteadOfUnsupportedInstant() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.query(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<WorkforceAccessPolicyRepository.PolicyRow>>any()))
                .thenAnswer(invocation -> {
                    MapSqlParameterSource parameters = invocation.getArgument(1);
                    assertThat(parameters.getValue("now")).isInstanceOf(Timestamp.class);
                    return List.of();
                });
        WorkforceAccessPolicyRepository repository = new WorkforceAccessPolicyRepository(jdbc);

        repository.resolve(1L, 37L, Set.of("HR_ADMIN"), Instant.parse("2026-08-14T04:00:00Z"));
    }

    @Test
    void mutationResolutionLocksOnlyCurrentTenantAuthorityRows() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        List<String> sql = new ArrayList<>();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers
                        .<RowMapper<WorkforceAccessPolicyRepository.PolicyRow>>any()))
                .thenAnswer(invocation -> {
                    sql.add(invocation.getArgument(0));
                    return List.of();
                });
        WorkforceAccessPolicyRepository repository = new WorkforceAccessPolicyRepository(jdbc);

        repository.resolveForShare(
                7L, 41L, Set.of("HR_ADMIN"), Instant.parse("2026-08-14T04:00:00Z"));

        assertThat(normalized(sql.getFirst()))
                .contains("WHERE policy.tenant_id = :tenantId")
                .contains("policy.lifecycle_state = 'ACTIVE'")
                .contains("policy.valid_from IS NULL OR policy.valid_from <= :now")
                .contains("policy.valid_to IS NULL OR policy.valid_to > :now")
                .endsWith("FOR SHARE OF policy");
    }

    @Test
    void mutationExpansionLocksTheResolvedHierarchyAndExactOrganizationRows() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID root = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        List<String> sql = new ArrayList<>();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenAnswer(invocation -> {
                    sql.add(invocation.getArgument(0));
                    return List.of(root, child);
                });
        WorkforceAccessPolicyRepository repository = new WorkforceAccessPolicyRepository(jdbc);

        Set<UUID> result = repository.expandOrganizationsForShare(
                7L, List.of(policy("ORG_TREE", root)));

        assertThat(result).containsExactlyInAnyOrder(root, child);
        assertThat(sql).hasSize(2);
        assertThat(normalized(sql.get(0)))
                .contains("WITH RECURSIVE organization_tree AS")
                .contains("WHERE tenant_id = :tenantId AND public_id IN (:roots)")
                .contains("lifecycle_state = 'ACTIVE'")
                .contains("WHERE child.tenant_id = :tenantId")
                .endsWith("FOR SHARE OF organization");
        assertThat(normalized(sql.get(1)))
                .contains("WHERE tenant_id = :tenantId")
                .contains("public_id IN (:organizationIds)")
                .contains("lifecycle_state = 'ACTIVE'")
                .endsWith("FOR SHARE");
    }

    @Test
    void mutationExpansionFailsClosedWhenAnExactOrganizationCannotBeLocked() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID organizationId = UUID.randomUUID();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of());
        WorkforceAccessPolicyRepository repository = new WorkforceAccessPolicyRepository(jdbc);

        Set<UUID> result = repository.expandOrganizationsForShare(
                7L, List.of(policy("ORG_UNIT", organizationId)));

        assertThat(result).isEmpty();
    }

    @Test
    void mutationExpansionFailsClosedWhenAnOrganizationTreeDisappears() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID root = UUID.randomUUID();
        when(jdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<UUID>>any()))
                .thenReturn(List.of());
        WorkforceAccessPolicyRepository repository = new WorkforceAccessPolicyRepository(jdbc);

        Set<UUID> result = repository.expandOrganizationsForShare(
                7L, List.of(policy("ORG_TREE", root)));

        assertThat(result).isEmpty();
    }

    private WorkforceAccessPolicyRepository.PolicyRow policy(
            String populationType,
            UUID organizationId) {
        return new WorkforceAccessPolicyRepository.PolicyRow(
                UUID.randomUUID(), "ROLE", "HR_ADMIN", populationType,
                organizationId, "Operations", List.of("DIRECTORY", "EMPLOYMENT"),
                List.of("READ"), null, null, "ACTIVE", "Approved scope", 3L);
    }

    private String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
