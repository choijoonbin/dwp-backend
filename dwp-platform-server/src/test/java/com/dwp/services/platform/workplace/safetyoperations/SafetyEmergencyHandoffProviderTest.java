package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderHttpTransport;
import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderSecretOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.CommandState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SafetyEmergencyHandoffProviderTest {
    private static final String BASE = "http://localhost:8890";
    private static final String PROVIDER = "EMERGENCY_RELAY";
    private static final String REFERENCE = "secret-manager://workplace/emergency-relay/v7";
    private static final String SECRET = "runtime-only-emergency-secret";
    private static final UUID HANDOFF =
            UUID.fromString("20700000-0000-4000-8000-000000000001");
    private static final UUID INCIDENT =
            UUID.fromString("20700000-0000-4000-8000-000000000002");
    private static final UUID CONTACT =
            UUID.fromString("20700000-0000-4000-8000-000000000003");

    private MockRestServiceServer server;
    private SafetyEmergencyHandoffProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        WorkplaceProviderSecretOwner owner = new WorkplaceProviderSecretOwner() {
            @Override public boolean supports(String opaqueReference) {
                return REFERENCE.equals(opaqueReference);
            }

            @Override public SecretLease lease(String opaqueReference) {
                return new SecretLease(SECRET.toCharArray());
            }
        };
        WorkplaceProviderHttpTransport transport = new WorkplaceProviderHttpTransport(
                builder, List.of(owner), BASE, "localhost", PROVIDER, true);
        SafetyProviderRelayBindings bindings = new SafetyProviderRelayBindings(
                PROVIDER + "@7=" + REFERENCE,
                "APP_PUSH=" + PROVIDER + "@7");
        provider = new SafetyEmergencyHandoffProvider(Optional.of(transport), bindings);
    }

    @Test
    void handoffUsesStableIdentityAndOmitsPersonalDataAndCredentialMaterial() {
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/safety-provider/emergency-handoffs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DWP-Tenant-ID", "71"))
                .andExpect(header("Idempotency-Key", HANDOFF.toString()))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET))
                .andExpect(content().string(not(containsString(SECRET))))
                .andExpect(content().string(not(containsString(REFERENCE))))
                .andExpect(content().string(not(containsString("reason"))))
                .andExpect(content().string(not(containsString("phone"))))
                .andRespond(withSuccess(response("SUCCEEDED", "provider-operation-71",
                        "HANDOFF_ACCEPTED", "provider-evidence-71"),
                        MediaType.APPLICATION_JSON));

        SafetyEmergencyHandoffProvider.Result result = provider.handoff(
                HANDOFF, 71, INCIDENT, CONTACT, PROVIDER, 7);

        assertThat(result.state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(result.providerOperationReference()).isEqualTo("provider-operation-71");
        assertThat(result.providerEvidenceReference()).isEqualTo("provider-evidence-71");
        server.verify();
    }

    @Test
    void reconciliationUsesGetOnlyAndMalformedIdentityRemainsUnknown() {
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/safety-provider/emergency-handoffs/" + HANDOFF))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(responseFor(UUID.randomUUID(), "SUCCEEDED",
                        "different-operation", "HANDOFF_ACCEPTED", "private-evidence"),
                        MediaType.APPLICATION_JSON));

        SafetyEmergencyHandoffProvider.Result result = provider.lookup(
                HANDOFF, 71, PROVIDER, 7, "provider-operation-71");

        assertThat(result.state()).isEqualTo(CommandState.RESULT_UNKNOWN);
        assertThat(result.providerEvidenceReference()).isNull();
        server.verify();
    }

    private static String response(String state, String operation, String code, String evidence) {
        return responseFor(HANDOFF, state, operation, code, evidence);
    }

    private static String responseFor(
            UUID handoff, String state, String operation, String code, String evidence) {
        return """
                {"handoffId":"%s","tenantId":71,
                 "providerCode":"%s","providerConfigurationVersion":7,
                 "state":"%s","providerOperationReference":%s,
                 "resultCode":%s,"evidenceReference":%s}
                """.formatted(handoff, PROVIDER, state, json(operation), json(code), json(evidence));
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
