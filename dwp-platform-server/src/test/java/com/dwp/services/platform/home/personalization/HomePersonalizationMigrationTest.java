package com.dwp.services.platform.home.personalization;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HomePersonalizationMigrationTest {

    @Test
    void v171CreatesAdditiveRollbackSafeStoresAndBackfillsWithoutDeletingLegacyPreferences()
            throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V171__create_bounded_home_personalization.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("CREATE TABLE usr_home_views")
                .contains("CREATE TABLE usr_home_view_revisions")
                .contains("CREATE TABLE usr_home_view_device_layouts")
                .contains("CREATE TABLE usr_home_widget_configurations")
                .contains("CREATE TABLE adm_home_templates")
                .contains("CREATE TABLE usr_home_composer_proposals")
                .contains("created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP")
                .contains("ON CONFLICT (tenant_id, user_id, surface_key, view_key) DO NOTHING")
                .doesNotContain("ck_usr_home_composer_expiry")
                .doesNotContain("UPDATE adm_home_experiences")
                .doesNotContain("DROP TABLE usr_home_preferences")
                .doesNotContain("DELETE FROM usr_home_preferences");
    }

    @Test
    void v172PersistsTheExactUndoRevisionForSafeReplay() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V172__harden_home_composer_undo.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ADD COLUMN undone_revision_id UUID")
                .contains("REFERENCES usr_home_view_revisions(revision_id)")
                .contains("state <> 'UNDONE' OR undone_revision_id IS NOT NULL")
                .doesNotContain("usr_home_preferences");
    }

    @Test
    void v173AddsOwnerIntegrityReceiptsHistoryAndDropsTheTimezoneDependentCheck()
            throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V173__harden_home_personalization_integrity.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("DROP CONSTRAINT IF EXISTS ck_usr_home_composer_expiry")
                .doesNotContain("expires_at > CURRENT_TIMESTAMP")
                .contains("CREATE TABLE usr_home_command_receipts")
                .contains("CREATE TABLE adm_home_template_revisions")
                .contains("fk_usr_home_device_layout_owner")
                .contains("fk_usr_home_widget_configuration_owner")
                .contains("ADD COLUMN deleted_at TIMESTAMPTZ");
    }

    @Test
    void v174ConvergesTimestampsQuarantinesUnsafeBackfillsAndBoundsEveryJsonStore()
            throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V174__finalize_home_personalization_cutover_safety.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ALTER COLUMN created_at TYPE TIMESTAMPTZ")
                .contains("WHERE is_default AND deleted_at IS NULL")
                .contains("integrity_state VARCHAR(24)")
                .contains("restorable BOOLEAN")
                .contains("ck_usr_home_view_layout_size")
                .contains("ck_usr_home_view_revision_snapshot_size")
                .doesNotContain("usr_home_preferences\n   SET")
                .doesNotContain("DELETE FROM usr_home_preferences");
    }

    @Test
    void v175FitsConcreteIdempotencyReplayDtoNames() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V175__widen_home_command_receipt_response_type.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains("ALTER COLUMN response_type TYPE VARCHAR(160)");
        assertThat(HomeComposerDtos.ComposerProposalResponse.class.getName().length())
                .isLessThanOrEqualTo(160);
        assertThat(HomeTemplateDtos.HomeTemplateResponse.class.getName().length())
                .isLessThanOrEqualTo(160);
        assertThat(HomeViewDtos.DeleteHomeViewResponse.class.getName().length())
                .isLessThanOrEqualTo(160);
    }

    @Test
    void v176MirrorsResetCustomizationMetadataWithoutMutatingLegacyRows() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V176__preserve_home_view_customization_state.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("ADD COLUMN IF NOT EXISTS is_customized BOOLEAN")
                .contains("SET is_customized = legacy.is_customized")
                .contains("active.is_default")
                .contains("active.deleted_at IS NULL")
                .doesNotContain("UPDATE usr_home_preferences")
                .doesNotContain("DELETE FROM usr_home_preferences");
    }
}
