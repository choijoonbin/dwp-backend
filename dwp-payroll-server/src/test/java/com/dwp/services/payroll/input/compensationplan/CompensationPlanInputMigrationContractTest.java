package com.dwp.services.payroll.input.compensationplan;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationPlanInputMigrationContractTest {

    @Test
    void v49KeysCompensationLinesByWorkerAssignmentAndComponent() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V49__pay_modern_compensation_plan_input.sql"));

        assertThat(sql).containsIgnoringWhitespaces("""
                CONSTRAINT pay_compensation_input_line_unique
                    UNIQUE (tenant_id, snapshot_id, snapshot_revision,
                        worker_id, assignment_id, component_code)
                """);
    }
}
