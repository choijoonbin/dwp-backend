package com.dwp.services.time.timecard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.time.timecard.TimecardModels.CommandKind;
import com.dwp.services.time.timecard.TimecardModels.PopulationKind;
import com.dwp.services.time.timecard.TimecardModels.TimeCommandReceiptV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardAction;
import com.dwp.services.time.timecard.TimecardModels.TimecardCommand;
import com.dwp.services.time.timecard.TimecardModels.TimecardRecord;
import com.dwp.services.time.timecard.TimecardModels.TimecardState;
import com.dwp.services.time.timecard.TimecardModels.TimecardV1;
import com.dwp.services.time.timecard.TimecardModels.VerifiedTimecardRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TimecardOwnerBoundaryContractTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final UUID CARD = uuid("17100000-0000-4000-8000-000000000001");
    private static final UUID WORKER = uuid("17100000-0000-4000-8000-000000000002");
    private static final UUID SUBJECT = uuid("17100000-0000-4000-8000-000000000003");
    private static final UUID ACTOR_WORKER =
            uuid("17100000-0000-4000-8000-000000000013");
    private static final UUID RECEIPT = uuid("17100000-0000-4000-8000-000000000004");
    private static final UUID POPULATION = uuid("17100000-0000-4000-8000-000000000005");
    private static final UUID CORRELATION = uuid("17100000-0000-4000-8000-000000000006");
    private static final String DECISION_REVISION = "psr-" + "b".repeat(64);

    private TimecardRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = mock(TimecardRepository.class);
        TimecardService service = new TimecardService(
                repository, Clock.fixed(NOW, ZoneOffset.UTC));
        mockMvc = MockMvcBuilders.standaloneSetup(new TimecardOwnerController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
    }

    @Test
    void getReturnsRawEightFieldTimecardAndQuotedVersionEtag() throws Exception {
        when(repository.find(41L, CARD)).thenReturn(Optional.of(record()));

        mockMvc.perform(get("/v1/timecards/{timecardId}", CARD)
                        .requestAttr(TimecardOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE,
                                authority(TimecardAction.VIEW, PopulationKind.SELF,
                                        "TIME_TIMECARD", Set.of("DATA.HR_TIME:VIEW"))))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"7\""))
                .andExpect(jsonPath("$.*", hasSize(8)))
                .andExpect(jsonPath("$.timecardId").value(CARD.toString()))
                .andExpect(jsonPath("$.workerId").value(WORKER.toString()))
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.totalMinutes").value(480))
                .andExpect(jsonPath("$.ledgerRevision").value(11))
                .andExpect(jsonPath("$.version").value(7))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void correctionReturnsRaw202ReceiptWithCanonicalBindingAndOpaqueKey() throws Exception {
        TimeCommandReceiptV1 receipt = receipt(CommandKind.CORRECT);
        when(repository.find(41L, CARD)).thenReturn(Optional.of(record()));
        when(repository.execute(any())).thenReturn(receipt);

        mockMvc.perform(post("/v1/timecards/{timecardId}/corrections", CARD)
                        .requestAttr(TimecardOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE,
                                authority(TimecardAction.CORRECT, PopulationKind.SELF,
                                        "TIME_TIMECARD", Set.of("DATA.HR_TIME:CREATE")))
                        .header("Idempotency-Key", "timecard-key-0001")
                        .header("If-Match", "\"7\"")
                        .header("X-DWP-Expected-Decision-Revision", DECISION_REVISION)
                        .contentType("application/json")
                        .content("""
                                {"reasonCode":"MISSED_CLOCK","entries":[
                                  {"workDate":"2026-10-14","payCode":"Regular / A|B","minutes":60}
                                ]}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.*", hasSize(9)))
                .andExpect(jsonPath("$.commandType").value("TIMECARD_CORRECT"))
                .andExpect(jsonPath("$.originatingAction")
                        .value("TIME_TIMECARD_CORRECT"))
                .andExpect(jsonPath("$.data").doesNotExist());

        ArgumentCaptor<TimecardCommand> command = ArgumentCaptor.forClass(TimecardCommand.class);
        verify(repository).execute(command.capture());
        assertThat(command.getValue().expectedVersion()).isEqualTo(7);
        assertThat(command.getValue().idempotencyKey()).isEqualTo("timecard-key-0001");
        assertThat(command.getValue().requestDigest()).matches("[0-9a-f]{64}");
    }

    @Test
    void submitAndDecisionAreRaw202WithExactReceiptLiterals() throws Exception {
        when(repository.find(41L, CARD)).thenReturn(Optional.of(record()));
        when(repository.execute(any())).thenAnswer(invocation ->
                receipt(invocation.<TimecardCommand>getArgument(0).kind()));

        mockMvc.perform(post("/v1/timecards/{timecardId}/submit", CARD)
                        .requestAttr(TimecardOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE,
                                authority(TimecardAction.SUBMIT, PopulationKind.SELF,
                                        "TIME_TIMECARD", Set.of("DATA.HR_TIME:CREATE")))
                        .header("Idempotency-Key", "timecard-key-0002")
                        .header("If-Match", "7")
                        .header("X-DWP-Expected-Decision-Revision", DECISION_REVISION)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandType").value("TIMECARD_SUBMIT"))
                .andExpect(jsonPath("$.originatingAction").value("TIME_TIMECARD_SUBMIT"));

        mockMvc.perform(post("/v1/timecards/{timecardId}/decisions", CARD)
                        .requestAttr(TimecardOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE,
                                authority(TimecardAction.DECIDE,
                                        PopulationKind.TARGET_POPULATION,
                                        "TIME_TIMECARD", Set.of("DATA.HR_TIME:APPROVE")))
                        .header("Idempotency-Key", "timecard-key-0003")
                        .header("If-Match", "7")
                        .header("X-DWP-Expected-Decision-Revision", DECISION_REVISION)
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"reasonCode\":\"ACCURATE\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.commandType").value("TIMECARD_DECIDE"))
                .andExpect(jsonPath("$.originatingAction").value("TIME_TIMECARD_DECIDE"));
    }

    @Test
    void correctionPayCodeMatchesPinnedOneToSixtyFourCharacterSchema() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String payCode = "a/b|" + "x".repeat(60);
        assertThat(payCode).hasSize(64);
        var command = mapper.readValue("""
                {"reasonCode":"R","entries":[
                  {"workDate":"2026-10-14","payCode":"%s","minutes":1}
                ]}
                """.formatted(payCode), TimecardModels.TimecardCorrectionCommandV1.class);
        assertThat(command.entries().getFirst().payCode()).isEqualTo(payCode);
    }

    @Test
    void unknownCommandFieldsFailClosedWith400BeforeRepositoryMutation() throws Exception {
        when(repository.find(41L, CARD)).thenReturn(Optional.of(record()));

        mockMvc.perform(post("/v1/timecards/{timecardId}/corrections", CARD)
                        .requestAttr(TimecardOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE,
                                authority(TimecardAction.CORRECT, PopulationKind.SELF,
                                        "TIME_TIMECARD", Set.of("DATA.HR_TIME:CREATE")))
                        .header("Idempotency-Key", "timecard-key-0004")
                        .header("If-Match", "7")
                        .header("X-DWP-Expected-Decision-Revision", DECISION_REVISION)
                        .contentType("application/json")
                        .content("""
                                {"reasonCode":"MISSED_CLOCK","entries":[
                                  {"workDate":"2026-10-14","payCode":"REGULAR","minutes":1,
                                   "unsealedField":true}
                                ]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("E4000"));

        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).execute(any());
    }

    private TimecardRecord record() {
        return new TimecardRecord(new TimecardV1(
                CARD, WORKER, LocalDate.parse("2026-10-13"),
                LocalDate.parse("2026-10-19"), TimecardState.OPEN,
                480, 11, 7), null, null, false);
    }

    private TimeCommandReceiptV1 receipt(CommandKind kind) {
        return new TimeCommandReceiptV1(
                RECEIPT, kind.commandType(), kind.originatingAction(), CARD,
                "SUCCEEDED", "a".repeat(64), NOW, NOW,
                "timecard:" + CARD + ":version:8");
    }

    private VerifiedTimecardRequest authority(
            TimecardAction action,
            PopulationKind population,
            String purpose,
            Set<String> permissions) {
        return new VerifiedTimecardRequest(
                41, 72, SUBJECT,
                population == PopulationKind.SELF ? WORKER : ACTOR_WORKER,
                permissions, Set.of(action), population,
                POPULATION, 9, 11, "a".repeat(64), 17, purpose, 23,
                DECISION_REVISION, NOW.plusSeconds(300), CORRELATION,
                Set.of(WORKER), Set.of(), Set.of());
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
