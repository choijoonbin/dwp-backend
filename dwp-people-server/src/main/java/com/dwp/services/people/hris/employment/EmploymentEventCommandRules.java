package com.dwp.services.people.hris.employment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Employment command header, lifecycle, replay, and identity invariants. */
final class EmploymentEventCommandRules {

    static PeopleRequestContext.Actor actor() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.tenantId() == null || actor.tenantId() < 1
                || actor.userId() == null || actor.userId() < 1
                || actor.personPublicId() == null) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "A tenant-bound People identity is required.");
        }
        return actor;
    }

    static void requireVersion(EmploymentEventRepository.EventRow event, long expected) {
        if (event.version() != expected) throw version(event.version());
    }

    static void requireState(EmploymentEventRepository.EventRow event, String state) {
        if (!state.equals(event.lifecycleState())) {
            throw new EmploymentEventProblem(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "ILLEGAL_TRANSITION", "The assignment-event transition is not allowed.",
                    event.version(), null, false);
        }
    }

    static void requireNoOverlap(EmploymentEventRepository.PreflightSnapshot snapshot) {
        if (snapshot.primaryOverlap()) {
            throw new EmploymentEventProblem(
                    org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "PRIMARY_ASSIGNMENT_OVERLAP",
                    "The requested primary assignment overlaps an existing primary assignment.",
                    null, null, false);
        }
    }

    static void requireSameDecision(
            EmploymentEventRepository.ApprovalDecisionRow existing,
            EmploymentEventDtos.ApprovalDecision supplied) {
        if (!existing.approvalRequestId().equals(supplied.approvalRequestId())
                || !existing.decision().equals(supplied.decision())
                || existing.expectedEventVersion() != supplied.expectedEventVersion()
                || !existing.payloadDigest().equals(supplied.payloadDigest())) {
            throw reused(null);
        }
    }

    static long expectedVersion(String value) {
        if (value == null || !value.matches("(?:W/)?\"(?:v:)?[0-9]+\"")) {
            throw invalid("If-Match must contain a numeric governed version.");
        }
        try {
            return Long.parseLong(value.replaceFirst("^(?:W/)?\"(?:v:)?", "")
                    .replace("\"", ""));
        } catch (RuntimeException exception) {
            throw invalid("If-Match must contain a numeric governed version.");
        }
    }

    static String key(String value) {
        if (value == null || value.length() < 16 || value.length() > 128
                || !value.equals(value.trim())
                || !value.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw invalid("A canonical Idempotency-Key is required.");
        }
        return value;
    }

    static void requireEqual(String expected, String actual, String field) {
        if (!Objects.equals(expected, actual)) throw invalid(field + " is not command-bound.");
    }

    static UUID correlation(UUID value) {
        if (value == null) throw invalid("X-Correlation-ID is required.");
        return value;
    }

    static LocalDate exclusive(LocalDate inclusive) {
        return inclusive == null ? null : inclusive.plusDays(1);
    }

    static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> strings = new ArrayList<>();
        for (Object item : list) if (item instanceof String text) strings.add(text);
        return List.copyOf(strings);
    }

    static String zone(String value) {
        return value == null || value.isBlank() ? "Etc/UTC" : value;
    }

    static long zero(Long value) {
        return value == null ? 0L : value;
    }

    static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    static EmploymentEventProblem notDisclosed() {
        return new EmploymentEventProblem(
                org.springframework.http.HttpStatus.NOT_FOUND, "TARGET_NOT_DISCLOSED",
                "The requested employment target is not available.", null, null, false);
    }

    static EmploymentEventProblem stale(long current) {
        return new EmploymentEventProblem(
                org.springframework.http.HttpStatus.CONFLICT, "STALE_OWNER_VERSION",
                "Employment owner evidence changed.", current, null, false);
    }

    static EmploymentEventProblem version(long current) {
        return new EmploymentEventProblem(
                org.springframework.http.HttpStatus.CONFLICT, "OBJECT_VERSION_CONFLICT",
                "The assignment event version changed.", current, null, false);
    }

    static EmploymentEventProblem reused(UUID receiptId) {
        return new EmploymentEventProblem(
                org.springframework.http.HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "The idempotency key is already bound to another command.",
                null, receiptId, false);
    }
}
