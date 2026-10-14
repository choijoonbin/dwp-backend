package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PayrollRunOperationsAuthorizationMigrationContractTest {

    @Test
    void v250CreatesOnlyExactPayrollRunGrantsAndSealsV41AsDraft() throws Exception {
        String migration = read(
                "db/migration/V250__authorize_payroll_run_operations.sql");

        assertThat(migration).contains(
                "'ACTION.HRIS_PAYROLL_RUN', 'ACTION'",
                "'DATA.HRIS_PAYROLL_STATUS', 'DATA'",
                "'ACTION.HRIS_PAYROLL_APPROVE', 'ACTION'",
                "'ACTION.HRIS_PAYROLL_RELEASE', 'ACTION'",
                "'DATA.HRIS_COMMAND_RECEIPT', 'DATA'",
                "('PAYROLL_ADMIN', 'ACTION.HRIS_PAYROLL_RUN', 'EXECUTE', 'ACTIVE')",
                "('PAYROLL_ADMIN', 'DATA.HRIS_PAYROLL_STATUS', 'VIEW', 'ACTIVE')",
                "('PAYROLL_ADMIN', 'ACTION.HRIS_PAYROLL_APPROVE', 'APPROVE', 'ACTIVE')",
                "('PAYROLL_ADMIN', 'ACTION.HRIS_PAYROLL_RELEASE', 'EXECUTE', 'ACTIVE')",
                "('WORKSPACE_MEMBER', 'DATA.HRIS_COMMAND_RECEIPT', 'VIEW', 'ACTIVE')",
                "026157acf598ebabf156b40e3c084c40d52346ebfd2019e2476f30ecdd2b8d93",
                "product-surfaces-v1.bundle-v41.generated.json",
                "'DRAFT'",
                "FALSE");
        assertThat(migration).doesNotContain(
                "ACTION.HRIS_PAYROLL_RUN', 'MANAGE'",
                "ACTION.HRIS_PAYROLL_APPROVE', 'MANAGE'",
                "ACTION.HRIS_PAYROLL_RELEASE', 'MANAGE'",
                "INSERT INTO auth_product_authorization_active",
                "bundle_status = 'APPROVED'",
                "bundle_status = 'ACTIVE'");
    }

    private String read(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
