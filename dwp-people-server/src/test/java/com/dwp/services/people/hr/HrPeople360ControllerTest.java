package com.dwp.services.people.hr;

import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.workforce.People360Dtos;
import com.dwp.services.people.workforce.People360ProjectionPolicyProvider;
import com.dwp.services.people.workforce.People360Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
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

class HrPeople360ControllerTest {

    private static final UUID PERSON_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 17);

    private final HrService hr = mock(HrService.class);
    private final People360Service people360 = mock(People360Service.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mvc = MockMvcBuilders.standaloneSetup(new HrController(hr, people360))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void personalHomeBindingSelectsSelfPeople360ProjectionExplicitly() throws Exception {
        when(people360.getSelf(AS_OF)).thenReturn(snapshot(People360Dtos.Archetype.SELF));

        mvc.perform(get("/v1/hr/home")
                        .queryParam("projection", "people360")
                        .queryParam("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.person.personId").value(PERSON_ID.toString()))
                .andExpect(jsonPath("$.data.access.archetype").value("SELF"))
                .andExpect(jsonPath("$.data.access.fieldDecisions.length()").value(22));

        verify(people360).getSelf(AS_OF);
        verify(hr, never()).home();
    }

    @Test
    void personalHomeLegacyResponseRemainsTheDefault() throws Exception {
        mvc.perform(get("/v1/hr/home"))
                .andExpect(status().isOk());

        verify(hr).home();
        verify(people360, never()).getSelf(AS_OF);
    }

    @Test
    void teamBindingSelectsTheManagerProjectionAndPreservesTheLegacyDefault() throws Exception {
        when(people360.getTeam(PERSON_ID, AS_OF))
                .thenReturn(snapshot(People360Dtos.Archetype.MANAGER));

        mvc.perform(get("/v1/hr/team")
                        .queryParam("projection", "people360")
                        .queryParam("personId", PERSON_ID.toString())
                        .queryParam("asOf", AS_OF.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.person.personId").value(PERSON_ID.toString()))
                .andExpect(jsonPath("$.data.access.archetype").value("MANAGER"));

        verify(people360).getTeam(PERSON_ID, AS_OF);
        verify(hr, never()).team();

        mvc.perform(get("/v1/hr/team"))
                .andExpect(status().isOk());

        verify(hr).team();
    }

    @Test
    void selfProjectionRequiresAsOfWhilePepPathMatchingRemainsCharacterizationOnly()
            throws Exception {
        mvc.perform(get("/v1/hr/home")
                        .queryParam("projection", "people360"))
                .andExpect(status().isBadRequest());

        // Path matching is not runtime readiness; HRM-XCON-W1-001 tracks the missing
        // generated query and response-projection binding.
        HcmV3PepRegistry registry = new HcmV3PepRegistry(
                new ObjectMapper().findAndRegisterModules());
        HcmV3PepRegistry.Decision decision = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "GET", "/v1/hr/home", Set.of("APP.HCM:VIEW"), null,
                        "NORMAL", Set.of(), "route.hcm.personal.home.page",
                        "projection=people360&asOf=" + AS_OF));

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void teamProjectionRequiresTargetAndAsOfWhilePepMatchingRemainsCharacterizationOnly()
            throws Exception {
        mvc.perform(get("/v1/hr/team")
                        .queryParam("projection", "people360")
                        .queryParam("personId", PERSON_ID.toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/hr/team")
                        .queryParam("projection", "people360")
                        .queryParam("asOf", AS_OF.toString()))
                .andExpect(status().isBadRequest());

        // Path matching is not runtime readiness; the service gate remains default-off.
        HcmV3PepRegistry registry = new HcmV3PepRegistry(
                new ObjectMapper().findAndRegisterModules());
        HcmV3PepRegistry.Decision decision = registry.authorize(
                new HcmV3PepRegistry.RequestEvidence(
                        "GET", "/v1/hr/team", Set.of("APP.HCM:VIEW"), null,
                        "NORMAL", Set.of(), "route.hcm.team.home.page",
                        "projection=people360&personId=" + PERSON_ID + "&asOf=" + AS_OF));

        assertThat(decision.allowed()).isTrue();
        verify(people360, never()).getTeam(PERSON_ID, null);
    }

    private People360Dtos.Snapshot snapshot(People360Dtos.Archetype archetype) {
        List<People360Dtos.FieldDecision> decisions =
                People360ProjectionPolicyProvider.FIELD_REGISTRY.stream()
                        .map(field -> new People360Dtos.FieldDecision(
                                field, People360Dtos.FieldDecisionValue.VIEW))
                        .toList();
        return new People360Dtos.Snapshot(
                1, AS_OF, People360Dtos.ProjectionState.READY,
                "projection-" + "a".repeat(64),
                new People360Dtos.Person(
                        PERSON_ID, "Synthetic Person", "en", "UTC", "ACTIVE"),
                new People360Dtos.Employment(
                        "SYN-0042", "EMPLOYEE", "ACTIVE", LocalDate.of(2024, 1, 1),
                        "EMPLOYEE", LocalDate.of(2024, 1, 1), null,
                        "Synthetic Legal Employer"),
                new People360Dtos.PrimaryAssignment(
                        "SYN-ASG-0042", "ACTIVE", "Synthetic Engineer",
                        "Synthetic Organization", "Synthetic Job", "Synthetic Grade",
                        "Synthetic Location", "Synthetic Manager",
                        LocalDate.of(2026, 1, 1), null),
                new People360Dtos.Access(
                        archetype,
                        archetype == People360Dtos.Archetype.SELF ? "SELF" : "TEAM",
                        "effective-policy-" + "b".repeat(64), decisions));
    }
}
