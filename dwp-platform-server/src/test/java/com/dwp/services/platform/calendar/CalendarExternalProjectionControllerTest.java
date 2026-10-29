package com.dwp.services.platform.calendar;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.platform.security.PlatformApprovalsPepRegistry;
import com.dwp.services.platform.security.PlatformCanaryPepRegistry;
import com.dwp.services.platform.security.PlatformSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CalendarExternalProjectionControllerTest {

    private static final UUID PERSON = UUID.fromString(
            "10000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-10-28T00:00:00Z");
    private static final OffsetDateTime TO = FROM.plusDays(7);

    private CalendarExternalProjectionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CalendarExternalProjectionService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PlatformSecurityFilter security = new PlatformSecurityFilter(
                "trusted", "runtime", false, mapper,
                new PlatformCanaryPepRegistry(mapper),
                new PlatformApprovalsPepRegistry(mapper));
        mvc = standaloneSetup(new CalendarExternalProjectionController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .addFilters(security)
                .build();
        when(service.project(any(), eq(FROM), eq(TO), eq(100)))
                .thenReturn(new CalendarExternalDtos.Projection(
                        List.of(),
                        List.of(new CalendarExternalDtos.SourceEvidence(
                                null, CalendarExternalDtos.Provider.MICROSOFT_365,
                                CalendarExternalDtos.SourceStatus.UNAVAILABLE, null,
                                "MICROSOFT_CALENDAR_CONNECTOR_NOT_CONFIGURED", false)),
                        true, false, FROM));
    }

    @Test
    void trustedCalendarMemberGetsPrivateNoStoreProjectionEvidence() throws Exception {
        mvc.perform(request())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.data.events").isEmpty())
                .andExpect(jsonPath("$.data.sources[0].provider").value("MICROSOFT_365"))
                .andExpect(jsonPath("$.data.sources[0].status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.partial").value(true));
        verify(service).project(
                new CalendarExternalAccess.Actor(7L, 42L, PERSON), FROM, TO, 100);
    }

    @Test
    void missingDuplicateOrNonCanonicalIdentityNeverReachesTheService() throws Exception {
        mvc.perform(replace(request(), "X-DWP-Person-Public-ID", null))
                .andExpect(status().isUnauthorized());
        mvc.perform(request().header("X-DWP-Person-Public-ID", PERSON.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(replace(request(), "X-DWP-User-ID", "042"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void calendarViewPermissionIsRequired() throws Exception {
        mvc.perform(replace(request(), "X-DWP-Permissions", "APP.CALENDAR:CREATE"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private MockHttpServletRequestBuilder request() {
        return get("/v2/calendar/external-events")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .header("X-DWP-Service-Token", "trusted")
                .header("X-DWP-Tenant-ID", "7")
                .header("X-DWP-User-ID", "42")
                .header("X-DWP-Person-Public-ID", PERSON.toString())
                .header("X-DWP-Identity-Plane", "TENANT")
                .header("X-DWP-Roles", "WORKSPACE_MEMBER")
                .header("X-DWP-Permissions", "APP.CALENDAR:VIEW");
    }

    private MockHttpServletRequestBuilder replace(
            MockHttpServletRequestBuilder builder,
            String name,
            String value) {
        return builder.with(request -> {
            request.removeHeader(name);
            if (value != null) request.addHeader(name, value);
            return request;
        });
    }
}
