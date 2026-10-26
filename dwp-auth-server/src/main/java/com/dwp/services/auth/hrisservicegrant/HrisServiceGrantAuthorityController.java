package com.dwp.services.auth.hrisservicegrant;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
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
        name = "dwp.auth.hris-service-grant-authority.enabled", havingValue = "true")
public final class HrisServiceGrantAuthorityController {
    private final ObjectMapper mapper;
    private final HrisServiceGrantAuthorityService service;

    public HrisServiceGrantAuthorityController(
            ObjectMapper mapper, HrisServiceGrantAuthorityService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @PostMapping(
            value = HrisServiceGrantAuthorityV1.PATH,
            consumes = "application/json", produces = "application/json")
    public void resolve(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var context = request.getAttribute(HrisServiceGrantAuthorityRequestContext.ATTRIBUTE);
        UUID correlation = context instanceof HrisServiceGrantAuthorityRequestContext value
                ? value.request().correlationId()
                : HrisServiceGrantAuthorityIngressFilter.correlationOrNull(request);
        try {
            if (!(context instanceof HrisServiceGrantAuthorityRequestContext value)) {
                throw Hrm013SafeErrorV1.authorityDenied();
            }
            byte[] body = HrisServiceGrantAuthorityV1.encodeResponse(
                    mapper, service.resolve(value));
            response.setStatus(HttpServletResponse.SC_OK);
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
