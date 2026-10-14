package com.dwp.services.people.hris.performance.review;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(
        value = "/v1/hris/performance/reviews",
        produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(
        name = "dwp.hris.performance.assigned-review.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class PerformanceAssignedReviewController {

    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String IF_MATCH = "If-Match";
    static final String EXPECTED_DECISION = "X-DWP-Expected-Decision-Revision";
    static final String PURPOSE = "X-Purpose-Code";
    static final String POPULATION = "X-DWP-Population-Kind";

    private final PerformanceReviewService service;

    public PerformanceAssignedReviewController(PerformanceReviewService service) {
        this.service = service;
    }

    @Operation(operationId = PerformanceReviewService.GET_OPERATION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Assigned review",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation =
                                    PerformanceReviewDtos.AssignedReview.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation =
                                    PerformanceReviewProblemDetails.class))),
            @ApiResponse(responseCode = "404",
                    description = "Review absent or outside the assigned scope",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation =
                                    PerformanceReviewProblemDetails.class)))
    })
    @GetMapping("/{reviewId}")
    public ResponseEntity<PerformanceReviewDtos.AssignedReview> review(
            @PathVariable UUID reviewId,
            @RequestHeader(PURPOSE) String purpose,
            @RequestHeader(POPULATION) String population,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        requireNoQuery(query);
        PerformanceReviewService.VersionedReview result =
                service.review(reviewId, purpose, population);
        return ResponseEntity.ok().eTag(result.etag()).body(result.review());
    }

    @Operation(operationId = PerformanceReviewService.SAVE_OPERATION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated review draft",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation =
                                    PerformanceReviewDtos.AssignedReview.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation =
                                    PerformanceReviewProblemDetails.class))),
            @ApiResponse(responseCode = "409", description = "Version or draft conflict",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation =
                                    PerformanceReviewProblemDetails.class)))
    })
    @PatchMapping(
            value = "/{reviewId}",
            consumes = "application/merge-patch+json")
    public ResponseEntity<PerformanceReviewDtos.AssignedReview> updateDraft(
            @PathVariable UUID reviewId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(IF_MATCH) String etag,
            @RequestHeader(EXPECTED_DECISION) String expectedDecisionRevision,
            @RequestHeader(PURPOSE) String purpose,
            @RequestHeader(POPULATION) String population,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @Valid @RequestBody PerformanceReviewDtos.DraftCommand request) {
        requireNoQuery(query);
        long expectedVersion = PerformanceReviewEtags.parse(reviewId, etag);
        PerformanceReviewService.VersionedReview result = service.saveDraft(
                reviewId, expectedVersion, idempotencyKey, expectedDecisionRevision,
                purpose, population, request);
        return ResponseEntity.ok().eTag(result.etag()).body(result.review());
    }

    @Operation(operationId = PerformanceReviewService.SUBMIT_OPERATION)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Submitted review",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation =
                                    PerformanceReviewDtos.ReviewSubmission.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation =
                                    PerformanceReviewProblemDetails.class))),
            @ApiResponse(responseCode = "409", description = "Version or draft conflict",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation =
                                    PerformanceReviewProblemDetails.class))),
            @ApiResponse(responseCode = "422", description = "Review rule violation",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation =
                                    PerformanceReviewProblemDetails.class)))
    })
    @PostMapping(
            value = "/{reviewId}/submit",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public PerformanceReviewDtos.ReviewSubmission submit(
            @PathVariable UUID reviewId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(IF_MATCH) String etag,
            @RequestHeader(EXPECTED_DECISION) String expectedDecisionRevision,
            @RequestHeader(PURPOSE) String purpose,
            @RequestHeader(POPULATION) String population,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @Valid @RequestBody PerformanceReviewDtos.SubmitCommand request) {
        requireNoQuery(query);
        return service.submit(
                reviewId, PerformanceReviewEtags.parse(reviewId, etag),
                idempotencyKey, expectedDecisionRevision, purpose, population, request);
    }

    private static void requireNoQuery(MultiValueMap<String, String> query) {
        if (query != null && !query.isEmpty()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Assigned-review operations do not accept query parameters.");
        }
    }
}
