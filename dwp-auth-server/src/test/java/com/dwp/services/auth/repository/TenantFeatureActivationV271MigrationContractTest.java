package com.dwp.services.auth.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV271MigrationContractTest {
    private static final String FEATURE = "HRIS_HRM_EXIT_RETURN_WORKBENCH";
    private static final List<String> ROUTES = List.of(
            "route.hcm.operations.exit-return.page",
            "route.hcm.operations.exit-return.data",
            "route.hcm.operations.separation-case.action");

    @Test
    void declaresExactV62DefaultOffSuccessorAndEmptyAuthoritySchema() throws Exception {
        String sql;
        try (var input = new ClassPathResource(
                "db/migration/V271__create_hris_service_grant_authority.sql")
                .getInputStream()) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonNode bundle;
        try (var input = new ClassPathResource(
                "product-authorization/product-surfaces-v1.bundle-v62.generated.json")
                .getInputStream()) {
            bundle = new ObjectMapper().readTree(input);
        }

        assertThat(bundle.path("version").asInt()).isEqualTo(62);
        assertThat(bundle.path("bundleStatus").asText()).isEqualTo("DRAFT");
        assertThat(bundle.path("checksum").asText()).isEqualTo(
                "cb370fd02a896452a64187fca8afb8bb3e3d281e490fba02e76f7a3c0d7f9770");
        assertThat(bundle.path("capabilities")).hasSize(280);
        assertThat(bundle.path("accessPolicies")).hasSize(24);
        assertThat(bundle.path("entitlementExpressions")).hasSize(17);
        assertThat(bundle.path("predicatePolicies")).hasSize(87);
        assertThat(bundle.path("routes")).hasSize(1158);
        assertThat(StreamSupport.stream(bundle.path("routes").spliterator(), false)
                .filter(route -> "PAGE".equals(route.path("routeKind").asText())).count())
                .isEqualTo(162);
        assertThat(StreamSupport.stream(bundle.path("routes").spliterator(), false)
                .filter(route -> "DATA".equals(route.path("routeKind").asText())).count())
                .isEqualTo(349);
        assertThat(StreamSupport.stream(bundle.path("routes").spliterator(), false)
                .filter(route -> "ACTION".equals(route.path("routeKind").asText())).count())
                .isEqualTo(647);

        Set<String> actualRoutes = StreamSupport.stream(
                        bundle.path("routes").spliterator(), false)
                .map(route -> route.path("routeContractKey").asText())
                .filter(ROUTES::contains)
                .collect(Collectors.toUnmodifiableSet());
        assertThat(actualRoutes).containsExactlyInAnyOrderElementsOf(ROUTES);

        assertThat(sql).contains(
                "CREATE TABLE auth_hris_service_grant_sets",
                "CREATE TABLE auth_hris_service_grant_bindings",
                "CREATE TABLE auth_hris_service_authority_proof_replays",
                "CREATE TABLE auth_hris_service_grant_quiesce_fences",
                "CREATE TABLE auth_hris_service_grant_drain_activities",
                "CREATE FUNCTION auth_hris_rs256_modulus_valid",
                "jsonb_typeof(caller_signing_public_jwk->'n') = 'string'",
                "octet_length(decoded) < 256",
                "NEW.reserved_at := transaction_timestamp()",
                "ORDER BY fence.quiesce_revision DESC",
                "NEW.recorded_at := transaction_timestamp()",
                "OLD.expires_at + INTERVAL '5 seconds'",
                "A new HRM-013 quiesce cycle requires the current ADMIT grant set",
                "current_setting('dwp.auth.hrm013.tenant', true)",
                "'product-surfaces', 62",
                "'product-surfaces-v1.bundle-v62.generated.json', 'DRAFT', FALSE",
                "'HRIS_HRM_EXIT_RETURN_WORKBENCH', 2, 'hcm', 'APP.HCM', 62, 'OFF', 'HRM')",
                "'SVC-PEP-APR-002'", "'SVC-PEP-HRM-010'", "'SVC-AUT-HRM-013'",
                "'QUIESCING', 'CUTOVER', 'ABORTED'",
                "'JTI', 'REQUEST_NONCE'",
                "'ROOT_DELIVERY', 'DEPENDENT_READ', 'TERMINAL_CHILD'");
        assertThat(sql.split("ENABLE ROW LEVEL SECURITY", -1)).hasSize(6);
        assertThat(sql.split("FORCE ROW LEVEL SECURITY", -1)).hasSize(6);
        assertThat(sql.split("CREATE POLICY auth_hris_", -1)).hasSize(6);
        assertThat(sql.split("'" + FEATURE + "', 2", -1)).hasSize(5);
        for (String route : ROUTES) {
            assertThat(sql).contains(route);
        }

        assertThat(sql).doesNotContain(
                "INSERT INTO auth_hris_service_grant_sets",
                "INSERT INTO auth_hris_service_grant_bindings",
                "INSERT INTO auth_hris_service_authority_proof_replays",
                "INSERT INTO auth_hris_service_grant_quiesce_fences",
                "INSERT INTO auth_hris_service_grant_drain_activities",
                "INSERT INTO sys_product_feature_route_owners",
                "INSERT INTO auth_product_feature_activation_changes",
                "INSERT INTO com_tenant_feature_enrollment_changes",
                "INSERT INTO com_admin_scoped_duty_assignments",
                "INSERT INTO com_principal_resource_grants");
    }
}
