package com.dwp.services.people.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class HcmProviderSupportBoundaryContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void workforceReadSupportIsLegalEntityBoundAndMustNotBecomeTenantWide() throws Exception {
        JsonNode fixtures = objectMapper.readTree(repositoryRoot()
                .resolve("contracts/product-authorization/pilot-fixtures.v1.generated.json")
                .toFile());
        JsonNode session = byKey(
                fixtures.path("catalogs").path("supportSessions"),
                "SUPPORT_HCM_READ_1");
        JsonNode scope = byKey(
                fixtures.path("catalogs").path("scopes"),
                session.path("scopeRef").asText());

        assertThat(session.path("supportScope").asText()).isEqualTo("WORKFORCE_READ");
        assertThat(session.path("readOnly").asBoolean()).isTrue();
        assertThat(scope.path("kind").asText()).isEqualTo("LEGAL_ENTITY");
        assertThat(scope.path("resourceRef").asText()).isEqualTo("LEGAL_A:WORKFORCE");
        assertThat(scope.path("readOnly").asBoolean()).isTrue();
    }

    private JsonNode byKey(JsonNode values, String key) {
        return StreamSupport.stream(values.spliterator(), false)
                .filter(value -> key.equals(value.path("key").asText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing pilot fixture: " + key));
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(
                    "contracts/product-authorization/pilot-fixtures.v1.generated.json"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Backend repository root could not be located.");
    }
}
