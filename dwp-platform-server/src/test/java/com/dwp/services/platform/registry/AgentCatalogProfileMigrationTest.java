package com.dwp.services.platform.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCatalogProfileMigrationTest {

    @Test
    void migrationPublishesVersionedProfilesForBothRuntimeAgents() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V230__publish_governed_agent_catalog_profiles.sql"));

        assertThat(sql)
                .contains("ADD COLUMN agent_catalog_profile JSONB")
                .contains("ck_adm_registry_entries_agent_catalog_profile")
                .contains("DWP_ASSISTANT")
                .contains("DWP_APPROVAL_EXPERT")
                .contains("ACTION.APPROVAL_TASK:VIEW")
                .contains("ADMIN.APPROVAL_OPERATIONS:VIEW")
                .contains("APP.MAIL:VIEW")
                .contains("\"humanConfirmationRequired\": true");

        var matcher = Pattern.compile("\\$profile\\$(.*?)\\$profile\\$::jsonb", Pattern.DOTALL)
                .matcher(sql);
        assertThat(matcher.find()).isTrue();
        JsonNode workplace = new ObjectMapper().readTree(matcher.group(1));
        assertThat(matcher.find()).isTrue();
        JsonNode approval = new ObjectMapper().readTree(matcher.group(1));
        assertThat(matcher.find()).isFalse();

        assertThat(workplace).isEqualTo(new ObjectMapper().readTree(
                GovernedAgentCatalogProfiles.jsonFor("DWP_ASSISTANT")));
        assertThat(approval).isEqualTo(new ObjectMapper().readTree(
                GovernedAgentCatalogProfiles.jsonFor("DWP_APPROVAL_EXPERT")));
    }
}
