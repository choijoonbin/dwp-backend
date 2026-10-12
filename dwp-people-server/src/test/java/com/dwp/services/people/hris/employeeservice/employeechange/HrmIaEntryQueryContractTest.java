package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrmIaEntryQueryContractTest {

    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");
    private static final UUID ACTOR = uuid(91);
    private static final UUID CORRELATION = uuid(92);
    private static final String SCOPE_REVISION = "scope:employee-change:31";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private EmployeeChangeWorkbenchProjector projector;

    @BeforeEach
    void setUp() {
        EmployeeChangeCanonicalizer canonicalizer = new EmployeeChangeCanonicalizer(mapper);
        projector = new EmployeeChangeWorkbenchProjector(mapper, canonicalizer);
    }

    @Test
    void emptyUnavailableHaveNoItems() {
        EmployeeChangeDtos.WorkbenchProjection empty = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(), Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions());
        EmployeeChangeDtos.WorkbenchProjection unavailable = projector.unavailable(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                "EMPLOYEE_CHANGE_QUERY_UNAVAILABLE", true);

        assertThat(empty.queryState().state()).isEqualTo("EMPTY");
        assertThat(empty.items()).isEmpty();
        assertThat(unavailable.queryState().state()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.queryState().freshness()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.queryState().partialFailures()).singleElement()
                .satisfies(failure -> {
                    assertThat(failure.errorCode())
                            .isEqualTo("EMPLOYEE_CHANGE_QUERY_UNAVAILABLE");
                    assertThat(failure.retryable()).isTrue();
                });
        assertThat(unavailable.items()).isEmpty();
    }

    @Test
    void partialRequiresFailure() {
        EmployeeChangeDtos.WorkbenchProjection partial = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(row(1, "DRAFT", ACTOR, 0L)),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions());

        assertThat(partial.queryState().state()).isEqualTo("PARTIAL");
        assertThat(partial.queryState().freshness()).isEqualTo("STALE");
        assertThat(partial.queryState().partialFailures()).singleElement()
                .extracting(EmployeeChangeDtos.PartialFailure::errorCode)
                .isEqualTo("INVALID_REQUEST_REVISION");
        assertThat(partial.items()).isEmpty();
    }

    @Test
    void completeHasNoFailure() {
        EmployeeChangeDtos.WorkbenchProjection projection = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(row(1, "DRAFT", ACTOR)),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions());

        assertThat(projection.queryState().state()).isEqualTo("COMPLETE");
        assertThat(projection.queryState().partialFailures()).isEmpty();
        assertThat(projection.items()).hasSize(1);
    }

    @Test
    void cursorBindsScopeAndRevision() {
        EmployeeChangeWorkbenchProjector.PreparedQuery first = projector.prepare(
                request(1, null), SCOPE_REVISION, NOW);
        String cursor = projector.project(
                first,
                List.of(row(1, "DRAFT", ACTOR), row(2, "SUBMITTED", uuid(70))),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions())
                .queryState().nextCursor();

        assertThat(cursor).matches("[A-Za-z0-9_-]{16,1024}");
        assertCode(() -> projector.prepare(
                request(1, cursor), "scope:employee-change:changed", NOW),
                ErrorCode.OBJECT_VERSION_CONFLICT);
        EmployeeChangeDtos.WorkbenchQuery changedQuery = new EmployeeChangeDtos.WorkbenchQuery(
                1, EmployeeChangeDtos.WorkbenchFilter.empty(),
                List.of("approvalStatus:DESC"), "ko-KR", "Asia/Seoul",
                CORRELATION, NOW, cursor);
        assertCode(() -> projector.prepare(changedQuery, SCOPE_REVISION, NOW),
                ErrorCode.OBJECT_VERSION_CONFLICT);
    }

    @Test
    void pageHonorsLimitAndStableOrder() {
        List<EmployeeChangeRepository.WorkbenchRow> reverseInput = List.of(
                row(2, "SUBMITTED", uuid(70)), row(1, "DRAFT", ACTOR));
        EmployeeChangeDtos.WorkbenchProjection first = projector.project(
                projector.prepare(request(1, null), SCOPE_REVISION, NOW),
                reverseInput, Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions());

        assertThat(first.items()).hasSize(1);
        assertThat(first.items().getFirst().itemPublicId()).isEqualTo(uuid(1).toString());
        assertThat(first.queryState().nextCursor()).isNotBlank();

        EmployeeChangeDtos.WorkbenchProjection second = projector.project(
                projector.prepare(
                        request(1, first.queryState().nextCursor()), SCOPE_REVISION, NOW),
                reverseInput, Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions());
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().itemPublicId()).isEqualTo(uuid(2).toString());
        assertThat(second.queryState().nextCursor()).isNull();
    }

    @Test
    void fieldSummaryMatchesSerializedDecisions() {
        EmployeeChangeDtos.WorkbenchProjection projection = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(row(1, "DRAFT", ACTOR)),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions());
        EmployeeChangeDtos.FieldDecisionSummary summary =
                projection.queryState().fieldDecisionSummary();

        assertThat(summary).isEqualTo(
                new EmployeeChangeDtos.FieldDecisionSummary(3, 0, 0));
        assertThat(mapper.valueToTree(
                projection.items().getFirst().fieldGroups()).size())
                .isEqualTo(summary.viewCount());
    }

    @Test
    void deepLinksAndActionsAreAllowlisted() {
        EmployeeChangeDtos.WorkbenchItem draft = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(row(1, "DRAFT", ACTOR)),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions()).items().getFirst();

        assertThat(draft.deepLink()).isEqualTo(new EmployeeChangeDtos.DeepLink(
                "/hr/operations/people/employee-change-requests", uuid(1).toString()));
        assertThat(draft.availableActions())
                .extracting(EmployeeChangeDtos.AvailableAction::actionKey)
                .containsExactly(
                        "hris.action.hrm.create-employee-change-request",
                        "hris.action.hrm.submit-employee-change-request");
        assertThat(draft.availableActions()).allSatisfy(action -> {
            assertThat(action.method()).isEqualTo("POST");
            assertThat(action.idempotencyPolicy())
                    .isEqualTo("REQUIRED_IDEMPOTENCY_KEY");
            assertThat(action.stepUpPolicy())
                    .isEqualTo("OWNER_PEP_RISK_POLICY_DECISION_REQUIRED");
            assertThat(action.hrefTemplate()).startsWith("/api/people/v1/hris/");
        });

        EmployeeChangeDtos.WorkbenchItem approved = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(row(2, "APPROVED", uuid(70))),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions()).items().getFirst();
        assertThat(approved.availableActions())
                .extracting(EmployeeChangeDtos.AvailableAction::actionKey)
                .containsExactly(
                        "hris.action.hrm.create-employee-change-request",
                        "hris.action.hrm.apply-employee-change-request");
    }

    @Test
    void trustedTenantContextIsNotSerialized() throws Exception {
        EmployeeChangeDtos.WorkbenchProjection projection = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(row(1, "DRAFT", ACTOR)),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions());

        String json = mapper.writeValueAsString(projection);

        assertThat(json)
                .doesNotContain("tenantId")
                .doesNotContain("requesterPublicId")
                .doesNotContain(ACTOR.toString());
    }

    @Test
    void localeAndTimeZoneAreCanonical() {
        EmployeeChangeDtos.WorkbenchQuery source = new EmployeeChangeDtos.WorkbenchQuery(
                25, EmployeeChangeDtos.WorkbenchFilter.empty(),
                List.of("itemPublicId:ASC"), "ko_kr", "Asia/Seoul",
                CORRELATION, NOW, null);

        EmployeeChangeWorkbenchProjector.PreparedQuery prepared =
                projector.prepare(source, SCOPE_REVISION, NOW);

        assertThat(prepared.locale()).isEqualTo("ko-KR");
        assertThat(prepared.timeZone()).isEqualTo("Asia/Seoul");
        EmployeeChangeDtos.WorkbenchQuery invalid = new EmployeeChangeDtos.WorkbenchQuery(
                25, EmployeeChangeDtos.WorkbenchFilter.empty(),
                List.of("itemPublicId:ASC"), "ko-KR", "Not/A_Zone",
                CORRELATION, NOW, null);
        assertCode(() -> projector.prepare(invalid, SCOPE_REVISION, NOW),
                ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    void fieldDecisionViewMaskOmitSemantics() throws Exception {
        Map<String, EmployeeChangeDtos.FieldDecision<?>> fields = new LinkedHashMap<>();
        fields.put("CHANGE_REQUEST_SUMMARY", EmployeeChangeDtos.FieldDecision.view(
                new EmployeeChangeDtos.AggregateValue(2, 3, NOW)));
        fields.put("APPROVAL_STATUS", EmployeeChangeDtos.FieldDecision.mask(
                "FIELD_POLICY_DENIED", "employee.change.approval-status.masked"));
        EmployeeChangeDtos.WorkbenchItem item = new EmployeeChangeDtos.WorkbenchItem(
                uuid(1).toString(), "Restricted employee change", "DRAFT",
                fields, new EmployeeChangeDtos.DeepLink(
                        EmployeeChangeDtos.DEEP_LINK_ROUTE, uuid(1).toString()), List.of());

        JsonNode value = mapper.valueToTree(item.fieldGroups());

        assertThat(value.path("CHANGE_REQUEST_SUMMARY").path("decision").asText())
                .isEqualTo("VIEW");
        assertThat(value.path("APPROVAL_STATUS").path("decision").asText())
                .isEqualTo("MASK");
        assertThat(value.path("APPROVAL_STATUS").has("value")).isTrue();
        assertThat(value.path("APPROVAL_STATUS").path("value").isNull()).isTrue();
        assertThat(value.has("EMPLOYMENT")).isFalse();
    }

    @Test
    void problemStatusAndCorrelationAreClosed() {
        EmployeeChangeDtos.WorkbenchQuery invalid = new EmployeeChangeDtos.WorkbenchQuery(
                101, EmployeeChangeDtos.WorkbenchFilter.empty(),
                List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul",
                CORRELATION, NOW, null);

        assertCode(() -> projector.prepare(invalid, SCOPE_REVISION, NOW),
                ErrorCode.INVALID_INPUT_VALUE);
        EmployeeChangeDtos.WorkbenchProjection projection = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(), Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions());
        assertThat(projection.queryState().correlationId()).isEqualTo(CORRELATION);
    }

    @Test
    void draftProjectionKeepsCanonicalPositiveRevision() {
        EmployeeChangeDtos.WorkbenchProjection projection = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(row(1, "DRAFT", ACTOR, 1L)),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions());

        assertThat(projection.queryState().state()).isEqualTo("COMPLETE");
        assertThat(projection.items().getFirst().fieldGroups().values())
                .allSatisfy(decision -> {
                    assertThat(decision).isInstanceOf(EmployeeChangeDtos.ViewFieldDecision.class);
                    Object value = ((EmployeeChangeDtos.ViewFieldDecision<?>) decision).value();
                    long revision = value instanceof EmployeeChangeDtos.AggregateValue aggregate
                            ? aggregate.revision()
                            : value instanceof EmployeeChangeDtos.StatusValue status
                            ? status.revision()
                            : ((EmployeeChangeDtos.EffectiveRangeValue) value).revision();
                    assertThat(revision).isEqualTo(1L);
                });
    }

    @Test
    void appliesFilterGlobalOrderAndCursorBeyondFiveHundredRows() {
        List<EmployeeChangeRepository.WorkbenchRow> rows = java.util.stream.IntStream
                .rangeClosed(1, 650)
                .mapToObj(ordinal -> row(
                        ordinal,
                        ordinal <= 525 ? "SUBMITTED" : "APPROVED",
                        uuid(70)))
                .sorted(java.util.Comparator.comparing(
                        EmployeeChangeRepository.WorkbenchRow::publicId).reversed())
                .toList();
        EmployeeChangeDtos.WorkbenchFilter filter = new EmployeeChangeDtos.WorkbenchFilter(
                null, new EmployeeChangeDtos.StatusFilter(List.of("APPROVED"), 1L),
                null, null);
        EmployeeChangeDtos.WorkbenchQuery firstRequest = new EmployeeChangeDtos.WorkbenchQuery(
                100, filter, List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul",
                CORRELATION, NOW, null);

        EmployeeChangeDtos.WorkbenchProjection first = projector.project(
                projector.prepare(firstRequest, SCOPE_REVISION, NOW), rows,
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions());
        assertThat(first.queryState().state()).isEqualTo("COMPLETE");
        assertThat(first.items()).hasSize(100);
        assertThat(first.items().getFirst().itemPublicId()).isEqualTo(uuid(526).toString());
        assertThat(first.items().getLast().itemPublicId()).isEqualTo(uuid(625).toString());
        assertThat(first.queryState().nextCursor()).isNotNull();

        EmployeeChangeDtos.WorkbenchQuery secondRequest = new EmployeeChangeDtos.WorkbenchQuery(
                100, filter, List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul",
                CORRELATION, NOW, first.queryState().nextCursor());
        EmployeeChangeDtos.WorkbenchProjection second = projector.project(
                projector.prepare(secondRequest, SCOPE_REVISION, NOW), rows,
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR, actions());
        assertThat(second.items()).hasSize(25);
        assertThat(second.items().getFirst().itemPublicId()).isEqualTo(uuid(626).toString());
        assertThat(second.items().getLast().itemPublicId()).isEqualTo(uuid(650).toString());
        assertThat(second.queryState().nextCursor()).isNull();
    }

    @Test
    void actionsFailClosedWhenCapabilityTruthIsAbsent() {
        EmployeeChangeDtos.WorkbenchProjection projection = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(row(1, "DRAFT", ACTOR)),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR,
                EmployeeChangeAuthority.ActionTruth.none());

        assertThat(projection.items().getFirst().availableActions()).isEmpty();
    }

    private EmployeeChangeDtos.WorkbenchQuery request(int limit, String cursor) {
        return new EmployeeChangeDtos.WorkbenchQuery(
                limit, EmployeeChangeDtos.WorkbenchFilter.empty(),
                List.of("itemPublicId:ASC"), "ko-KR", "Asia/Seoul",
                CORRELATION, NOW, cursor);
    }

    private EmployeeChangeRepository.WorkbenchRow row(
            int ordinal,
            String state,
            UUID requester) {
        return row(ordinal, state, requester, ordinal + 2L);
    }

    private EmployeeChangeRepository.WorkbenchRow row(
            int ordinal,
            String state,
            UUID requester,
            long version) {
        return new EmployeeChangeRepository.WorkbenchRow(
                uuid(ordinal), state, "PROFILE_UPDATE", LocalDate.of(2026, 10, 12),
                version, List.of("PERSON_NAME", "PERSON_PROFILE"),
                NOW.minusSeconds(ordinal), "Employee " + ordinal, requester, ordinal + 100L);
    }

    private EmployeeChangeAuthority.ActionTruth actions() {
        return new EmployeeChangeAuthority.ActionTruth(true, true, true);
    }

    private void assertCode(Runnable operation, ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(expected);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
