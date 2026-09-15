package com.dwp.services.platform.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovedHomeApplicationCatalogTest {

    @Test
    void javaCatalogExactlyMatchesTheSharedMachineReadableContract() throws Exception {
        JsonNode contract = new ObjectMapper().readTree(
                Path.of("../contracts/home-launchpad-contract.v1.json").toFile());
        List<ContractApplication> expected = new ArrayList<>();
        contract.path("groups").forEach(group -> group.path("apps").forEach(app ->
                expected.add(new ContractApplication(
                        group.path("groupKey").asText(),
                        app.path("sortOrder").asInt(),
                        app.path("appId").asText(),
                        app.path("resourceKey").asText(),
                        app.path("requiredPermissionCode").asText(),
                        app.path("route").asText(),
                        app.path("iconKey").asText(),
                        app.path("badgeSourceKey").isNull()
                                ? null : app.path("badgeSourceKey").asText()))));

        assertThat(ApprovedHomeApplicationCatalog.applications().stream()
                .map(app -> new ContractApplication(
                        app.groupKey(), app.sortOrder(), app.appKey(), app.resourceKey(),
                        app.requiredPermissionCode(), app.launchTarget(), app.iconKey(),
                        app.badgeSourceKey()))
                .toList()).containsExactlyElementsOf(expected);

        Map<String, String> aliases = new ObjectMapper().convertValue(
                contract.path("legacyResourceAliases"),
                new com.fasterxml.jackson.core.type.TypeReference<>() { });
        aliases.forEach((legacy, canonical) ->
                assertThat(ApprovedHomeApplicationCatalog.canonicalResourceKey(legacy))
                        .isEqualTo(canonical));
    }

    private record ContractApplication(
            String groupKey,
            int sortOrder,
            String appId,
            String resourceKey,
            String requiredPermissionCode,
            String route,
            String iconKey,
            String badgeSourceKey) {
    }
}
