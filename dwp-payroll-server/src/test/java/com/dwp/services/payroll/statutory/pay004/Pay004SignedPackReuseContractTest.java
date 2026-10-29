package com.dwp.services.payroll.statutory.pay004;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class Pay004SignedPackReuseContractTest {

    @Test
    void v55ReferencesV53PackSnapshotAndActivationWithoutRecreatingThem()
            throws IOException {
        String sql = Pay004V55MigrationContractTest.migrationSql();
        assertThat(sql)
                .contains("REFERENCES pay_country_pack_version_snapshots")
                .contains("REFERENCES pay_country_pack_activation_versions")
                .contains("activation_row.lifecycle_state <> 'ACTIVE'")
                .contains("pack_row.legal_entity_id IS DISTINCT FROM "
                        + "case_row.legal_entity_id")
                .contains("pack_row.payroll_group_id IS DISTINCT FROM "
                        + "case_row.payroll_group_id")
                .doesNotContain("CREATE TABLE pay_country_pack_version_snapshots")
                .doesNotContain("CREATE TABLE pay_country_pack_activation_versions")
                .doesNotContain("CREATE TABLE pay_country_pack_command_receipts")
                .doesNotContain("COUNTRY_PACK_ACTIVATE");
    }

    @Test
    void requiredPackNeverFallsBackToNonPackPolicy() {
        for (RetirementYearEndCaseContract.CountryPackState state : java.util.List.of(
                RetirementYearEndCaseContract.CountryPackState.NOT_INSTALLED,
                RetirementYearEndCaseContract.CountryPackState.DISABLED)) {
            RetirementYearEndCase blocked = Pay004TestFixtures.draft(state)
                    .validate(state, Pay004TestFixtures.SUBJECT,
                            Pay004TestFixtures.NOW.plusSeconds(1));
            assertThat(blocked.state()).isEqualTo(
                    RetirementYearEndCaseContract.State.BLOCKED_PACK_REQUIRED);
        }
    }
}
