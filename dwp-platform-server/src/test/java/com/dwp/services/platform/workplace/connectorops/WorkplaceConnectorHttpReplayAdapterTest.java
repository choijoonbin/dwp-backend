package com.dwp.services.platform.workplace.connectorops;

import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderHttpTransport;
import com.dwp.services.platform.workplace.providerintegration.WorkplaceProviderSecretOwner;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.*;
import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorReplayAdapter.ProviderContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class WorkplaceConnectorHttpReplayAdapterTest {
    private static final String REFERENCE = "secret-manager://workplace/msgraph/calendar";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-16T03:00:00Z");

    @Test
    void realHttpContractCoversPreviewDispatchLookupAndRuntimeObservation() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WorkplaceProviderSecretOwner owner = new TestSecretOwner();
        WorkplaceProviderHttpTransport transport = new WorkplaceProviderHttpTransport(
                builder, List.of(owner), "http://localhost:18082", "localhost", "msgraph", true);
        WorkplaceConnectorHttpReplayAdapter adapter = new WorkplaceConnectorHttpReplayAdapter(transport);
        ProviderContext context = new ProviderContext(77, ConnectorKind.CALENDAR, "msgraph", REFERENCE);
        UUID jobId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();

        server.expect(once(), requestTo("http://localhost:18082/v1/workplace-connectors/msgraph/calendar/replays:preview"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DWP-Tenant-ID", "77"))
                .andExpect(header("Authorization", "Bearer sandbox-token-123456789"))
                .andExpect(header("Idempotency-Key", org.hamcrest.Matchers.startsWith("preview:")))
                .andRespond(withSuccess("""
                        {"estimatedRecords":12,"limitations":[]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:18082/v1/workplace-connectors/msgraph/calendar/replays"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", jobId.toString()))
                .andRespond(withSuccess("""
                        {"state":"RUNNING","providerOperationReference":"op-77","resultCode":"ACCEPTED"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:18082/v1/workplace-connectors/msgraph/calendar/replays/" + jobId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"state":"SUCCEEDED","providerOperationReference":"op-77","resultCode":"COMPLETED"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("http://localhost:18082/v1/workplace-connectors/msgraph/calendar/runtime"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"reportedState":"HEALTHY","capabilities":["HEALTH","REPLAY"],
                         "sourceObservedAt":"2026-09-16T02:59:58Z",
                         "lastSuccessAt":"2026-09-16T02:59:57Z","lagSeconds":2,
                         "checkpointReference":"opaque-checkpoint","retryQueueDepth":0,
                         "deadLetterQueueDepth":0,"errorCode":null,
                         "adapterId":"calendar-relay","adapterVersion":"2.0.0",
                         "payloadFingerprint":"sha256:1234567890abcdef"}
                        """, MediaType.APPLICATION_JSON));
        assertThat(adapter.preview(context, NOW.minusHours(1), NOW, true, 20))
                .isEqualTo(new WorkplaceConnectorReplayAdapter.PreviewEstimate(12, List.of()));
        assertThat(adapter.dispatch(context, jobId, previewId, NOW.minusHours(1), NOW, true, 20))
                .isEqualTo(new WorkplaceConnectorReplayAdapter.DispatchResult(
                        ReplayState.RUNNING, "op-77", "ACCEPTED"));
        assertThat(adapter.lookup(context, jobId, "op-77").state()).isEqualTo(ReplayState.SUCCEEDED);
        WorkplaceConnectorReplayAdapter.RuntimeObservation observation = adapter.observe(context);
        assertThat(observation.state()).isEqualTo(ProviderReportedState.HEALTHY);
        assertThat(observation.capabilities()).containsExactly(Capability.HEALTH, Capability.REPLAY);
        server.verify();
    }

    @Test
    void providerCredentialReferenceAndAllowlistFailClosed() {
        RestClient.Builder builder = RestClient.builder();
        WorkplaceProviderHttpTransport transport = new WorkplaceProviderHttpTransport(
                builder, List.of(new TestSecretOwner()), "http://localhost:18082",
                "localhost", "msgraph", true);
        WorkplaceConnectorHttpReplayAdapter adapter = new WorkplaceConnectorHttpReplayAdapter(transport);

        assertThat(adapter.ready(new ProviderContext(
                77, ConnectorKind.CALENDAR, "unapproved", REFERENCE))).isFalse();
        assertThat(adapter.ready(new ProviderContext(
                77, ConnectorKind.CALENDAR, "msgraph", "env://RAW_SECRET"))).isFalse();
        assertThat(adapter.ready(new ProviderContext(
                77, ConnectorKind.CALENDAR, "msgraph", REFERENCE))).isTrue();
    }

    @Test
    void providerLookupErrorIsPropagatedOnlyToTheSanitizingCoordinatorBoundary() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WorkplaceProviderHttpTransport transport = new WorkplaceProviderHttpTransport(
                builder, List.of(new TestSecretOwner()), "http://localhost:18082",
                "localhost", "msgraph", true);
        WorkplaceConnectorHttpReplayAdapter adapter = new WorkplaceConnectorHttpReplayAdapter(transport);
        ProviderContext context = new ProviderContext(
                77, ConnectorKind.CALENDAR, "msgraph", REFERENCE);
        UUID jobId = UUID.randomUUID();
        server.expect(once(), requestTo(
                        "http://localhost:18082/v1/workplace-connectors/msgraph/calendar/replays/"
                                + jobId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("person@example.invalid bearer-secret-from-provider"));

        assertThatThrownBy(() -> adapter.lookup(context, jobId, null))
                .isInstanceOf(org.springframework.web.client.RestClientResponseException.class);
        server.verify();
    }

    private static final class TestSecretOwner implements WorkplaceProviderSecretOwner {
        @Override
        public boolean supports(String opaqueReference) {
            return REFERENCE.equals(opaqueReference);
        }

        @Override
        public SecretLease lease(String opaqueReference) {
            return new SecretLease("sandbox-token-123456789".toCharArray());
        }
    }
}
