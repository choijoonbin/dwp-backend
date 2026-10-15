package com.dwp.services.auth.controller;

import com.dwp.services.auth.dto.HrisRecipientAuthorityDtos;
import com.dwp.services.auth.service.HrisRecipientAuthorityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HrisRecipientAuthorityControllerTest {

    private static final UUID REQUEST_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PERSON_PUBLIC_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String BODY = """
            {"requestId":"11111111-1111-4111-8111-111111111111",
             "tenantId":17,
             "personPublicId":"22222222-2222-4222-8222-222222222222",
             "purpose":"HCM.EMPLOYEE_CHANGE_APPLIED_NOTIFICATION"}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void successIsRawExactSevenFieldNoStoreResponseWithRequestEcho() throws Exception {
        HrisRecipientAuthorityService service = mock(HrisRecipientAuthorityService.class);
        when(service.resolve(any())).thenReturn(new HrisRecipientAuthorityDtos.ResolveResponse(
                REQUEST_ID,
                17L,
                PERSON_PUBLIC_ID,
                93L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                Instant.parse("2026-10-15T04:00:00Z"),
                Instant.parse("2026-10-15T04:00:30Z")));

        var result = mvc(service).perform(post(
                        "/internal/auth/v1/hris-recipient-authority/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.tenantId").value(17))
                .andExpect(jsonPath("$.personPublicId").value(PERSON_PUBLIC_ID.toString()))
                .andExpect(jsonPath("$.userId").value(93))
                .andReturn();

        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsByteArray());
        Set<String> fields = new LinkedHashSet<>();
        response.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "requestId", "tenantId", "personPublicId", "userId",
                "evidenceRevision", "capturedAt", "validUntil");
        verify(service).resolve(any(HrisRecipientAuthorityDtos.ResolveRequest.class));
    }

    @Test
    void unavailableAuthorityUsesFixedStandardNotFoundEnvelopeAndNoStore()
            throws Exception {
        HrisRecipientAuthorityService service = mock(HrisRecipientAuthorityService.class);
        when(service.resolve(any())).thenThrow(
                new HrisRecipientAuthorityService
                        .RecipientAuthorityUnavailableException());

        mvc(service).perform(post("/internal/auth/v1/hris-recipient-authority/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.message")
                        .value("Recipient authority is unavailable."))
                .andExpect(jsonPath("$.errorCode").value("E1004"))
                .andExpect(jsonPath("$.success").value(false));
    }

    private MockMvc mvc(HrisRecipientAuthorityService service) {
        return MockMvcBuilders.standaloneSetup(
                new HrisRecipientAuthorityController(service)).build();
    }
}
