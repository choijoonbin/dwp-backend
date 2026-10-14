package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HrisExplorerAuthorizationMigrationContractTest {

    @Test
    void v252CreatesOnlyTheShellViewGrantAndDeclaresV43AsDraft() throws Exception {
        String migration = read(
                "db/migration/V252__authorize_hris_work_explorer.sql");

        assertThat(migration).contains(
                "'DATA.HRIS_APP_SHELL', 'DATA'",
                "('WORKSPACE_MEMBER', 'DATA.HRIS_APP_SHELL', 'VIEW', 'ACTIVE')",
                "INSERT INTO com_resources",
                "INSERT INTO com_role_permissions",
                "access_revision = user_record.access_revision + 1",
                "2222319502c362caf247a7a47fdacfc63c774dbf156f6272cafae797627e2400",
                "product-surfaces-v1.bundle-v43.generated.json",
                "'DRAFT'",
                "FALSE");
        assertThat(migration).doesNotContain(
                "'DATA.HRIS_APP_SHELL', 'CREATE'",
                "'DATA.HRIS_APP_SHELL', 'UPDATE'",
                "'DATA.HRIS_APP_SHELL', 'MANAGE'",
                "HRIS_DUTY_APP_SHELL_READER', 'ACTIVE'",
                "INSERT INTO auth_product_authorization_active",
                "bundle_status = 'APPROVED'",
                "bundle_status = 'ACTIVE'",
                "automatic_import_enabled)\nVALUES (\n    'product-surfaces',\n    43,\n"
                        + "    '2222319502c362caf247a7a47fdacfc63c774dbf156f6272cafae797627e2400',\n"
                        + "    'product-surfaces-v1.bundle-v43.generated.json',\n"
                        + "    'DRAFT',\n    TRUE");
    }

    private String read(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
