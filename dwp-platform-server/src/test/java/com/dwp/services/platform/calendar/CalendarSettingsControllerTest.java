package com.dwp.services.platform.calendar;

import com.dwp.core.exception.GlobalExceptionHandler;
import com.dwp.services.platform.security.PlatformApprovalsPepRegistry;
import com.dwp.services.platform.security.PlatformCanaryPepRegistry;
import com.dwp.services.platform.security.PlatformSecurityFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.calendar.CalendarSettingsDtos.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CalendarSettingsControllerTest {

    private static final UUID PERSON = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID DELEGATE = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private CalendarSettingsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(CalendarSettingsService.class);
        PlatformSecurityFilter security = new PlatformSecurityFilter(
                "trusted", "runtime", false, mapper,
                new PlatformCanaryPepRegistry(mapper),
                new PlatformApprovalsPepRegistry(mapper));
        mvc = standaloneSetup(new CalendarSettingsController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .addFilters(security)
                .build();
        when(service.settings(any())).thenReturn(settings(0));
        when(service.updateSettings(any(), any(), any())).thenReturn(settings(1));
        when(service.resetSettings(any(), any(), any())).thenReturn(settings(2));
    }

    @Test
    void verifiedOwnerReadsOnlyTheirHeaderBoundSettingsWithoutSharedCaching() throws Exception {
        mvc.perform(request(get("/v1/calendar/settings"), "APP.CALENDAR:VIEW"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store, max-age=0"))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.governance[0].key").value("WORKING_DAYS"));

        verify(service).settings(new CalendarSettingsAccess.Actor(7, 101, PERSON));
    }

    @Test
    void missingMalformedOrDuplicateIdentityNeverReachesTheService() throws Exception {
        mvc.perform(replace(request(get("/v1/calendar/settings"), "APP.CALENDAR:VIEW"),
                        CalendarSettingsAccess.PERSON, null))
                .andExpect(status().isForbidden());
        mvc.perform(replace(request(get("/v1/calendar/settings"), "APP.CALENDAR:VIEW"),
                        CalendarSettingsAccess.USER, "007"))
                .andExpect(status().isForbidden());
        mvc.perform(request(get("/v1/calendar/settings"), "APP.CALENDAR:VIEW")
                        .header(CalendarSettingsAccess.PERSON, PERSON.toString()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void updatesAndResetRequireCalendarUpdateAndCarryCorrelationEvidence() throws Exception {
        String update = mapper.writeValueAsString(new UpdateSettingsRequest(
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                LocalTime.of(9, 0), LocalTime.of(18, 0), "Asia/Seoul",
                DayOfWeek.MONDAY, 30, SpeedyMeetingMode.FIVE_TEN,
                5, DefaultVisibility.FREE_BUSY, 10, 0L));
        mvc.perform(request(put("/v1/calendar/settings"), "APP.CALENDAR:VIEW")
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isForbidden());

        mvc.perform(request(put("/v1/calendar/settings"), "APP.CALENDAR:UPDATE")
                        .header(CalendarSettingsAccess.CORRELATION, "corr-update")
                        .contentType(MediaType.APPLICATION_JSON).content(update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));
        verify(service).updateSettings(
                eq(new CalendarSettingsAccess.Actor(7, 101, PERSON)),
                eq("corr-update"), any(UpdateSettingsRequest.class));

        mvc.perform(request(post("/v1/calendar/settings/reset"), "APP.CALENDAR:UPDATE")
                        .header(CalendarSettingsAccess.CORRELATION, "corr-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        verify(service).resetSettings(
                eq(new CalendarSettingsAccess.Actor(7, 101, PERSON)),
                eq("corr-reset"), eq(new ResetSettingsRequest(1L)));
    }

    @Test
    void delegationCommandsUseOnlyTheAuthenticatedOwnersPathlessIdentity() throws Exception {
        Delegation response = new Delegation(
                UUID.randomUUID(), PERSON, DELEGATE,
                List.of(DelegationScope.RESPOND),
                java.time.OffsetDateTime.parse("2026-09-17T00:00:00Z"),
                java.time.OffsetDateTime.parse("2026-09-18T00:00:00Z"),
                DelegationStatus.ACTIVE, 0,
                java.time.OffsetDateTime.parse("2026-09-17T00:00:00Z"),
                java.time.OffsetDateTime.parse("2026-09-17T00:00:00Z"));
        when(service.createDelegation(any(), any(), any())).thenReturn(response);
        String body = mapper.writeValueAsString(new CreateDelegationRequest(
                DELEGATE,
                List.of(DelegationScope.RESPOND),
                response.validFrom(), response.validUntil()));

        mvc.perform(request(post("/v1/calendar/settings/delegations"), "APP.CALENDAR:UPDATE")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delegatePersonPublicId").value(DELEGATE.toString()));
        verify(service).createDelegation(
                eq(new CalendarSettingsAccess.Actor(7, 101, PERSON)),
                isNull(), any(CreateDelegationRequest.class));

        when(service.delegations(any())).thenReturn(List.of(response));
        mvc.perform(request(get("/v1/calendar/settings/delegations"), "APP.CALENDAR:VIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].delegationId")
                        .value(response.delegationId().toString()));

        Delegation revoked = new Delegation(
                response.delegationId(), PERSON, DELEGATE, response.scopes(),
                response.validFrom(), response.validUntil(), DelegationStatus.REVOKED,
                1, response.createdAt(), response.updatedAt());
        when(service.revokeDelegation(any(), any(), any(), any())).thenReturn(revoked);
        mvc.perform(request(post("/v1/calendar/settings/delegations/"
                        + response.delegationId() + "/revoke"), "APP.CALENDAR:UPDATE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.version").value(1));
        verify(service).revokeDelegation(
                eq(new CalendarSettingsAccess.Actor(7, 101, PERSON)),
                eq(response.delegationId()), isNull(), eq(new RevokeDelegationRequest(0L)));
    }

    private MockHttpServletRequestBuilder request(
            MockHttpServletRequestBuilder request,
            String permissions) {
        return request
                .header("X-DWP-Service-Token", "trusted")
                .header(CalendarSettingsAccess.TENANT, "7")
                .header(CalendarSettingsAccess.USER, "101")
                .header(CalendarSettingsAccess.PERSON, PERSON.toString())
                .header("X-DWP-Identity-Plane", "TENANT")
                .header("X-DWP-Roles", "WORKSPACE_MEMBER")
                .header("X-DWP-Permissions", permissions);
    }

    private MockHttpServletRequestBuilder replace(
            MockHttpServletRequestBuilder request,
            String name,
            String value) {
        return request.with(servletRequest -> {
            servletRequest.removeHeader(name);
            if (value != null) servletRequest.addHeader(name, value);
            return servletRequest;
        });
    }

    private Settings settings(long version) {
        return new Settings(
                List.of(DayOfWeek.MONDAY), LocalTime.of(9, 0), LocalTime.of(18, 0),
                "Asia/Seoul", DayOfWeek.MONDAY, 30, SpeedyMeetingMode.FIVE_TEN,
                5, DefaultVisibility.FREE_BUSY, 10,
                List.of(new SettingGovernance(
                        SettingKey.WORKING_DAYS, SettingSource.SYSTEM_DEFAULT,
                        false, true, false)), version, null);
    }
}
