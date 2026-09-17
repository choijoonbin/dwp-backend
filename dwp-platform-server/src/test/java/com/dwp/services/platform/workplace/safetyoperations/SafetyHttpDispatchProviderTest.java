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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyDispatchProvider.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SafetyHttpDispatchProviderTest {
    private static final String BASE = "http://localhost:8890";
    private static final String PROVIDER = "SAFETY_TEST";
    private static final String REFERENCE = "secret-manager://workplace/safety-test/v7";
    private static final String SECRET = "runtime-only-safety-secret";
    private static final UUID ATTEMPT =
            UUID.fromString("a31695d4-e441-44df-bbab-dc607cc82497");
    private static final UUID INCIDENT =
            UUID.fromString("cdb2b6d1-14c0-4616-ac92-a35070a3f6d2");

    private MockRestServiceServer server;
    private SafetyHttpDispatchProvider provider;
    private ProviderContext context;

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
                "APP_PUSH=" + PROVIDER + "@7,SMS=" + PROVIDER + "@7");
        provider = new SafetyHttpDispatchProvider(Optional.of(transport), bindings);
        context = bindings.resolve(DeliveryChannel.APP_PUSH).orElseThrow();
    }

    @Test
    void dispatchUsesAttemptAsStableIdempotencyIdentityAndDoesNotLeakCredential() {
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/safety-provider/dispatches"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DWP-Tenant-ID", "71"))
                .andExpect(header("Idempotency-Key", ATTEMPT.toString()))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET))
                .andExpect(content().string(not(containsString(SECRET))))
                .andExpect(content().string(not(containsString(REFERENCE))))
                .andExpect(jsonPath("$.attemptId").value(ATTEMPT.toString()))
                .andExpect(jsonPath("$.providerConfigurationVersion").value(7))
                .andRespond(withSuccess(response("DELIVERED", "op-safety-71",
                        "DELIVERED", "evidence:safety-71"), MediaType.APPLICATION_JSON));

        DispatchResult result = provider.dispatch(request());

        assertThat(result.state()).isEqualTo(AttemptState.DELIVERED);
        assertThat(result.providerOperationReference()).isEqualTo("op-safety-71");
        assertThat(result.evidenceReference()).isEqualTo("evidence:safety-71");
        server.verify();
    }

    @Test
    void statusLookupIsReadOnlyAndAddressesOriginalAttempt() {
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/safety-provider/dispatches/" + ATTEMPT))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response("RESULT_UNKNOWN", "op-safety-71",
                        "PROVIDER_PENDING", null), MediaType.APPLICATION_JSON));

        DispatchResult result = provider.lookupStatus(new LookupRequest(
                ATTEMPT, 71, INCIDENT, DeliveryChannel.APP_PUSH, "op-safety-71", context));

        assertThat(result.state()).isEqualTo(AttemptState.RESULT_UNKNOWN);
        assertThat(result.providerOperationReference()).isEqualTo("op-safety-71");
        server.verify();
        server.reset();

        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/safety-provider/dispatches/" + ATTEMPT))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));
        DispatchResult unavailable = provider.lookupStatus(new LookupRequest(
                ATTEMPT, 71, INCIDENT, DeliveryChannel.APP_PUSH, "op-safety-71", context));
        assertThat(unavailable.state()).isEqualTo(AttemptState.RESULT_UNKNOWN);
        assertThat(unavailable.providerOperationReference()).isEqualTo("op-safety-71");
        server.verify();
    }

    @Test
    void connectorObservationRequiresMatchingProviderIdentityAndReturnsSourceClock() {
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/safety-provider/runtime/ebs"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"tenantId":71,"connectorKind":"EBS",
                         "providerCode":"SAFETY_TEST","providerConfigurationVersion":7,
                         "reportedState":"READY","evidenceReference":"evidence:ebs:71",
                         "sourceObservedAt":"2026-09-17T01:00:00Z",
                         "lastSuccessAt":"2026-09-17T00:59:30Z","errorCode":null}
                        """, MediaType.APPLICATION_JSON));

        SafetyHttpDispatchProvider.RuntimeObservation observation = provider.observe(
                71, ConnectorKind.EBS,
                new ProviderContext(DeliveryChannel.EBS, PROVIDER, 7, REFERENCE));

        assertThat(observation.reportedState()).isEqualTo(ProviderReportedState.READY);
        assertThat(observation.evidenceReference()).isEqualTo("evidence:ebs:71");
        assertThat(observation.sourceAt())
                .isEqualTo(OffsetDateTime.parse("2026-09-17T01:00:00Z"));
        server.verify();
    }

    @Test
    void definitiveRejectionFailsButTimeoutAndMalformedIdentityStayRecoverable() {
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/safety-provider/dispatches"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(provider.dispatch(request()).state()).isEqualTo(AttemptState.DELIVERY_FAILED);
        server.verify();
        server.reset();

        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/safety-provider/dispatches"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY));
        assertThat(provider.dispatch(request()).state()).isEqualTo(AttemptState.RESULT_UNKNOWN);
        server.verify();
        server.reset();

        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/safety-provider/dispatches"))
                .andRespond(withSuccess(responseFor(UUID.randomUUID(), "DELIVERED",
                        "op-other", "DELIVERED", "private-provider-evidence"),
                        MediaType.APPLICATION_JSON));
        DispatchResult malformed = provider.dispatch(request());
        assertThat(malformed.state()).isEqualTo(AttemptState.RESULT_UNKNOWN);
        assertThat(malformed.evidenceReference()).isNull();
        server.verify();
    }

    @Test
    void configurationParserAndMissingTransportFailClosed() {
        assertThatThrownBy(() -> new SafetyProviderRelayBindings(
                PROVIDER + "@7=plain-text-secret", "APP_PUSH=" + PROVIDER + "@7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("plain-text-secret");
        SafetyProviderRelayBindings bindings = new SafetyProviderRelayBindings(
                PROVIDER + "@7=" + REFERENCE, "APP_PUSH=" + PROVIDER + "@7");
        SafetyHttpDispatchProvider unavailable =
                new SafetyHttpDispatchProvider(Optional.empty(), bindings);
        assertThat(unavailable.ready(context)).isFalse();
        assertThatThrownBy(() -> unavailable.dispatch(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(REFERENCE);
    }

    private DispatchRequest request() {
        return new DispatchRequest(ATTEMPT, 71, INCIDENT, DeliveryChannel.APP_PUSH,
                "a".repeat(64), 701L, Severity.CRITICAL, "Evacuate now",
                "Use the north exit", context);
    }

    private static String response(String state, String operation, String code, String evidence) {
        return responseFor(ATTEMPT, state, operation, code, evidence);
    }

    private static String responseFor(
            UUID attempt, String state, String operation, String code, String evidence) {
        return """
                {"attemptId":"%s","tenantId":71,"channel":"APP_PUSH",
                 "providerCode":"SAFETY_TEST","providerConfigurationVersion":7,
                 "state":"%s","providerOperationReference":%s,
                 "resultCode":%s,"evidenceReference":%s}
                """.formatted(attempt, state, json(operation), json(code), json(evidence));
    }

    private static String json(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
