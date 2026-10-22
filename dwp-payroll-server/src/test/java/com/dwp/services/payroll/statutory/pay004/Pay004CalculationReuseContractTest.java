package com.dwp.services.payroll.statutory.pay004;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class Pay004CalculationReuseContractTest {

    @Test
    void v55BindsExactV50WorkerResultAndRunRevisionWithoutNewEngine()
            throws IOException {
        String sql = Pay004V55MigrationContractTest.migrationSql();
        assertThat(sql)
                .containsIgnoringWhitespaces("""
                        FOREIGN KEY (
                            tenant_id, run_id, attempt_no, worker_result_id)
                            REFERENCES pay_worker_results (
                                tenant_id, run_id, attempt_no, worker_result_id)
                        """)
                .containsIgnoringWhitespaces("""
                        FOREIGN KEY (
                            tenant_id, run_id, run_revision)
                            REFERENCES pay_payroll_runs (
                                tenant_id, run_id, run_revision)
                        """)
                .contains("worker_row.worker_id IS DISTINCT FROM NEW.worker_id")
                .contains("run.run_revision = NEW.run_revision")
                .doesNotContain("CREATE TABLE pay_payroll_runs")
                .doesNotContain("CREATE TABLE pay_worker_results")
                .doesNotContain("CREATE TABLE pay_command_receipts")
                .doesNotContain("CREATE TABLE pay_outbox_events")
                .doesNotContain("PayrollCalculated")
                .doesNotContain("run.calculate");
    }

    @Test
    void resultCannotBeReconstructedFromGenericLinesOrControlTotals()
            throws IOException {
        String sql = Pay004V55MigrationContractTest.migrationSql();
        assertThat(sql)
                .doesNotContain("pay_result_lines")
                .doesNotContain("control_totals")
                .doesNotContain("line_type")
                .doesNotContain("DEDUCTION")
                .contains("retirement_amount_text VARCHAR(24) NOT NULL")
                .containsIgnoringWhitespaces("""
                        retirement_amount NUMERIC(19,4) GENERATED ALWAYS AS
                            (retirement_amount_text::NUMERIC(19,4)) STORED
                        """)
                .contains("CONSTRAINT pay_case_result_money_shape CHECK")
                .contains("retirement_amount_text ~");
    }
}
