package com.dwp.services.people.hris.organization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class HrmOrganizationPositionsContractTest {

    private static final UUID ROOT =
            UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID POSITION =
            UUID.fromString("00000000-0000-4000-8000-000000000201");
    private static final UUID INCUMBENT =
            UUID.fromString("00000000-0000-4000-8000-000000000301");
    private static final Instant AS_OF = Instant.parse("2026-10-16T15:30:00Z");

    private OrganizationGraphService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(OrganizationGraphService.class);
        mvc = standaloneSetup(new OrganizationGraphController(service))
                .setControllerAdvice(new OrganizationGraphAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().findAndRegisterModules()
                                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
                .build();
    }

    @Test
    void getReturnsTheUnwrappedClosedV2ProjectionAndCanonicalInstant() throws Exception {
        OrganizationGraphWithPositionsV2 response =
                new OrganizationGraphWithPositionsV2(
                        2,
                        AS_OF,
                        LocalDate.parse("2026-10-16"),
                        ROOT,
                        0,
                        "REPORTING",
                        true,
                        List.of(new OrganizationGraphWithPositionsV2.OrganizationNodeV2(
                                ROOT, null, "Root", 0)),
                        List.of(new OrganizationGraphWithPositionsV2.OrganizationPositionV2(
                                POSITION, "POS-001", "Chief Executive", ROOT, null,
                                "FILLED", "REGULAR", "CRITICAL", BigDecimal.ONE,
                                new BigDecimal("250000.00"), "GBP", "Executive",
                                "London", LocalDate.parse("2026-01-01"),
                                List.of(INCUMBENT), 0)));
        when(service.query(AS_OF, ROOT, 0, "REPORTING", true)).thenReturn(response);

        mvc.perform(get("/v1/hris/organizations/graph")
                        .param("asOf", "2026-10-17T00:30:00+09:00")
                        .param("rootPublicId", ROOT.toString())
                        .param("depth", "0")
                        .param("relationshipType", "REPORTING")
                        .param("includePositions", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.schemaVersion").value(2))
                .andExpect(jsonPath("$.asOf").value("2026-10-16T15:30:00Z"))
                .andExpect(jsonPath("$.effectiveDate").value("2026-10-16"))
                .andExpect(jsonPath("$.rootPublicId").value(ROOT.toString()))
                .andExpect(jsonPath("$.depth").value(0))
                .andExpect(jsonPath("$.relationshipType").value("REPORTING"))
                .andExpect(jsonPath("$.positionsIncluded").value(true))
                .andExpect(jsonPath("$.nodes[0].organizationPublicId")
                        .value(ROOT.toString()))
                .andExpect(jsonPath("$.nodes[0].parentPublicId").doesNotExist())
                .andExpect(jsonPath("$.nodes[0].name").value("Root"))
                .andExpect(jsonPath("$.nodes[0].depth").value(0))
                .andExpect(jsonPath("$.positions[0].positionPublicId")
                        .value(POSITION.toString()))
                .andExpect(jsonPath("$.positions[0].positionKey").value("POS-001"))
                .andExpect(jsonPath("$.positions[0].organizationPublicId")
                        .value(ROOT.toString()))
                .andExpect(jsonPath("$.positions[0].incumbentPersonPublicIds[0]")
                        .value(INCUMBENT.toString()))
                .andExpect(jsonPath("$.positions[0].subordinatePositionCount").value(0));
    }

    @Test
    void missingRootAndAdditionalOrDuplicateQueryMembersFailClosed() throws Exception {
        mvc.perform(get("/v1/hris/organizations/graph")
                        .param("asOf", AS_OF.toString())
                        .param("depth", "1")
                        .param("relationshipType", "REPORTING")
                        .param("includePositions", "false"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code")
                        .value(OrganizationGraphService.INVALID_QUERY));

        mvc.perform(get("/v1/hris/organizations/graph")
                        .param("asOf", AS_OF.toString())
                        .param("rootPublicId", ROOT.toString())
                        .param("depth", "1", "2")
                        .param("relationshipType", "REPORTING")
                        .param("includePositions", "false")
                        .param("extra", "closed"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value(OrganizationGraphService.INVALID_QUERY));

        verifyNoInteractions(service);
    }

    @Test
    void dateOnlyAndOffsetFreeLocalDateTimeNeverReachTheService() throws Exception {
        for (String invalidAsOf : List.of("2026-10-17", "2026-10-17T00:30:00")) {
            mvc.perform(get("/v1/hris/organizations/graph")
                            .param("asOf", invalidAsOf)
                            .param("rootPublicId", ROOT.toString())
                            .param("depth", "1")
                            .param("relationshipType", "REPORTING")
                            .param("includePositions", "false"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code")
                            .value(OrganizationGraphService.INVALID_QUERY));
        }
        verifyNoInteractions(service);
    }

    @Test
    void legalAndCostRelationshipModelsUseTheSealed422Problem() throws Exception {
        for (String relationship : List.of("LEGAL", "COST")) {
            when(service.query(AS_OF, ROOT, 1, relationship, false)).thenThrow(
                    new OrganizationGraphProblem(
                            org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                            OrganizationGraphService.UNSUPPORTED_MODEL,
                            "The requested organization relationship model is not supported."));

            mvc.perform(get("/v1/hris/organizations/graph")
                            .param("asOf", AS_OF.toString())
                            .param("rootPublicId", ROOT.toString())
                            .param("depth", "1")
                            .param("relationshipType", relationship)
                            .param("includePositions", "false"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code")
                            .value(OrganizationGraphService.UNSUPPORTED_MODEL));
        }
    }
}
