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
                List.of(), Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR);
        EmployeeChangeDtos.QueryState unavailable = new EmployeeChangeDtos.QueryState(
                "UNAVAILABLE", SCOPE_REVISION, "STALE", null,
                new EmployeeChangeDtos.FieldDecisionSummary(0, 0, 0),
                List.of(), CORRELATION,
                new EmployeeChangeDtos.CachePolicy(
                        0, "dwp.hris.access.assignment.changed.v1", SCOPE_REVISION));

        assertThat(empty.queryState().state()).isEqualTo("EMPTY");
        assertThat(empty.items()).isEmpty();
        assertThat(stateInvariant(unavailable, List.of())).isTrue();
        assertThat(stateInvariant(unavailable, List.of(item(1)))).isFalse();
    }

    @Test
    void partialRequiresFailure() {
        EmployeeChangeDtos.QueryState invalid = state("PARTIAL", List.of());
        EmployeeChangeDtos.QueryState valid = state(
                "PARTIAL",
                List.of(new EmployeeChangeDtos.PartialFailure(
                        "employee-change.approval", "OWNER_UNAVAILABLE",
                        CORRELATION, true)));

        assertThat(stateInvariant(invalid, List.of())).isFalse();
        assertThat(stateInvariant(valid, List.of())).isTrue();
    }

    @Test
    void completeHasNoFailure() {
        EmployeeChangeDtos.WorkbenchProjection projection = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(row(1, "DRAFT", ACTOR)),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR);

        assertThat(projection.queryState().state()).isEqualTo("COMPLETE");
        assertThat(projection.queryState().partialFailures()).isEmpty();
        assertThat(stateInvariant(projection.queryState(), projection.items())).isTrue();
    }

    @Test
    void cursorBindsScopeAndRevision() {
        EmployeeChangeWorkbenchProjector.PreparedQuery first = projector.prepare(
                request(1, null), SCOPE_REVISION, NOW);
        String cursor = projector.project(
                first,
                List.of(row(1, "DRAFT", ACTOR), row(2, "SUBMITTED", uuid(70))),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR)
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
                reverseInput, Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR);

        assertThat(first.items()).hasSize(1);
        assertThat(first.items().getFirst().itemPublicId()).isEqualTo(uuid(1).toString());
        assertThat(first.queryState().nextCursor()).isNotBlank();

        EmployeeChangeDtos.WorkbenchProjection second = projector.project(
                projector.prepare(
                        request(1, first.queryState().nextCursor()), SCOPE_REVISION, NOW),
                reverseInput, Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR);
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().itemPublicId()).isEqualTo(uuid(2).toString());
        assertThat(second.queryState().nextCursor()).isNull();
    }

    @Test
    void fieldSummaryMatchesSerializedDecisions() {
        EmployeeChangeDtos.WorkbenchProjection projection = projector.project(
                projector.prepare(request(25, null), SCOPE_REVISION, NOW),
                List.of(row(1, "DRAFT", ACTOR)),
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR);
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
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR).items().getFirst();

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
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR).items().getFirst();
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
                Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR);

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
                List.of(), Set.of("DIRECTORY", "EMPLOYMENT"), ACTOR);
        assertThat(projection.queryState().correlationId()).isEqualTo(CORRELATION);
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
        return new EmployeeChangeRepository.WorkbenchRow(
                uuid(ordinal), state, "PROFILE_UPDATE", LocalDate.of(2026, 10, 12),
                ordinal + 2L, List.of("PERSON_NAME", "PERSON_PROFILE"),
                NOW.minusSeconds(ordinal), "Employee " + ordinal, requester, ordinal + 100L);
    }

    private EmployeeChangeDtos.WorkbenchItem item(int ordinal) {
        return new EmployeeChangeDtos.WorkbenchItem(
                uuid(ordinal).toString(), "Employee " + ordinal, "DRAFT", Map.of(),
                new EmployeeChangeDtos.DeepLink(
                        EmployeeChangeDtos.DEEP_LINK_ROUTE, uuid(ordinal).toString()),
                List.of());
    }

    private EmployeeChangeDtos.QueryState state(
            String state,
            List<EmployeeChangeDtos.PartialFailure> failures) {
        return new EmployeeChangeDtos.QueryState(
                state, SCOPE_REVISION, "PARTIAL".equals(state) ? "STALE" : "CURRENT",
                null, new EmployeeChangeDtos.FieldDecisionSummary(0, 0, 0),
                failures, CORRELATION,
                new EmployeeChangeDtos.CachePolicy(
                        0, "dwp.hris.access.assignment.changed.v1", SCOPE_REVISION));
    }

    private boolean stateInvariant(
            EmployeeChangeDtos.QueryState state,
            List<EmployeeChangeDtos.WorkbenchItem> items) {
        if (List.of("EMPTY", "UNAVAILABLE").contains(state.state()) && !items.isEmpty()) {
            return false;
        }
        if ("PARTIAL".equals(state.state()) && state.partialFailures().isEmpty()) {
            return false;
        }
        return !"COMPLETE".equals(state.state()) || state.partialFailures().isEmpty();
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
