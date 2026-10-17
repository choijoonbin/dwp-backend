package com.dwp.services.people.hris.workbench.hrm007;

import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Hrm007ControllerContractTest {

    private static final UUID CORRELATION =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void exposesOnlyTheThreeExactOwnerGetPathsAndIsDefaultOff() {
        RequestMapping root = Hrm007IaEntryQueryController.class
                .getAnnotation(RequestMapping.class);
        ConditionalOnProperty condition = Hrm007IaEntryQueryController.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(root.value()).containsExactly("/v1/hris/workbench");
        assertThat(paths()).containsExactlyInAnyOrder(
                "/team/people-changes",
                "/operations/appointments",
                "/configuration/people-employment");
        assertThat(java.util.Arrays.stream(Hrm007IaEntryQueryController.class
                        .getDeclaredMethods())
                .map(method -> method.getAnnotation(Operation.class))
                .filter(java.util.Objects::nonNull)
                .map(Operation::operationId))
                .containsExactlyInAnyOrder("IAQ.OP.005", "IAQ.OP.007", "IAQ.OP.013");
        assertThat(condition.prefix()).isEqualTo(Hrm007RolloutGate.PREFIX);
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void mandatoryEmptyFilterEnvelopeIsAcceptedByEveryQuery() {
        Hrm007IaEntryQueryService service = mock(Hrm007IaEntryQueryService.class);
        Hrm007IaEntryQueryController controller =
                new Hrm007IaEntryQueryController(service);
        when(service.query(any(), any())).thenReturn(result());

        controller.peopleChanges(
                10, List.of("itemPublicId:ASC"), "en-US", "UTC",
                CORRELATION, Instant.parse("2026-10-17T12:00:00Z"), null,
                emptyFilter());
        controller.appointments(
                10, List.of("itemPublicId:ASC"), "en-US", "UTC",
                CORRELATION, Instant.parse("2026-10-17T12:00:00Z"), null,
                emptyFilter());
        controller.peopleEmployment(
                10, List.of("itemPublicId:ASC"), "en-US", "UTC",
                CORRELATION, Instant.parse("2026-10-17T12:00:00Z"), null,
                emptyFilter());

        verify(service).query(eq(Hrm007QueryDefinition.PEOPLE_CHANGES), any());
        verify(service).query(eq(Hrm007QueryDefinition.APPOINTMENTS), any());
        verify(service).query(eq(Hrm007QueryDefinition.PEOPLE_EMPLOYMENT), any());
    }

    @Test
    void nestedFilterIsAcceptedWithoutBaseEnvelope() {
        Hrm007IaEntryQueryService service = mock(Hrm007IaEntryQueryService.class);
        Hrm007IaEntryQueryController controller =
                new Hrm007IaEntryQueryController(service);
        when(service.query(eq(Hrm007QueryDefinition.PEOPLE_CHANGES), any()))
                .thenReturn(result());
        MultiValueMap<String, String> parameters = common();
        parameters.add("filter[approvalStatus][codes]", "DRAFT");

        controller.peopleChanges(
                10, List.of("itemPublicId:ASC"), "en-US", "UTC",
                CORRELATION, Instant.parse("2026-10-17T12:00:00Z"), null,
                parameters);

        verify(service).query(eq(Hrm007QueryDefinition.PEOPLE_CHANGES), any());
    }

    @Test
    void missingAmbiguousOrNonemptyBaseFilterIsRejectedBeforeService() {
        Hrm007IaEntryQueryService service = mock(Hrm007IaEntryQueryService.class);
        Hrm007IaEntryQueryController controller =
                new Hrm007IaEntryQueryController(service);

        MultiValueMap<String, String> missing = common();
        assertRejected(controller, missing);
        MultiValueMap<String, String> ambiguous = common();
        ambiguous.add("filter", "{}");
        ambiguous.add("filter[approvalStatus][codes]", "DRAFT");
        assertRejected(controller, ambiguous);
        MultiValueMap<String, String> nonempty = common();
        nonempty.add("filter", "{\"approvalStatus\":{}}");
        assertRejected(controller, nonempty);
        MultiValueMap<String, String> callerTenant = common();
        callerTenant.add("filter", "{}");
        callerTenant.add("tenantId", "41");
        assertRejected(controller, callerTenant);

        verifyNoInteractions(service);
    }

    @Test
    void requiredAndOptionalTopLevelScalarsRejectMultiplicityPollution() {
        Hrm007IaEntryQueryService service = mock(Hrm007IaEntryQueryService.class);
        Hrm007IaEntryQueryController controller =
                new Hrm007IaEntryQueryController(service);

        for (String key : List.of(
                "limit", "locale", "timeZone", "correlationId")) {
            MultiValueMap<String, String> missing = emptyFilter();
            missing.remove(key);
            assertRejected(controller, missing);
            MultiValueMap<String, String> duplicate = emptyFilter();
            duplicate.add(key, duplicate.getFirst(key));
            assertRejected(controller, duplicate);
        }
        for (String key : List.of("asOf", "cursor")) {
            MultiValueMap<String, String> duplicate = emptyFilter();
            if (!duplicate.containsKey(key)) {
                duplicate.add(key, "cursor-value-0001");
            }
            duplicate.add(key, duplicate.getFirst(key));
            assertRejected(controller, duplicate);
        }

        verifyNoInteractions(service);
    }

    @Test
    void sortIsTheOnlyRepeatableTopLevelParameter() {
        Hrm007IaEntryQueryService service = mock(Hrm007IaEntryQueryService.class);
        Hrm007IaEntryQueryController controller =
                new Hrm007IaEntryQueryController(service);
        when(service.query(any(), any())).thenReturn(result());
        MultiValueMap<String, String> parameters = emptyFilter();
        parameters.add("sort", "approvalStatus:DESC");

        controller.peopleChanges(
                10, List.of("itemPublicId:ASC", "approvalStatus:DESC"),
                "en-US", "UTC", CORRELATION,
                Instant.parse("2026-10-17T12:00:00Z"), null, parameters);

        verify(service).query(eq(Hrm007QueryDefinition.PEOPLE_CHANGES), any());
    }

    private void assertRejected(
            Hrm007IaEntryQueryController controller,
            MultiValueMap<String, String> parameters) {
        assertThatThrownBy(() -> controller.peopleChanges(
                10, List.of("itemPublicId:ASC"), "en-US", "UTC",
                CORRELATION, Instant.parse("2026-10-17T12:00:00Z"), null,
                parameters)).isInstanceOf(Hrm007Exception.class);
    }

    private List<String> paths() {
        return java.util.Arrays.stream(Hrm007IaEntryQueryController.class
                        .getDeclaredMethods())
                .map(method -> method.getAnnotation(GetMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> java.util.Arrays.stream(mapping.value()))
                .sorted()
                .toList();
    }

    private MultiValueMap<String, String> common() {
        MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
        result.add("limit", "10");
        result.add("sort", "itemPublicId:ASC");
        result.add("locale", "en-US");
        result.add("timeZone", "UTC");
        result.add("correlationId", CORRELATION.toString());
        result.add("asOf", "2026-10-17T12:00:00Z");
        return result;
    }

    private MultiValueMap<String, String> emptyFilter() {
        MultiValueMap<String, String> result = common();
        result.add("filter", "{}");
        return result;
    }

    private Hrm007IaEntryQueryService.QueryResult result() {
        Instant asOf = Instant.parse("2026-10-17T12:00:00Z");
        Hrm007Dtos.Projection projection = new Hrm007Dtos.Projection(
                "1", asOf, "PEOPLE_CHANGES",
                new Hrm007Dtos.QueryState(
                        "EMPTY", "scope:1", "CURRENT", null,
                        new Hrm007Dtos.FieldDecisionSummary(0, 0, 0), List.of(),
                        CORRELATION,
                        new Hrm007Dtos.CachePolicy(
                                "psr-" + "a".repeat(64),
                                Hrm007Dtos.INVALIDATION_EVENT, 0)),
                List.of());
        return new Hrm007IaEntryQueryService.QueryResult(
                projection, "psr-" + "a".repeat(64), "\"etag\"");
    }
}
