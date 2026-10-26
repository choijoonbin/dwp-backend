package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.TransportIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class Hrm013RequesterIdentityAuthorityControllerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID APPROVAL = new UUID(0, 1);
    private static final UUID ACTOR = new UUID(0, 2);
    private static final UUID CORRELATION = new UUID(0, 3);

    @Test
    void missingVerifiedContextIsAnEmptyNoStoreDenial() throws Exception {
        var controller = new Hrm013RequesterIdentityAuthorityController(
                MAPPER, mock(Hrm013RequesterIdentityAuthorityService.class));
        var response = new MockHttpServletResponse();

        controller.resolve(new MockHttpServletRequest(), response);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void admitUsesOkAndPreservesCanonicalNoStoreBody() throws Exception {
        assertResponse(Hrm013RequesterIdentityAuthorityV1.Status.ADMIT, 200);
    }

    @Test
    void denyUsesForbiddenAndPreservesOpaqueNoStoreBody() throws Exception {
        assertResponse(Hrm013RequesterIdentityAuthorityV1.Status.DENY, 403);
    }

    @Test
    void unavailableUsesServiceUnavailableAndPreservesOpaqueNoStoreBody()
            throws Exception {
        assertResponse(Hrm013RequesterIdentityAuthorityV1.Status.UNAVAILABLE, 503);
    }

    @Test
    void databaseFailureUsesTypedUnavailableResponse() throws Exception {
        var repository = mock(Hrm013RequesterIdentityAuthorityRepository.class);
        var proofVerifier = mock(Hrm013RequesterIdentityProofVerifier.class);
        when(proofVerifier.verify(any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("database offline"));
        var service = new Hrm013RequesterIdentityAuthorityService(repository, proofVerifier);
        var controller = new Hrm013RequesterIdentityAuthorityController(MAPPER, service);
        var request = new Hrm013RequesterIdentityAuthorityV1.ResolveRequest(
                17, APPROVAL, ACTOR, CORRELATION);
        byte[] requestBody = Hrm013RequesterIdentityAuthorityV1.encodeRequest(MAPPER, request);
        var context = new Hrm013RequesterIdentityIngressFilter.RequestContext(
                request, requestBody, List.of(),
                new TransportIdentity("dwp-approval-server",
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        var servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute(Hrm013RequesterIdentityIngressFilter.ATTRIBUTE, context);
        var response = new MockHttpServletResponse();

        controller.resolve(servletRequest, response);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(Hrm013RequesterIdentityAuthorityV1.decodeResponse(
                MAPPER, response.getContentAsByteArray()).status())
                .isEqualTo(Hrm013RequesterIdentityAuthorityV1.Status.UNAVAILABLE);
        assertThat(response.getContentType()).isEqualTo("application/json");
    }

    private static void assertResponse(
            Hrm013RequesterIdentityAuthorityV1.Status status, int expectedHttpStatus)
            throws Exception {
        var service = mock(Hrm013RequesterIdentityAuthorityService.class);
        var controller = new Hrm013RequesterIdentityAuthorityController(MAPPER, service);
        var request = new Hrm013RequesterIdentityAuthorityV1.ResolveRequest(
                17, APPROVAL, ACTOR, CORRELATION);
        byte[] requestBody = Hrm013RequesterIdentityAuthorityV1.encodeRequest(MAPPER, request);
        var context = new Hrm013RequesterIdentityIngressFilter.RequestContext(
                request, requestBody, List.of(),
                new TransportIdentity("dwp-approval-server",
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        Hrm013RequesterIdentityAuthorityV1.ResolveResponse resolved =
                status == Hrm013RequesterIdentityAuthorityV1.Status.ADMIT
                        ? new Hrm013RequesterIdentityAuthorityV1.ResolveResponse(
                        status, 41L, ACTOR, 7L, 11L, 13L, 17L, CORRELATION)
                        : Hrm013RequesterIdentityAuthorityV1.ResolveResponse.opaque(
                        status, CORRELATION);
        when(service.resolve(context)).thenReturn(resolved);
        var servletRequest = new MockHttpServletRequest();
        servletRequest.setAttribute(Hrm013RequesterIdentityIngressFilter.ATTRIBUTE, context);
        var response = new MockHttpServletResponse();

        controller.resolve(servletRequest, response);

        assertThat(response.getStatus()).isEqualTo(expectedHttpStatus);
        assertThat(Hrm013RequesterIdentityAuthorityV1.decodeResponse(
                MAPPER, response.getContentAsByteArray())).isEqualTo(resolved);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Pragma")).isEqualTo("no-cache");
        assertThat(response.getContentType()).isEqualTo("application/json");
    }
}
