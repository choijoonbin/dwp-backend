package com.dwp.services.auth.controller;

import com.dwp.services.auth.scim.ScimConnectorDtos;
import com.dwp.services.auth.scim.ScimCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScimConnectorAdminControllerTest {

    private final ScimCredentialService service = mock(ScimCredentialService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ScimConnectorAdminController(service))
            .build();

    @Test
    void createSecretResponseCanNeverBeStoredByBrowsersOrIntermediaries() throws Exception {
        when(service.create(eq(42L), eq(7L), eq("correlation-create"), any()))
                .thenReturn(new ScimConnectorDtos.CredentialIssued(null, "one-time-create-secret"));

        mvc.perform(post("/auth/admin/provisioning/scim/connectors")
                        .principal(identityAdmin())
                        .header("X-Tenant-ID", "42")
                        .header("X-Correlation-ID", "correlation-create")
                        .contentType("application/json")
                        .content("""
                                {
                                  "connectorKey":"entra-production",
                                  "displayName":"Entra production",
                                  "purpose":"Provision workforce identities",
                                  "allowedOperations":["USERS","GROUPS"],
                                  "credentialTtlDays":180
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "Thu, 01 Jan 1970 00:00:00 GMT"))
                .andExpect(jsonPath("$.data.bearerToken").value("one-time-create-secret"));
    }

    @Test
    void rotatedSecretResponseCanNeverBeStoredByBrowsersOrIntermediaries() throws Exception {
        UUID connectorId = UUID.fromString("47cf180a-e073-4fbf-804b-9e028be25a76");
        when(service.rotate(
                eq(42L), eq(7L), eq("correlation-rotate"), eq(connectorId), any()))
                .thenReturn(new ScimConnectorDtos.CredentialIssued(null, "one-time-rotated-secret"));

        mvc.perform(post(
                        "/auth/admin/provisioning/scim/connectors/{connectorId}/rotate-secret",
                        connectorId)
                        .principal(identityAdmin())
                        .header("X-Tenant-ID", "42")
                        .header("X-Correlation-ID", "correlation-rotate")
                        .contentType("application/json")
                        .content("""
                                {
                                  "expectedVersion":4,
                                  "credentialTtlDays":90,
                                  "explicitConfirmation":true,
                                  "reason":"Scheduled rotation"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "Thu, 01 Jan 1970 00:00:00 GMT"))
                .andExpect(jsonPath("$.data.bearerToken").value("one-time-rotated-secret"));
    }

    private UsernamePasswordAuthenticationToken identityAdmin() {
        Jwt jwt = Jwt.withTokenValue("identity-admin")
                .header("alg", "none")
                .subject("7")
                .claim("roles", List.of("IDENTITY_ADMIN"))
                .claim("tenant_id", 42L)
                .build();
        return new UsernamePasswordAuthenticationToken(jwt, null, List.of());
    }
}
