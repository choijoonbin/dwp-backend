package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV261MigrationContractTest {

    private static final String PATH =
            "db/migration/V261__declare_hris_r7_batch03_hrm009_v52_authorization_seed.sql";

    @Test
    void declaresTheExactDraftV52DefaultOffFeatureWithoutActivationOrEnrollment() throws Exception {
        String sql = sql();
        assertThat(sql).contains(
                "'product-surfaces',",
                "52,",
                "'b33fbf968e71f97b09fa497fdfad7d2f6854199170b79fd1602a0ea604977346'",
                "'product-surfaces-v1.bundle-v52.generated.json'",
                "'DRAFT'",
                "FALSE",
                "('HRIS_HRM_CERTIFICATE_WORKBENCH', 1, 'hcm', 'APP.HCM', 52, 'OFF', 'HRM')");
        assertThat(sql).doesNotContain(
                "INSERT INTO auth_product_feature_activation_changes",
                "INSERT INTO com_tenant_feature_enrollment_changes");
    }

    @Test
    void bindsExactlyFiveOwnersAndBindingsIncludingPersonalSurface() throws Exception {
        String sql = sql();
        Set<String> routes = Set.of(
                "route.hcm.personal.requests.page",
                "route.hcm.personal.requests.data",
                "route.hcm.operations.certificates-documents.page",
                "route.hcm.operations.certificates-documents.data",
                "route.hcm.operations.certificate-issue.action");
        Matcher owners = Pattern.compile(
                "\\('((?:route\\.hcm\\.)[^']+)', 'HRIS_HRM_CERTIFICATE_WORKBENCH'\\)")
                .matcher(sql);
        java.util.LinkedHashSet<String> ownerRoutes = new java.util.LinkedHashSet<>();
        while (owners.find()) ownerRoutes.add(owners.group(1));
        assertThat(ownerRoutes).isEqualTo(routes);

        Matcher bindings = Pattern.compile(
                "\\('((?:route\\.hcm\\.)[^']+)', 'HRIS_HRM_CERTIFICATE_WORKBENCH', 1,\\s*"
                        + "'hcm', 'APP\\.HCM', 52, '(PAGE|DATA|ACTION)', 'PRODUCT',\\s*"
                        + "'(hcm\\.(?:personal|operations))'\\)")
                .matcher(sql);
        java.util.LinkedHashSet<String> tuples = new java.util.LinkedHashSet<>();
        while (bindings.find()) {
            tuples.add(bindings.group(1) + '|' + bindings.group(2) + '|' + bindings.group(3));
        }
        assertThat(tuples).hasSize(5);
        assertThat(tuples).extracting(value -> value.substring(0, value.indexOf('|')))
                .containsExactlyInAnyOrderElementsOf(routes);
        assertThat(sql).contains(
                "'hcm.personal', 'hcm.team', 'hcm.operations', 'hcm.management'");
    }

    private String sql() throws Exception {
        try (var input = new ClassPathResource(PATH).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
