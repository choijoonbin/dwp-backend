package com.dwp.services.people.hris.performance.per008;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Exact seven-operation owner surface; lifecycle commands have no network mapping. */
@RestController
@ConditionalOnProperty(name = "dwp.hris.performance.feedback.enabled", havingValue = "true")
final class Per008Controller {
    private static final Set<String> TEAM_KEYS = Set.of(
            "limit", "filter", "filter[performanceAssignment][codes]",
            "filter[performanceAssignment][publicIds]", "filter[dueStatus][codes]",
            "filter[dueStatus][minimumRevision]", "filter[limitedProfile][codes]",
            "filter[limitedProfile][minimumRevision]", "filter[effectiveOn]", "sort",
            "locale", "timeZone", "correlationId", "asOf", "cursor");
    private static final Set<String> OPTIONS_KEYS = Set.of(
            "cycleVersionId", "subjectWorkerPublicId", "subjectAssignmentPublicId");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    private static final Pattern PUBLIC_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final Per008Service service;
    private final ObjectReader createCommandReader;
    private final ObjectReader submitCommandReader;

    Per008Controller(Per008Service service, ObjectMapper objectMapper) {
        this.service = service;
        ObjectMapper strict = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.createCommandReader = strict.readerFor(CreateFeedbackRequestCommand.class);
        this.submitCommandReader = strict.readerFor(SubmitFeedbackResponseCommand.class);
    }

    @GetMapping("/v1/hris/performance/workbench/team-performance")
    ResponseEntity<ApiResponse<TeamProjection>> team(
            @RequestParam MultiValueMap<String, String> wire) {
        MultiValueMap<String, String> query = copy(wire);
        requireClosed(query, TEAM_KEYS);
        int limit = integer(one(query, "limit", true), 1, 100);
        List<String> sort = many(query, "sort", true, null, 3);
        String locale = one(query, "locale", true);
        String timeZone = one(query, "timeZone", true);
        UUID correlationId = uuid(one(query, "correlationId", true));
        OffsetDateTime asOf = offset(one(query, "asOf", false));
        String cursor = one(query, "cursor", false);

        boolean emptyFilter = query.containsKey("filter");
        boolean nestedFilter = query.keySet().stream().anyMatch(key -> key.startsWith("filter["));
        if (emptyFilter == nestedFilter || emptyFilter && !"{}".equals(one(query, "filter", true))) {
            throw Per008Problem.invalid("PER008_IAQ038_REQUEST_INVALID");
        }
        Per008IaEntryQuery.ReferenceFilter assignment = referenceFilter(query,
                "filter[performanceAssignment][codes]",
                "filter[performanceAssignment][publicIds]");
        Per008IaEntryQuery.StatusFilter due = statusFilter(query,
                "filter[dueStatus][codes]", "filter[dueStatus][minimumRevision]");
        Per008IaEntryQuery.StatusFilter limited = statusFilter(query,
                "filter[limitedProfile][codes]", "filter[limitedProfile][minimumRevision]");
        LocalDate effectiveOn = date(one(query, "filter[effectiveOn]", false));
        Per008IaEntryQuery.Filter filter = new Per008IaEntryQuery.Filter(
                assignment, due, limited, effectiveOn);
        return noStore(service.team(new Per008IaEntryQuery.Query(limit, filter, sort,
                locale, timeZone, correlationId, asOf, cursor)), HttpStatus.OK);
    }

    @GetMapping("/v1/hris/performance/feedback-request-options")
    ResponseEntity<ApiResponse<RequestOptions>> options(
            @RequestParam MultiValueMap<String, String> query) {
        requireClosed(query, OPTIONS_KEYS);
        return noStore(service.options(uuid(one(query, "cycleVersionId", true)),
                uuid(one(query, "subjectWorkerPublicId", true)),
                uuid(one(query, "subjectAssignmentPublicId", true))), HttpStatus.OK);
    }

