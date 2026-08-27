package com.dwp.services.platform.home;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HomeExperiencePresentationMigrationTest {

    @Test
    void addsBoundedResponsiveFocalPointsAndIndependentContentAlignment() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V186__enhance_home_experience_presentation.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("background_focal_x INTEGER NOT NULL DEFAULT 50")
                .contains("background_focal_y INTEGER NOT NULL DEFAULT 50")
                .contains("mobile_background_focal_x INTEGER NOT NULL DEFAULT 50")
                .contains("mobile_background_focal_y INTEGER NOT NULL DEFAULT 50")
                .contains("content_alignment VARCHAR(16) NOT NULL DEFAULT 'LEFT'")
                .contains("WHEN 'LEFT' THEN 0")
                .contains("WHEN 'RIGHT' THEN 100")
                .contains("background_focal_x BETWEEN 0 AND 100")
                .contains("mobile_background_focal_y BETWEEN 0 AND 100")
                .contains("content_alignment IN ('LEFT', 'CENTER', 'RIGHT')")
                .contains("'EXPERIENCE_PUBLISHED'");
    }

    @Test
    void alignsLegacyContentInAForwardOnlyMigration() throws Exception {
        String sql = new ClassPathResource(
                "db/migration/V200__align_legacy_home_content_with_background_position.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql)
                .contains("WHEN 'LEFT' THEN 'RIGHT'")
                .contains("WHEN 'CENTER' THEN 'CENTER'")
                .contains("WHERE content_alignment = 'LEFT'")
                .contains("background_position IN ('LEFT', 'CENTER')");
    }
}
