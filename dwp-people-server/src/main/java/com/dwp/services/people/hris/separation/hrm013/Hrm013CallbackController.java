package com.dwp.services.people.hris.separation.hrm013;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** One controller for the three sealed callback profiles. */
@RestController
@ConditionalOnProperty(prefix = "dwp.hris.separation", name = "enabled",
        havingValue = "true")
public final class Hrm013CallbackController {
    private final Hrm013CallbackService service;

    public Hrm013CallbackController(Hrm013CallbackService service) {
        this.service = java.util.Objects.requireNonNull(service);
    }

    @PostMapping(value = {
            "/internal/hris-people/v1/separation-cases/{casePublicId}/tasks/{taskPublicId}/receipts",
            "/internal/hris-people/v1/separation-cases/{casePublicId}/reconciliation-results",
            "/internal/hris-people/v1/separation-cases/{casePublicId}/tasks/{taskPublicId}/waiver-decisions"
    }, consumes = "application/json", produces = "application/json")
    public void accept(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Hrm013CallbackRequestContext context = Hrm013CallbackRequestContext.require(request);
        try {
            byte[] body = service.accept(context);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader("Pragma", "no-cache");
            response.setContentType("application/json");
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        } catch (RuntimeException failure) {
            Hrm013RuntimeProblem.write(
                    response, failure, context.route().correlationId());
        }
    }
}
