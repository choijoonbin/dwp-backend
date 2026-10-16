package com.dwp.services.time.home;

import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1;
import com.dwp.platform.contracts.hris.home.HomeMaterializationWorkloadProofV1.Header;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.services.time.home.TimeHomeAdmissionVerifier.TimeHomeDeniedException;
import com.dwp.services.time.home.TimeHomeAdmissionVerifier.VerifiedRequest;
import com.dwp.services.time.home.TimeHomePayloadReferenceCodec.InvalidPayloadReferenceException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Workload-only owner endpoint. It is intentionally absent from the public OpenAPI surface. */
@Hidden
@RestController
@ConditionalOnProperty(
        name = "dwp.hris.time.home.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class TimeHomeMaterializationController {

    private final TimeHomeAdmissionVerifier admissions;
    private final TimeHomeContributionService contributions;

    TimeHomeMaterializationController(
            TimeHomeAdmissionVerifier admissions,
            TimeHomeContributionService contributions) {
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.contributions = Objects.requireNonNull(contributions, "contributions");
    }

    @GetMapping(
            value = "/internal/hris-time/v1/home-contributions/{payloadRef}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<HrisHomeContributionMaterializationV1> materialize(
            @PathVariable String payloadRef,
            HttpServletRequest request) {
        if (request.getQueryString() != null
                || request.getContentLengthLong() > 0
                || request.getHeader("Transfer-Encoding") != null) {
            throw new TimeHomeDeniedException();
        }
        String exactPath = HomeMaterializationWorkloadProofV1.Endpoint.TIME_LEAVE
                .path(payloadRef);
        if (!exactPath.equals(request.getRequestURI())) {
            throw new TimeHomeDeniedException();
        }
        VerifiedRequest admitted = admissions.verify(exactPath, headers(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(contributions.materialize(
                        payloadRef, admitted.currentAuthority()));
    }

    private static List<Header> headers(HttpServletRequest request) {
        List<Header> result = new ArrayList<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            throw new TimeHomeDeniedException();
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                result.add(new Header(name, values.nextElement()));
            }
        }
        return List.copyOf(result);
    }

    @ExceptionHandler({
            HomeMaterializationWorkloadProofV1.AdmissionDenied.class,
            TimeHomeDeniedException.class,
            InvalidPayloadReferenceException.class
    })
    ResponseEntity<Void> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
