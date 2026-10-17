package com.dwp.services.people.hris.workbench.hrm007;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm007IaEntryQueryContractTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final UUID CORRELATION =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ACTOR =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Hrm007Projector projector = new Hrm007Projector(mapper);

    @Test
    void emptyUnavailableHaveNoItems() {
        for (Hrm007QueryDefinition definition : Hrm007QueryDefinition.values()) {
            Hrm007Projector.PreparedQuery query = prepared(definition, 10);
            Hrm007Authority.Decision authority = authority(definition, views(definition));
            Hrm007Projector.Page empty = projector.project(
                    query, List.of(), authority, null);
            Hrm007Dtos.Projection emptyProjection = projector.projection(
                    query, authority, empty, null);
            Hrm007Dtos.Projection unavailable = projector.unavailable(query, authority);

            assertThat(emptyProjection.queryState().state()).isEqualTo("EMPTY");
            assertThat(emptyProjection.items()).isEmpty();
            assertThat(emptyProjection.queryState().nextCursor()).isNull();
            assertThat(unavailable.queryState().state()).isEqualTo("UNAVAILABLE");
            assertThat(unavailable.queryState().freshness()).isEqualTo("UNAVAILABLE");
            assertThat(unavailable.queryState().nextCursor()).isNull();
            assertThat(unavailable.items()).isEmpty();
        }
    }

    @Test
    void partialRequiresFailure() {
        Hrm007QueryDefinition definition = Hrm007QueryDefinition.PEOPLE_CHANGES;
        Hrm007Projector.PreparedQuery query = prepared(definition, 10);
        Hrm007Authority.Decision authority = authority(definition, views(definition));
        Hrm007ProjectionSource.PeopleChangeRow invalid =
                new Hrm007ProjectionSource.PeopleChangeRow(
                        "not a public id", "Invalid row", "DRAFT", 1,
                        "ACTIVE", LocalDate.parse("2026-10-17"), 0L,
                        ACTOR.toString());

        Hrm007Projector.Page page = projector.project(
                query, List.of(invalid), authority, null);
        Hrm007Dtos.Projection projection = projector.projection(
                query, authority, page, null);

        assertThat(projection.queryState().state()).isEqualTo("PARTIAL");
        assertThat(projection.queryState().partialFailures()).hasSize(1);
        assertThat(projection.queryState().partialFailures().getFirst().errorCode())
                .isEqualTo("INVALID_SOURCE_PROJECTION");
    }

    @Test
    void completeHasNoFailure() {
        for (Hrm007QueryDefinition definition : Hrm007QueryDefinition.values()) {
            Hrm007Projector.PreparedQuery query = prepared(definition, 10);
            Hrm007Authority.Decision authority = authority(definition, views(definition));
            Hrm007Projector.Page page = projector.project(
                    query, List.of(row(definition, 1)), authority, null);
            Hrm007Dtos.Projection projection = projector.projection(
                    query, authority, page, null);

            assertThat(projection.queryState().state()).isEqualTo("COMPLETE");
            assertThat(projection.queryState().partialFailures()).isEmpty();
            assertThat(projection.variantKey()).isEqualTo(definition.variantKey());
            assertThat(projection.projectionVersion()).isEqualTo("1");
            assertThat(projection.items()).hasSize(1);
            assertThat(projection.items().getFirst().fieldGroups())
                    .containsOnlyKeys(definition.fieldGroups().toArray(String[]::new));
            assertThat(projection.items().getFirst().fieldGroups().values())
                    .allMatch(Hrm007Dtos.ViewedField.class::isInstance);
        }
    }

    @Test
    void eachQueryRejectsAnotherQueryFilterShape() {
        Map<Hrm007QueryDefinition, Object> mismatches = Map.of(
                Hrm007QueryDefinition.PEOPLE_CHANGES,
                emptyFilter(Hrm007QueryDefinition.APPOINTMENTS),
                Hrm007QueryDefinition.APPOINTMENTS,
                emptyFilter(Hrm007QueryDefinition.PEOPLE_EMPLOYMENT),
                Hrm007QueryDefinition.PEOPLE_EMPLOYMENT,
                emptyFilter(Hrm007QueryDefinition.PEOPLE_CHANGES));

        mismatches.forEach((definition, filter) -> assertThatThrownBy(() ->
                projector.prepare(
                        definition,
                        new Hrm007Dtos.QueryRequest(
                                100, filter, List.of("itemPublicId:ASC"),
                                "en-US", "UTC", CORRELATION, NOW, null), NOW))
                .isInstanceOf(Hrm007Exception.class));
    }

    @Test
    void eachQueryCursorRejectsChangedAuthorizationRevision() {
        Hrm007CursorCodec codec = new Hrm007CursorCodec(
                "0123456789abcdef0123456789abcdef", Duration.ofMinutes(15),
                mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        for (Hrm007QueryDefinition definition : Hrm007QueryDefinition.values()) {
            Hrm007CursorCodec.Binding issued = new Hrm007CursorCodec.Binding(
                    41L, definition.queryId(),
                    definition.populations().iterator().next() + ":population",
                    "scope:17", "psr-" + "a".repeat(64), NOW,
                    List.of("itemPublicId:ASC"), 100,
                    definition.queryId().toLowerCase(Locale.ROOT) + ":100",
                    "filter-digest", "en-US", "UTC");
            String cursor = codec.issue(issued);
            Hrm007CursorCodec.Binding expected = issued.withLastItemPublicId(null);

            assertThat(codec.verify(cursor, expected)).isEqualTo(issued.lastItemPublicId());
            assertDenied(() -> codec.verify(cursor, new Hrm007CursorCodec.Binding(
                    expected.tenantId(), expected.queryId(), expected.targetPopulation(),
                    expected.scopeDecisionRevision(), "psr-" + "b".repeat(64),
                    expected.asOf(), expected.sort(), expected.pageLimit(), null,
                    expected.filterDigest(), expected.locale(), expected.timeZone())));
        }
    }

    @Test
    void cursorBindsScopeAndRevision() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Hrm007CursorCodec codec = new Hrm007CursorCodec(
                "0123456789abcdef0123456789abcdef", Duration.ofMinutes(15),
                mapper, clock);
        Hrm007CursorCodec.Binding binding = new Hrm007CursorCodec.Binding(
                41L, "IAQ-005", "DIRECT_REPORTS:population",
                "scope:17", "psr-" + "a".repeat(64), NOW,
                List.of("itemPublicId:ASC"), 25, "change:025",
                "filter-digest", "ko-KR", "Asia/Seoul");
        String cursor = codec.issue(binding);
        Hrm007CursorCodec.Binding expected = binding.withLastItemPublicId(null);

        assertThat(codec.verify(cursor, expected)).isEqualTo("change:025");
        assertDenied(() -> codec.verify(cursor, new Hrm007CursorCodec.Binding(
                42L, expected.queryId(), expected.targetPopulation(),
                expected.scopeDecisionRevision(), expected.authorizationDecisionRevision(),
                expected.asOf(), expected.sort(), expected.pageLimit(), null,
                expected.filterDigest(), expected.locale(), expected.timeZone())));
        assertDenied(() -> codec.verify(cursor, new Hrm007CursorCodec.Binding(
                expected.tenantId(), expected.queryId(), expected.targetPopulation(),
                "scope:18", expected.authorizationDecisionRevision(), expected.asOf(),
                expected.sort(), expected.pageLimit(), null, expected.filterDigest(),
                expected.locale(), expected.timeZone())));
        assertDenied(() -> codec.verify(cursor, new Hrm007CursorCodec.Binding(
                expected.tenantId(), expected.queryId(), expected.targetPopulation(),
                expected.scopeDecisionRevision(), "psr-" + "b".repeat(64),
                expected.asOf(), expected.sort(), expected.pageLimit(), null,
                expected.filterDigest(), expected.locale(), expected.timeZone())));
    }

    @Test
    void pageHonorsLimitAndStableOrder() {
        Hrm007QueryDefinition definition = Hrm007QueryDefinition.PEOPLE_CHANGES;
        Hrm007Projector.PreparedQuery query = projector.prepare(
                definition,
                new Hrm007Dtos.QueryRequest(
                        2, emptyFilter(definition),
                        List.of("changeRequestSummary:ASC"), "en-US", "UTC",
                        CORRELATION, NOW, null), NOW);
        Hrm007Authority.Decision authority = authority(definition, views(definition));
        List<Hrm007ProjectionSource.ProjectionRow> rows = List.of(
                row(definition, 3), row(definition, 1), row(definition, 2));

        Hrm007Projector.Page first = projector.project(query, rows, authority, null);
        Hrm007Projector.Page second = projector.project(
                query, rows, authority, first.lastItemPublicId());

        assertThat(first.items()).extracting(Hrm007Dtos.Item::itemPublicId)
                .containsExactly("change:001", "change:002");
        assertThat(first.hasMore()).isTrue();
        assertThat(second.items()).extracting(Hrm007Dtos.Item::itemPublicId)
                .containsExactly("change:003");
        assertThat(second.hasMore()).isFalse();
        assertThatThrownBy(() -> projector.prepare(
                definition,
                new Hrm007Dtos.QueryRequest(
                        101, emptyFilter(definition), List.of("itemPublicId:ASC"),
                        "en-US", "UTC", CORRELATION, NOW, null), NOW))
                .isInstanceOf(Hrm007Exception.class);
    }

    @Test
    void fieldSummaryMatchesSerializedDecisions() throws Exception {
        Hrm007QueryDefinition definition = Hrm007QueryDefinition.PEOPLE_CHANGES;
        Map<String, Hrm007Authority.FieldDecision> decisions = new LinkedHashMap<>();
        decisions.put("CHANGE_REQUEST_SUMMARY", Hrm007Authority.FieldDecision.VIEW);
        decisions.put("LIMITED_EMPLOYMENT", Hrm007Authority.FieldDecision.MASK);
        decisions.put("APPROVAL_STATUS", Hrm007Authority.FieldDecision.OMIT);
        Hrm007Projector.PreparedQuery query = prepared(definition, 10);
        Hrm007Authority.Decision authority = authority(definition, decisions);
        Hrm007Projector.Page page = projector.project(
                query, List.of(row(definition, 1)), authority, null);
        Hrm007Dtos.Projection projection = projector.projection(
                query, authority, page, null);
        JsonNode json = mapper.valueToTree(projection);

        assertThat(projection.queryState().fieldDecisionSummary())
                .isEqualTo(new Hrm007Dtos.FieldDecisionSummary(1, 1, 1));
        assertThat(json.at("/items/0/fieldGroups").size()).isEqualTo(2);
    }

    @Test
    void deepLinksAndActionsAreAllowlisted() {
        Map<Hrm007QueryDefinition, Set<String>> allowlist = Map.of(
                Hrm007QueryDefinition.PEOPLE_CHANGES, Set.of(
                        "hris.action.hrm.create-employee-change-request",
                        "hris.action.hrm.submit-employee-change-request"),
                Hrm007QueryDefinition.APPOINTMENTS, Set.of(
                        "hris.action.hrm.create-assignment-event",
                        "hris.action.hrm.validate-assignment-event",
                        "hris.action.hrm.submit-assignment-event",
                        "hris.action.hrm.publish-assignment-event"),
                Hrm007QueryDefinition.PEOPLE_EMPLOYMENT, Set.of());
        for (Hrm007QueryDefinition definition : Hrm007QueryDefinition.values()) {
            Hrm007Projector.PreparedQuery query = prepared(definition, 10);
            Hrm007Authority.Decision authority = authority(definition, views(definition));
            Hrm007Dtos.Item item = projector.project(
                    query, List.of(row(definition, 1)), authority, null).items().getFirst();

            assertThat(item.deepLink().route()).isEqualTo(definition.deepLinkRoute());
            assertThat(item.deepLink().resourcePublicId()).isEqualTo(item.itemPublicId());
            assertThat(item.availableActions())
                    .allMatch(action -> allowlist.get(definition).contains(action.actionKey()))
                    .allMatch(action -> "POST".equals(action.method()))
                    .allMatch(action -> "REQUIRED_IDEMPOTENCY_KEY"
                            .equals(action.idempotencyPolicy()))
                    .allMatch(action -> "OWNER_PEP_RISK_POLICY_DECISION_REQUIRED"
                            .equals(action.stepUpPolicy()));
            if (definition == Hrm007QueryDefinition.PEOPLE_EMPLOYMENT) {
                assertThat(item.availableActions()).isEmpty();
            }
        }
    }

    @Test
    void actionsRequireTrustedPermissionsFieldViewOwnershipAndState() {
        Hrm007Authority.ActionTruth all = Hrm007Authority.actionTruth(
                new com.dwp.services.people.security.PeopleRequestContext.Actor(
                        7L, 41L, ACTOR, Set.of(), Set.of(
                        "action.hris_people_change_request:create",
                        "action.hris_assignment_event:manage",
                        "action.hris_workforce_change_approve:approve")));
        assertThat(all).isEqualTo(new Hrm007Authority.ActionTruth(true, true, true));
        assertThat(Hrm007Authority.actionTruth(
                new com.dwp.services.people.security.PeopleRequestContext.Actor(
                        7L, 41L, ACTOR, Set.of(), Set.of(
                        "ACTION.HRIS_PEOPLE_CHANGE_REQUEST",
                        "ACTION.HRIS_ASSIGNMENT_EVENT",
                        "ACTION.HRIS_WORKFORCE_CHANGE_APPROVE"))))
                .isEqualTo(Hrm007Authority.ActionTruth.none());

        Hrm007QueryDefinition changes = Hrm007QueryDefinition.PEOPLE_CHANGES;
        Hrm007ProjectionSource.PeopleChangeRow ownDraft =
                peopleChange("DRAFT", ACTOR.toString());
        assertThat(actions(changes, ownDraft,
                authority(changes, views(changes), Hrm007Authority.ActionTruth.none())))
                .isEmpty();
        assertThat(actions(changes, ownDraft, authority(changes, views(changes), all)))
                .containsExactly(
                        "hris.action.hrm.create-employee-change-request",
                        "hris.action.hrm.submit-employee-change-request");
        assertThat(actions(changes, peopleChange("DRAFT",
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                authority(changes, views(changes), all)))
                .containsExactly("hris.action.hrm.create-employee-change-request");
        assertThat(actions(changes, peopleChange("SUBMITTED", ACTOR.toString()),
                authority(changes, views(changes), all)))
                .containsExactly("hris.action.hrm.create-employee-change-request");
        Map<String, Hrm007Authority.FieldDecision> maskedChanges = views(changes);
        maskedChanges.put("CHANGE_REQUEST_SUMMARY", Hrm007Authority.FieldDecision.MASK);
        assertThat(actions(changes, ownDraft,
                authority(changes, maskedChanges, all))).isEmpty();

        Hrm007QueryDefinition appointments = Hrm007QueryDefinition.APPOINTMENTS;
        Hrm007Authority.ActionTruth manage =
                new Hrm007Authority.ActionTruth(false, true, false);
        Hrm007Authority.ActionTruth approve =
                new Hrm007Authority.ActionTruth(false, false, true);
        assertThat(actions(appointments, appointment("DRAFT"),
                authority(appointments, views(appointments), manage)))
                .containsExactly(
                        "hris.action.hrm.create-assignment-event",
                        "hris.action.hrm.validate-assignment-event");
        assertThat(actions(appointments, appointment("VALIDATED"),
                authority(appointments, views(appointments), manage)))
                .containsExactly(
                        "hris.action.hrm.create-assignment-event",
                        "hris.action.hrm.submit-assignment-event");
        assertThat(actions(appointments, appointment("APPROVED"),
                authority(appointments, views(appointments), manage)))
                .containsExactly("hris.action.hrm.create-assignment-event");
        assertThat(actions(appointments, appointment("APPROVED"),
                authority(appointments, views(appointments), approve)))
                .containsExactly("hris.action.hrm.publish-assignment-event");
        assertThat(actions(appointments, appointment("DRAFT"),
                authority(appointments, views(appointments),
                        Hrm007Authority.ActionTruth.none()))).isEmpty();
        Map<String, Hrm007Authority.FieldDecision> maskedAppointments =
                views(appointments);
        maskedAppointments.put("ASSIGNMENT", Hrm007Authority.FieldDecision.MASK);
        assertThat(actions(appointments, appointment("APPROVED"),
                authority(appointments, maskedAppointments, all))).isEmpty();
    }

    @Test
    void trustedTenantContextIsNotSerialized() throws Exception {
        Hrm007QueryDefinition definition = Hrm007QueryDefinition.APPOINTMENTS;
        Hrm007Projector.PreparedQuery query = prepared(definition, 10);
        Hrm007Authority.Decision authority = authority(definition, views(definition));
        Hrm007Projector.Page page = projector.project(
                query, List.of(row(definition, 1)), authority, null);
        String json = mapper.writeValueAsString(
                projector.projection(query, authority, page, null));

        assertThat(json).doesNotContain(
                "tenantId", "targetPopulation", "profile", "duty", "purpose",
                "HRIS_HR_BUSINESS_PARTNER", "HRIS_DUTY_CORE_VIEWER");
    }

    @Test
    void localeAndTimeZoneAreCanonical() {
        Hrm007Projector.PreparedQuery query = projector.prepare(
                Hrm007QueryDefinition.PEOPLE_CHANGES,
                new Hrm007Dtos.QueryRequest(
                        10, emptyFilter(Hrm007QueryDefinition.PEOPLE_CHANGES),
                        List.of("itemPublicId:ASC"), "ko_kr", "Asia/Seoul",
                        CORRELATION, NOW, null), NOW);

        assertThat(query.locale()).isEqualTo("ko-KR");
        assertThat(query.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(projector.prepare(
                Hrm007QueryDefinition.PEOPLE_CHANGES,
                new Hrm007Dtos.QueryRequest(
                        10, emptyFilter(Hrm007QueryDefinition.PEOPLE_CHANGES),
                        List.of("itemPublicId:ASC"), "en-US", "UTC",
                        CORRELATION, NOW, null), NOW).timeZone()).isEqualTo("Z");
        assertThatThrownBy(() -> projector.prepare(
                Hrm007QueryDefinition.PEOPLE_CHANGES,
                new Hrm007Dtos.QueryRequest(
                        10, emptyFilter(Hrm007QueryDefinition.PEOPLE_CHANGES),
                        List.of("itemPublicId:ASC"), " en-US", "Not/AZone",
                        CORRELATION, NOW, null), NOW))
                .isInstanceOf(Hrm007Exception.class);
        assertThatThrownBy(() -> projector.prepare(
                Hrm007QueryDefinition.PEOPLE_CHANGES,
                new Hrm007Dtos.QueryRequest(
                        10, emptyFilter(Hrm007QueryDefinition.PEOPLE_CHANGES),
                        List.of("itemPublicId:ASC"), "en-US", "PST",
                        CORRELATION, NOW, null), NOW))
                .isInstanceOf(Hrm007Exception.class);
    }

    @Test
    void fieldDecisionViewMaskOmitSemantics() throws Exception {
        Hrm007QueryDefinition definition = Hrm007QueryDefinition.PEOPLE_CHANGES;
        Map<String, Hrm007Authority.FieldDecision> decisions = new LinkedHashMap<>();
        decisions.put("CHANGE_REQUEST_SUMMARY", Hrm007Authority.FieldDecision.VIEW);
        decisions.put("LIMITED_EMPLOYMENT", Hrm007Authority.FieldDecision.MASK);
        decisions.put("APPROVAL_STATUS", Hrm007Authority.FieldDecision.OMIT);
        Hrm007Projector.PreparedQuery query = prepared(definition, 10);
        Hrm007Authority.Decision authority = authority(definition, decisions);
        Hrm007Projector.Page page = projector.project(
                query, List.of(row(definition, 1)), authority, null);
        JsonNode fields = mapper.valueToTree(
                projector.projection(query, authority, page, null))
                .at("/items/0/fieldGroups");

        assertThat(fields.at("/CHANGE_REQUEST_SUMMARY/decision").asText())
                .isEqualTo("VIEW");
        assertThat(fields.at("/CHANGE_REQUEST_SUMMARY/value/revision").asLong())
                .isGreaterThanOrEqualTo(1L);
        assertThat(fields.at("/LIMITED_EMPLOYMENT/decision").asText())
                .isEqualTo("MASK");
        assertThat(fields.at("/LIMITED_EMPLOYMENT/value").isNull()).isTrue();
        assertThat(fields.has("APPROVAL_STATUS")).isFalse();
        assertThat(fields.toString()).doesNotContain("\"decision\":\"OMIT\"");
    }

    @Test
    void problemStatusAndCorrelationAreClosed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("correlationId", CORRELATION.toString());
        var response = new Hrm007ProblemAdvice().closed(
                Hrm007Exception.denied("diagnostic must not escape"), request);
        JsonNode json = mapper.valueToTree(response.getBody());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/problem+json");
        assertThat(json.propertyStream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder(
                        "type", "title", "status", "code", "correlationId", "retryable");
        assertThat(json.path("correlationId").asText()).isEqualTo(CORRELATION.toString());
        assertThat(URI.create(json.path("type").asText()).isAbsolute()).isTrue();
        assertThat(json.path("type").asText())
                .isEqualTo("urn:dwp:problem:hris:hrm007:hris-workbench-access-denied");
        assertThat(json.toString()).doesNotContain("diagnostic");
    }

    private Hrm007Projector.PreparedQuery prepared(
            Hrm007QueryDefinition definition,
            int limit) {
        return projector.prepare(
                definition,
                new Hrm007Dtos.QueryRequest(
                        limit, emptyFilter(definition),
                        List.of("itemPublicId:ASC"), "en-US", "UTC",
                        CORRELATION, NOW, null), NOW);
    }

    private Object emptyFilter(Hrm007QueryDefinition definition) {
        return switch (definition) {
            case PEOPLE_CHANGES -> new Hrm007Dtos.PeopleChangesFilter(
                    null, null, null, null);
            case APPOINTMENTS -> new Hrm007Dtos.AppointmentsFilter(
                    null, null, null, null);
            case PEOPLE_EMPLOYMENT -> new Hrm007Dtos.PeopleEmploymentFilter(
                    null, null, null, null);
        };
    }

    private Hrm007ProjectionSource.ProjectionRow row(
            Hrm007QueryDefinition definition,
            int sequence) {
        String suffix = "%03d".formatted(sequence);
        return switch (definition) {
            case PEOPLE_CHANGES -> new Hrm007ProjectionSource.PeopleChangeRow(
                    "change:" + suffix, "Taylor · TITLE_CHANGE", "DRAFT",
                    1, "ACTIVE", LocalDate.parse("2026-10-17"), 0L,
                    ACTOR.toString());
            case APPOINTMENTS -> new Hrm007ProjectionSource.AppointmentRow(
                    "appointment:" + suffix, "Taylor · HIRE", "DRAFT",
                    NOW.plusSeconds(sequence), NOW.plus(Duration.ofDays(30)),
                    "assignment:" + suffix, "HIRE", "Engineer",
                    LocalDate.parse("2026-10-17"),
                    LocalDate.parse("2026-11-16"), 0L);
            case PEOPLE_EMPLOYMENT -> new Hrm007ProjectionSource.PeopleEmploymentRow(
                    "policy:" + suffix, "Promotion", "ACTIVE",
                    "people-employment.policy.promotion",
                    "people-employment.rule.promotion",
                    LocalDate.parse("1900-01-01"), null, 1, 0L);
        };
    }

    private Hrm007ProjectionSource.PeopleChangeRow peopleChange(
            String status,
            String requesterPublicId) {
        return new Hrm007ProjectionSource.PeopleChangeRow(
                "change:action", "Taylor · TITLE_CHANGE", status,
                1, "ACTIVE", LocalDate.parse("2026-10-17"), 1L,
                requesterPublicId);
    }

    private Hrm007ProjectionSource.AppointmentRow appointment(String status) {
        return new Hrm007ProjectionSource.AppointmentRow(
                "appointment:action", "Taylor · HIRE", status,
                NOW, NOW.plus(Duration.ofDays(30)),
                "assignment:action", "HIRE", "Engineer",
                LocalDate.parse("2026-10-17"),
                LocalDate.parse("2026-11-16"), 1L);
    }

    private List<String> actions(
            Hrm007QueryDefinition definition,
            Hrm007ProjectionSource.ProjectionRow row,
            Hrm007Authority.Decision authority) {
        return projector.project(
                        prepared(definition, 10), List.of(row), authority, null)
                .items().getFirst().availableActions().stream()
                .map(Hrm007Dtos.AvailableAction::actionKey)
                .toList();
    }

    private Map<String, Hrm007Authority.FieldDecision> views(
            Hrm007QueryDefinition definition) {
        Map<String, Hrm007Authority.FieldDecision> result = new LinkedHashMap<>();
        definition.fieldGroups().forEach(key ->
                result.put(key, Hrm007Authority.FieldDecision.VIEW));
        return result;
    }

    private Hrm007Authority.Decision authority(
            Hrm007QueryDefinition definition,
            Map<String, Hrm007Authority.FieldDecision> decisions) {
        return authority(definition, decisions,
                new Hrm007Authority.ActionTruth(true, true, true));
    }

    private Hrm007Authority.Decision authority(
            Hrm007QueryDefinition definition,
            Map<String, Hrm007Authority.FieldDecision> decisions,
            Hrm007Authority.ActionTruth actionTruth) {
        return new Hrm007Authority.Decision(
                41L, 7L, ACTOR,
                definition.populations().iterator().next() + ":population",
                "scope:revision-17", "psr-" + "a".repeat(64),
                new Hrm007Authority.TargetPopulation(
                        definition.populations().iterator().next(), true, 11L,
                        "manager:1", Set.of(), Set.of("DIRECTORY", "EMPLOYMENT"),
                        "population:17"), actionTruth, decisions);
    }

    private void assertDenied(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(Hrm007Exception.class)
                .extracting(value -> ((Hrm007Exception) value).status())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
