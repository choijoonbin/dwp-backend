package com.dwp.services.time.interpretation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dwp.services.time.interpretation.InterpretationModels.InterpretCommand;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAction;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationCommandReceipt;
import com.dwp.services.time.interpretation.InterpretationModels.PopulationKind;
import com.dwp.services.time.interpretation.InterpretationModels.VerifiedInterpretationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InterpretationOwnerBoundaryContractTest {

    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
    private static final UUID SUBJECT =
            UUID.fromString("74000000-0000-4000-8000-000000000001");
    private static final UUID POPULATION =
            UUID.fromString("74000000-0000-4000-8000-000000000002");
    private static final UUID WORKER =
            UUID.fromString("74000000-0000-4000-8000-000000000003");
    private static final UUID CORRELATION =
            UUID.fromString("74000000-0000-4000-8000-000000000004");
    private static final UUID IDEMPOTENCY =
            UUID.fromString("74000000-0000-4000-8000-000000000005");

    private InterpretationOwnerAuthoritySource authoritySource;
    private InterpretationService service;
    private InterpretationRepository repository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authoritySource = mock(InterpretationOwnerAuthoritySource.class);
        service = mock(InterpretationService.class);
        repository = mock(InterpretationRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var controller = new InterpretationOwnerController(
                service,
                repository,
                new InterpretationIaProjectionService(
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        "interpretation-owner-boundary-secret"),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var filter = new InterpretationOwnerAccessFilter(authoritySource, objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(filter)
                .build();
    }

    @Test
    void routeContractsAndReleasePinsAreExact() {
        assertThat(InterpretationOwnerRoute.WORKBENCH.matches(
                "GET", "/v1/hris/workbench/time-interpretation")).isTrue();
        assertThat(InterpretationOwnerRoute.WORKBENCH.bindingId()).isEqualTo("IAQ-041");
        assertThat(InterpretationOwnerRoute.RUN.matches(
                "POST", "/v1/interpretation-runs")).isTrue();
        assertThat(InterpretationOwnerRoute.RUN.bindingId()).isEqualTo("PEP-TIM-009");
        assertThat(InterpretationOwnerRoute.TRACE.matches(
                "GET", "/v1/interpretation-runs/" + SUBJECT + "/trace")).isTrue();
        assertThat(InterpretationOwnerRoute.TRACE.bindingId()).isEqualTo("PEP-TIM-010");
        assertThat(InterpretationOwnerRoute.RUN.requiredPurpose())
                .isEqualTo("TIME_INTERPRETATION");
        assertThat(InterpretationOwnerRoute.AUTHORIZATION_VERSION).isEqualTo(37);
        assertThat(InterpretationOwnerRoute.AUTHORIZATION_SHA256)
                .isEqualTo("1f23280116bc5b29f1a7ea6cc4ccbd2092a91673979d6bb46164a0dbb95ae10c");
        assertThat(InterpretationOwnerRoute.GATEWAY_OPENAPI_SHA256)
                .isEqualTo("9d788107956401a3c319f81ed2ca4fe4b1b2ac1e8c23aec9ce2f0a71d55d330a");
    }

    @Test
    void runReturnsTheContractualAcceptedStatusAndReceiptConvention() throws Exception {
        VerifiedInterpretationRequest authority = authority(
                InterpretationAction.RUN,
                Set.of("APP.HCM:VIEW", "hcm.operations.time.update"),
                "TIME_INTERPRETATION");
        when(authoritySource.verify(
                any(HttpServletRequest.class), eq(InterpretationOwnerRoute.RUN)))
                .thenReturn(Optional.of(authority));
        when(service.run(eq(authority), eq(IDEMPOTENCY), any(InterpretCommand.class)))
                .thenReturn(receipt());

        mockMvc.perform(post("/v1/interpretation-runs")
                        .header("Idempotency-Key", IDEMPOTENCY)
                        .contentType("application/json")
                        .content("""
                                {
                                  "workerIds":["74000000-0000-4000-8000-000000000003"],
                                  "periodStart":"2026-10-12",
                                  "periodEnd":"2026-10-12",
                                  "asOf":"2026-10-13T02:00:00Z"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.commandType").value("INTERPRETATION_RUN"))
                .andExpect(jsonPath("$.data.originatingAction").value("TIME_INTERPRET_RUN"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));

        verify(service).run(eq(authority), eq(IDEMPOTENCY), any(InterpretCommand.class));
    }

    @Test
    void workbenchPassesNormalizedFiltersSortAndLookaheadToRepository() throws Exception {
        VerifiedInterpretationRequest authority = authority(
                InterpretationAction.VIEW,
                Set.of("APP.HCM:VIEW", "hcm.operations.time.view"),
                "TIME_OPERATIONS");
        when(authoritySource.verify(
                any(HttpServletRequest.class), eq(InterpretationOwnerRoute.WORKBENCH)))
                .thenReturn(Optional.of(authority));
        when(repository.query(any())).thenReturn(List.of());

        mockMvc.perform(get("/v1/hris/workbench/time-interpretation")
                        .queryParam("limit", "1")
                        .queryParam("sort", "traceSummary:DESC", "ruleVersion:ASC")
                        .queryParam("locale", "en-US")
                        .queryParam("timeZone", "UTC")
                        .queryParam("correlationId", CORRELATION.toString())
                        .queryParam("effectiveOn", "2026-10-03")
                        .queryParam("interpretationResultCodes", " succeeded ")
                        .queryParam("minimumInterpretationResultRevision", "2")
                        .queryParam("ruleVersionPolicyKeys", " tim.rule." + SUBJECT + " ")
                        .queryParam("minimumRuleVersionRevision", "3")
                        .queryParam("minimumTraceItemCount", "4")
                        .queryParam("maximumTraceItemCount", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queryState.state").value("EMPTY"));

        var query = org.mockito.ArgumentCaptor
                .forClass(InterpretationModels.InterpretationQuery.class);
        verify(repository).query(query.capture());
        assertThat(query.getValue().limit()).isEqualTo(2);
        assertThat(query.getValue().offset()).isZero();
        assertThat(query.getValue().sort())
                .containsExactly("traceSummary:DESC", "ruleVersion:ASC");
        assertThat(query.getValue().filter().effectiveOn())
                .isEqualTo(java.time.LocalDate.parse("2026-10-03"));
        assertThat(query.getValue().filter().interpretationResult().codes())
                .containsExactly("SUCCEEDED");
        assertThat(query.getValue().filter().interpretationResult().minimumRevision())
                .isEqualTo(2L);
        assertThat(query.getValue().filter().ruleVersion().policyKeys())
                .containsExactly("tim.rule." + SUBJECT);
        assertThat(query.getValue().filter().ruleVersion().minimumRevision())
                .isEqualTo(3L);
        assertThat(query.getValue().filter().traceSummary().minimumItemCount())
                .isEqualTo(4L);
        assertThat(query.getValue().filter().traceSummary().maximumItemCount())
                .isEqualTo(8L);
    }

    @Test
    void malformedOrUnregisteredOwnerRouteFailsBeforeAuthorityResolution() throws Exception {
        mockMvc.perform(get("/v1/interpretation-runs/not-a-uuid/trace"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/interpretation-runs/" + SUBJECT + "/trace"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(authoritySource);
        verify(service, never()).trace(any(), any());
    }

    @Test
    void staleOrPermissionMismatchedAuthorityNeverReachesController() throws Exception {
        VerifiedInterpretationRequest stale = authority(
                InterpretationAction.RUN,
                Set.of("APP.HCM:VIEW", "hcm.operations.time.update"),
                "TIME_INTERPRETATION",
                Instant.now().minusSeconds(1));
        when(authoritySource.verify(
                any(HttpServletRequest.class), eq(InterpretationOwnerRoute.RUN)))
                .thenReturn(Optional.of(stale));

        mockMvc.perform(post("/v1/interpretation-runs")
                        .header("Idempotency-Key", IDEMPOTENCY)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());

        verify(service, never()).run(any(), any(), any());
    }

    private VerifiedInterpretationRequest authority(
            InterpretationAction action, Set<String> permissions, String purpose) {
        return authority(action, permissions, purpose, NOW.plusSeconds(600));
    }

    private VerifiedInterpretationRequest authority(
            InterpretationAction action,
            Set<String> permissions,
            String purpose,
            Instant revalidateAt) {
        return new VerifiedInterpretationRequest(
                41,
                72,
                SUBJECT,
                permissions,
                Set.of(action),
                PopulationKind.TIME_GROUP,
                POPULATION,
                9,
                11,
                "a".repeat(64),
                17,
                purpose,
                23,
                "psr-" + "b".repeat(64),
                revalidateAt,
                CORRELATION,
                Set.of(WORKER),
                Set.of(),
                Set.of());
    }

    private InterpretationCommandReceipt receipt() {
        return new InterpretationCommandReceipt(
                UUID.fromString("74000000-0000-4000-8000-000000000006"),
                "INTERPRETATION_RUN",
                "TIME_INTERPRET_RUN",
                SUBJECT,
                "a".repeat(64),
                17,
                "TIME_INTERPRETATION",
                23,
                "SUCCEEDED",
                CORRELATION,
                "c".repeat(64),
                NOW,
                1L,
                "/api/time/v1/interpretation-runs/"
                        + "74000000-0000-4000-8000-000000000007/trace",
                UUID.fromString("74000000-0000-4000-8000-000000000007"),
                "d".repeat(64),
                false);
    }
}
