package com.dwp.services.platform.workplace.workplaceplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.platform.workplace.workplaceplanning.WorkplacePlanningDtos.*;
import static com.dwp.services.platform.workplace.workplaceplanning.WorkplacePlanningRepository.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkplacePlanningServiceTest {
    private static final Instant FIXED = Instant.parse("2026-09-16T12:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(FIXED, ZoneOffset.UTC);
    private static final long TENANT = 42;
    private static final long ACTOR = 7;

    private final WorkplacePlanningRepository repository = mock(WorkplacePlanningRepository.class);
    private final WorkplacePlanningService service = new WorkplacePlanningService(repository, CLOCK);
    private final UUID siteId = UUID.randomUUID();
    private final UUID floorId = UUID.randomUUID();
    private final PlanningScope scope = new PlanningScope(
            siteId, floorId, null, "ROOM", NOW, NOW.plusDays(7));

    @BeforeEach
    void catalog() {
        when(repository.siteExists(TENANT, siteId)).thenReturn(true);
        when(repository.floorBelongsToSite(TENANT, siteId, floorId)).thenReturn(true);
        when(repository.resourcesBelongToScope(eq(TENANT), any(), anyList())).thenReturn(true);
        when(repository.currentMetrics(eq(TENANT), any())).thenReturn(
                new CurrentSpaceMetrics(100, 40, 10, 50, NOW.minusMinutes(1)));
        when(repository.latestEmission(eq(TENANT), any())).thenReturn(Optional.empty());
        when(repository.scenarios(TENANT, siteId, null)).thenReturn(List.of());
    }

    @Test
    void staleEvidenceSuppressesReadyForecastPointsAndRecommendationMetrics() {
        List<SourceRow> sources = sourceRows(NOW.minusHours(25));
        List<UUID> sourceIds = sources.stream().map(SourceRow::observationId).toList();
        ForecastRow forecast = new ForecastRow(UUID.randomUUID(), TENANT, scope,
                ForecastState.READY, "forecast-v3", "evidence:forecast:v3", sourceIds,
                List.of(new ForecastPoint(NOW.plusHours(1), new BigDecimal("88"),
                        new BigDecimal("80"), new BigDecimal("95"), "people")),
                new RecommendationMetrics(new BigDecimal("88"), new BigDecimal("20"),
                        new BigDecimal("91"), "forecast-v3"),
                List.of(), NOW.minusMinutes(5), NOW.minusMinutes(4));
        when(repository.latestSources(TENANT, scope)).thenReturn(sources);
        when(repository.latestForecast(TENANT, scope)).thenReturn(Optional.of(forecast));
        when(repository.sourcesByIds(TENANT, sourceIds)).thenReturn(sources);

        PlanningOverview overview = service.overview(TENANT, scope);

        assertThat(overview.sources()).hasSize(6)
                .allMatch(source -> source.freshness() == FreshnessState.STALE);
        assertThat(overview.forecast().state()).isEqualTo(ForecastState.STALE);
        assertThat(overview.forecast().points()).isEmpty();
        assertThat(overview.forecast().recommendationMetrics()).isNull();
        assertThat(overview.forecast().limitations()).isNotEmpty();
    }

    @Test
    void nonReadyForecastAdapterCannotPersistInventedMetrics() {
        ForecastObservation observation = new ForecastObservation(UUID.randomUUID(), TENANT, scope,
                ForecastState.DATA_INSUFFICIENT, null, null, List.of(),
                List.of(new ForecastPoint(NOW.plusHours(1), BigDecimal.TEN,
                        null, null, "people")),
                new RecommendationMetrics(BigDecimal.TEN, BigDecimal.ONE,
                        new BigDecimal("50"), "invalid"),
                List.of("Missing access evidence"), NOW.minusMinutes(1), NOW);

        assertThatThrownBy(() -> service.observeForecast(observation))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(repository, never()).appendForecast(any());
    }

    @Test
    void approvedEmissionModelRequiresHumanAuthority() {
        EmissionObservation observation = new EmissionObservation(UUID.randomUUID(), TENANT,
                siteId, floorId, EmissionEvidenceKind.APPROVED_MODEL,
                new BigDecimal("120.5"), "kWh", new BigDecimal("52.2"), "kgCO2e",
                "factor-2026-kr", "KR", "evidence:model:22", NOW.minusHours(1), NOW,
                null, null);

        assertThatThrownBy(() -> service.observeEmission(observation))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(repository, never()).appendEmissionEvidence(any());
    }

    @Test
    void sameCreateCommandReplaysExactSnapshotAfterAdvisoryLock() {
        AtomicReference<CommandRow> saved = scenarioCommandCapture();
        CreateScenarioRequest request = createRequest("Capacity plan");

        ScenarioCommandResult first = service.create(
                TENANT, ACTOR, "create-key", "corr-create", request);
        ScenarioCommandResult replay = service.create(
                TENANT, ACTOR, "create-key", "changed-correlation", request);

        assertThat(replay.scenario()).isEqualTo(first.scenario());
        assertThat(first.receipt().idempotentReplay()).isFalse();
        assertThat(replay.receipt().idempotentReplay()).isTrue();
        assertThat(saved.get().scenarioId()).isEqualTo(first.scenario().scenarioId());
        verify(repository, times(2)).lockCommand(TENANT, ACTOR, "CREATE", "create-key");
        verify(repository, times(1)).insertScenario(any());
        verify(repository, times(1)).saveCommand(anyLong(), anyLong(), any(), anyString(),
                anyString(), anyString(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    void reusedIdempotencyKeyWithDifferentPayloadConflictsBeforeMutation() {
        scenarioCommandCapture();
        service.create(TENANT, ACTOR, "same-key", "corr-a", createRequest("First plan"));

        assertThatThrownBy(() -> service.create(
                TENANT, ACTOR, "same-key", "corr-b", createRequest("Other plan")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(repository, times(1)).insertScenario(any());
    }

    @Test
    void expiredPreviewCannotBeSubmitted() {
        ScenarioRow scenario = scenarioRow(UUID.randomUUID(), ScenarioState.PREVIEWED, 2,
                UUID.randomUUID(), ACTOR);
        ScenarioPreviewRow preview = new ScenarioPreviewRow(scenario.activePreviewId(), TENANT,
                scenario.scenarioId(), 1, null, ForecastState.READY,
                new ForecastProjection(null, ForecastState.READY, "v1", "evidence", List.of(),
                        List.of(), null, List.of(), NOW.minusMinutes(2), NOW.minusMinutes(1), NOW),
                new SpaceComparison(100, 100, 40, 40, 10, 10,
                        null, null, null, null, null, 0), null, true, List.of(),
                NOW.minusSeconds(1), ACTOR, NOW.minusMinutes(10));
        when(repository.commandByIdempotency(TENANT, ACTOR, "submit-key"))
                .thenReturn(Optional.empty());
        when(repository.scenario(TENANT, scenario.scenarioId())).thenReturn(Optional.of(scenario));
        when(repository.preview(TENANT, scenario.activePreviewId())).thenReturn(Optional.of(preview));

        assertThatThrownBy(() -> service.submit(TENANT, ACTOR, scenario.scenarioId(),
                "submit-key", "corr", new ScenarioTransitionRequest(
                        2, "Submit reviewed scenario", true)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(repository, never()).submit(anyLong(), anyLong(), any(), anyLong(), any());
    }

    @Test
    void bookingImpactPreviewAlsoReplaysReceiptWithoutDuplicateWrite() {
        UUID scenarioId = UUID.randomUUID();
        ScenarioRow scenario = scenarioRow(scenarioId, ScenarioState.DRAFT, 1, null, ACTOR);
        AtomicReference<CommandRow> saved = new AtomicReference<>();
        when(repository.commandByIdempotency(TENANT, ACTOR, "impact-key"))
                .thenAnswer(ignored -> Optional.ofNullable(saved.get()));
        when(repository.scenario(TENANT, scenarioId)).thenReturn(Optional.of(scenario));
        when(repository.saveBookingImpactCommand(anyLong(), anyLong(), any(), anyString(),
                anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
            BookingImpactPreview result = invocation.getArgument(7);
            CommandRow row = new CommandRow(UUID.randomUUID(), TENANT, ACTOR, scenarioId,
                    "BOOKING_IMPACT_PREVIEW", invocation.getArgument(3), invocation.getArgument(4),
                    invocation.getArgument(5), CommandState.SUCCEEDED, ScenarioState.DRAFT, 1,
                    null, result, invocation.getArgument(6), UUID.randomUUID(),
                    OutboxState.PENDING, NOW);
            saved.set(row);
            return row;
        });
        BookingImpactPreviewRequest request = new BookingImpactPreviewRequest(
                1, "Inspect booking impact", true);

        BookingImpactCommandResult first = service.previewBookingImpact(
                TENANT, ACTOR, scenarioId, "impact-key", "corr", request);
        BookingImpactCommandResult replay = service.previewBookingImpact(
                TENANT, ACTOR, scenarioId, "impact-key", "changed", request);

        assertThat(replay.preview()).isEqualTo(first.preview());
        assertThat(replay.receipt().idempotentReplay()).isTrue();
        verify(repository, times(1)).saveBookingImpactPreview(eq(TENANT), eq(ACTOR), any());
        verify(repository, times(2)).lockCommand(
                TENANT, ACTOR, "BOOKING_IMPACT_PREVIEW", "impact-key");
    }

    private AtomicReference<CommandRow> scenarioCommandCapture() {
        AtomicReference<CommandRow> saved = new AtomicReference<>();
        when(repository.commandByIdempotency(eq(TENANT), eq(ACTOR), anyString()))
                .thenAnswer(ignored -> Optional.ofNullable(saved.get()));
        when(repository.saveCommand(anyLong(), anyLong(), any(), anyString(), anyString(),
                anyString(), anyString(), nullable(String.class), any(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    ScenarioView result = invocation.getArgument(8);
                    CommandRow row = new CommandRow(UUID.randomUUID(), TENANT, ACTOR,
                            result.scenarioId(), invocation.getArgument(3), invocation.getArgument(4),
                            invocation.getArgument(5), invocation.getArgument(6),
                            CommandState.SUCCEEDED, result.state(), result.version(), result, null,
                            invocation.getArgument(7), UUID.randomUUID(), OutboxState.PENDING, NOW);
                    saved.set(row);
                    return row;
                });
        return saved;
    }

    private CreateScenarioRequest createRequest(String name) {
        return new CreateScenarioRequest(name, "Evidence-backed capacity scenario", scope,
                draft(List.of()), "Plan verified demand and capacity", true);
    }

    private ScenarioDraftInput draft(List<UUID> affected) {
        return new ScenarioDraftInput(100, 40, 10, LocalTime.of(8, 0),
                LocalTime.of(20, 0), "policy:capacity:v2", affected, List.of(), null);
    }

    private ScenarioRow scenarioRow(
            UUID scenarioId, ScenarioState state, long version, UUID previewId, long actor) {
        return new ScenarioRow(scenarioId, TENANT, "Capacity plan", "Description", state,
                scope, draft(List.of()), previewId, version, state == ScenarioState.SUBMITTED
                ? NOW.minusMinutes(5) : null, state == ScenarioState.SUBMITTED ? actor : null,
                null, null, null, null, null, null, null, null,
                NOW.minusHours(1), actor, NOW.minusMinutes(1), actor);
    }

    private List<SourceRow> sourceRows(OffsetDateTime receivedAt) {
        List<SourceRow> result = new ArrayList<>();
        for (PlanningSeries series : PlanningSeries.values()) {
            result.add(new SourceRow(UUID.randomUUID(), TENANT, series, scope,
                    SourceAvailability.AVAILABLE, new BigDecimal("100"),
                    receivedAt.minusMinutes(1), receivedAt, "evidence:" + series.name(),
                    List.of(), List.of(new SeriesPoint(NOW.plusHours(1), BigDecimal.TEN,
                    null, null, "people")), 1));
        }
        return List.copyOf(result);
    }
}
