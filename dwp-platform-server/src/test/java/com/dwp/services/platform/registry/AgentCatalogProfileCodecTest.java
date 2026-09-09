package com.dwp.services.platform.registry;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCatalogProfileCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentCatalogProfileCodec codec = new AgentCatalogProfileCodec(objectMapper);

    @Test
    void decodesPublishedProfilesWithTheirActualSourcePermissionContracts() throws Exception {
        RegistryDtos.AgentCatalogProfile workplace = codec.decode(objectMapper.readTree(
                GovernedAgentCatalogProfiles.jsonFor("DWP_ASSISTANT")));
        RegistryDtos.AgentCatalogProfile approval = codec.decode(objectMapper.readTree(
                GovernedAgentCatalogProfiles.jsonFor("DWP_APPROVAL_EXPERT")));

        assertThat(workplace.category()).isEqualTo(RegistryDtos.AgentCatalogCategory.GENERAL);
        assertThat(workplace.sources())
                .extracting(RegistryDtos.AgentCatalogSource::sourceSystem)
                .containsExactly("WORK_ITEM", "MAIL", "CALENDAR");
        assertThat(workplace.sources().get(1).requiredPermissions())
                .containsExactly("APP.MAIL:VIEW");

        assertThat(approval.category()).isEqualTo(RegistryDtos.AgentCatalogCategory.APPROVAL);
        assertThat(approval.sources())
                .extracting(RegistryDtos.AgentCatalogSource::sourceSystem)
                .containsExactly(
                        "APPROVAL_TASK",
                        "APPROVAL_REQUEST",
                        "APPROVAL_FORM",
                        "APPROVAL_OPERATION");
        assertThat(approval.sources().get(2).requiredPermissions())
                .containsExactly(
                        "ACTION.APPROVAL_REQUEST:VIEW",
                        "ACTION.APPROVAL_REQUEST:CREATE",
                        "ACTION.APPROVAL_REQUEST:MANAGE");
    }

    @Test
    void rejectsMalformedProfilesInsteadOfPublishingInventedFallbackMetadata() throws Exception {
        var malformed = objectMapper.readTree(GovernedAgentCatalogProfiles.jsonFor("DWP_ASSISTANT"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) malformed).put("schemaVersion", 99);

        assertThatThrownBy(() -> codec.decode(malformed)).isInstanceOf(BaseException.class);
    }
}
