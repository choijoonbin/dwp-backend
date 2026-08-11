package com.dwp.services.platform.codecatalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemCodeCatalogContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void runtimeProjectionDoesNotExposeAdministrativeMetadata() throws Exception {
        SystemCodeCatalogDtos.RuntimeCodeSet runtimeCodeSet =
                new SystemCodeCatalogDtos.RuntimeCodeSet(
                        "PLATFORM.PREFERENCE.COLOR_MODE",
                        1,
                        List.of(new SystemCodeCatalogDtos.RuntimeCodeValue(
                                "system", "시스템")));

        String json = objectMapper.writeValueAsString(runtimeCodeSet);

        assertThat(json)
                .contains("codeSetKey", "schemaVersion", "values", "system")
                .doesNotContain(
                        "ownerService",
                        "configurationLevel",
                        "validationSource",
                        "sourceReference",
                        "displayName",
                        "sortOrder",
                        "predefined",
                        "lifecycleState",
                        "behaviorMetadata",
                        "bindings");
    }

    @Test
    void providerSnapshotDeclaresItsGlobalReleaseManagedBoundary() throws Exception {
        SystemCodeCatalogDtos.CatalogSnapshot snapshot =
                new SystemCodeCatalogDtos.CatalogSnapshot(
                        "GLOBAL_PRODUCT", "RELEASE_MANAGED", List.of());

        String json = objectMapper.writeValueAsString(snapshot);

        assertThat(json)
                .contains("\"catalogScope\":\"GLOBAL_PRODUCT\"")
                .contains("\"changePolicy\":\"RELEASE_MANAGED\"")
                .contains("\"codeSets\":[]");
    }
}
