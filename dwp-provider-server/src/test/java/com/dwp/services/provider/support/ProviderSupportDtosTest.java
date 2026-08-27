package com.dwp.services.provider.support;

import com.dwp.services.provider.ProviderDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderSupportDtosTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void browserProjectionOmitsAuthRoutingIdentity() {
        JsonNode browser = objectMapper.valueToTree(
                new ProviderSupportDtos.BrowserSessionContext(
                        UUID.randomUUID(), UUID.randomUUID(), "acme", "Acme",
                        "production", "ap-northeast-2",
                        List.of("TENANT_EXPERIENCE_PREVIEW"), "STANDARD",
                        Instant.parse("2030-01-01T00:00:00Z"), 4));

        assertThat(browser.has("authTenantId")).isFalse();
        assertThat(browser.has("providerTenantId")).isFalse();
        assertThat(browser.path("tenantId").asText()).isNotBlank();
    }

    @Test
    void internalProjectionCarriesOnlyTheVerifiedGatewayRoutingIdentity() {
        UUID providerTenantId = UUID.randomUUID();
        JsonNode verified = objectMapper.valueToTree(
                new ProviderSupportDtos.VerifiedSessionContext(
                        UUID.randomUUID(), providerTenantId, 42L, "acme", "Acme",
                        List.of("TENANT_EXPERIENCE_PREVIEW"), "STANDARD",
                        Instant.parse("2030-01-01T00:00:00Z"), 4));

        assertThat(verified.path("providerTenantId").asText())
                .isEqualTo(providerTenantId.toString());
        assertThat(verified.path("authTenantId").asLong()).isEqualTo(42L);
        assertThat(verified.has("environmentKey")).isFalse();
        assertThat(verified.has("dataRegion")).isFalse();
    }

    @Test
    void supportRequestRejectsNullScopeElementsAtTheApiBoundary() {
        var request = new ProviderDtos.CreateSupportAccessRequest(
                UUID.randomUUID(), Collections.singletonList(null), 15,
                "Investigate the tenant preview", "CUSTOMER-APPROVAL-42",
                "request-00000042");

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("scopes[0].<list element>");
    }
}
