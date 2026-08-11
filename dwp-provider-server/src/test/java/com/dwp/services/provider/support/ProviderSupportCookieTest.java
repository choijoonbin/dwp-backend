package com.dwp.services.provider.support;

import com.dwp.services.provider.ProviderDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderSupportCookieTest {

    @Test
    void issuesTheCredentialOnlyAsASecureHttpOnlyStrictCookie() {
        List<String> cookies = ProviderSupportCookie.issue(
                        "secret-token", Instant.now().plusSeconds(600), true)
                .stream().map(Object::toString).toList();

        assertThat(cookies).hasSize(3).allSatisfy(cookie -> assertThat(cookie)
                .contains("DWP_SUPPORT_SESSION=secret-token")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Max-Age="));
        assertThat(cookies)
                .anyMatch(cookie -> cookie.contains("Path=/api/platform/v1/admin/"))
                .anyMatch(cookie -> cookie.contains("Path=/api/people/v1"));
    }

    @Test
    void neverSerializesTheRawCredentialInTheApiPayload() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(600);
        ProviderDtos.SupportSessionSummary session = new ProviderDtos.SupportSessionSummary(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "acme",
                "Acme",
                7L,
                "Support operator",
                "ACTIVE",
                "Approved case",
                List.of("TENANT_CONFIGURATION_READ"),
                "STANDARD",
                "CASE-17",
                true,
                "L1",
                Instant.now(),
                expiresAt,
                null,
                null,
                0L);

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(
                new ProviderDtos.SupportSessionGrant(session, "never-return-this-token"));

        assertThat(json).contains("supportSessionId").doesNotContain("never-return-this-token");
    }
}
