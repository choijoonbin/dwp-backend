package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-only immutable compensation snapshot refetch endpoint. */
@Hidden
@RestController
final class ApprovedCompensationSnapshotController {

    static final String PATH =
            ApprovedCompensationSnapshotIngressFilter.PATH_PREFIX + "{snapshotId}";

    private final ApprovedCompensationSnapshotRefetchService service;

    ApprovedCompensationSnapshotController(
            ApprovedCompensationSnapshotRefetchService service) {
        this.service = service;
    }

    @GetMapping(path = PATH, params = {"snapshotRevision", "payloadDigest"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Xcon020ApprovedCompensationPlanSnapshotV1> get(
            HttpServletRequest request) {
        var trusted = ApprovedCompensationSnapshotRequestContext.require(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.get(trusted));
    }
}
