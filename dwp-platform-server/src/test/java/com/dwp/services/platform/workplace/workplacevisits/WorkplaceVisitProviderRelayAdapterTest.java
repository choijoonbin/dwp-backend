package com.dwp.services.platform.workplace.workplacevisits;

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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitDtos.ProviderKind;
import static com.dwp.services.platform.workplace.workplacevisits.WorkplaceVisitProviderPort.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class WorkplaceVisitProviderRelayAdapterTest {
    private static final String BASE = "http://localhost:8877";
    private static final String SECRET = "runtime-secret-value";
    private static final String PROVIDER = "VISITOR_TEST";
    private static final String GUEST_PROVIDER = "GUEST_VAULT";
    private static final String PROVIDER_REF = "secret-manager://workplace/visitor-test";
    private static final String GUEST_REF = "secret-manager://workplace/guest-vault";

    private MockRestServiceServer server;
    private WorkplaceVisitProviderRelayAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        WorkplaceProviderSecretOwner secretOwner = new WorkplaceProviderSecretOwner() {
            @Override public boolean supports(String opaqueReference) {
                return opaqueReference.startsWith("secret-manager://workplace/");
            }

            @Override public SecretLease lease(String opaqueReference) {
                return new SecretLease(SECRET.toCharArray());
            }
        };
        WorkplaceProviderHttpTransport transport = new WorkplaceProviderHttpTransport(
                builder, List.of(secretOwner), BASE, "localhost",
                PROVIDER + "," + GUEST_PROVIDER, true);
        adapter = new WorkplaceVisitProviderRelayAdapter(Optional.of(transport),
                PROVIDER + "@9=" + PROVIDER_REF + ","
                        + GUEST_PROVIDER + "@3=" + GUEST_REF,
                GUEST_PROVIDER, 3, "vault://guest/");
    }

    @Test
    void dispatchUsesStableOperationIdentityAndRuntimeCredentialLease() {
        ProviderOperation operation = operation();
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/visit-provider/operations:dispatch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DWP-Tenant-ID", "71"))
                .andExpect(header("Idempotency-Key", operation.operationId().toString()))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET))
                .andExpect(content().string(not(containsString(SECRET))))
                .andExpect(jsonPath("$.operationId").value(operation.operationId().toString()))
                .andExpect(jsonPath("$.providerConfigurationVersion").value(9))
                .andRespond(withSuccess(response(operation, "SUCCEEDED",
                        "evidence:visit-relay-71", null), MediaType.APPLICATION_JSON));

        ProviderOutcome outcome = adapter.dispatch(operation);

        assertThat(outcome.state()).isEqualTo(OutcomeState.SUCCEEDED);
        assertThat(outcome.evidenceReference()).isEqualTo("evidence:visit-relay-71");
        server.verify();
    }

    @Test
    void lookupAddressesOriginalOperationWithoutRepeatingMutation() {
        ProviderOperation operation = operation();
        server.expect(requestTo(BASE + "/internal/v1/workplace/visit-provider/operations/"
                        + operation.operationId()))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-DWP-Tenant-ID", "71"))
                .andRespond(withSuccess(response(operation, "RESULT_UNKNOWN",
                        null, "PROVIDER_PENDING"), MediaType.APPLICATION_JSON));

        ProviderOutcome outcome = adapter.lookup(operation);

        assertThat(outcome.state()).isEqualTo(OutcomeState.RESULT_UNKNOWN);
        assertThat(outcome.evidenceReference()).isEqualTo(
                "operation:" + operation.operationId());
        server.verify();
    }

    @Test
    void lookupWithoutOriginalCredentialRemainsUnknownAndNeverBecomesFailed() {
        ProviderOperation oldConfiguration = new ProviderOperation(
                operation().operationId(), operation().tenantId(), operation().visitId(),
                operation().providerKind(), operation().providerCode(), 8,
                operation().operationType());

        ProviderOutcome outcome = adapter.lookup(oldConfiguration);

        assertThat(outcome.state()).isEqualTo(OutcomeState.RESULT_UNKNOWN);
        assertThat(outcome.detailCode()).isEqualTo("PROVIDER_BINDING_UNAVAILABLE");
        server.verify();
    }

    @Test
    void lookupHttpRejectionIsNotAuthoritativeFailureEvidence() {
        ProviderOperation operation = operation();
        server.expect(requestTo(BASE + "/internal/v1/workplace/visit-provider/operations/"
                        + operation.operationId()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        ProviderOutcome outcome = adapter.lookup(operation);

        assertThat(outcome.state()).isEqualTo(OutcomeState.RESULT_UNKNOWN);
        assertThat(outcome.detailCode()).isEqualTo("PROVIDER_TRANSPORT_OUTCOME_UNKNOWN");
        server.verify();
    }

    @Test
    void lookupTerminalizesOnlyFromIdentityValidatedProviderEvidence() {
        ProviderOperation operation = operation();
        server.expect(requestTo(BASE + "/internal/v1/workplace/visit-provider/operations/"
                        + operation.operationId()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response(operation, "FAILED", null,
                        "PROVIDER_CONFIRMED_FAILED"), MediaType.APPLICATION_JSON));

        ProviderOutcome outcome = adapter.lookup(operation);

        assertThat(outcome.state()).isEqualTo(OutcomeState.FAILED);
        assertThat(outcome.detailCode()).isEqualTo("PROVIDER_CONFIRMED_FAILED");
        server.verify();
    }

    @Test
    void guestVerificationSendsOnlyOpaqueReferenceAndRejectsInvalidResponseIdentity() {
        String opaque = "vault://guest/opaque-71";
        UUID verificationId = UUID.nameUUIDFromBytes(
                ("workplace-guest-ref:71:" + opaque)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        server.expect(requestTo(BASE + "/internal/v1/workplace/guest-references:verify"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", verificationId.toString()))
                .andExpect(content().string(not(containsString("Masked Person"))))
                .andExpect(content().string(not(containsString("Private purpose"))))
                .andExpect(jsonPath("$.opaqueGuestReference").value(opaque))
                .andRespond(withSuccess("""
                        {"verificationId":"%s","tenantId":71,
                         "providerCode":"GUEST_VAULT","providerConfigurationVersion":3,
                         "valid":true,
                         "evidenceReference":"evidence:guest-ref-verified"}
                        """.formatted(verificationId), MediaType.APPLICATION_JSON));

        var verified = adapter.verify(new WorkplaceVisitGuestRefVerificationPort
                .VerificationRequest(71, opaque, "Masked Person", "Private purpose",
                Map.of("name", OffsetDateTime.parse("2026-10-01T00:00:00Z"))));

        assertThat(verified.valid()).isTrue();
        assertThat(verified.evidenceReference()).isEqualTo("evidence:guest-ref-verified");
        server.verify();
    }

    @Test
    void missingTransportAndUnknownProvidersFailClosed() {
        WorkplaceVisitProviderRelayAdapter unavailable =
                new WorkplaceVisitProviderRelayAdapter(Optional.empty(),
                        PROVIDER + "@9=" + PROVIDER_REF + ","
                                + GUEST_PROVIDER + "@3=" + GUEST_REF,
                        GUEST_PROVIDER, 3, "vault://guest/");

        assertThat(unavailable.supports(ProviderKind.VISITOR, PROVIDER)).isTrue();
        assertThat(unavailable.supports(ProviderKind.VISITOR, "NOT_ALLOWED")).isFalse();
        assertThat(unavailable.dispatch(operation()).state()).isEqualTo(OutcomeState.FAILED);
        assertThatThrownBy(() -> unavailable.verify(new WorkplaceVisitGuestRefVerificationPort
                .VerificationRequest(71, "vault://guest/opaque-71", "M**", "Business",
                Map.of("name", OffsetDateTime.parse("2026-10-01T00:00:00Z")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SECRET);

        ProviderOperation wrongConfiguration = new ProviderOperation(
                operation().operationId(), operation().tenantId(), operation().visitId(),
                operation().providerKind(), operation().providerCode(), 8,
                operation().operationType());
        assertThat(adapter.dispatch(wrongConfiguration).state()).isEqualTo(OutcomeState.FAILED);

        WorkplaceProviderHttpTransport missingSecretOwner = new WorkplaceProviderHttpTransport(
                RestClient.builder(), List.of(), BASE, "localhost", PROVIDER, true);
        WorkplaceVisitProviderRelayAdapter noSecretOwner =
                new WorkplaceVisitProviderRelayAdapter(Optional.of(missingSecretOwner),
                        PROVIDER + "@9=" + PROVIDER_REF, "", 0, "");
        assertThat(noSecretOwner.dispatch(operation()).state()).isEqualTo(OutcomeState.FAILED);
    }

    @Test
    void malformedProviderResponseBecomesRecoverableUnknownWithoutLeakingBody() {
        ProviderOperation operation = operation();
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/visit-provider/operations:dispatch"))
                .andRespond(withSuccess("""
                        {"operationId":"00000000-0000-0000-0000-000000000000",
                         "tenantId":71,"providerKind":"VISITOR",
                         "providerCode":"VISITOR_TEST",
                         "providerConfigurationVersion":9,"state":"SUCCEEDED",
                         "evidenceReference":"secret-that-must-not-propagate"}
                        """, MediaType.APPLICATION_JSON));

        ProviderOutcome outcome = adapter.dispatch(operation);

        assertThat(outcome.state()).isEqualTo(OutcomeState.RESULT_UNKNOWN);
        assertThat(outcome.detailCode()).isEqualTo("PROVIDER_RESPONSE_INVALID");
        assertThat(outcome.evidenceReference()).doesNotContain("secret-that-must-not-propagate");
        server.verify();
    }

    @Test
    void insecureNonLocalProviderBaseUrlIsRejectedAtStartup() {
        assertThatThrownBy(() -> new WorkplaceProviderHttpTransport(
                RestClient.builder(), List.of(), "http://provider.example", "provider.example",
                PROVIDER, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowlisted HTTPS");
    }

    private static ProviderOperation operation() {
        return new ProviderOperation(UUID.fromString("93fe0194-e197-4b47-995c-cb78495bbfc2"),
                71, UUID.fromString("e7055ce7-dba7-44d0-83bb-59667b5e7e22"),
                ProviderKind.VISITOR, PROVIDER, 9, "SEND_INVITATION");
    }

    private static String response(
            ProviderOperation operation, String state, String evidence, String detail) {
        return """
                {"operationId":"%s","tenantId":%d,"providerKind":"%s",
                 "providerCode":"%s",
                 "providerConfigurationVersion":%d,"state":"%s",
                 "evidenceReference":%s,"detailCode":%s}
                """.formatted(operation.operationId(), operation.tenantId(),
                operation.providerKind().name(), operation.providerCode(),
                operation.providerConfigurationVersion(), state,
                evidence == null ? "null" : "\"" + evidence + "\"",
                detail == null ? "null" : "\"" + detail + "\"");
    }
}
