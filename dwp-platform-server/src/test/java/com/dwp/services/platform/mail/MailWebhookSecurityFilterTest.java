package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MailWebhookSecurityFilterTest {

    @Test
    void graphOwnershipValidationEchoesExactTokenAsPlainText() throws Exception {
        MailWebhookIngressService ingress = mock(MailWebhookIngressService.class);
        MailWebhookSecurityFilter filter = new MailWebhookSecurityFilter(
                ingress, objectMapper(), 32);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MailWebhookIngressController(ingress))
                .addFilters(filter)
                .build();
        String token = "graph-validation-token_%2F-safe";

        mvc.perform(post("/internal/v1/mail/webhooks/microsoft_graph")
                        .queryParam("validationToken", token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(token));
    }

    @Test
    void graphOwnershipValidationCannotBypassNotificationBodyVerification()
            throws Exception {
        MailWebhookIngressService ingress = mock(MailWebhookIngressService.class);
        MailWebhookSecurityFilter filter = new MailWebhookSecurityFilter(
                ingress, objectMapper(), 32);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MailWebhookIngressController(ingress))
                .addFilters(filter)
                .build();

        mvc.perform(post("/internal/v1/mail/webhooks/microsoft_graph")
                        .queryParam("validationToken", "validation-token")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void graphOwnershipValidationRejectsOversizedAndControlCharacterTokens()
            throws Exception {
        MailWebhookIngressService ingress = mock(MailWebhookIngressService.class);
        MailWebhookSecurityFilter filter = new MailWebhookSecurityFilter(
                ingress, objectMapper(), 32);
        for (String token : List.of("x".repeat(2_049), "unsafe\nvalue")) {
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "POST", "/internal/v1/mail/webhooks/microsoft_graph");
            request.setQueryString("validationToken=bounded");
            request.setParameter("validationToken", token);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<jakarta.servlet.ServletRequest> admitted =
                    new AtomicReference<>();

            filter.doFilter(request, response,
                    (incoming, outgoing) -> admitted.set(incoming));

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(admitted).hasValue(null);
        }
    }

    @Test
    void exactPostIsVerifiedBeforePlatformIdentityExemption() throws Exception {
        MailWebhookVerificationPort verifier = mock(MailWebhookVerificationPort.class);
        when(verifier.supports("GOOGLE_GMAIL")).thenReturn(true);
        when(verifier.verify(any())).thenReturn(new MailWebhookVerificationPort.Verification(
                true, "GOOGLE_GMAIL", 7, UUID.randomUUID(),
                "a".repeat(64), "b".repeat(64),
                OffsetDateTime.now(ZoneOffset.UTC), "corr", null));
        MailWebhookIngressService ingress = new MailWebhookIngressService(
                List.of(verifier), mock(MailSyncJobRepository.class),
                new MailMetrics(new SimpleMeterRegistry()), 32, 5);
        MailWebhookSecurityFilter filter = new MailWebhookSecurityFilter(
                ingress, objectMapper(), 32);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/v1/mail/webhooks/google_gmail");
        request.setContent("provider-callback".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.addHeader("X-Provider-Signature", "opaque-signature");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<jakarta.servlet.ServletRequest> admitted = new AtomicReference<>();
        FilterChain chain = (incoming, outgoing) -> admitted.set(incoming);

        filter.doFilter(request, response, chain);

        assertThat(admitted.get()).isInstanceOf(jakarta.servlet.http.HttpServletRequest.class);
        assertThat(MailWebhookSecurityFilter.verified(
                (jakarta.servlet.http.HttpServletRequest) admitted.get())).isTrue();
    }

    @Test
    void verifiedNotificationStillReturnsAcceptedAndOnlyEnqueuesSync() throws Exception {
        MailWebhookVerificationPort verifier = mock(MailWebhookVerificationPort.class);
        MailSyncJobRepository jobs = mock(MailSyncJobRepository.class);
        UUID connectionId = UUID.randomUUID();
        MailWebhookVerificationPort.Verification receipt =
                new MailWebhookVerificationPort.Verification(
                        true, "GOOGLE_GMAIL", 7, connectionId,
                        "a".repeat(64), "b".repeat(64),
                        OffsetDateTime.now(ZoneOffset.UTC), "corr", null);
        when(verifier.supports("GOOGLE_GMAIL")).thenReturn(true);
        when(verifier.verify(any())).thenReturn(receipt);
        when(jobs.enqueueVerifiedWebhook(receipt, 5)).thenReturn(
                new MailSyncJobRepository.WebhookEnqueue(
                        UUID.randomUUID(), UUID.randomUUID()));
        MailWebhookIngressService ingress = new MailWebhookIngressService(
                List.of(verifier), jobs,
                new MailMetrics(new SimpleMeterRegistry()), 128, 5);
        MailWebhookSecurityFilter filter = new MailWebhookSecurityFilter(
                ingress, objectMapper(), 128);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new MailWebhookIngressController(ingress))
                .addFilters(filter)
                .build();

        mvc.perform(post("/internal/v1/mail/webhooks/google_gmail")
                        .contentType("application/json")
                        .content("{\"message\":{\"data\":\"opaque\"}}"))
                .andExpect(status().isAccepted());

        org.mockito.Mockito.verify(jobs).enqueueVerifiedWebhook(receipt, 5);
    }

    @Test
    void contentLengthAndStreamAreCappedBeforeMvcAllocation() throws Exception {
        MailWebhookIngressService ingress = mock(MailWebhookIngressService.class);
        MailWebhookSecurityFilter filter = new MailWebhookSecurityFilter(
                ingress, objectMapper(), 4);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/v1/mail/webhooks/google_gmail");
        request.setContent(new byte[]{1, 2, 3, 4, 5});
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<jakarta.servlet.ServletRequest> admitted = new AtomicReference<>();

        filter.doFilter(request, response, (incoming, outgoing) -> admitted.set(incoming));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(admitted).hasValue(null);
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
