package com.dwp.services.people.hr.assignment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AssignmentProposalPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static AssignmentProposalRepository repository;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
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
        repository = new AssignmentProposalRepository(
                new NamedParameterJdbcTemplate(dataSource),
                new ObjectMapper().findAndRegisterModules());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void clearProposalEvidence() {
        jdbc.update("TRUNCATE TABLE ppl_assignment_proposal_outbox");
        jdbc.update("TRUNCATE TABLE ppl_assignment_change_proposal_events");
        jdbc.update("TRUNCATE TABLE ppl_assignment_command_receipts");
        jdbc.update("TRUNCATE TABLE ppl_assignment_change_proposals");
    }

    @Test
    void receiptCasAndOutboxAreDurableWhileSubmitLeavesAssignmentLedgerUntouched() {
        Map<String, Object> seeded = jdbc.queryForMap("""
                SELECT assignment.tenant_id,
                       assignment.public_id AS assignment_public_id,
                       assignment.version AS assignment_version,
                       assignment.business_title,
                       reason.reason_code
                  FROM ppl_assignments assignment
                  JOIN ppl_assignment_change_reason_catalog reason
                    ON reason.tenant_id = assignment.tenant_id
                   AND reason.lifecycle_state = 'ACTIVE'
                 ORDER BY assignment.assignment_id, reason.sort_order
                 LIMIT 1
                """);
        long tenantId = ((Number) seeded.get("tenant_id")).longValue();
        UUID assignmentId = (UUID) seeded.get("assignment_public_id");
        long assignmentVersion = ((Number) seeded.get("assignment_version")).longValue();
        String assignmentTitle = (String) seeded.get("business_title");
        String reasonCode = (String) seeded.get("reason_code");
        UUID commandId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();

        transactions.executeWithoutResult(ignored -> {
            AssignmentProposalRepository.TargetAssignment target = repository
                    .targetForMutation(tenantId, assignmentId).orElseThrow();
            AssignmentProposalRepository.ReceiptRow receipt = repository.claimReceipt(
                    tenantId, receiptId, commandId, 17L, UUID.randomUUID(),
                    "CREATE", "idem-create-1", "a".repeat(64),
                    "population-v1", null).orElseThrow();
            AssignmentProposalRepository.ProposalRow proposal = repository.create(
                    tenantId, target, UUID.randomUUID(), "CORRECTION",
                    LocalDate.now(), reasonCode,
                    "{\"businessTitle\":\"Corrected title\"}",
                    "b".repeat(64), 17L);
            repository.appendLifecycle(
                    tenantId, receipt.internalId(), proposal, "CREATED",
                    "{\"event\":\"created\"}", 17L);
            repository.completeReceipt(
                    tenantId, receipt.internalId(), proposal,
                    "{\"proposalId\":\"" + proposal.publicId() + "\"}");
            AssignmentProposalRepository.ProposalRow validated = repository.validate(
                    tenantId, proposal.publicId(), 0L, "c".repeat(64), "[]", 17L)
                    .orElseThrow();
            assertThat(repository.validate(
                    tenantId, proposal.publicId(), 0L,
                    "c".repeat(64), "[]", 17L)).isEmpty();
            assertThat(repository.submit(
                    tenantId, proposal.publicId(), validated.version(), 17L))
                    .get().extracting(
                            AssignmentProposalRepository.ProposalRow::lifecycleState,
                            AssignmentProposalRepository.ProposalRow::version)
                    .containsExactly("SUBMITTED", 2L);
        });

        assertThat(repository.claimReceipt(
                tenantId, UUID.randomUUID(), commandId, 17L, UUID.randomUUID(),
                "CREATE", "idem-create-1", "a".repeat(64),
                "population-v1", null)).isEmpty();
        assertThat(repository.receipt(
                tenantId, 17L, "CREATE", "idem-create-1"))
                .get().extracting(
                        AssignmentProposalRepository.ReceiptRow::lifecycleState,
                        AssignmentProposalRepository.ReceiptRow::resultingVersion)
                .containsExactly("SUCCEEDED", 0L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ppl_assignment_change_proposal_events",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ppl_assignment_proposal_outbox WHERE status = 'PENDING'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT version, business_title FROM ppl_assignments
                 WHERE tenant_id = ? AND public_id = ?
                """, tenantId, assignmentId))
                .containsEntry("version", assignmentVersion)
                .containsEntry("business_title", assignmentTitle);
    }
}
