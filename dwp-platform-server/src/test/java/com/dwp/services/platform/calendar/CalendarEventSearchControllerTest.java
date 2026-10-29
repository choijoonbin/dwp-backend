package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.platform.security.PlatformApprovalsPepRegistry;
import com.dwp.services.platform.security.PlatformCanaryPepRegistry;
import com.dwp.services.platform.security.PlatformSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CalendarEventSearchControllerTest {

    private static final UUID PERSON = UUID.fromString(
            "a0000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime FROM = OffsetDateTime.parse(
            "2026-10-28T00:00:00Z");
    private static final OffsetDateTime TO = FROM.plusDays(1);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private CalendarEventSearchService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CalendarEventSearchService.class);
        PlatformSecurityFilter security = new PlatformSecurityFilter(
                "trusted", "runtime", false, mapper,
                new PlatformCanaryPepRegistry(mapper),
                new PlatformApprovalsPepRegistry(mapper));
        mvc = standaloneSetup(new CalendarEventSearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .addFilters(security)
                .build();
        when(service.search(
                anyLong(), anyLong(), any(), any(), any(), any(), any(), any(),
                any(), anyInt(), any()))
                .thenReturn(new CalendarEventSearchDtos.SearchPage(
                        List.of(), null, false, "team sync",
                        new CalendarEventSearchDtos.Range(FROM, TO, "UTC"),
                        OffsetDateTime.parse("2026-10-28T00:00:00Z")));
    }

    @Test
    void viewAuthorityReturnsPrivateNoStoreSearchEnvelope() throws Exception {
        mvc.perform(request("APP.CALENDAR:VIEW"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.hasMore").value(false))
                .andExpect(jsonPath("$.data.normalizedQuery").value("team sync"))
                .andExpect(jsonPath("$.data.range.timeZone").value("UTC"))
                .andExpect(jsonPath("$.data.generatedAt").exists());

        verify(service).search(
                7L, 101L, PERSON, null, "en", " Team  Sync ",
                FROM, TO, "UTC", 25, null);
    }

    @Test
    void missingCalendarViewOrCanonicalIdentityNeverReachesSearch() throws Exception {
        mvc.perform(request("APP.CALENDAR:UPDATE"))
                .andExpect(status().isForbidden());
        mvc.perform(replace(request("APP.CALENDAR:VIEW"),
                        CalendarEventSearchAccess.USER, "0101"))
                .andExpect(status().isUnauthorized());
        mvc.perform(request("APP.CALENDAR:VIEW")
                        .header(CalendarEventSearchAccess.PERSON, PERSON.toString()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void accessBoundaryRejectsDuplicateUppercaseAndNoncanonicalIdentity() {
        MockHttpServletRequest duplicate = accessRequest();
        duplicate.addHeader(CalendarEventSearchAccess.TENANT, "7");
        assertThatThrownBy(() -> CalendarEventSearchAccess.require(duplicate))
                .isInstanceOf(BaseException.class);

        MockHttpServletRequest aliased = accessRequest();
        aliased.removeHeader(CalendarEventSearchAccess.PERSON);
        aliased.addHeader(CalendarEventSearchAccess.PERSON,
                PERSON.toString().toUpperCase(java.util.Locale.ROOT));
        assertThatThrownBy(() -> CalendarEventSearchAccess.require(aliased))
                .isInstanceOf(BaseException.class);
    }

    private MockHttpServletRequestBuilder request(String permission) {
        return get(CalendarEventSearchAccess.PATH)
                .header("X-DWP-Service-Token", "trusted")
                .header(CalendarEventSearchAccess.TENANT, "7")
                .header(CalendarEventSearchAccess.USER, "101")
                .header(CalendarEventSearchAccess.PERSON, PERSON.toString())
                .header("X-DWP-Identity-Plane", "TENANT")
                .header("X-DWP-Roles", "WORKSPACE_MEMBER")
                .header("X-DWP-Permissions", permission)
                .header(CalendarEventSearchAccess.LOCALE, "en")
                .param("q", " Team  Sync ")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("timeZone", "UTC")
                .param("limit", "25");
    }

    private MockHttpServletRequestBuilder replace(
            MockHttpServletRequestBuilder request, String name, String value) {
        return request.with(servletRequest -> {
            servletRequest.removeHeader(name);
            servletRequest.addHeader(name, value);
            return servletRequest;
        });
    }

    private MockHttpServletRequest accessRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", CalendarEventSearchAccess.PATH);
        request.addHeader(CalendarEventSearchAccess.TENANT, "7");
        request.addHeader(CalendarEventSearchAccess.USER, "101");
        request.addHeader(CalendarEventSearchAccess.PERSON, PERSON.toString());
        return request;
    }
}
