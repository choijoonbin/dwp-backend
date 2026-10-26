package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(
        name = "dwp.auth.hrm013-requester-authority.enabled", havingValue = "true")
public final class Hrm013RequesterIdentityAuthorityController {
    private final ObjectMapper mapper;
    private final Hrm013RequesterIdentityAuthorityService service;

    public Hrm013RequesterIdentityAuthorityController(
            ObjectMapper mapper, Hrm013RequesterIdentityAuthorityService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @PostMapping(
            value = Hrm013RequesterIdentityAuthorityV1.PATH,
            consumes = "application/json", produces = "application/json")
    public void resolve(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Object raw = request.getAttribute(Hrm013RequesterIdentityIngressFilter.ATTRIBUTE);
        UUID correlation = raw instanceof Hrm013RequesterIdentityIngressFilter.RequestContext value
                ? value.request().correlationId()
                : HrisServiceGrantAuthorityIngressFilter.correlationOrNull(request);
        try {
            if (!(raw instanceof Hrm013RequesterIdentityIngressFilter.RequestContext value)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            Hrm013RequesterIdentityAuthorityV1.ResolveResponse resolved =
                    service.resolve(value);
            byte[] body = Hrm013RequesterIdentityAuthorityV1.encodeResponse(
                    mapper, resolved);
            response.setStatus(switch (resolved.status()) {
                case ADMIT -> HttpServletResponse.SC_OK;
                case DENY -> HttpServletResponse.SC_FORBIDDEN;
                case UNAVAILABLE -> HttpServletResponse.SC_SERVICE_UNAVAILABLE;
            });
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("Pragma", "no-cache");
            response.setContentType("application/json");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        } catch (RuntimeException failure) {
            HrisServiceGrantAuthorityProblem.write(response, failure, correlation);
        }
    }
}
