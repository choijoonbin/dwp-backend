package com.dwp.services.people.hr;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrMailProposalOutcomeClientTest {

    @Test
    void preflightUsesOnlyTheTrustedPeopleIdentityAndStableBinding() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://platform.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HrMailProposalOutcomeClient client = new HrMailProposalOutcomeClient(
                builder.build(), "platform-token");
        HrMailProposalBinding binding = new HrMailProposalBinding(
                UUID.randomUUID(), UUID.randomUUID(), 6L);

        server.expect(once(), requestTo(
                        "https://platform.test/internal/v1/mail/proposal-outcomes/preflight"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DWP-Service-Token", "platform-token"))
                .andExpect(header("X-DWP-Service-Identity", "dwp-people-server"))
                .andExpect(header("X-DWP-Tenant-ID", "3"))
                .andExpect(header("X-DWP-User-ID", "17"))
                .andExpect(content().json("""
                        {
                          "proposalId": "%s",
                          "commandId": "%s",
                          "proposalVersion": 6,
                          "resultRef": null
                        }
                        """.formatted(binding.proposalId(), binding.commandId())))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.preflight(3L, 17L, binding);

        server.verify();
    }

    @Test
    void durableDeliveryKeepsTheSameBoundPayloadAndCorrelationHeaders() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://platform.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HrMailProposalOutcomeClient client = new HrMailProposalOutcomeClient(
                builder.build(), "platform-token");
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID leaveRequestId = UUID.randomUUID();
        var outcome = new HrMailProposalOutcomeOutboxRepository.PendingOutcome(
                UUID.randomUUID(), 3L, 17L, proposalId, commandId, 6L,
                "hr-leave-request:" + leaveRequestId, "corr-owner", 2);

        server.expect(once(), requestTo(
                        "https://platform.test/internal/v1/mail/proposal-outcomes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-DWP-Service-Token", "platform-token"))
                .andExpect(header("X-DWP-Service-Identity", "dwp-people-server"))
                .andExpect(header("X-DWP-Tenant-ID", "3"))
                .andExpect(header("X-DWP-User-ID", "17"))
                .andExpect(header("X-Correlation-ID", "corr-owner"))
                .andExpect(content().json("""
                        {
                          "proposalId": "%s",
                          "commandId": "%s",
                          "proposalVersion": 6,
                          "resultRef": "hr-leave-request:%s"
                        }
                        """.formatted(proposalId, commandId, leaveRequestId)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.record(outcome);

        server.verify();
    }

    @Test
    void platformConflictIsClassifiedAsPermanentReconciliationEvidence() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://platform.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HrMailProposalOutcomeClient client = new HrMailProposalOutcomeClient(
                builder.build(), "platform-token");
        var outcome = new HrMailProposalOutcomeOutboxRepository.PendingOutcome(
                UUID.randomUUID(), 3L, 17L, UUID.randomUUID(), UUID.randomUUID(), 6L,
                "hr-leave-request:" + UUID.randomUUID(), "corr-cancel-race", 1);
        server.expect(once(), requestTo(
                        "https://platform.test/internal/v1/mail/proposal-outcomes"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> client.record(outcome))
                .isInstanceOfSatisfying(
                        HrMailProposalOutcomeClient.DeliveryException.class,
                        exception -> org.assertj.core.api.Assertions
                                .assertThat(exception.retryable()).isFalse());

        server.verify();
    }
}
