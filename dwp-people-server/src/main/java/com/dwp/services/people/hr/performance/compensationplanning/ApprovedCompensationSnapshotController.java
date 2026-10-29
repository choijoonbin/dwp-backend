package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;

/** Service-only immutable compensation snapshot refetch endpoint. */
@Hidden
@RestController
final class ApprovedCompensationSnapshotController {

    static final String PATH =
            ApprovedCompensationSnapshotIngressContract.PATH_PREFIX + "{snapshotId}";

    private final ApprovedCompensationSnapshotRefetchService service;
    private final ApprovedCompensationSnapshotResponseSpool spool;

    ApprovedCompensationSnapshotController(
            ApprovedCompensationSnapshotRefetchService service,
            ApprovedCompensationSnapshotResponseSpool spool) {
        this.service = service;
        this.spool = spool;
    }

    @GetMapping(path = PATH, params = {"snapshotRevision", "payloadDigest"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StreamingResponseBody> get(
            HttpServletRequest request) {
        var trusted = ApprovedCompensationSnapshotRequestContext.require(request);
        Xcon020ApprovedCompensationPlanSnapshotV1 snapshot = service.get(trusted);
        try {
            var artifact = spool.prepare(snapshot);
            StreamingResponseBody body = artifact::transferTo;
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_JSON)
                    .contentLength(artifact.length())
                    .body(body);
        } catch (ApprovedCompensationSnapshotResponseSpool.ResponseBoundExceededException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INSUFFICIENT_STORAGE,
                    "SVC_PEP_PER_001_ATOMIC_RESPONSE_BOUND_EXCEEDED",
                    exception);
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SVC_PEP_PER_001_ATOMIC_RESPONSE_PREPARATION_FAILED",
                    exception);
        }
    }
}
