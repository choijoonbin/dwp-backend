package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderHttpTransport;
import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderSecretOwner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceLineAdjustmentProvider.*;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class WorkplaceServiceHttpProviderAdapterTest {
    private static final String BASE = "http://localhost:8897";
    private static final String PROVIDER = "SERVICES_TEST";
    private static final String ADAPTER = "SERVICES_HTTP";
    private static final String REFERENCE = "secret-manager://workplace/services-test/v7";
    private static final String SECRET = "runtime-only-services-secret";
    private static final UUID OPERATION =
            UUID.fromString("77330b4e-46d8-4dbe-87a5-20388ec175b1");
    private static final UUID ORDER =
            UUID.fromString("a304db19-cd4d-4014-a19a-0fde98f4c49e");
    private static final UUID LINE =
            UUID.fromString("7df321fe-c4ab-4c8f-a9c7-4368d6a55279");

    private MockRestServiceServer server;
    private WorkplaceServiceHttpProviderAdapter adapter;

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
        adapter = new WorkplaceServiceHttpProviderAdapter(Optional.of(transport), ADAPTER);
    }

    @Test
    void verificationIsGetOnlyAndValidatesImmutableProviderIdentity() {
        UUID profile = UUID.randomUUID();
        var request = new WorkplaceServiceProviderVerifier.VerificationRequest(
                71, OPERATION, profile, PROVIDER, ADAPTER, REFERENCE, 7,
                List.of("EPHEMERAL_CREDENTIAL"));
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/service-provider/verifications/" + OPERATION))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-DWP-Tenant-ID", "71"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET))
                .andRespond(withSuccess(identity("""
                        ,"reportedState":"HEALTHY",
                         "evidenceReference":"evidence:service-provider-71",
                         "capabilityEvidence":["EPHEMERAL_CREDENTIAL"],
                         "sourceObservedAt":"2026-09-17T00:00:00Z",
                         "errorCode":null
                        """), MediaType.APPLICATION_JSON));

        var result = adapter.verify(request);

        assertThat(result.reportedState()).isEqualTo("HEALTHY");
        assertThat(result.capabilityEvidence()).containsExactly("EPHEMERAL_CREDENTIAL");
        server.verify();
    }

    @Test
    void issueUsesStableIdempotencyAndNeverSendsActorOrSecretReference() {
        var request = issueRequest();
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/service-provider/access-grants/"
                        + OPERATION + ":issue"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", OPERATION.toString()))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SECRET))
                .andExpect(content().string(not(containsString(SECRET))))
                .andExpect(content().string(not(containsString(REFERENCE))))
                .andExpect(content().string(not(containsString("99001"))))
                .andExpect(jsonPath("$.operationId").value(OPERATION.toString()))
                .andRespond(withSuccess(identity("""
                        ,"serviceOrderId":"%s","serviceOrderLineId":"%s",
                         "state":"ISSUED","providerGrantReference":"grant-71",
                         "oneTimeCredential":"one-time-credential-71",
                         "expiresAt":"2026-09-17T00:10:00Z"
                        """.formatted(ORDER, LINE)), MediaType.APPLICATION_JSON));

        var issued = adapter.issue(request);

        assertThat(issued.providerGrantReference()).isEqualTo("grant-71");
        assertThat(issued.oneTimeCredential()).isEqualTo("one-time-credential-71");
        server.verify();
    }

    @Test
    void issueRecoveryAndRevokeRecoveryUseGetOnlyStatusEndpoints() {
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/service-provider/access-grants/" + OPERATION))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(identity("""
                        ,"serviceOrderId":"%s","serviceOrderLineId":"%s",
                         "state":"ISSUED","providerGrantReference":"grant-71",
                         "oneTimeCredential":"one-time-credential-71",
                         "expiresAt":"2026-09-17T00:10:00Z"
                        """.formatted(ORDER, LINE)), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/service-provider/access-grants/"
                        + OPERATION + "/revocation"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(identity(",\"state\":\"REVOKED\""),
                        MediaType.APPLICATION_JSON));
        assertThat(adapter.lookupIssue(issueRequest()).providerGrantReference())
                .isEqualTo("grant-71");
        var revoked = adapter.lookupRevoke(revokeRequest());

        assertThat(revoked.revoked()).isTrue();
        assertThat(revoked.resultUnknown()).isFalse();
        server.verify();
    }

    @Test
    void cancellationMutationAndRecoveryPreserveOperationAndProviderSnapshot() {
        ProviderRequest request = adjustmentRequest();
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/service-provider/line-adjustments/"
                        + OPERATION + ":cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", OPERATION.toString()))
                .andExpect(jsonPath("$.providerConfigurationVersion").value(7))
                .andRespond(withSuccess(identity("""
                        ,"state":"SUCCEEDED","providerOperationReference":"refund-op-71",
                         "refundedAmount":100.00,
                         "refundReceiptReference":"refund-receipt-71"
                        """), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/service-provider/line-adjustments/" + OPERATION))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(identity("""
                        ,"state":"RESULT_UNKNOWN",
                         "providerOperationReference":"refund-op-71",
                         "refundedAmount":0,"detailCode":"PROVIDER_PENDING"
                         """), MediaType.APPLICATION_JSON));
        assertThat(adapter.cancel(request).state()).isEqualTo(OutcomeState.SUCCEEDED);
        assertThat(adapter.lookup(request, "refund-op-71").state())
                .isEqualTo(OutcomeState.RESULT_UNKNOWN);
        server.verify();
    }

    @Test
    void missingTransportSecretOrAdapterFailClosedWithoutLeakingOpaqueReferences() {
        var unavailable = new WorkplaceServiceHttpProviderAdapter(Optional.empty(), ADAPTER);
        assertThat(unavailable.supports(ADAPTER)).isTrue();
        assertThat(unavailable.supports("UNCONFIGURED")).isFalse();
        assertThat(unavailable.cancel(adjustmentRequest()).state())
                .isEqualTo(OutcomeState.NOT_CONFIGURED);
        assertThatThrownBy(() -> unavailable.issue(issueRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(REFERENCE)
                .hasMessageNotContaining(SECRET);

        WorkplaceProviderHttpTransport missingOwner = new WorkplaceProviderHttpTransport(
                RestClient.builder(), List.of(), BASE, "localhost", PROVIDER, true);
        var noSecretOwner = new WorkplaceServiceHttpProviderAdapter(
                Optional.of(missingOwner), ADAPTER);
        assertThat(noSecretOwner.cancel(adjustmentRequest()).state())
                .isEqualTo(OutcomeState.NOT_CONFIGURED);
    }

    @Test
    void malformedIdentityNeverPropagatesProviderBodyOrClaimsSuccess() {
        server.expect(requestTo(BASE
                        + "/internal/v1/workplace/service-provider/line-adjustments/"
                        + OPERATION + ":cancel"))
                .andRespond(withSuccess("""
                        {"operationId":"00000000-0000-0000-0000-000000000000",
                         "tenantId":71,"providerCode":"SERVICES_TEST",
                         "providerConfigurationVersion":7,"state":"SUCCEEDED",
                         "providerOperationReference":"private-provider-data",
                         "refundedAmount":100.00,
                         "refundReceiptReference":"private-provider-receipt"}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.cancel(adjustmentRequest()))
                .isInstanceOf(OutcomeUncertainException.class)
                .hasMessageNotContaining("private-provider-data")
                .hasMessageNotContaining("private-provider-receipt");
        server.verify();
    }

    private static WorkplaceServiceEphemeralCredentialProvider.IssueRequest issueRequest() {
        return new WorkplaceServiceEphemeralCredentialProvider.IssueRequest(
                71, OPERATION, ORDER, LINE, PROVIDER, ADAPTER, 7, REFERENCE, 99001,
                OffsetDateTime.parse("2026-09-17T00:15:00Z"));
    }

    private static WorkplaceServiceEphemeralCredentialProvider.RevokeRequest revokeRequest() {
        return new WorkplaceServiceEphemeralCredentialProvider.RevokeRequest(
                71, OPERATION, PROVIDER, ADAPTER, 7, REFERENCE, "grant-71");
    }

    private static ProviderRequest adjustmentRequest() {
        return new ProviderRequest(OPERATION, 71, ORDER, LINE, PROVIDER, 7, REFERENCE,
                1, new BigDecimal("100.00"), "KRW", "Requester approved cancellation");
    }

    private static String identity(String extra) {
        return """
                {"operationId":"%s","tenantId":71,"providerCode":"%s",
                 "providerConfigurationVersion":7%s}
                """.formatted(OPERATION, PROVIDER, extra);
    }
}
