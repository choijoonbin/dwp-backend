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
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CalendarTeamAvailabilityControllerTest {
    private static final UUID PERSON = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID GROUP = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private CalendarTeamAvailabilityService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CalendarTeamAvailabilityService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PlatformSecurityFilter security = new PlatformSecurityFilter("trusted", "runtime", false, mapper,
                new PlatformCanaryPepRegistry(mapper), new PlatformApprovalsPepRegistry(mapper));
        mvc = standaloneSetup(new CalendarTeamAvailabilityController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .addFilters(security).build();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-04T00:40:00Z");
        when(service.snapshot(any(), anyString())).thenReturn(new CalendarTeamAvailabilityDtos.Snapshot(
                now.toLocalDate(), "Asia/Seoul", now, now.plusSeconds(30),
                "DWP_NATIVE_CALENDAR", "SHARED_WITH_ME", List.of(), false));
    }

    @Test
    void trustedMemberUsesTheIndependentReadRouteAndServerSideIdentityOnly() throws Exception {
        mvc.perform(request().header("X-DWP-Group-Refs", GROUP.toString())
                        .param("personIds", UUID.randomUUID().toString()).param("tenantId", "999"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.scope").value("SHARED_WITH_ME"))
                .andExpect(jsonPath("$.data.members").isEmpty());
        verify(service).snapshot(new CalendarTeamAvailabilityAccess.Actor(7, 101, PERSON, Set.of(GROUP)), "Asia/Seoul");
    }

    @Test
    void directCallerOrRuntimeTokenCannotPretendToBeTheGateway() throws Exception {
        mvc.perform(replace(request(), "X-DWP-Service-Token", "forged"))
                .andExpect(status().isUnauthorized());
        mvc.perform(replace(request(), "X-DWP-Service-Token", "runtime"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void calendarAndPeopleViewPermissionsAreBothRequired() throws Exception {
        for (String permissions : List.of("APP.CALENDAR:VIEW", "APP.PEOPLE_DIRECTORY:VIEW",
                "APP.CALENDAR:CREATE,APP.PEOPLE_DIRECTORY:VIEW", "ADMIN.CALENDAR:VIEW")) {
            mvc.perform(replace(request(), "X-DWP-Permissions", permissions)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void providerGuestAndNonMemberAdminRolesCannotReadTheMemberSurface() throws Exception {
        for (String roles : List.of("PROVIDER_ADMIN", "GUEST", "TENANT_ADMIN", "WORKSPACE_MEMBER,PROVIDER_ADMIN")) {
            mvc.perform(replace(request(), "X-DWP-Roles", roles)).andExpect(status().isForbidden());
        }
        mvc.perform(request().header("X-DWP-Support-Session-ID", "support"))
                .andExpect(status().isForbidden());
        mvc.perform(replace(request(), "X-DWP-Identity-Plane", "PROVIDER"))
                .andExpect(status().isForbidden());
        mvc.perform(replace(request(), "X-DWP-Identity-Plane", null))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void missingMalformedAndDuplicatePersonIdentityAreFailClosed() throws Exception {
        mvc.perform(replace(request(), "X-DWP-Person-Public-ID", null)).andExpect(status().isForbidden());
        mvc.perform(replace(request(), "X-DWP-Person-Public-ID", "1-1-1-1-1")).andExpect(status().isForbidden());
        mvc.perform(request().header("X-DWP-Person-Public-ID", PERSON.toString())).andExpect(status().isForbidden());
        mvc.perform(request().header("X-DWP-Tenant-ID", "8")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void invalidDuplicateAndOversizedVerifiedGroupsNeverReachTheRepository() throws Exception {
        for (String groups : List.of("not-uuid", GROUP + ",", GROUP + "," + GROUP,
                String.join(",", java.util.stream.IntStream.range(0, 201).mapToObj(value -> UUID.randomUUID().toString()).toList()))) {
            mvc.perform(request().header("X-DWP-Group-Refs", groups)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    private MockHttpServletRequestBuilder request() {
        return get("/v1/calendar/team-availability/snapshot")
                .header("X-DWP-Service-Token", "trusted")
                .header("X-DWP-Tenant-ID", "7")
                .header("X-DWP-User-ID", "101")
                .header("X-DWP-Person-Public-ID", PERSON.toString())
                .header("X-DWP-Identity-Plane", "TENANT")
                .header("X-DWP-Roles", "WORKSPACE_MEMBER")
                .header("X-DWP-Permissions", "APP.CALENDAR:VIEW,APP.PEOPLE_DIRECTORY:VIEW");
    }

    private MockHttpServletRequestBuilder replace(MockHttpServletRequestBuilder builder, String name, String value) {
        return builder.with(request -> {
            request.removeHeader(name);
            if (value != null) request.addHeader(name, value);
            return request;
        });
    }
}
