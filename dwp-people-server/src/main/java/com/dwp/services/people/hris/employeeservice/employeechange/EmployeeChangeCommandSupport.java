package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Canonical command headers, actor/purpose checks, and receipt projections. */
final class EmployeeChangeCommandSupport {

    static CommandReceiptV1 toReceipt(EmployeeChangeRepository.ReceiptRow row) {
        return new CommandReceiptV1(
                row.receiptId(), row.commandType(), row.action(), row.subjectPublicId(),
                row.populationDigest(), row.fieldRevision(), row.purpose(),
                row.authorizationRevision(), receiptStatus(row.lifecycleState()),
                row.correlationId(), row.requestDigest(), row.createdAt());
    }

    static String receiptStatus(String lifecycle) {
        return switch (lifecycle) {
            case "RECEIVED" -> "ACCEPTED";
            case "VALIDATING", "RUNNING" -> "RUNNING";
            case "SUCCEEDED" -> "SUCCEEDED";
            case "REJECTED", "CANCELLED" -> "REJECTED";
            case "FAILED_RETRYABLE", "DEAD" -> "FAILED";
            default -> "RESULT_UNKNOWN";
        };
    }

    static PeopleRequestContext.Actor actor() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.userId() == null || actor.userId() < 1
                || actor.tenantId() == null || actor.tenantId() < 1
                || actor.personPublicId() == null) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "A tenant-bound People identity is required.");
        }
        return actor;
    }

    static String idempotencyKey(String value) {
        if (value == null || value.length() < 16 || value.length() > 128
                || !value.equals(value.trim())
                || !value.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "A canonical Idempotency-Key is required.");
        }
        return value;
    }

    static long expectedVersion(String value, boolean allowZero) {
        if (value == null || !value.matches("(?:W/)?\"(?:v:)?[0-9]+\"")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "If-Match must contain a numeric governed version.");
        }
        long version;
        try {
            String number = value.replaceFirst("^(?:W/)?\"(?:v:)?", "")
                    .replace("\"", "");
            version = Long.parseLong(number);
        } catch (RuntimeException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "If-Match contains an invalid governed version.");
        }
        if (!allowZero && version < 1) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Employee-change request versions must be positive.");
        }
        return version;
    }

    static String purpose(String capability, String requested) {
        String expected = switch (capability) {
            case "hcm.personal.profile.update" -> "EMPLOYEE_SELF_SERVICE";
            case "hcm.team.people-change.request" -> "TEAM_MANAGEMENT";
            case EmployeeChangeAuthority.APPLY_CAPABILITY -> "HR_LIFECYCLE";
            default -> "HR_OPERATIONS";
        };
        if (requested == null || requested.isBlank()) return expected;
        String value = requested.trim().toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z][A-Z0-9._-]{2,63}") || !expected.equals(value)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The employee-change purpose is not authorized for this route.");
        }
        return value;
    }

    static void requireUniqueEvidence(List<EmployeeChangeDtos.EvidenceReference> evidence) {
        if (evidence.stream().map(EmployeeChangeDtos.EvidenceReference::objectRef)
                .distinct().count() != evidence.size()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "Employee-change evidence references must be unique.");
        }
    }

    static UUID deterministicUuid(
            String namespace,
            long tenantId,
            UUID subject,
            String action,
            String key) {
        String value = namespace + '\n' + tenantId + '\n' + subject + '\n' + action + '\n' + key;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    static BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    static long replayEntityVersion(ObjectMapper mapper, EmployeeChangeRepository.ReceiptRow receipt) {
        try {
            if (receipt.resultReference() != null) {
                com.fasterxml.jackson.databind.JsonNode result =
                        mapper.readTree(receipt.resultReference());
                com.fasterxml.jackson.databind.JsonNode version = result.get("requestVersion");
                if (version != null && version.canConvertToLong() && version.longValue() > 0) {
                    return version.longValue();
                }
            }
        } catch (Exception ignored) {
            // Fail closed below; a successful receipt without its immutable result is corrupt.
        }
        throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                "The prior employee-change command result is unavailable.");
    }
}