    @PostMapping("/v1/hris/performance/feedback-requests")
    ResponseEntity<ApiResponse<FeedbackRequestReceipt>> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-DWP-Expected-Decision-Revision") String decisionRevision,
            @RequestParam MultiValueMap<String, String> query,
            @RequestBody String body) {
        requireClosed(query, Set.of());
        CreateFeedbackRequestCommand command = strictBody(body, createCommandReader,
                "PER008_REQUEST_COMMAND_INVALID");
        FeedbackRequestReceipt receipt = service.create(command, idempotencyKey, decisionRevision);
        return noStore(receipt, receipt.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
    }

    @GetMapping("/v1/hris/performance/feedback-assignments/mine")
    ResponseEntity<ApiResponse<AssignedList>> mine(
            @RequestParam MultiValueMap<String, String> query) {
        requireClosed(query, Set.of("cursor"));
        return noStore(service.mine(one(query, "cursor", false)), HttpStatus.OK);
    }

    @GetMapping("/v1/hris/performance/feedback-assignments/{feedbackAssignmentId}")
    ResponseEntity<ApiResponse<AssignedDetail>> detail(@PathVariable UUID feedbackAssignmentId,
            @RequestParam MultiValueMap<String, String> query) {
        requireClosed(query, Set.of());
        return noStore(service.detail(feedbackAssignmentId), HttpStatus.OK);
    }

    @PostMapping("/v1/hris/performance/feedback-assignments/{feedbackAssignmentId}/responses")
    ResponseEntity<ApiResponse<FeedbackSubmissionReceipt>> submit(
            @PathVariable UUID feedbackAssignmentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-DWP-Expected-Decision-Revision") String decisionRevision,
            @RequestParam MultiValueMap<String, String> query,
            @RequestBody String body) {
        requireClosed(query, Set.of());
        SubmitFeedbackResponseCommand command = strictBody(body, submitCommandReader,
                "PER008_RESPONSE_COMMAND_INVALID");
        FeedbackSubmissionReceipt receipt = service.submit(feedbackAssignmentId, command,
                idempotencyKey, decisionRevision);
        return noStore(receipt, receipt.replayed() ? HttpStatus.OK : HttpStatus.CREATED);
    }

    @GetMapping("/v1/hris/performance/feedback-requests/{feedbackRequestId}/release")
    ResponseEntity<ApiResponse<FeedbackRelease>> release(@PathVariable UUID feedbackRequestId,
            @RequestParam MultiValueMap<String, String> query) {
        requireClosed(query, Set.of());
        return noStore(service.release(feedbackRequestId), HttpStatus.OK);
    }

    private Per008IaEntryQuery.ReferenceFilter referenceFilter(MultiValueMap<String, String> query,
            String codesKey, String publicIdsKey) {
        List<String> codes = many(query, codesKey, false, CODE, 50);
        List<String> publicIds = many(query, publicIdsKey, false, PUBLIC_ID, 50);
        return codes.isEmpty() && publicIds.isEmpty() ? null
                : new Per008IaEntryQuery.ReferenceFilter(
                new LinkedHashSet<>(publicIds), new LinkedHashSet<>(codes));
    }

    private <T> T strictBody(String body, ObjectReader reader, String code) {
        if (body == null || body.isBlank()) throw Per008Problem.invalid(code);
        try {
            @SuppressWarnings("unchecked")
            T value = (T) reader.readValue(body);
            if (value == null) throw Per008Problem.invalid(code);
            return value;
        } catch (JsonProcessingException invalid) {
            throw Per008Problem.invalid(code);
        }
    }

    private Per008IaEntryQuery.StatusFilter statusFilter(MultiValueMap<String, String> query,
            String codesKey, String revisionKey) {
        List<String> codes = many(query, codesKey, false, CODE, 50);
        String revision = one(query, revisionKey, false);
        Long minimumRevision = revision == null ? null : (long) integer(revision, 1, Integer.MAX_VALUE);
        return codes.isEmpty() && minimumRevision == null ? null
                : new Per008IaEntryQuery.StatusFilter(new LinkedHashSet<>(codes), minimumRevision);
    }

    private void requireClosed(MultiValueMap<String, String> query, Set<String> allowed) {
        if (query == null || !allowed.containsAll(query.keySet())) {
            throw Per008Problem.invalid("PER008_QUERY_PARAMETER_INVALID");
        }
    }

    private String one(MultiValueMap<String, String> query, String key, boolean required) {
        List<String> values = query.get(key);
        if (values == null || values.isEmpty()) {
            if (required) throw Per008Problem.invalid("PER008_QUERY_PARAMETER_INVALID");
            return null;
        }
        if (values.size() != 1 || values.getFirst() == null || values.getFirst().isBlank()
                || !values.getFirst().equals(values.getFirst().trim())) {
            throw Per008Problem.invalid("PER008_QUERY_PARAMETER_INVALID");
        }
        return values.getFirst();
    }

    private List<String> many(MultiValueMap<String, String> query, String key, boolean required,
            Pattern pattern, int maximum) {
        List<String> values = query.get(key);
        if (values == null || values.isEmpty()) {
            if (required) throw Per008Problem.invalid("PER008_QUERY_PARAMETER_INVALID");
            return List.of();
        }
        if (values.size() > maximum || new LinkedHashSet<>(values).size() != values.size()
                || values.stream().anyMatch(value -> value == null || value.isBlank()
                || !value.equals(value.trim()) || pattern != null && !pattern.matcher(value).matches())) {
            throw Per008Problem.invalid("PER008_QUERY_PARAMETER_INVALID");
        }
        return List.copyOf(values);
    }

    private int integer(String value, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum || !String.valueOf(parsed).equals(value)) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (RuntimeException invalid) {
            throw Per008Problem.invalid("PER008_QUERY_PARAMETER_INVALID");
        }
    }

    private UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException invalid) {
            throw Per008Problem.invalid("PER008_QUERY_PARAMETER_INVALID");
        }
    }

    private OffsetDateTime offset(String value) {
        if (value == null) return null;
        try { return OffsetDateTime.parse(value); }
        catch (RuntimeException invalid) {
            throw Per008Problem.invalid("PER008_QUERY_PARAMETER_INVALID");
        }
    }

    private LocalDate date(String value) {
        if (value == null) return null;
        try { return LocalDate.parse(value); }
        catch (RuntimeException invalid) {
            throw Per008Problem.invalid("PER008_QUERY_PARAMETER_INVALID");
        }
    }

    private MultiValueMap<String, String> copy(MultiValueMap<String, String> source) {
        return source == null ? new LinkedMultiValueMap<>() : new LinkedMultiValueMap<>(source);
    }

    private <T> ResponseEntity<ApiResponse<T>> noStore(T value, HttpStatus status) {
        ApiResponse<T> envelope = ApiResponse.<T>builder()
                .status("SUCCESS").message("SUCCESS").data(value).success(true)
                .timestamp(LocalDateTime.now()).build();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(envelope);
    }
}
