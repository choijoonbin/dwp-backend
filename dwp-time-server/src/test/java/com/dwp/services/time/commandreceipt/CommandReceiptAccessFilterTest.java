package com.dwp.services.time.commandreceipt;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.api.HrisTimeProblemAdvice;
import com.dwp.services.time.api.HrisTimeProblemWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommandReceiptAccessFilterTest {

    private static final UUID RECEIPT =
            UUID.fromString("17900000-0000-4000-8000-000000000041");
    private static final UUID CORRELATION =
            UUID.fromString("17900000-0000-4000-8000-000000000042");
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    private CommandReceiptProviderRegistry registry;

    @AfterEach
    void closeRegistry() {
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void deniedReceiptAuthorityUsesTheScopedProblemShape() throws Exception {
        CommandReceiptAuthorityProvider authority =
                mock(CommandReceiptAuthorityProvider.class);
        when(authority.providerId()).thenReturn("timecard");
        when(authority.verify(any())).thenReturn(Optional.empty());

        MockMvc mockMvc = mvc(authority);
        mockMvc.perform(get("/v1/command-receipts/{receiptId}", RECEIPT)
                        .header("X-Correlation-ID", CORRELATION))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Correlation-ID", CORRELATION.toString()))
                .andExpect(jsonPath("$.*", hasSize(6)))
                .andExpect(jsonPath("$.code").value("AUTHORIZATION_DENIED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void receiptAuthorityDependencyFailureUsesAuditableRetrySemantics() throws Exception {
        CommandReceiptAuthorityProvider authority =
                mock(CommandReceiptAuthorityProvider.class);
        when(authority.providerId()).thenReturn("timecard");
        when(authority.verify(any())).thenThrow(
                new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        MockMvc mockMvc = mvc(authority);
        mockMvc.perform(get("/v1/command-receipts/{receiptId}", RECEIPT)
                        .header("X-Correlation-ID", CORRELATION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("AUTHORITY_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void mixedProviderAuthorityWithOneSharedEnvelopeReachesOwnerRegistry() throws Exception {
        CommandReceiptAuthorityProvider timecard =
                mock(CommandReceiptAuthorityProvider.class);
        CommandReceiptAuthorityProvider close =
                mock(CommandReceiptAuthorityProvider.class);
        when(timecard.providerId()).thenReturn("timecard");
        when(close.providerId()).thenReturn("close");
        when(timecard.verify(any())).thenReturn(Optional.of(authority("timecard")));
        when(close.verify(any())).thenReturn(Optional.of(authority("close")));

        MockMvc mockMvc = mvc(List.of(timecard, close));
        mockMvc.perform(get("/v1/command-receipts/{receiptId}", RECEIPT)
                        .header("X-Correlation-ID", CORRELATION))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TARGET_NOT_DISCLOSED"));
    }

    private MockMvc mvc(CommandReceiptAuthorityProvider authority) {
        return mvc(List.of(authority));
    }

    private MockMvc mvc(List<CommandReceiptAuthorityProvider> authorities) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HrisTimeProblemWriter writer = new HrisTimeProblemWriter(mapper);
        registry = new CommandReceiptProviderRegistry(
                List.of(), ignored -> { }, Duration.ofSeconds(1));
        return MockMvcBuilders.standaloneSetup(new CommandReceiptController(registry))
                .setControllerAdvice(new HrisTimeProblemAdvice(writer))
                .addFilters(new CommandReceiptAccessFilter(
                        authorities, writer, Clock.fixed(NOW, ZoneOffset.UTC)))
                .build();
    }

    private CommandReceiptAuthority authority(String evidence) {
        return new CommandReceiptAuthority(
                41, CORRELATION, "psr-" + "b".repeat(64),
                NOW.plusSeconds(300), evidence);
    }
}
