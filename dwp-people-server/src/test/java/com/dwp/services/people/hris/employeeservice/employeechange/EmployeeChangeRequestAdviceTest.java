package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class EmployeeChangeRequestAdviceTest {

    private static final UUID CORRELATION =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID REQUEST =
            UUID.fromString("10000000-0000-4000-8000-000000000003");

    private EmployeeChangeService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(EmployeeChangeService.class);
        mvc = standaloneSetup(new EmployeeChangeRequestController(service))
                .setControllerAdvice(
                        new GlobalExceptionHandler(mock(MessageSource.class)),
                        new EmployeeChangeRequestAdvice())
                .build();
    }

    @Test
    void workbenchFailureUsesExactIaqProblemAndMandatoryHeaders() throws Exception {
        when(service.workbench(any())).thenThrow(new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        mvc.perform(get("/v1/hris/workbench/operations/employee/change/requests")
                        .param("limit", "25")
                        .param("filter", "{}")
                        .param("sort", "itemPublicId:ASC")
                        .param("locale", "ko-KR")
                        .param("timeZone", "Asia/Seoul")
                        .param("correlationId", CORRELATION.toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(header().string("X-Correlation-ID", CORRELATION.toString()))
                .andExpect(header().string(
                        "X-Authorization-Decision-Revision", "unavailable"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"v:0\""))
                .andExpect(jsonPath("$.type")
                        .value("urn:dwp:problem:AUTHORITY_RESOLUTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code")
                        .value("AUTHORITY_RESOLUTION_UNAVAILABLE"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION.toString()))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void commandDomainFailureUsesExactHrisProblemAndMandatoryHeaders() throws Exception {
        when(service.submit(
                any(UUID.class), anyString(), anyString(), any(UUID.class),
                nullable(String.class)))
                .thenThrow(new BaseException(ErrorCode.INVALID_STATE));

        mvc.perform(post("/v1/hris/employee-change-requests/{requestPublicId}/submit", REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Idempotency-Key", "employee-change-key-0001")
                        .header(HttpHeaders.IF_MATCH, "\"v:1\"")
                        .header("X-Correlation-ID", CORRELATION.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("private")))
                .andExpect(header().string("X-Correlation-ID", CORRELATION.toString()))
                .andExpect(header().string(
                        "X-Authorization-Decision-Revision", "unavailable"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"v:0\""))
                .andExpect(jsonPath("$.type").value("urn:dwp:problem:E3002"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("E3002"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION.toString()))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.safeDetail").doesNotExist())
                .andExpect(jsonPath("$.currentVersion").doesNotExist())
                .andExpect(jsonPath("$.receiptId").doesNotExist())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void malformedControllerInputIsClosedAsProblemBeforeServiceInvocation() throws Exception {
        mvc.perform(post("/v1/hris/employee-change-requests/{requestPublicId}/submit", REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Idempotency-Key", "employee-change-key-0001")
                        .header("X-Correlation-ID", CORRELATION.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION.toString()));
    }
}
