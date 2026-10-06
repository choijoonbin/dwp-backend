package com.dwp.services.people.hr.assignment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentProposalMigrationContractTest {

    @Test
    void v53CreatesGovernedProposalEvidenceWithoutLedgerMutationSql() throws IOException {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V53__create_assignment_change_proposals.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("People migration lineage V49-V52 is reserved")
                    .contains("must reconcile those immutable predecessors before V53")
                    .contains("CREATE TABLE ppl_assignment_change_proposals")
                    .contains("CREATE TABLE ppl_assignment_change_proposal_events")
                    .contains("CREATE TABLE ppl_assignment_command_receipts")
                    .contains("CREATE TABLE ppl_assignment_proposal_outbox")
                    .contains("aggregate_version")
                    .contains("DEFAULT pg_catalog.gen_random_uuid()")
                    .contains("ON ppl_assignment_proposal_outbox(status, available_at")
                    .doesNotContain("UPDATE ppl_assignments")
                    .doesNotContain("INSERT INTO ppl_assignments");
        }
    }
}
