package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformCanaryOpenApiBindingContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void everyGeneratedPublicAndServicePepBindingExistsInTheCheckedInOpenApiContracts()
            throws Exception {
        Path root = repositoryRoot();
        JsonNode publicApi = objectMapper.readTree(
                root.resolve("contracts/openapi/gateway-public.json").toFile());
        JsonNode serviceApi = objectMapper.readTree(
                root.resolve("contracts/openapi/platform.json").toFile());
        PlatformCanaryPepRegistry registry = new PlatformCanaryPepRegistry(objectMapper);

        assertThat(registry.bindingContracts()).hasSize(36).allSatisfy(binding -> {
            String method = binding.method().toLowerCase(Locale.ROOT);
            assertThat(publicApi.path("paths").path(binding.publicPath()).has(method))
                    .as("public %s %s", binding.method(), binding.publicPath())
                    .isTrue();
            assertThat(serviceApi.path("paths").path(binding.servicePath()).has(method))
                    .as("service %s %s", binding.method(), binding.servicePath())
                    .isTrue();
        });
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("contracts/openapi/platform.json"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Backend repository root could not be located.");
    }
}
