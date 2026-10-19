package com.dwp.services.auth.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV264MigrationContractTest {
    @Test void onlyTheDraftCarrierAndDefaultOffFourQueryRoutesAreDeclared() throws Exception {
        String sql;
        try (var input = new ClassPathResource("db/migration/V264__declare_hris_r7_batch04_per004_v55_authorization_seed.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String checksum;
        try (var input = new ClassPathResource("product-authorization/product-surfaces-v1.bundle-v55.generated.json").getInputStream()) {
            checksum = new ObjectMapper().readTree(input).get("checksum").textValue();
        }
        assertThat(sql).contains("'product-surfaces', 55", "'" + checksum + "'", "'DRAFT', FALSE",
                "('HRIS_PERFORMANCE_CONFIGURATION', 1, 'hcm', 'APP.HCM', 55, 'OFF', 'PER')");
        assertThat(sql.lines().filter(line -> line.startsWith("INSERT INTO ")).toList()).containsExactly(
                "INSERT INTO auth_product_authorization_seed_release (",
                "INSERT INTO sys_product_feature_activation_policies (",
                "INSERT INTO sys_product_feature_route_owners (route_contract_key, feature_key)",
                "INSERT INTO sys_product_feature_route_bindings (");
        assertThat(sql).doesNotContain(".action'", "INSERT INTO auth_product_feature_activation_changes",
                "INSERT INTO com_tenant_feature_enrollment_changes", "INSERT INTO com_role_permissions",
                "INSERT INTO sys_tenant_role_permission_templates");
    }
}
