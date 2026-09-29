package com.dwp.services.people.workforce;

import com.dwp.services.people.directory.PeopleDirectoryService;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkforcePeople360ControllerTest {

    private static final UUID PERSON_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 17);

    private final PeopleDirectoryService directory = mock(PeopleDirectoryService.class);
    private final People360Service people360 = mock(People360Service.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mvc = MockMvcBuilders.standaloneSetup(
                        new WorkforcePeopleController(directory, people360))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void registeredDetailBindingSelectsPeople360OnlyForTheExactProjection() throws Exception {
        when(people360.get(PERSON_ID, AS_OF)).thenReturn(snapshot());

        mvc.perform(get("/v1/workforce/people/{publicId}", PERSON_ID)
                        .queryParam("projection", "people360")
                        .queryParam("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.schemaVersion").value(1))
                .andExpect(jsonPath("$.data.person.personId").value(PERSON_ID.toString()))
                .andExpect(jsonPath("$.data.person.preferredLocale").doesNotExist())
                .andExpect(jsonPath("$.data.employment.workerNumber")
                        .value(People360Dtos.MASK_LITERAL))
                .andExpect(jsonPath("$.data.employment.workerId").doesNotExist())
                .andExpect(jsonPath("$.data.primaryAssignment.assignmentKey").doesNotExist())
                .andExpect(jsonPath("$.data.primaryAssignment.assignmentId").doesNotExist())
                .andExpect(jsonPath("$.data.access.fieldDecisions.length()").value(22));

        verify(people360).get(PERSON_ID, AS_OF);
        verify(directory, never()).getWorkforce(PERSON_ID, AS_OF);
    }

    @Test
    void legacyDetailBindingRemainsCompatibleWhenProjectionIsAbsent() throws Exception {
        mvc.perform(get("/v1/workforce/people/{publicId}", PERSON_ID)
                        .queryParam("asOf", AS_OF.toString()))
                .andExpect(status().isOk());

        verify(directory).getWorkforce(PERSON_ID, AS_OF);
        verify(people360, never()).get(PERSON_ID, AS_OF);
    }

    @Test
    void people360ListUsesTheSameSnapshotContract() throws Exception {
        People360Dtos.Page page = new People360Dtos.Page(
                List.of(listSnapshot()), "next-synthetic", 1, true, AS_OF);
        when(people360.search("synthetic", "ACTIVE", null, 25, AS_OF)).thenReturn(page);

        mvc.perform(get("/v1/workforce/people")
                        .queryParam("projection", "people360")
                        .queryParam("asOf", AS_OF.toString())
                        .queryParam("query", "synthetic")
                        .queryParam("status", "ACTIVE")
                        .queryParam("size", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].access.fieldDecisions.length()").value(22))
                .andExpect(jsonPath("$.data.items[0].person.timeZone").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].employment.workerNumber").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].primaryAssignment.jobGradeName")
                        .doesNotExist())
                .andExpect(jsonPath(
                        "$.data.items[0].access.fieldDecisions[?(@.field == 'person.timeZone')].decision")
                        .value("OMIT"))
                .andExpect(jsonPath("$.data.nextCursor").value("next-synthetic"));

        verify(people360).search("synthetic", "ACTIVE", null, 25, AS_OF);
    }

    @Test
    void people360ProjectionRequiresAnExplicitAsOfDate() throws Exception {
        mvc.perform(get("/v1/workforce/people/{publicId}", PERSON_ID)
                        .queryParam("projection", "people360"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/workforce/people")
                        .queryParam("projection", "people360"))
                .andExpect(status().isBadRequest());

        verify(people360, never()).get(PERSON_ID, null);
        verify(people360, never()).search(null, null, null, 50, null);
    }

    @Test
    void generatedPepPathMatchingDoesNotProveTheMissingProjectionBinding() {
        // Characterization only: HRM-XCON-W1-001 keeps runtime activation disabled until
        // the central query and response-projection contract is generated and released.
        HcmV3PepRegistry registry = new HcmV3PepRegistry(
                new ObjectMapper().findAndRegisterModules());
        String query = "projection=people360&asOf=" + AS_OF;

        HcmV3PepRegistry.Decision list = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "GET", "/v1/workforce/people",
                        Set.of("DATA.WORKFORCE:VIEW"), null,
                        "NORMAL", Set.of(), "route.hcm.operations.people.page", query));
        HcmV3PepRegistry.Decision detail = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "GET", "/v1/workforce/people/" + PERSON_ID,
                        Set.of("DATA.WORKFORCE:VIEW"), null,
                        "NORMAL", Set.of(),
                        "route.hcm.operations.person-detail.data", query));

        assertThat(list.allowed()).isTrue();
        assertThat(detail.allowed()).isTrue();
    }

    private People360Dtos.Snapshot snapshot() {
        List<People360Dtos.FieldDecision> decisions = new ArrayList<>();
        People360ProjectionPolicyProvider.FIELD_REGISTRY.forEach(field -> decisions.add(
                new People360Dtos.FieldDecision(
                        field,
                        switch (field) {
                            case "person.preferredLocale",
                                 "primaryAssignment.assignmentKey" ->
                                    People360Dtos.FieldDecisionValue.OMIT;
                            case "employment.workerNumber" ->
                                    People360Dtos.FieldDecisionValue.MASK;
                            default -> People360Dtos.FieldDecisionValue.VIEW;
                        })));
        return new People360Dtos.Snapshot(
                1, AS_OF, People360Dtos.ProjectionState.READY,
                "projection-" + "a".repeat(64),
                new People360Dtos.Person(
                        PERSON_ID, "Synthetic Person", null, "Asia/Seoul", "ACTIVE"),
                new People360Dtos.Employment(
                        People360Dtos.MASK_LITERAL, "EMPLOYEE", "ACTIVE",
                        LocalDate.of(2024, 1, 1), "EMPLOYEE",
                        LocalDate.of(2024, 1, 1), null, "Synthetic Legal Employer"),
                new People360Dtos.PrimaryAssignment(
                        null, "ACTIVE", "Synthetic Engineer", "Synthetic Organization",
                        "Synthetic Job", "Synthetic Grade", "Synthetic Location",
                        "Synthetic Manager", LocalDate.of(2026, 1, 1), null),
                new People360Dtos.Access(
                        People360Dtos.Archetype.MANAGER, "TEAM",
                        "effective-policy-" + "b".repeat(64), List.copyOf(decisions)));
    }

    private People360Dtos.Snapshot listSnapshot() {
        Set<String> viewed = Set.of(
                "person.displayName",
                "person.lifecycleState",
                "employment.workerStatus",
                "primaryAssignment.businessTitle",
                "primaryAssignment.organizationName",
                "primaryAssignment.jobProfileName");
        List<People360Dtos.FieldDecision> decisions =
                People360ProjectionPolicyProvider.FIELD_REGISTRY.stream()
                        .map(field -> new People360Dtos.FieldDecision(
                                field,
                                viewed.contains(field)
                                        ? People360Dtos.FieldDecisionValue.VIEW
                                        : People360Dtos.FieldDecisionValue.OMIT))
                        .toList();
        return new People360Dtos.Snapshot(
                1, AS_OF, People360Dtos.ProjectionState.READY,
                "projection-" + "c".repeat(64),
                new People360Dtos.Person(PERSON_ID, "Synthetic Person", null, null, "ACTIVE"),
                new People360Dtos.Employment(
                        null, null, "ACTIVE", null, null, null, null, null),
                new People360Dtos.PrimaryAssignment(
                        null, null, "Synthetic Engineer", "Synthetic Organization",
                        "Synthetic Job", null, null, null, null, null),
                new People360Dtos.Access(
                        People360Dtos.Archetype.HR_OPERATOR, "WORKFORCE_POLICY",
                        "effective-policy-" + "d".repeat(64), decisions));
    }
}
