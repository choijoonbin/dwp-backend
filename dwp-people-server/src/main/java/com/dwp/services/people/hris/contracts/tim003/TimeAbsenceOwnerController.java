package com.dwp.services.people.hris.contracts.tim003;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal-only SVC-PEP-HRM-007 transport consuming only the verified request context. */
@RestController
@ConditionalOnProperty(prefix = "dwp.hris.tim003.employment-absence-owner",
        name = "enabled", havingValue = "true", matchIfMissing = false)
final class TimeAbsenceOwnerController {

    private static final String ROUTE =
            TimeAbsenceOwnerContract.PATH_PREFIX + "{relationshipPublicId}";

    private final TimeAbsenceOwnerService service;

    TimeAbsenceOwnerController(TimeAbsenceOwnerService service) {
        this.service = service;
    }

    @GetMapping(value = ROUTE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> read(HttpServletRequest servletRequest) {
        TimeAbsenceOwnerContract.VerifiedRequest request =
                TimeAbsenceOwnerRequestContext.require(servletRequest);
        try {
            return headers(ResponseEntity.ok())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(service.read(request));
        } catch (TimeAbsenceOwnerContract.Failure failure) {
            return service.signedProblem(request, failure)
                    .<ResponseEntity<?>>map(problem -> headers(
                                    ResponseEntity.status(problem.status()))
                            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                            .body(problem))
                    .orElseGet(() -> headers(ResponseEntity.status(503)).build());
        }
    }

    private static <T extends ResponseEntity.HeadersBuilder<T>> T headers(T response) {
        return response.cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache");
    }
}
