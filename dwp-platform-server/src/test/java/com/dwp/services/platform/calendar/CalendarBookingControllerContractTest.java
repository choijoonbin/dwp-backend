package com.dwp.services.platform.calendar;

import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarBookingControllerContractTest {

    @Mock private CalendarBookingService service;

    @Test
    void openApiSurfaceContainsManagementBookableAvailabilityAndReloadWithoutPublicRoutes() {
        Set<String> routes = new HashSet<>();
        Set<String> operationIds = new HashSet<>();
        for (Method method : CalendarBookingController.class.getDeclaredMethods()) {
            String route = route(method);
            if (route == null) continue;
            routes.add(route);
            Operation operation = method.getAnnotation(Operation.class);
            assertThat(operation).as(method.getName()).isNotNull();
            assertThat(operation.operationId()).as(method.getName()).isNotBlank();
            assertThat(operationIds.add(operation.operationId())).as(operation.operationId())
                    .isTrue();
        }

        assertThat(routes).contains(
                "GET /pages/{pageId}/bookable",
                "GET /pages/{pageId}/availability",
                "POST /pages/{pageId}/holds",
                "POST /pages/{pageId}/appointments",
                "GET /appointments",
                "GET /appointments/{appointmentId}",
                "POST /appointments/{appointmentId}/reschedule",
                "POST /appointments/{appointmentId}/cancel",
                "POST /pages/{pageId}/public-token:rotate",
                "POST /pages/{pageId}/public-token:revoke",
                "POST /appointments/{appointmentId}/management-token:rotate",
                "POST /appointments/{appointmentId}/management-token:revoke");
        assertThat(routes).noneMatch(value -> value.contains("/public/")
                || value.contains("external"));
    }

    @Test
    void capabilityResponseIsNoStoreAndExternalBookingIsExplicitlyDisabled() {
        CalendarBookingController controller = new CalendarBookingController(service);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(service.capabilities()).thenReturn(
                new CalendarBookingDtos.BookingSecurityCapabilities(
                        true, false, "Security controls not installed", 300));

        controller.capabilities(request, response);

        assertThat(response.getHeader("Cache-Control"))
                .isEqualTo("private, no-store, max-age=0");
        assertThat(service.capabilities().externalPublicBookingEnabled()).isFalse();
    }

    @Test
    void controllerIdentityRejectsDuplicateAndNonCanonicalPersonEvidence() {
        MockHttpServletRequest duplicate = request();
        duplicate.addHeader(CalendarBookingAccess.PERSON,
                "123e4567-e89b-42d3-a456-426614174000");
        assertThatThrownBy(() -> CalendarBookingAccess.require(duplicate))
                .isInstanceOf(BaseException.class);

        MockHttpServletRequest aliased = request();
        aliased.removeHeader(CalendarBookingAccess.PERSON);
        aliased.addHeader(CalendarBookingAccess.PERSON,
                "123E4567-E89B-42D3-A456-426614174000");
        assertThatThrownBy(() -> CalendarBookingAccess.require(aliased))
                .isInstanceOf(BaseException.class);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/calendar/booking/capabilities");
        request.addHeader(CalendarBookingAccess.TENANT, "3");
        request.addHeader(CalendarBookingAccess.USER, "17");
        request.addHeader(CalendarBookingAccess.PERSON,
                "123e4567-e89b-42d3-a456-426614174000");
        return request;
    }

    private String route(Method method) {
        List<String> mappings = new ArrayList<>();
        String verb;
        GetMapping get = method.getAnnotation(GetMapping.class);
        PostMapping post = method.getAnnotation(PostMapping.class);
        PutMapping put = method.getAnnotation(PutMapping.class);
        if (get != null) {
            verb = "GET";
            mappings.addAll(List.of(get.value()));
        } else if (post != null) {
            verb = "POST";
            mappings.addAll(List.of(post.value()));
        } else if (put != null) {
            verb = "PUT";
            mappings.addAll(List.of(put.value()));
        } else {
            return null;
        }
        return verb + " " + (mappings.isEmpty() ? "" : mappings.getFirst());
    }
}
