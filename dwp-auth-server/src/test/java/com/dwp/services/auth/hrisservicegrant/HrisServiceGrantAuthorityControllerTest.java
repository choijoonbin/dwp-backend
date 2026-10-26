package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HrisServiceGrantAuthorityControllerTest {
    @Test
    void missingVerifiedContextIsAnEmptyNoStoreDenial() throws Exception {
        var controller = new HrisServiceGrantAuthorityController(
                new ObjectMapper(), mock(HrisServiceGrantAuthorityService.class));
        var response = new MockHttpServletResponse();

        controller.resolve(new MockHttpServletRequest(), response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void unavailableRepositoryFailureUsesServiceUnavailableProblem() throws Exception {
        UUID correlation = new UUID(0, 91);
        var requestValue = new HrisServiceGrantAuthorityV1.ResolveRequest(
                17, "dwp-people-server", "dwp-people-server", "dwp-time-server",
                "SVC-PEP-TIM-004", "HRIS_SEPARATION_TIME_TASK_DISPATCH",
                HrisServiceGrantAuthorityV1.ResolutionMode.PROBE_MISSING,
                0, 0, correlation, null);
        var context = mock(HrisServiceGrantAuthorityRequestContext.class);
        when(context.request()).thenReturn(requestValue);
        var service = mock(HrisServiceGrantAuthorityService.class);
        when(service.resolve(context)).thenThrow(Hrm013SafeErrorV1.authorityUnavailable(1));
        var controller = new HrisServiceGrantAuthorityController(
                new ObjectMapper(), service);
        var request = new MockHttpServletRequest();
        request.setAttribute(HrisServiceGrantAuthorityRequestContext.ATTRIBUTE, context);
        var response = new MockHttpServletResponse();

        controller.resolve(request, response);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"correlationId\":\"" + correlation + "\"}");
    }
}
