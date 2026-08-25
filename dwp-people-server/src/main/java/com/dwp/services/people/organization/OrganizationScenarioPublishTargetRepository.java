package com.dwp.services.people.organization;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Locks maker, lifecycle, baseline, and object-version evidence for publish. */
@Repository
public class OrganizationScenarioPublishTargetRepository {

    private final JdbcTemplate jdbc;

    public OrganizationScenarioPublishTargetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrganizationScenarioRepository.ScenarioRecord> lock(
            Long tenantId, UUID scenarioId) {
        return jdbc.query("""
                SELECT organization_scenario_id, scenario_key, name,
                       baseline_date, effective_date, baseline_fingerprint,
                       lifecycle_state, owner_user_id, version
                  FROM ppl_organization_scenarios
                 WHERE tenant_id = ? AND organization_scenario_id = ?
                 FOR UPDATE
                """, (result, ignored) -> new OrganizationScenarioRepository.ScenarioRecord(
                result.getObject("organization_scenario_id", UUID.class),
                result.getString("scenario_key"), result.getString("name"),
                result.getDate("baseline_date").toLocalDate(),
                result.getDate("effective_date").toLocalDate(),
                result.getString("baseline_fingerprint"),
                result.getString("lifecycle_state"), result.getLong("owner_user_id"),
                result.getLong("version")), tenantId, scenarioId).stream().findFirst();
    }
}
