package com.dwp.services.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class HrisControlledTransferAuthorizationMigrationContractTest {

    @Test
    void v253DeclaresOnlyTheV44DraftSeedAndActivatesNothing() throws Exception {
        String migration = read(
                "db/migration/V253__declare_hris_controlled_transfer_authorization_seed.sql");

        assertThat(migration).contains(
                "INSERT INTO auth_product_authorization_seed_release",
                "8b4e05e5974a8a25fb9edf318bb3cd609105f2e6353677b69c2eab1cd7038a1b",
                "product-surfaces-v1.bundle-v44.generated.json",
                "'DRAFT'",
                "FALSE");
        assertThat(migration).doesNotContain(
                "INSERT INTO auth_product_authorization_active",
                "INSERT INTO com_resources",
                "INSERT INTO com_role_permissions",
                "INSERT INTO sys_tenant_resource_templates",
                "INSERT INTO sys_tenant_role_permission_templates",
                "bundle_status = 'APPROVED'",
                "bundle_status = 'ACTIVE'",
                "automatic_import_enabled)\nVALUES (\n    'product-surfaces',\n    44,\n"
                        + "    '8b4e05e5974a8a25fb9edf318bb3cd609105f2e6353677b69c2eab1cd7038a1b',\n"
                        + "    'product-surfaces-v1.bundle-v44.generated.json',\n"
                        + "    'DRAFT',\n    TRUE");
    }

    private String read(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
