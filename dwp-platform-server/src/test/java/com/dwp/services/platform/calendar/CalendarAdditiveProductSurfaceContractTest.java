package com.dwp.services.platform.calendar;

import com.dwp.core.security.ProductSurfaceScopeKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarAdditiveProductSurfaceContractTest {

    private static final String UUID_VALUE =
            "123e4567-e89b-42d3-a456-426614174000";

    @Test
    void v2EventsIsAnExactReadOnlyViewPolicyOwnerBinding() {
        CalendarProductSurfaceContract contract = new CalendarProductSurfaceContract();

        CalendarProductSurfaceContract.Binding binding = contract.resolveOwner(
                "GET", "/v2/calendar/events").orElseThrow();

        assertThat(binding.routeContractKey())
                .isEqualTo(CalendarProductSurfaceContract.EVENTS_V2_DATA_ROUTE);
        assertThat(binding.gatewayPath()).isEqualTo("/api/platform/v2/calendar/events");
        assertThat(binding.routeKind()).isEqualTo(
                CalendarProductSurfaceContract.RouteKind.DATA);
        assertThat(binding.accessContractType()).isEqualTo(
                CalendarProductSurfaceContract.AccessContractType.POLICY);
        assertThat(binding.accessContractKey()).isEqualTo(
                CalendarProductSurfaceContract.ACCESS_POLICY_KEY);
        assertThat(binding.resolvedAuthority()).isEqualTo("APP.CALENDAR:VIEW");
        assertThat(binding.readOnly()).isTrue();
        assertThat(contract.resolveOwner("POST", "/v2/calendar/events")).isEmpty();
        assertThat(contract.resolveOwner("GET", "/v2/calendar/events/")).isEmpty();
        assertThat(contract.resolveOwner("GET", "/v2/calendar/%65vents")).isEmpty();
    }

    @Test
    void v2EventsExecutesThroughTheOwnerPepAndRejectsWrongAuthority() throws Exception {
        CalendarProductSurfaceContract contract = new CalendarProductSurfaceContract();
        CalendarProductSurfacePepFilter filter = new CalendarProductSurfacePepFilter(
                true,
                contract,
                new CalendarProductSurfaceAccessPolicy(),
                new ObjectMapper().findAndRegisterModules());

        MockHttpServletResponse allowed = new MockHttpServletResponse();
        filter.doFilter(ownerRequest("APP.CALENDAR:VIEW"), allowed, new MockFilterChain());
        assertThat(allowed.getStatus()).isEqualTo(200);

        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(ownerRequest("APP.CALENDAR:CREATE"), denied, new MockFilterChain());
        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void reminderAccessRejectsNonCanonicalOrEncodedEventPaths() {
        UUID eventId = UUID.fromString(UUID_VALUE);
        for (String path : new String[] {
                "/v1/calendar/events/123E4567-E89B-42D3-A456-426614174000/reminders",
                "/v1/calendar/events/%31" + UUID_VALUE.substring(1) + "/reminders",
                "/v1/calendar/events/" + UUID_VALUE + ";source=spoof/reminders",
                "/v1/calendar/events/" + UUID_VALUE + "/%72eminders",
                "/v1/calendar/events/" + UUID_VALUE + "/reminders/"
        }) {
            MockHttpServletRequest request = reminderRequest(path);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> CalendarReminderAccess.require(request, eventId))
                    .as(path)
                    .isInstanceOf(com.dwp.core.exception.BaseException.class);
        }

        assertThat(CalendarReminderAccess.require(
                reminderRequest(
                        "/v1/calendar/events/" + UUID_VALUE + "/reminders"),
                eventId).personPublicId()).isEqualTo(UUID.fromString(UUID_VALUE));
    }

    private MockHttpServletRequest ownerRequest(String permissions) {
        long tenant = 7L;
        long user = 101L;
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v2/calendar/events");
        request.addHeader("X-DWP-Tenant-ID", Long.toString(tenant));
        request.addHeader("X-DWP-User-ID", Long.toString(user));
        request.addHeader("X-DWP-Person-Public-ID", UUID_VALUE);
        request.addHeader("X-DWP-Roles", "WORKSPACE_MEMBER");
        request.addHeader("X-DWP-Permissions", permissions);
        request.addHeader(CalendarProductSurfacePepFilter.ROLLOUT_STATE_HEADER, "110");
        request.addHeader(
                CalendarProductSurfacePepFilter.ROLLOUT_REVISION_HEADER,
                "rollout-" + "0123456789abcdef".repeat(4));
        request.addHeader(CalendarProductSurfacePepFilter.ROLLOUT_COHORT_HEADER, "full");
        request.addHeader(
                CalendarProductSurfacePepFilter.ROUTE_CONTRACT_HEADER,
                CalendarProductSurfaceContract.EVENTS_V2_DATA_ROUTE);
        request.addHeader(CalendarProductSurfacePepFilter.ACTIVE_ACCESS_MODE_HEADER, "NORMAL");
        request.addHeader(
                CalendarProductSurfacePepFilter.CURRENT_REVISION_HEADER,
                "psr-" + "0123456789abcdef".repeat(4));
        request.addHeader(
                CalendarProductSurfacePepFilter.CURRENT_REVALIDATE_AT_HEADER,
                "2099-01-01T00:00:00Z");
        request.addHeader(
                CalendarProductSurfacePepFilter.CONTEXT_HEADER,
                "psc-" + "a".repeat(64));
        request.addHeader(
                CalendarProductSurfacePepFilter.SCOPE_HEADER,
                ProductSurfaceScopeKey.key(
                        tenant,
                        user,
                        CalendarProductSurfaceContract.PRODUCT_ID,
                        CalendarProductSurfaceContract.SURFACE_KEY,
                        "SELF",
                        "SELF"));
        return request;
    }

    private MockHttpServletRequest reminderRequest(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", path);
        request.addHeader(CalendarReminderAccess.TENANT, "7");
        request.addHeader(CalendarReminderAccess.USER, "101");
        request.addHeader(CalendarReminderAccess.PERSON, UUID_VALUE);
        return request;
    }
}
