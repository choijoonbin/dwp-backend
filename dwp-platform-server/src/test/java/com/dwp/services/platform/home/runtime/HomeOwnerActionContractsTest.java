package com.dwp.services.platform.home.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HomeOwnerActionContractsTest {

    @Test
    void codeContractMatchesTheVersionedOwnerManifestAndStaysDisabledByDefault()
            throws Exception {
        JsonNode action = new ObjectMapper().readTree(Path.of(
                "../contracts/home-runtime/platform-owner-actions.v1.json").toFile())
                .path("actions").get(0);
        HomeOwnerActionContracts.Contract contract =
                HomeOwnerActionContracts.DISMISS_RECOMMENDATION;

        assertThat(action.path("contractId").asText()).isEqualTo(contract.contractId());
        assertThat(action.path("definitionKey").asText()).isEqualTo(contract.definitionKey());
        assertThat(action.path("definitionVersion").asText())
                .isEqualTo(contract.definitionVersion());
        assertThat(action.path("definitionManifestHash").asText())
                .isEqualTo(contract.definitionManifestHash());
        assertThat(action.path("actionId").asText()).isEqualTo(contract.actionId());
        assertThat(action.path("commandKey").asText()).isEqualTo(contract.commandKey());
        assertThat(action.path("requiredAuthority").asText())
                .isEqualTo(contract.requiredAuthority());
        assertThat(action.path("enabledByDefault").asBoolean()).isFalse();
    }
}
