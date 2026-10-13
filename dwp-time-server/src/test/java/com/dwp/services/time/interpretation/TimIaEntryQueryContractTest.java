package com.dwp.services.time.interpretation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationAction;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationSummary;
import com.dwp.services.time.interpretation.InterpretationModels.PopulationKind;
import com.dwp.services.time.interpretation.InterpretationModels.RunStatus;
import com.dwp.services.time.interpretation.InterpretationModels.VerifiedInterpretationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TimIaEntryQueryContractTest {

    private static final Instant NOW = Instant.parse("2026-10-13T03:00:00Z");
    private static final UUID SUBJECT = uuid(91);
    private static final UUID POPULATION = uuid(92);
    private static final UUID WORKER = uuid(93);
    private static final UUID OTHER_WORKER = uuid(94);
    private static final UUID CORRELATION = uuid(95);
    private static final String DIGEST = "a".repeat(64);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private InterpretationIaProjectionService service;

    @BeforeEach
    void setUp() {
        service = new InterpretationIaProjectionService(
                Clock.fixed(NOW, ZoneOffset.UTC),
                "tim-iaq-041-test-cursor-secret");
    }

    @Test
    void emptyUnavailableHaveNoItems() {
        InterpretationIaQueryModels.InterpretationProjection empty =
                service.project(authority(Set.of(), Set.of()), request(25, null), List.of());
        InterpretationIaQueryModels.InterpretationProjection unavailable =
                service.unavailable(
                        authority(Set.of(), Set.of()), request(25, null),
                        "INTERPRETATION_QUERY_UNAVAILABLE", true);

        assertThat(empty.variantKey()).isEqualTo("INTERPRETATION");
        assertThat(empty.queryState().state()).isEqualTo("EMPTY");
        assertThat(empty.items()).isEmpty();
        assertThat(unavailable.queryState().state()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.queryState().freshness()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.queryState().nextCursor()).isNull();
        assertThat(unavailable.queryState().partialFailures()).singleElement()
                .satisfies(failure -> {
                    assertThat(failure.errorCode())
                            .isEqualTo("INTERPRETATION_QUERY_UNAVAILABLE");
                    assertThat(failure.retryable()).isTrue();
                });
        assertThat(unavailable.items()).isEmpty();
    }

    @Test
    void partialRequiresFailure() {
        InterpretationSummary invalid = summary(1, WORKER, RunStatus.SUCCEEDED, 0, 1);

        InterpretationIaQueryModels.InterpretationProjection partial = service.project(
                authority(Set.of(), Set.of()), request(25, null), List.of(invalid));

        assertThat(partial.queryState().state()).isEqualTo("PARTIAL");
        assertThat(partial.queryState().freshness()).isEqualTo("STALE");
        assertThat(partial.queryState().partialFailures()).singleElement()
                .extracting(InterpretationIaQueryModels.PartialFailure::errorCode)
                .isEqualTo("INVALID_INTERPRETATION_SUMMARY");
        assertThat(partial.items()).isEmpty();
    }

    @Test
    void completeHasNoFailure() {
        InterpretationIaQueryModels.InterpretationProjection projection = service.project(
                authority(Set.of(), Set.of()),
                request(25, null),
                List.of(summary(1, WORKER, RunStatus.SUCCEEDED, 3, 1)),
                Map.of(uuid(1), 7));

        assertThat(projection.projectionVersion()).isEqualTo("1");
        assertThat(projection.queryState().state()).isEqualTo("COMPLETE");
        assertThat(projection.queryState().partialFailures()).isEmpty();
        assertThat(projection.items()).hasSize(1);
    }

    @Test
    void cursorBindsScopeAndRevision() {
        VerifiedInterpretationRequest firstAuthority = authority(Set.of(), Set.of());
        List<InterpretationSummary> rows = List.of(
                summary(1, WORKER, RunStatus.SUCCEEDED, 3, 1),
                summary(2, WORKER, RunStatus.FAILED, 4, 2));
        String cursor = service.project(firstAuthority, request(1, null), rows)
                .queryState().nextCursor();

        assertThat(cursor).matches("[A-Za-z0-9_-]{16,1024}");
        VerifiedInterpretationRequest changedRevision = authority(
                Set.of(), Set.of(), 18, "psr-" + "b".repeat(64));
        assertCode(
                () -> service.project(changedRevision, request(1, cursor), rows),
                ErrorCode.OBJECT_VERSION_CONFLICT);

        InterpretationIaQueryModels.InterpretationQueryRequest changedSort =
                new InterpretationIaQueryModels.InterpretationQueryRequest(
                        1,
                        InterpretationIaQueryModels.InterpretationFilter.empty(),
                        List.of("interpretationResult:DESC"),
                        "ko-KR",
                        "Asia/Seoul",
                        CORRELATION,
                        NOW,
                        cursor);
        assertCode(
                () -> service.project(firstAuthority, changedSort, rows),
                ErrorCode.OBJECT_VERSION_CONFLICT);
    }

    @Test
    void pageHonorsLimitAndStableOrder() {
        List<InterpretationSummary> reverseInput = List.of(
                summary(2, WORKER, RunStatus.SUCCEEDED, 3, 1),
                summary(1, WORKER, RunStatus.SUCCEEDED, 3, 1));
        List<String> expected = List.of(itemId(1, WORKER), itemId(2, WORKER)).stream()
                .sorted().toList();

        InterpretationIaQueryModels.InterpretationProjection first = service.project(
                authority(Set.of(), Set.of()), request(1, null), reverseInput);
        assertThat(first.items()).hasSize(1);
        assertThat(first.items().getFirst().itemPublicId()).isEqualTo(expected.getFirst());
        assertThat(first.queryState().nextCursor()).isNotBlank();

        InterpretationIaQueryModels.InterpretationProjection second = service.project(
                authority(Set.of(), Set.of()),
                request(1, first.queryState().nextCursor()),
                reverseInput);
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().itemPublicId()).isEqualTo(expected.get(1));
        assertThat(second.queryState().nextCursor()).isNull();

        assertCode(
                () -> service.project(
                        authority(Set.of(), Set.of()), request(101, null), reverseInput),
                ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void repositoryWindowCursorDoesNotApplyGlobalOffsetTwice() {
        List<InterpretationSummary> rows = List.of(
                summary(1, WORKER, RunStatus.SUCCEEDED, 3, 1),
                summary(2, WORKER, RunStatus.SUCCEEDED, 3, 1),
                summary(3, WORKER, RunStatus.SUCCEEDED, 3, 1)).stream()
                .sorted(java.util.Comparator.comparing(
                        row -> itemId(row.runPublicId(), row.workerPublicId())))
                .toList();
        var first = service.projectWindow(
                authority(Set.of(), Set.of()), request(1, null), rows.subList(0, 2));
        var second = service.projectWindow(
                authority(Set.of(), Set.of()),
                request(1, first.queryState().nextCursor()),
                rows.subList(1, 3));

        assertThat(first.items()).singleElement()
                .extracting(InterpretationIaQueryModels.InterpretationItem::itemPublicId)
                .isEqualTo(itemId(rows.getFirst().runPublicId(), WORKER));
        assertThat(second.items()).singleElement()
                .extracting(InterpretationIaQueryModels.InterpretationItem::itemPublicId)
                .isEqualTo(itemId(rows.get(1).runPublicId(), WORKER));
        assertThat(second.queryState().nextCursor()).isNotBlank();
    }

    @Test
    void fieldSummaryMatchesSerializedDecisions() {
        InterpretationIaQueryModels.InterpretationProjection projection = service.project(
                authority(Set.of("RULE_VERSION"), Set.of("TRACE_SUMMARY")),
                request(25, null),
                List.of(summary(1, WORKER, RunStatus.SUCCEEDED, 3, 1)));

        assertThat(projection.queryState().fieldDecisionSummary()).isEqualTo(
                new InterpretationIaQueryModels.FieldDecisionSummary(1, 1, 1));
        JsonNode fields = mapper.valueToTree(projection.items().getFirst().fieldGroups());
        assertThat(fields.size()).isEqualTo(2);
        assertThat(fields.path("INTERPRETATION_RESULT").path("decision").asText())
                .isEqualTo("VIEW");
        assertThat(fields.path("RULE_VERSION").path("decision").asText())
                .isEqualTo("MASK");
    }

    @Test
    void deepLinksAndActionsAreAllowlisted() {
        InterpretationIaQueryModels.InterpretationItem item = service.project(
                authority(Set.of(), Set.of()),
                request(25, null),
                List.of(summary(1, WORKER, RunStatus.SUCCEEDED, 3, 1)))
                .items().getFirst();

        assertThat(item.deepLink()).isEqualTo(new InterpretationIaQueryModels.DeepLink(
                "/hr/operations/time/interpretation", uuid(1).toString()));
        assertThat(item.availableActions()).singleElement().satisfies(action -> {
            assertThat(action.actionKey())
                    .isEqualTo("hris.action.tim.interpretation.run");
            assertThat(action.method()).isEqualTo("POST");
            assertThat(action.hrefTemplate())
                    .isEqualTo("/api/time/v1/interpretation-runs");
            assertThat(action.idempotencyPolicy())
                    .isEqualTo("REQUIRED_IDEMPOTENCY_KEY");
            assertThat(action.stepUpPolicy())
                    .isEqualTo("OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
        });
    }

    @Test
    void trustedTenantContextIsNotSerialized() throws Exception {
        InterpretationIaQueryModels.InterpretationProjection projection = service.project(
                authority(Set.of(), Set.of()),
                request(25, null),
                List.of(
                        summary(1, WORKER, RunStatus.SUCCEEDED, 3, 1),
                        summary(2, OTHER_WORKER, RunStatus.SUCCEEDED, 3, 1)));

        String json = mapper.writeValueAsString(projection);

        assertThat(projection.items()).hasSize(1);
        assertThat(json)
                .doesNotContain("tenantId")
                .doesNotContain("workerPublicId")
                .doesNotContain(WORKER.toString())
                .doesNotContain(OTHER_WORKER.toString())
                .doesNotContain(SUBJECT.toString())
                .doesNotContain(POPULATION.toString());
    }

    @Test
    void multiWorkerRunHasStableUniqueItemsWithoutPartialFailure() {
        InterpretationSummary first = summary(1, WORKER, RunStatus.SUCCEEDED, 3, 1);
        InterpretationSummary second = new InterpretationSummary(
                first.runPublicId(),
                OTHER_WORKER,
                first.periodStart(),
                first.periodEnd(),
                first.status(),
                first.ruleVersionPublicId(),
                first.ruleVersionRevision(),
                first.workedMinutes(),
                first.regularMinutes(),
                first.overtimeMinutes(),
                first.attemptNo(),
                first.traceItemCount(),
                first.inputDigest(),
                first.ruleDigest(),
                first.outputDigest(),
                first.createdAt());

        var projection = service.project(
                authority(
                        Set.of(), Set.of(), 17, "psr-" + "a".repeat(64),
                        Set.of("hcm.operations.time.view", "hcm.operations.time.update"),
                        PopulationKind.TIME_GROUP,
                        Set.of(WORKER, OTHER_WORKER)),
                request(25, null),
                List.of(first, second));

        assertThat(projection.queryState().state()).isEqualTo("COMPLETE");
        assertThat(projection.queryState().partialFailures()).isEmpty();
        assertThat(projection.items()).extracting(
                InterpretationIaQueryModels.InterpretationItem::itemPublicId)
                .containsExactlyInAnyOrder(itemId(1, WORKER), itemId(1, OTHER_WORKER));
        assertThat(projection.items()).extracting(item -> item.deepLink().resourcePublicId())
                .containsOnly(first.runPublicId().toString());
    }

    @Test
    void personalSubmitActionIsExposedOnlyForSelfPopulation() {
        Set<String> permissions = Set.of(
                "hcm.operations.time.view", "hcm.personal.time.submit");
        InterpretationSummary row = summary(1, WORKER, RunStatus.SUCCEEDED, 3, 1);

        var nonSelf = service.project(
                authority(
                        Set.of(), Set.of(), 17, "psr-" + "a".repeat(64),
                        permissions, PopulationKind.ORG_UNIT, Set.of(WORKER)),
                request(25, null), List.of(row));
        var self = service.project(
                authority(
                        Set.of(), Set.of(), 17, "psr-" + "a".repeat(64),
                        permissions, PopulationKind.SELF, Set.of(WORKER)),
                request(25, null), List.of(row));

        assertThat(nonSelf.items().getFirst().availableActions()).isEmpty();
        assertThat(self.items().getFirst().availableActions()).singleElement()
                .extracting(InterpretationIaQueryModels.AvailableAction::actionKey)
                .isEqualTo("hris.action.tim.interpretation.run");
    }

    @Test
    void localeAndTimeZoneAreCanonical() {
        InterpretationIaQueryModels.InterpretationQueryRequest source =
                new InterpretationIaQueryModels.InterpretationQueryRequest(
                        25,
                        InterpretationIaQueryModels.InterpretationFilter.empty(),
                        List.of("itemPublicId:ASC"),
                        "ko_kr",
                        "Asia/Seoul",
                        CORRELATION,
                        NOW,
                        null);

        InterpretationIaProjectionService.PreparedQuery prepared =
                service.prepare(authority(Set.of(), Set.of()), source);

        assertThat(prepared.locale()).isEqualTo("ko-KR");
        assertThat(prepared.timeZone()).isEqualTo("Asia/Seoul");

        InterpretationIaQueryModels.InterpretationQueryRequest invalid =
                new InterpretationIaQueryModels.InterpretationQueryRequest(
                        25,
                        InterpretationIaQueryModels.InterpretationFilter.empty(),
                        List.of("itemPublicId:ASC"),
                        "ko-KR",
                        "Not/A_Zone",
                        CORRELATION,
                        NOW,
                        null);
        assertCode(
                () -> service.prepare(authority(Set.of(), Set.of()), invalid),
                ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void fieldDecisionViewMaskOmitSemantics() throws Exception {
        InterpretationIaQueryModels.InterpretationProjection projection = service.project(
                authority(Set.of("RULE_VERSION"), Set.of("TRACE_SUMMARY")),
                request(25, null),
                List.of(summary(1, WORKER, RunStatus.SUCCEEDED, 3, 1)));

        JsonNode fields = mapper.valueToTree(projection.items().getFirst().fieldGroups());

        assertThat(fields.path("INTERPRETATION_RESULT").path("decision").asText())
                .isEqualTo("VIEW");
        assertThat(fields.path("RULE_VERSION").path("decision").asText())
                .isEqualTo("MASK");
        assertThat(fields.path("RULE_VERSION").has("value")).isTrue();
        assertThat(fields.path("RULE_VERSION").path("value").isNull()).isTrue();
        assertThat(fields.has("TRACE_SUMMARY")).isFalse();
        assertThat(mapper.writeValueAsString(projection))
                .doesNotContain("tenantId")
                .doesNotContain("workerPublicId");
    }

    @Test
    void problemStatusAndCorrelationAreClosed() {
        InterpretationIaQueryModels.Problem problem = new InterpretationIaQueryModels.Problem(
                "https://dwp.example/problems/invalid-input",
                "Invalid input",
                400,
                "INVALID_INPUT",
                CORRELATION,
                false);

        assertThat(problem.correlationId()).isEqualTo(CORRELATION);
        assertThatThrownBy(() -> new InterpretationIaQueryModels.Problem(
                "https://dwp.example/problems/error",
                "Error",
                500,
                "INTERNAL_ERROR",
                CORRELATION,
                false))
                .isInstanceOf(IllegalArgumentException.class);

        assertCode(
                () -> service.project(
                        authority(Set.of(), Set.of()), request(0, null), List.of()),
                ErrorCode.INVALID_INPUT_VALUE);
        InterpretationIaQueryModels.InterpretationProjection empty = service.project(
                authority(Set.of(), Set.of()), request(25, null), List.of());
        assertThat(empty.queryState().correlationId()).isEqualTo(CORRELATION);
    }

    private static InterpretationIaQueryModels.InterpretationQueryRequest request(
            int limit,
            String cursor) {
        return new InterpretationIaQueryModels.InterpretationQueryRequest(
                limit,
                InterpretationIaQueryModels.InterpretationFilter.empty(),
                List.of("itemPublicId:ASC"),
                "ko-KR",
                "Asia/Seoul",
                CORRELATION,
                NOW,
                cursor);
    }

    private static VerifiedInterpretationRequest authority(
            Set<String> masked,
            Set<String> omitted) {
        return authority(masked, omitted, 17, "psr-" + "a".repeat(64));
    }

    private static VerifiedInterpretationRequest authority(
            Set<String> masked,
            Set<String> omitted,
            long authorizationRevision,
            String decisionRevision) {
        return authority(
                masked,
                omitted,
                authorizationRevision,
                decisionRevision,
                Set.of("hcm.operations.time.view", "hcm.operations.time.update"),
                PopulationKind.TIME_GROUP,
                Set.of(WORKER));
    }

    private static VerifiedInterpretationRequest authority(
            Set<String> masked,
            Set<String> omitted,
            long authorizationRevision,
            String decisionRevision,
            Set<String> permissions,
            PopulationKind populationKind,
            Set<UUID> workers) {
        return new VerifiedInterpretationRequest(
                41L,
                72L,
                SUBJECT,
                permissions,
                Set.of(
                        InterpretationAction.VIEW,
                        InterpretationAction.RUN,
                        InterpretationAction.TRACE),
                populationKind,
                POPULATION,
                7,
                11,
                DIGEST,
                13,
                InterpretationIaProjectionService.VIEW_PURPOSE,
                authorizationRevision,
                decisionRevision,
                NOW.plusSeconds(300),
                CORRELATION,
                workers,
                masked,
                omitted);
    }

    private static InterpretationSummary summary(
            int ordinal,
            UUID worker,
            RunStatus status,
            long ruleRevision,
            int attempt) {
        return new InterpretationSummary(
                uuid(ordinal),
                worker,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 7),
                status,
                uuid(ordinal + 100),
                ruleRevision,
                480,
                420,
                60,
                attempt,
                0,
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                NOW.minusSeconds(ordinal));
    }

    private static void assertCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(expected);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }

    private static String itemId(int ordinal, UUID worker) {
        return itemId(uuid(ordinal), worker);
    }

    private static String itemId(UUID runId, UUID worker) {
        return InterpretationDigests.sha256(
                "IAQ-041-InterpretationItem.v1", runId, worker);
    }
}
