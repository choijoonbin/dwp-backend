package com.dwp.services.people.hris.organization;

import com.dwp.services.people.security.HcmPepContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

/** Canonical PEP-HRM-010 organization graph transport adapter. */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
final class OrganizationGraphController {

    static final String AUTHORIZATION_REVISION = "X-Authorization-Decision-Revision";
    private static final Set<String> QUERY_FIELDS = Set.of(
            "asOf", "rootPublicId", "depth", "relationshipType", "includePositions");

    private final OrganizationGraphService service;

    OrganizationGraphController(OrganizationGraphService service) {
        this.service = service;
    }

    @Operation(operationId = "queryOrganizationGraph")
    @GetMapping("/v1/hris/organizations/graph")
    ResponseEntity<OrganizationGraphWithPositionsV2> graph(
            @RequestParam String asOf,
            @RequestParam String rootPublicId,
            @RequestParam String depth,
            @RequestParam String relationshipType,
            @RequestParam String includePositions,
            HttpServletRequest request) {
        requireClosedQuery(request);
        OrganizationGraphWithPositionsV2 result = service.query(
                instant(asOf), uuid(rootPublicId), integer(depth),
                relationshipType, bool(includePositions));
        return ResponseEntity.ok().headers(headers()).body(result);
    }

    private void requireClosedQuery(HttpServletRequest request) {
        Set<String> actual = Collections.list(request.getParameterNames()).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!QUERY_FIELDS.equals(actual) || actual.stream().anyMatch(
                name -> request.getParameterValues(name) == null
                        || request.getParameterValues(name).length != 1)) {
            throw invalid();
        }
    }

    private Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw invalid();
        }
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid();
        }
    }

    private Integer integer(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException | NullPointerException exception) {
            throw invalid();
        }
    }

    private Boolean bool(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw invalid();
    }

    private OrganizationGraphProblem invalid() {
        return new OrganizationGraphProblem(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                OrganizationGraphService.INVALID_QUERY,
                "The organization graph query is invalid.");
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        if (evidence != null && evidence.decisionRevision() != null) {
            headers.set(AUTHORIZATION_REVISION, evidence.decisionRevision());
        }
        return headers;
    }
}
