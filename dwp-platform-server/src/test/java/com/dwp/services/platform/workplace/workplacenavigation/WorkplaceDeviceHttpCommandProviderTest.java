package com.dwp.services.platform.workplace.workplacenavigation;

import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderHttpTransport;
import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderSecretOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceDeviceCommandProvider.*;
import static com.dwp.services.platform.workplace.workplacenavigation.WorkplaceNavigationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WorkplaceDeviceHttpCommandProviderTest {
    private static final String BASE = "http://localhost:8878";
    private static final String PROVIDER = "MDM_TEST";
    private static final String REFERENCE = "secret-manager://workplace/mdm-test-v9";
    private static final String SECRET = "runtime-device-secret";

    private MockRestServiceServer server;
    private WorkplaceDeviceHttpCommandProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        WorkplaceProviderSecretOwner owner = new WorkplaceProviderSecretOwner() {
            @Override public boolean supports(String opaqueReference) {
                return opaqueReference.startsWith("secret-manager://workplace/");
            }

            @Override public SecretLease lease(String opaqueReference) {
                return new SecretLease(SECRET.toCharArray());
            }
        };
        WorkplaceProviderHttpTransport transport = new WorkplaceProviderHttpTransport(
                builder, List.of(owner), BASE, "localhost", PROVIDER, true);
        provider = new WorkplaceDeviceHttpCommandProvider(Optional.of(transport),
                PROVIDER + "@8=secret-manager://workplace/mdm-test-v8,"
                        + PROVIDER + "@9=" + REFERENCE);
    }

    @Test
    void dispatchUsesStableCommandIdAndLeasesCredentialOutsidePayload() {
        ProviderCommand command = command(9);
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/device-provider/commands:dispatch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DWP-Tenant-ID", "71"))
                .andExpect(header("Idempotency-Key", command.commandId().toString()))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET))
                .andExpect(content().string(not(containsString(SECRET))))
                .andExpect(content().string(not(containsString(REFERENCE))))
                .andExpect(jsonPath("$.commandId").value(command.commandId().toString()))
                .andExpect(jsonPath("$.providerConfigurationVersion").value(9))
                .andRespond(withSuccess(response(command, "SUCCEEDED", "operation:71", "SYNCED"),
                        MediaType.APPLICATION_JSON));

        ProviderCommandOutcome outcome = provider.execute(command);

        assertThat(outcome.state()).isEqualTo(OutcomeState.SUCCEEDED);
        assertThat(outcome.providerOperationReference()).isEqualTo("operation:71");
        server.verify();
    }

    @Test
    void lookupUsesGetAndTheOriginalConfigurationSnapshot() {
        ProviderCommand command = command(8);
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/device-provider/commands/"
                        + command.commandId()))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-DWP-Tenant-ID", "71"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET))
                .andRespond(withSuccess(response(command, "RESULT_UNKNOWN", null, "PENDING"),
                        MediaType.APPLICATION_JSON));

        ProviderCommandOutcome outcome = provider.status(command);

        assertThat(outcome.state()).isEqualTo(OutcomeState.RESULT_UNKNOWN);
        assertThat(outcome.providerOperationReference())
                .isEqualTo("command:" + command.commandId());
        server.verify();
    }

    @Test
    void runtimeObservationRequiresMatchingTenantProviderVersionAndCapability() {
        ProviderBinding binding = provider.binding(PROVIDER, 9).orElseThrow();
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/device-provider/runtime/mdm"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"tenantId":71,"capability":"MDM","providerCode":"MDM_TEST",
                         "providerConfigurationVersion":9,"reportedState":"HEALTHY",
                         "evidenceReference":"evidence:mdm-runtime-71",
                         "sourceAt":"2026-09-16T11:59:58Z",
                         "lastSuccessAt":"2026-09-16T11:59:50Z","errorCode":null}
                        """, MediaType.APPLICATION_JSON));

        Optional<ProviderRuntimeObservation> observation = provider.observe(
                new ProviderObservationCommand(71, ProviderCapability.MDM, binding));

        assertThat(observation).isPresent();
        assertThat(observation.orElseThrow().state()).isEqualTo(ProviderReportedState.HEALTHY);
        assertThat(observation.orElseThrow().evidenceReference())
                .isEqualTo("evidence:mdm-runtime-71");
        server.verify();
    }

    @Test
    void malformedIdentityBecomesUnknownWithoutLeakingProviderBody() {
        ProviderCommand command = command(9);
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/device-provider/commands:dispatch"))
                .andRespond(withSuccess("""
                        {"commandId":"00000000-0000-0000-0000-000000000000",
                         "tenantId":71,"deviceId":"00000000-0000-0000-0000-000000000000",
                         "commandType":"FORCE_SYNC","providerCode":"MDM_TEST",
                         "providerConfigurationVersion":9,"state":"SUCCEEDED",
                         "providerOperationReference":"secret-body-must-not-propagate"}
                        """, MediaType.APPLICATION_JSON));

        ProviderCommandOutcome outcome = provider.execute(command);

        assertThat(outcome.state()).isEqualTo(OutcomeState.RESULT_UNKNOWN);
        assertThat(outcome.resultCode()).isEqualTo("PROVIDER_RESPONSE_INVALID");
        assertThat(outcome.providerOperationReference())
                .doesNotContain("secret-body-must-not-propagate");
        server.verify();
    }

    @Test
    void missingRelayOrUnknownConfigurationFailsClosed() {
        WorkplaceDeviceHttpCommandProvider unavailable =
                new WorkplaceDeviceHttpCommandProvider(Optional.empty(), PROVIDER + "@9=" + REFERENCE);
        ProviderCommand command = command(9);

        assertThat(unavailable.binding(PROVIDER, 9)).isPresent();
        assertThat(unavailable.ready(command.binding())).isFalse();
        assertThat(unavailable.execute(command).state()).isEqualTo(OutcomeState.FAILED);
        assertThat(unavailable.status(command).state()).isEqualTo(OutcomeState.RESULT_UNKNOWN);
        assertThat(provider.binding(PROVIDER, 10)).isEmpty();
    }

    @Test
    void lookupChannelRejectionsNeverClaimTheOriginalMutationFailed() {
        ProviderCommand command = command(9);
        List<HttpStatus> rejectedLookups = List.of(
                HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        rejectedLookups.forEach(status -> server.expect(requestTo(BASE
                        + "/internal/v1/workplace/device-provider/commands/"
                        + command.commandId()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(status)));

        rejectedLookups.forEach(ignored -> {
            ProviderCommandOutcome outcome = provider.status(command);
            assertThat(outcome.state()).isEqualTo(OutcomeState.RESULT_UNKNOWN);
            assertThat(outcome.resultCode()).isEqualTo("PROVIDER_STATUS_UNAVAILABLE");
            assertThat(outcome.providerOperationReference())
                    .isEqualTo("command:" + command.commandId());
        });
        server.verify();
    }

    @Test
    void lookupAcceptsOnlyAnIdentityBoundProviderBodyAsAuthoritativeFailure() {
        ProviderCommand command = command(9);
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/device-provider/commands/"
                        + command.commandId()))
                .andRespond(withSuccess(response(
                        command, "FAILED", "operation:rejected", "POLICY_REJECTED"),
                        MediaType.APPLICATION_JSON));

        ProviderCommandOutcome outcome = provider.status(command);

        assertThat(outcome.state()).isEqualTo(OutcomeState.FAILED);
        assertThat(outcome.resultCode()).isEqualTo("POLICY_REJECTED");
        server.verify();
    }

    private ProviderCommand command(long version) {
        ProviderBinding binding = provider.binding(PROVIDER, version).orElseThrow();
        return new ProviderCommand(71,
                UUID.fromString("b1fb5063-ae86-45f8-9dab-b612b5aa1944"),
                UUID.fromString("762c74a2-e210-4ef6-bc04-c8dc78454aca"),
                DeviceCommandType.FORCE_SYNC, Map.of(), "correlation-71", binding);
    }

    private static String response(
            ProviderCommand command, String state, String reference, String resultCode) {
        return """
                {"commandId":"%s","tenantId":%d,"deviceId":"%s",
                 "commandType":"%s","providerCode":"%s",
                 "providerConfigurationVersion":%d,"state":"%s",
                 "providerOperationReference":%s,"resultCode":%s}
                """.formatted(command.commandId(), command.tenantId(), command.deviceId(),
                command.type(), command.binding().providerCode(),
                command.binding().configurationVersion(), state,
                reference == null ? "null" : "\"" + reference + "\"",
                resultCode == null ? "null" : "\"" + resultCode + "\"");
    }
}
