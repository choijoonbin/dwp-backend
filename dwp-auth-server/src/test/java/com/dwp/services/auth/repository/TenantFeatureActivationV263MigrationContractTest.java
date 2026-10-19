package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV263MigrationContractTest {

    private static final String PATH =
            "db/migration/V263__declare_hris_r7_batch04_pay003_v54_authorization_seed.sql";
    private static final String FEATURE = "HRIS_PAYROLL_STATUTORY_INSURANCE";
    private static final Set<String> ROUTES = Set.of(
            "route.hcm.configuration.statutory.page",
            "route.hcm.configuration.statutory.data",
            "route.hcm.operations.pay-tax-social-insurance.page",
            "route.hcm.operations.pay-tax-social-insurance.data",
            "route.hcm.operations.country-pack-activate.action");

    @Test
    void declaresTheExactDraftV54DefaultOffFeatureWithoutActivationOrEnrollment()
            throws Exception {
        assertThat(sql()).contains(
                "'product-surfaces',",
                "54,",
                "'1a199b3ec84b6d6c16aaec57c9be0b09092c5f81e0c5566f893c4dbe0e0deed4'",
                "'product-surfaces-v1.bundle-v54.generated.json'",
                "'DRAFT'",
                "FALSE",
                "('HRIS_PAYROLL_STATUTORY_INSURANCE', 1, "
                        + "'hcm', 'APP.HCM', 54, 'OFF', 'PAY')")
                .doesNotContain(
                        "INSERT INTO auth_product_feature_activation_changes",
                        "INSERT INTO com_tenant_feature_enrollment_changes");
    }

    @Test
    void bindsExactlyFivePay003OwnersAndBindings() throws Exception {
        String sql = sql();
        Matcher owners = Pattern.compile(
                "\\('((?:route\\.hcm\\.)[^']+)',\\s*'" + FEATURE + "'\\)")
                .matcher(sql);
        LinkedHashSet<String> ownerRoutes = new LinkedHashSet<>();
        while (owners.find()) ownerRoutes.add(owners.group(1));
        assertThat(ownerRoutes).containsExactlyInAnyOrderElementsOf(ROUTES);

        Matcher bindings = Pattern.compile(
                "\\('((?:route\\.hcm\\.)[^']+)',\\s*'" + FEATURE + "', 1,\\s*"
                        + "'hcm', 'APP\\.HCM', 54, '(PAGE|DATA|ACTION)', 'PRODUCT',\\s*"
                        + "'(hcm\\.(?:management|operations))'\\)")
                .matcher(sql);
        LinkedHashSet<String> tuples = new LinkedHashSet<>();
        while (bindings.find()) {
            tuples.add(bindings.group(1) + '|' + bindings.group(2) + '|' + bindings.group(3));
        }
        assertThat(tuples).containsExactlyInAnyOrder(
                "route.hcm.configuration.statutory.page|PAGE|hcm.management",
                "route.hcm.configuration.statutory.data|DATA|hcm.management",
                "route.hcm.operations.pay-tax-social-insurance.page|PAGE|hcm.operations",
                "route.hcm.operations.pay-tax-social-insurance.data|DATA|hcm.operations",
                "route.hcm.operations.country-pack-activate.action|ACTION|hcm.operations");
    }

    @Test
    void registersReachableStatutoryAuthorityWithoutEnrollmentOrBroadGrant() throws Exception {
        assertThat(sql()).contains(
                "'ACTION.HRIS_STATUTORY_FILING', 'ACTION'",
                "'HRIS_DUTY_STATUTORY_OPERATOR', 'HRIS statutory operator'",
                "('HRIS_DUTY_STATUTORY_OPERATOR', 'hcm', 'PAYROLL_ADMIN', 'APP.HCM',",
                "('HRIS_DUTY_STATUTORY_OPERATOR', 'hcm.operations.statutory.execute',",
                "'ACTION.HRIS_STATUTORY_FILING', 'EXECUTE'",
                "('HRIS_STATUTORY_OPERATOR', 'hcm', 'APP.HCM',",
                "'APP_CONFIG_ADMIN', 'HIGH', 'ACTIVE', 1)",
                "('HRIS_STATUTORY_OPERATOR', 'HRIS_DUTY_STATUTORY_OPERATOR', 10)",
                "PACK_INSTALLER_NE_STATUTORY_APPROVER")
                .doesNotContain(
                        "INSERT INTO sys_tenant_role_permission_templates",
                        "INSERT INTO com_role_permissions",
                        "INSERT INTO com_admin_role_assignments",
                        "INSERT INTO com_admin_scoped_duty_assignments",
                        "INSERT INTO sys_admin_scoped_duty_conflicts");
    }

    private String sql() throws Exception {
        try (var input = new ClassPathResource(PATH).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
