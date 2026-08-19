package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceSpatialGovernanceMigrationTest {

    @Test
    void v157DefinesTenantSafeHierarchyAndPublishedOnlyRevisionGovernance()
            throws IOException {
        String sql = new ClassPathResource(
                "db/migration/V157__add_workplace_spatial_governance.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE wp_campuses")
                .contains("FOREIGN KEY (tenant_id, campus_id)")
                .contains("CREATE TABLE wp_zones")
                .contains("FOREIGN KEY (tenant_id, floor_id, zone_id)")
                .contains("subject_group_ref UUID")
                .contains("scope_type VARCHAR(20) NOT NULL")
                .contains("scope_type = 'TENANT'")
                .contains("scope_type = 'CAMPUS'")
                .contains("scope_type = 'RESOURCE'")
                .contains("lifecycle_state IN ('DRAFT', 'REVIEW', 'PUBLISHED', 'ARCHIVED')")
                .contains("resource_version BIGINT NOT NULL")
                .contains("Only draft workplace floor-plan placements are mutable")
                .contains("published_plan_revision_id")
                .contains("CREATE TABLE wp_delegated_admin_scopes");
    }

    @Test
    void v157BackfillsEveryExistingBuildingResourceAndPublishedPlan() throws IOException {
        String sql = new ClassPathResource(
                "db/migration/V157__add_workplace_spatial_governance.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("UPDATE wp_sites site")
                .contains("ALTER COLUMN campus_id SET NOT NULL")
                .contains("UPDATE wp_resources resource")
                .contains("ALTER COLUMN zone_id SET NOT NULL")
                .contains("CREATE TRIGGER trg_wp_sites_compatibility_campus")
                .contains("CREATE TRIGGER trg_wp_floors_default_zone")
                .contains("CREATE TRIGGER trg_wp_resources_default_zone")
                .contains("'V157 baseline migration'")
                .contains("revision.lifecycle_state = 'PUBLISHED'");
    }

    @Test
    void tenantPolicyRemainsTheBaseInsteadOfBeingCopiedIntoAnOverride() throws IOException {
        String sql = new ClassPathResource(
                "db/migration/V157__add_workplace_spatial_governance.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).doesNotContain("INSERT INTO wp_policy_overrides");
    }

    @Test
    void v157ExposesStableExtensionPointsUsedByV159() throws IOException {
        String v157 = new ClassPathResource(
                "db/migration/V157__add_workplace_spatial_governance.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String v159 = new ClassPathResource(
                "db/migration/V159__govern_workplace_audit_and_privacy.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(v157)
                .contains("CONSTRAINT ck_wp_policy_overrides_patch")
                .contains("CREATE TRIGGER trg_wp_audit_events_immutable")
                .contains("sys_reject_wp_audit_mutation");
        assertThat(v159)
                .contains("DROP CONSTRAINT ck_wp_policy_overrides_patch")
                .contains("'bookingRetentionDays'")
                .contains("DROP TRIGGER IF EXISTS trg_wp_audit_events_immutable")
                .contains("wp_reject_audit_event_mutation");
    }
}
