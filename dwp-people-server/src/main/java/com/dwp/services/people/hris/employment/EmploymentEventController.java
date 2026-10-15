package com.dwp.services.people.hris.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon016WorkplaceAssignmentSnapshotV1;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.services.people.hris.experience.HrisExperienceCommandReceiptService;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmStepUpHeaders;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Canonical public and owner-internal routes for BASE-TFR-HRM-008. */
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
final class EmploymentEventController {

    static final String CORRELATION = "X-Correlation-ID";
    static final String PURPOSE = "X-Purpose-Code";
    static final String AUTHORIZATION_REVISION = "X-Authorization-Decision-Revision";
    private static final String EVENT_LOCATION =
            "/api/people/v1/hris/assignment-events/";

    private final EmploymentEventService service;
    private final EmploymentSnapshotAuthority snapshotAuthority;
    private final HrisExperienceCommandReceiptService receipts;
    private final boolean experienceEnabled;

    EmploymentEventController(
            EmploymentEventService service,
            EmploymentSnapshotAuthority snapshotAuthority,
            HrisExperienceCommandReceiptService receipts,
            @Value("${dwp.hris.experience.enabled:false}") boolean experienceEnabled) {
        this.service = service;
        this.snapshotAuthority = snapshotAuthority;
        this.receipts = receipts;
        this.experienceEnabled = experienceEnabled;
    }

    @Operation(operationId = "getSelfServiceEmploymentDocuments")
    @GetMapping("/v1/hris/workbench/self-service/employment-documents")
    ResponseEntity<EmploymentEventDtos.EmploymentDocuments> selfDocuments(
            @RequestParam(required = false) Instant asOf) {
        return ok(service.selfDocuments(asOf));
    }

    @Operation(operationId = "getOperationsEmploymentAssignments")
    @GetMapping("/v1/hris/workbench/operations/employment/assignments")
    ResponseEntity<EmploymentEventDtos.AssignmentWorkbench> operationsAssignments(
            @RequestParam(required = false) Instant asOf) {
        return ok(service.operationsAssignments(asOf));
    }

    @Operation(operationId = "createAssignmentEvent")
    @PostMapping(value = "/v1/hris/assignment-events",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CommandReceiptV1> create(
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(CORRELATION) UUID correlationId,
            @RequestHeader(PURPOSE) String purpose,
            @Valid @RequestBody EmploymentEventDtos.CreateCommand command) {
        return accepted(service.create(
                command, idempotencyKey, ifMatch, correlationId, purpose));
    }

    @Operation(operationId = "validateAssignmentEvent")
    @PostMapping(value = "/v1/hris/assignment-events/{eventPublicId}/validate",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EmploymentEventDtos.AssignmentImpact> validate(
            @PathVariable UUID eventPublicId,
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(CORRELATION) UUID correlationId,
            @RequestHeader(PURPOSE) String purpose,
            @Valid @RequestBody EmploymentEventDtos.EmptyCommand ignored) {
        EmploymentEventDtos.CommandResult result = service.validate(
                eventPublicId, idempotencyKey, ifMatch, correlationId, purpose);
        return ResponseEntity.ok()
                .headers(headers(result.event().version(),
                        result.receipt().correlationId()))
                .body(result.impact());
    }

    @Operation(operationId = "submitAssignmentEvent")
    @PostMapping(value = "/v1/hris/assignment-events/{eventPublicId}/submit",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CommandReceiptV1> submit(
            @PathVariable UUID eventPublicId,
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(CORRELATION) UUID correlationId,
            @RequestHeader(PURPOSE) String purpose,
            @Valid @RequestBody EmploymentEventDtos.EmptyCommand ignored) {
        return accepted(service.submit(
                eventPublicId, idempotencyKey, ifMatch, correlationId, purpose));
    }

    @Operation(operationId = "publishAssignmentEvent")
    @PostMapping(value = "/v1/hris/assignment-events/{eventPublicId}/publish",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CommandReceiptV1> publish(
            @PathVariable UUID eventPublicId,
            @RequestHeader(HcmStepUpHeaders.IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @RequestHeader(HcmStepUpHeaders.CHALLENGE) String challenge,
            @RequestHeader(HcmStepUpHeaders.DECISION_REVISION) String decisionRevision,
            @RequestHeader(HcmStepUpHeaders.EXPECTED_OBJECT_VERSION) Long expectedObjectVersion,
            @RequestHeader(CORRELATION) UUID correlationId,
            @RequestHeader(PURPOSE) String purpose,
            @Valid @RequestBody EmploymentEventDtos.EmptyCommand ignored) {
        return accepted(service.publish(
                eventPublicId, idempotencyKey, ifMatch, correlationId, purpose,
                new HcmStepUpHeaders(
                        challenge, idempotencyKey, decisionRevision, expectedObjectVersion)));
    }

    @Operation(operationId = "getAssignmentEvent")
    @GetMapping("/v1/hris/assignment-events/{eventPublicId}")
    ResponseEntity<EmploymentEventDtos.EventView> event(
            @PathVariable UUID eventPublicId) {
        EmploymentEventDtos.EventView event = service.event(eventPublicId);
        return ResponseEntity.ok().headers(headers(event.version())).body(event);
    }

    @Operation(operationId = "getHrisCommandReceipt")
    @GetMapping("/v1/hris/command-receipts/{receiptId}")
    ResponseEntity<CommandReceiptV1> receipt(@PathVariable UUID receiptId) {
        if (!experienceEnabled) {
            CommandReceiptV1 receipt = service.receipt(receiptId);
            return ResponseEntity.ok().headers(headers(0, receipt.correlationId()))
                    .body(receipt);
        }
        HrisExperienceCommandReceiptService.ProjectedReceipt projection =
                receipts.read(receiptId);
        CommandReceiptV1 receipt = projection.receipt();
        HttpHeaders headers = headers(0, receipt.correlationId());
        if (projection.resultLocation() != null) {
            headers.setLocation(projection.resultLocation());
        }
        return ResponseEntity.ok().headers(headers).body(receipt);
    }

    @Hidden
    @GetMapping("/internal/hris-people/v1/workplace-assignment-snapshots")
    ResponseEntity<List<Xcon016WorkplaceAssignmentSnapshotV1>> workplaceSnapshots(
            @RequestHeader(EmploymentSnapshotAuthority.SERVICE_IDENTITY_HEADER)
            String serviceIdentity,
            @RequestHeader(PURPOSE) String purpose,
            @RequestParam UUID workerPublicId,
            @RequestParam(required = false) Instant asOf) {
        snapshotAuthority.require(serviceIdentity, purpose);
        return ResponseEntity.ok().headers(headers(0))
                .body(service.workplaceSnapshots(workerPublicId, asOf));
    }

    private ResponseEntity<CommandReceiptV1> accepted(
            EmploymentEventDtos.CommandResult result) {
        long version = result.event() == null ? 0L : result.event().version();
        HttpHeaders headers = headers(version, result.receipt().correlationId());
        if (result.event() != null) {
            headers.setLocation(URI.create(EVENT_LOCATION + result.event().eventPublicId()));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).headers(headers)
                .body(result.receipt());
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().headers(headers(0)).body(body);
    }

    private HttpHeaders headers(long version) {
        return headers(version, null);
    }

    private HttpHeaders headers(long version, UUID correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore().cachePrivate());
        if (correlationId != null) headers.set(CORRELATION, correlationId.toString());
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        if (evidence != null && evidence.decisionRevision() != null) {
            headers.set(AUTHORIZATION_REVISION, evidence.decisionRevision());
        }
        headers.setETag("\"v:" + version + "\"");
        return headers;
    }
}
