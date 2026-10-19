package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV262MigrationContractTest {

    private static final String PATH =
            "db/migration/V262__declare_hris_r7_batch04_hrm011_v53_authorization_seed.sql";
    private static final String FEATURE = "HRIS_HRM_CONTRACT_COMPENSATION_WORKBENCH";
    private static final Set<String> ROUTES = Set.of(
            "route.hcm.operations.contracts-compensation-basis.page",
            "route.hcm.operations.contracts-compensation-basis.data",
            "route.hcm.operations.employment-contract-issue.action");

    @Test
    void declaresTheExactDraftV53DefaultOffFeatureWithoutActivationOrEnrollment()
            throws Exception {
        String sql = sql();
        assertThat(sql).contains(
                "'product-surfaces',",
                "53,",
                "'c655a850ae2ac01466edb37f2e1c69f9e269dd2a4a529c3a87b1610c0561a359'",
                "'product-surfaces-v1.bundle-v53.generated.json'",
                "'DRAFT'",
                "FALSE",
                "('HRIS_HRM_CONTRACT_COMPENSATION_WORKBENCH', 1, "
                        + "'hcm', 'APP.HCM', 53, 'OFF', 'HRM')");
        assertThat(sql).doesNotContain(
                "INSERT INTO auth_product_feature_activation_changes",
                "INSERT INTO com_tenant_feature_enrollment_changes");
    }

    @Test
    void bindsExactlyThreeHrm011OwnersAndBindings() throws Exception {
        String sql = sql();
        Matcher owners = Pattern.compile(
                "\\('((?:route\\.hcm\\.)[^']+)',\\s*'" + FEATURE + "'\\)")
                .matcher(sql);
        LinkedHashSet<String> ownerRoutes = new LinkedHashSet<>();
        while (owners.find()) ownerRoutes.add(owners.group(1));
        assertThat(ownerRoutes).containsExactlyInAnyOrderElementsOf(ROUTES);

        Matcher bindings = Pattern.compile(
                "\\('((?:route\\.hcm\\.)[^']+)',\\s*'" + FEATURE + "', 1,\\s*"
                        + "'hcm', 'APP\\.HCM', 53, '(PAGE|DATA|ACTION)', 'PRODUCT',\\s*"
                        + "'hcm\\.operations'\\)")
                .matcher(sql);
        LinkedHashSet<String> tuples = new LinkedHashSet<>();
        while (bindings.find()) {
            tuples.add(bindings.group(1) + '|' + bindings.group(2));
        }
        assertThat(tuples).hasSize(3);
        assertThat(tuples).extracting(value -> value.substring(0, value.indexOf('|')))
                .containsExactlyInAnyOrderElementsOf(ROUTES);
    }

    @Test
    void registersReachableIssueAuthorityWithoutAssigningTheRequiredDuty() throws Exception {
        assertThat(sql()).contains(
                "('ISSUE', 'Issue', 1, 1)",
                "('ACTION.HRIS_EMPLOYMENT_CONTRACT', 'ACTION',",
                "('HRIS_DUTY_CONTRACT_ISSUER', 'HRIS employment contract issuer',",
                "INSERT INTO sys_admin_responsibility_catalog",
                "INSERT INTO sys_admin_scoped_duty_catalog",
                "('HRIS_DUTY_CONTRACT_ISSUER', 'hcm', 'HR_ADMIN', 'APP.HCM',",
                "INSERT INTO sys_admin_scoped_duty_capabilities",
                "('HRIS_DUTY_CONTRACT_ISSUER', 'hcm.operations.contract.issue',",
                "INSERT INTO sys_admin_app_preset_catalog",
                "('HRIS_CONTRACT_ISSUER', 'hcm', 'APP.HCM',",
                "'APP_CONFIG_ADMIN', 'HIGH', 'ACTIVE', 1)",
                "('HRIS_CONTRACT_ISSUER', 'HRIS_DUTY_CONTRACT_ISSUER', 10)")
                .doesNotContain(
                        "INSERT INTO sys_tenant_role_permission_templates",
                        "INSERT INTO com_role_permissions",
                        "INSERT INTO com_admin_role_assignments",
                        "INSERT INTO com_admin_scoped_duty_assignments");
    }

    private String sql() throws Exception {
        try (var input = new ClassPathResource(PATH).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
