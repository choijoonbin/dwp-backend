package com.dwp.services.people.hris.employment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HrmMigrationCleanUpgradeTest {

    @Test
    void v55IsAForwardOnlyCanonicalLedgerUpgrade() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V55__hrm_base_tfr_hrm_008.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("CREATE TABLE public.ppl_assignment_events")
                    .contains("CREATE TABLE public.ppl_assignment_event_approval_decisions")
                    .contains("canonical_assignment_event_id")
                    .contains("sys_people_outbox_events")
                    .contains("AssignmentChanged.v1")
                    .contains("ENABLE ROW LEVEL SECURITY")
                    .contains("FORCE ROW LEVEL SECURITY")
                    .doesNotContain("DROP TABLE")
                    .doesNotContain("TRUNCATE TABLE")
                    .doesNotContain("ALTER TABLE public.ppl_assignment_command_receipts")
                    .doesNotContain("ALTER TABLE public.ppl_assignment_change_proposals\n    ADD CONSTRAINT ck_");
        }
    }
}
