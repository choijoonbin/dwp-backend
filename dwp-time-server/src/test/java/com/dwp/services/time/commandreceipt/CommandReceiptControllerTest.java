package com.dwp.services.time.commandreceipt;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.services.time.api.HrisTimeProblemAdvice;
import com.dwp.services.time.api.HrisTimeProblemWriter;
import com.dwp.services.time.timecard.TimecardModels.PopulationKind;
import com.dwp.services.time.timecard.TimecardModels.TimeCommandReceiptV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardAction;
import com.dwp.services.time.timecard.TimecardModels.VerifiedTimecardRequest;
import com.dwp.services.time.timecard.TimecardOwnerAccessFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommandReceiptControllerTest {

    private static final UUID RECEIPT =
            UUID.fromString("17900000-0000-4000-8000-000000000011");
    private static final UUID CARD =
            UUID.fromString("17900000-0000-4000-8000-000000000012");
    private static final UUID CORRELATION =
            UUID.fromString("17900000-0000-4000-8000-000000000013");
    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final String DECISION_REVISION = "psr-" + "b".repeat(64);

    private CommandReceiptProviderRegistry registry;

    @AfterEach
    void closeRegistry() {
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void uniqueTimecardOwnerReturnsTheUnwrappedNineFieldWireShape() throws Exception {
        TimeCommandReceiptV1 receipt = new TimeCommandReceiptV1(
                RECEIPT,
                "TIMECARD_CORRECT",
                "TIME_TIMECARD_CORRECT",
                CARD,
                "SUCCEEDED",
                "a".repeat(64),
                NOW,
                NOW,
                "timecard:" + CARD + ":version:8");
        registry = registry(List.of(provider(true, receipt)));
        MockMvc mockMvc = mvc(registry);

        mockMvc.perform(get("/v1/command-receipts/{receiptId}", RECEIPT)
                        .requestAttr(
                                TimecardOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE,
                                authority()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Correlation-ID", CORRELATION.toString()))
                .andExpect(header().string("X-DWP-Decision-Revision", DECISION_REVISION))
                .andExpect(jsonPath("$.*", hasSize(9)))
                .andExpect(jsonPath("$.receiptId").value(RECEIPT.toString()))
                .andExpect(jsonPath("$.timecardId").value(CARD.toString()))
                .andExpect(jsonPath("$.commandType").value("TIMECARD_CORRECT"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void zeroOwnersReturnsTheOpaqueProblemShape() throws Exception {
        registry = registry(List.of(provider(false, null)));
        MockMvc mockMvc = mvc(registry);

        mockMvc.perform(get("/v1/command-receipts/{receiptId}", RECEIPT)
                        .header("X-Correlation-ID", CORRELATION)
                        .requestAttr(
                                TimecardOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE,
                                authority()))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Correlation-ID", CORRELATION.toString()))
                .andExpect(jsonPath("$.*", hasSize(6)))
                .andExpect(jsonPath("$.code").value("TARGET_NOT_DISCLOSED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.receiptId").doesNotExist());
    }

    private CommandReceiptProviderRegistry registry(List<CommandReceiptProvider> providers) {
        return new CommandReceiptProviderRegistry(
                providers, ignored -> { }, Duration.ofSeconds(1));
    }

    private MockMvc mvc(CommandReceiptProviderRegistry registry) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return MockMvcBuilders.standaloneSetup(new CommandReceiptController(registry))
                .setControllerAdvice(new HrisTimeProblemAdvice(
                        new HrisTimeProblemWriter(mapper)))
                .build();
    }

    private CommandReceiptProvider provider(boolean owns, Object receipt) {
        return new CommandReceiptProvider() {
            @Override
            public String providerId() {
                return "timecard";
            }

            @Override
            public boolean owns(CommandReceiptLookup lookup) {
                return owns;
            }

            @Override
            public Object receipt(CommandReceiptLookup lookup) {
                return receipt;
            }
        };
    }

    private VerifiedTimecardRequest authority() {
        UUID subject = UUID.fromString("17900000-0000-4000-8000-000000000014");
        UUID actorWorker = UUID.fromString("17900000-0000-4000-8000-000000000015");
        UUID population = UUID.fromString("17900000-0000-4000-8000-000000000016");
        UUID worker = UUID.fromString("17900000-0000-4000-8000-000000000017");
        return new VerifiedTimecardRequest(
                41,
                72,
                subject,
                actorWorker,
                Set.of("DATA.HRIS_COMMAND_RECEIPT:VIEW"),
                Set.of(TimecardAction.RECEIPT_VIEW),
                PopulationKind.TARGET_POPULATION,
                population,
                9,
                11,
                "a".repeat(64),
                17,
                "COMMAND_STATUS",
                23,
                DECISION_REVISION,
                NOW.plusSeconds(300),
                CORRELATION,
                Set.of(worker),
                Set.of(),
                Set.of());
    }
}
