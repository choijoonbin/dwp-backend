package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TenantFeatureActivationV259MigrationContractTest {

    private static final String PATH =
            "db/migration/V259__govern_product_feature_activation_and_tenant_enrollment.sql";

    @Test
    void declaresExactDefaultOffCatalogAndNoPositiveStateSeeds() throws Exception {
        String sql = sql();

        assertThat(sql).contains(
                "CREATE TABLE sys_product_feature_activation_policies",
                "CREATE TABLE sys_product_feature_route_owners",
                "CREATE TABLE sys_product_feature_route_bindings",
                "CREATE TABLE auth_product_feature_activation_changes",
                "CREATE TABLE com_tenant_feature_enrollment_changes",
                "CREATE TABLE auth_product_feature_control_events",
                "baseline_state VARCHAR(16) NOT NULL DEFAULT 'OFF'",
                "trg_product_feature_policy_immutable",
                "trg_product_feature_route_owner_immutable",
                "trg_product_feature_route_immutable",
                "trg_product_feature_event_immutable",
                "uk_product_feature_activation_open",
                "uk_product_feature_activation_active",
                "uk_tenant_feature_enrollment_open",
                "uk_tenant_feature_enrollment_active");
        assertThat(sql).contains(
                "PRIMARY KEY (feature_key, policy_version)",
                "PRIMARY KEY (route_contract_key, feature_key, policy_version)",
                "lifecycle_state VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'",
                "lifecycle_state IN ('ACTIVE', 'RETIRED')");
        assertThat(sql).doesNotContain("uk_product_feature_policy_active");
        assertThat(sql).doesNotContain(
                "INSERT INTO auth_product_feature_activation_changes",
                "INSERT INTO com_tenant_feature_enrollment_changes");

        Matcher policy = Pattern.compile(
                "\\('(?:HRIS_[A-Z_]+)', 1, 'hcm', 'APP\\.HCM', 50, 'OFF', '(?:HRM|SYS|PAY|TIM)'\\)")
                .matcher(sql);
        int policies = 0;
        while (policy.find()) policies++;
        assertThat(policies).isEqualTo(4);
    }

    @Test
    void bindsTheExactSixteenV50ProductRouteTuples() throws Exception {
        String sql = sql();
        Matcher matcher = Pattern.compile(
                "\\('((?:route\\.hcm\\.)[^']+)', 'HRIS_[A-Z_]+', 1, 'hcm', "
                        + "'APP\\.HCM', 50, '(PAGE|DATA|ACTION)', 'PRODUCT', "
                        + "'(hcm\\.(?:operations|management))'\\)")
                .matcher(sql);
        java.util.LinkedHashSet<String> tuples = new java.util.LinkedHashSet<>();
        int rows = 0;
        while (matcher.find()) {
            rows++;
            tuples.add(matcher.group(1) + '|' + matcher.group(2) + '|' + matcher.group(3));
        }
        assertThat(rows).isEqualTo(16);
        assertThat(tuples).hasSize(16);
        assertThat(tuples.stream().map(value -> value.substring(0, value.indexOf('|')))
                .collect(java.util.stream.Collectors.toSet())).isEqualTo(Set.of(
                "route.hcm.operations.organization-positions.page",
                "route.hcm.operations.organization-graph.data",
                "route.hcm.configuration.settings-operations.page",
                "route.hcm.operations.exception.data",
                "route.hcm.operations.exception-detail.data",
                "route.hcm.operations.payroll-population.page",
                "route.hcm.operations.payroll-support.data",
                "route.hcm.operations.payroll-run.action",
                "route.hcm.operations.time-command-center.page",
                "route.hcm.operations.time-daily-close.page",
                "route.hcm.operations.time-monthly-close.page",
                "route.hcm.operations.time-close-validate.action",
                "route.hcm.operations.time-close.action",
                "route.hcm.operations.time-reopen.action",
                "route.hcm.operations.time-payroll-handoff-create.action",
                "route.hcm.operations.time-payroll-handoff.data"));
        assertThat(sql).doesNotContain("route.hcm.time.command-receipt.data");
    }

    @Test
    void databaseConstraintsCarryThreePersonSodCasAndAdoptionPrerequisites() throws Exception {
        assertThat(sql()).contains(
                "approved_by <> requested_by",
                "activated_by <> requested_by AND activated_by <> approved_by",
                "version BIGINT NOT NULL DEFAULT 0",
                "lifecycle_state IN (",
                "'DRAFT', 'IN_REVIEW', 'APPROVED', 'ACTIVE', 'REVOKED', 'SUPERSEDED'",
                "tenant application adoption must be enabled before feature enrollment",
                "global feature activation must be active before tenant enrollment",
                "events are immutable");
    }

    private String sql() throws Exception {
        try (var input = new ClassPathResource(PATH).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
