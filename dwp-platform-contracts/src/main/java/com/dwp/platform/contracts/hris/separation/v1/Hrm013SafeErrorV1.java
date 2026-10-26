package com.dwp.platform.contracts.hris.separation.v1;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Closed, disclosure-safe error contract shared by the HRM-013 service lanes.
 *
 * <p>The mapper deliberately ignores exception messages and causes.  In
 * particular, tenant, person, grant, key, certificate and proof values can
 * never become response bytes through this type.</p>
 */
public final class Hrm013SafeErrorV1 {

    public static final int MAXIMUM_RESPONSE_BYTES = 16_384;
    public static final long MAXIMUM_RETRY_AFTER_SECONDS = 300;

    private Hrm013SafeErrorV1() {
    }

    public enum ErrorCode {
        MALFORMED_REQUEST(400, "MALFORMED_REQUEST", BodyKind.EMPTY, false),
        AUTHENTICATION_REQUIRED(401, "AUTHENTICATION_REQUIRED", BodyKind.EMPTY, false),
        AUTHORITY_DENIED(403, "AUTHORITY_DENIED", BodyKind.EMPTY, false),
        TARGET_NOT_FOUND(404, "TARGET_NOT_FOUND", BodyKind.EMPTY, false),
        DIGEST_CONFLICT(409, "DIGEST_CONFLICT", BodyKind.EMPTY, false),
        REVISION_CONFLICT(409, "REVISION_CONFLICT", BodyKind.SAFE_CODE, false),
        INVALID_STATE(422, "INVALID_STATE", BodyKind.SAFE_CODE, false),
        RATE_LIMITED(429, "RATE_LIMITED", BodyKind.SAFE_CODE, true),
        AUTHORITY_UNAVAILABLE(503, "AUTHORITY_UNAVAILABLE", BodyKind.CORRELATION, true),
        INTERNAL_ERROR(500, "INTERNAL_ERROR", BodyKind.CORRELATION, false);

        private final int httpStatus;
        private final String wireCode;
        private final BodyKind bodyKind;
        private final boolean retryable;

        ErrorCode(int httpStatus, String wireCode, BodyKind bodyKind, boolean retryable) {
            this.httpStatus = httpStatus;
            this.wireCode = wireCode;
            this.bodyKind = bodyKind;
            this.retryable = retryable;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String wireCode() {
            return wireCode;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private enum BodyKind {
        EMPTY,
        SAFE_CODE,
        CORRELATION
    }

    /** A controlled failure whose message is always the public allowlisted code. */
    public static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final ErrorCode errorCode;
        private final Long retryAfterSeconds;

        private Failure(ErrorCode errorCode, Long retryAfterSeconds) {
            super(Objects.requireNonNull(errorCode, "errorCode").wireCode(), null,
                    false, false);
            this.errorCode = errorCode;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public ErrorCode errorCode() {
            return errorCode;
        }

        public Long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** HTTP adapter value; body bytes and retry metadata are immutable. */
    public record SafeResponse(
            int httpStatus,
            byte[] bodyBytes,
            Long retryAfterSeconds) {
        public SafeResponse {
            if (httpStatus < 400 || httpStatus > 599) {
                throw new IllegalArgumentException("httpStatus must be an error status");
            }
            bodyBytes = Objects.requireNonNull(bodyBytes, "bodyBytes").clone();
            if (bodyBytes.length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalArgumentException("safe response exceeds byte limit");
            }
            if (retryAfterSeconds != null
                    && (retryAfterSeconds < 1
                    || retryAfterSeconds > MAXIMUM_RETRY_AFTER_SECONDS)) {
                throw new IllegalArgumentException("retryAfterSeconds is out of range");
            }
        }

        @Override
        public byte[] bodyBytes() {
            return bodyBytes.clone();
        }

        public String bodyUtf8() {
            return new String(bodyBytes, StandardCharsets.UTF_8);
        }

        public boolean emptyBody() {
            return bodyBytes.length == 0;
        }
    }

    public static Failure failure(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode");
        if (errorCode.retryable()) {
            throw new IllegalArgumentException("retryable error requires Retry-After");
        }
        return new Failure(errorCode, null);
    }

    public static Failure retryable(ErrorCode errorCode, long retryAfterSeconds) {
        Objects.requireNonNull(errorCode, "errorCode");
        if (!errorCode.retryable()) {
            throw new IllegalArgumentException("error is not retryable");
        }
        requireRetryAfter(retryAfterSeconds);
        return new Failure(errorCode, retryAfterSeconds);
    }

    public static Failure malformed() {
        return failure(ErrorCode.MALFORMED_REQUEST);
    }

    public static Failure authenticationRequired() {
        return failure(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    public static Failure authorityDenied() {
        return failure(ErrorCode.AUTHORITY_DENIED);
    }

    public static Failure notFound() {
        return failure(ErrorCode.TARGET_NOT_FOUND);
    }

    public static Failure digestConflict() {
        return failure(ErrorCode.DIGEST_CONFLICT);
    }

    public static Failure revisionConflict() {
        return failure(ErrorCode.REVISION_CONFLICT);
    }

    public static Failure invalidState() {
        return failure(ErrorCode.INVALID_STATE);
    }

    public static Failure rateLimited(long retryAfterSeconds) {
        return retryable(ErrorCode.RATE_LIMITED, retryAfterSeconds);
    }

    public static Failure authorityUnavailable(long retryAfterSeconds) {
        return retryable(ErrorCode.AUTHORITY_UNAVAILABLE, retryAfterSeconds);
    }

    /**
     * Maps a failure without consulting its message, stack, cause or attached
     * domain object. Unknown failures collapse to one opaque 500 response.
     */
    public static SafeResponse map(Throwable failure, UUID correlationId) {
        Objects.requireNonNull(failure, "failure");
        ErrorCode code;
        Long retryAfter = null;
        if (failure instanceof Failure safeFailure) {
            code = safeFailure.errorCode();
            retryAfter = safeFailure.retryAfterSeconds();
        } else {
            code = ErrorCode.INTERNAL_ERROR;
        }

        byte[] body = switch (code.bodyKind) {
            case EMPTY -> new byte[0];
            case SAFE_CODE -> ("{\"code\":\"" + code.wireCode() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            case CORRELATION -> correlationBody(correlationId);
        };
        return new SafeResponse(code.httpStatus(), body, retryAfter);
    }

    /** Alias used by transport adapters that treat mapping as encoding. */
    public static byte[] encode(SafeResponse response) {
        return Objects.requireNonNull(response, "response").bodyBytes();
    }

    private static byte[] correlationBody(UUID correlationId) {
        UUID safeCorrelationId = Objects.requireNonNull(
                correlationId, "correlationId is required for opaque failures");
        return ("{\"correlationId\":\"" + safeCorrelationId + "\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void requireRetryAfter(long retryAfterSeconds) {
        if (retryAfterSeconds < 1 || retryAfterSeconds > MAXIMUM_RETRY_AFTER_SECONDS) {
            throw new IllegalArgumentException("retryAfterSeconds is out of range");
        }
    }
}
