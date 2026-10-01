package com.dwp.services.provider.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DataPolicyRepositoryPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private DataPolicyRepository repository;

    @BeforeEach
    void migrateAndResetPolicies() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM prv_data_policy_approvals");
        jdbc.update("DELETE FROM prv_data_policy_revisions");
        jdbc.update("DELETE FROM prv_data_policies");
        repository = new DataPolicyRepository(
                new NamedParameterJdbcTemplate(dataSource),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void boundedReadsPrioritizeOpenRevisionsAndBatchLatestApprovals() {
        UUID closedPolicy = insertPolicy("governance.closed", "Closed policy");
        insertRevision(closedPolicy, 1, "SUPERSEDED");
        UUID openPolicy = insertPolicy("governance.open", "Open policy");
        for (int revision = 100; revision <= 104; revision++) {
            insertRevision(openPolicy, revision, "SUPERSEDED");
        }
        UUID pending = insertRevision(openPolicy, 1, "PENDING_APPROVAL");
        UUID approved = insertRevision(openPolicy, 2, "APPROVED");
        UUID draft = insertRevision(openPolicy, 3, "DRAFT");
        UUID active = insertRevision(openPolicy, 4, "ACTIVE");

        UUID olderApproval = UUID.randomUUID();
        UUID latestApproval = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_data_policy_approvals (
                    data_policy_approval_id, data_policy_revision_id, lifecycle_state,
                    requested_by, requested_at, decision_reason)
                VALUES (?, ?, 'REJECTED', 1, CURRENT_TIMESTAMP - INTERVAL '2 days', 'Old'),
                       (?, ?, 'PENDING', 1, CURRENT_TIMESTAMP - INTERVAL '1 day', NULL)
                """, olderApproval, pending, latestApproval, pending);

        assertThat(repository.policies(1))
                .extracting(DataPolicyRepository.PolicyRow::policyId)
                .containsExactly(openPolicy);
        List<DataPolicyRepository.RevisionRow> revisions =
                repository.revisions(List.of(openPolicy), 4);
        assertThat(revisions)
                .extracting(DataPolicyRepository.RevisionRow::revisionId)
                .containsExactly(pending, approved, draft, active);
        assertThat(repository.approvals(List.of(
                        pending, approved, draft, active)))
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.revisionId()).isEqualTo(pending);
                    assertThat(result.approval().approvalId()).isEqualTo(latestApproval);
                    assertThat(result.approval().lifecycleState()).isEqualTo("PENDING");
                    assertThat(result.approval().requestedAt()).isNotNull();
                });
    }

    private UUID insertPolicy(String key, String displayName) {
        UUID policyId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_data_policies (
                    data_policy_id, policy_key, display_name, description, policy_type,
                    scope_type, owner_service, created_by, updated_by)
                VALUES (?, ?, ?, 'Repository paging test', 'RETENTION',
                        'GLOBAL', 'dwp-provider-server', 1, 1)
                """, policyId, key, displayName);
        return policyId;
    }

    private UUID insertRevision(UUID policyId, int revisionNumber, String state) {
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_data_policy_revisions (
                    data_policy_revision_id, data_policy_id, revision_number,
                    lifecycle_state, policy_rule, effective_from, justification,
                    requested_by)
                VALUES (?, ?, ?, ?, CAST(? AS JSONB), CURRENT_TIMESTAMP,
                        'Repository paging test', 1)
                """, revisionId, policyId, revisionNumber, state,
                "{\"retentionDays\":365}");
        return revisionId;
    }
}
