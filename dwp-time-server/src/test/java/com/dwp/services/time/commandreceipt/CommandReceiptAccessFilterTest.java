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
import com.dwp.services.time.timecard.TimecardOwnerAccessFilter;
import com.dwp.services.time.timecard.TimecardOwnerAuthoritySource;
import com.dwp.services.time.timecard.TimecardOwnerRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
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

    private CommandReceiptProviderRegistry registry;

    @AfterEach
    void closeRegistry() {
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void deniedReceiptAuthorityUsesTheScopedProblemShape() throws Exception {
        TimecardOwnerAuthoritySource authority = mock(TimecardOwnerAuthoritySource.class);
        when(authority.verify(any(), any(TimecardOwnerRoute.class)))
                .thenReturn(Optional.empty());

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
        TimecardOwnerAuthoritySource authority = mock(TimecardOwnerAuthoritySource.class);
        when(authority.verify(any(), any(TimecardOwnerRoute.class))).thenThrow(
                new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        MockMvc mockMvc = mvc(authority);
        mockMvc.perform(get("/v1/command-receipts/{receiptId}", RECEIPT)
                        .header("X-Correlation-ID", CORRELATION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("AUTHORITY_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    private MockMvc mvc(TimecardOwnerAuthoritySource authority) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        HrisTimeProblemWriter writer = new HrisTimeProblemWriter(mapper);
        registry = new CommandReceiptProviderRegistry(
                List.of(), ignored -> { }, Duration.ofSeconds(1));
        return MockMvcBuilders.standaloneSetup(new CommandReceiptController(registry))
                .setControllerAdvice(new HrisTimeProblemAdvice(writer))
                .addFilters(new TimecardOwnerAccessFilter(authority, mapper, writer))
                .build();
    }
}
