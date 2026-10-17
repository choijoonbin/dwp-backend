package com.dwp.services.time.api;

import com.dwp.core.exception.BaseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Typed failure carrier for the scoped Time problem boundary. */
public final class HrisTimeApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HrisTimeProblemCode code;
    private final Long currentVersion;
    private final UUID receiptId;
    private final List<HrisTimeProblemV1.FieldErrorV1> fieldErrors;

    public HrisTimeApiException(HrisTimeProblemCode code) {
        this(code, null, null, null, null);
    }

    public HrisTimeApiException(HrisTimeProblemCode code, Throwable cause) {
        this(code, null, null, null, cause);
    }

    public HrisTimeApiException(
            HrisTimeProblemCode code,
            Long currentVersion,
            UUID receiptId,
            List<HrisTimeProblemV1.FieldErrorV1> fieldErrors,
            Throwable cause) {
        super(Objects.requireNonNull(code, "code").title(), cause);
        if (currentVersion != null && currentVersion < 0) {
            throw new IllegalArgumentException("currentVersion must not be negative");
        }
        this.code = code;
        this.currentVersion = currentVersion;
        this.receiptId = receiptId;
        this.fieldErrors = fieldErrors == null ? null : List.copyOf(fieldErrors);
    }

    public HrisTimeProblemCode code() {
        return code;
    }

    public Long currentVersion() {
        return currentVersion;
    }

    public UUID receiptId() {
        return receiptId;
    }

    public List<HrisTimeProblemV1.FieldErrorV1> fieldErrors() {
        return fieldErrors;
    }

    public static HrisTimeApiException of(HrisTimeProblemCode code) {
        return new HrisTimeApiException(code);
    }

    public static HrisTimeApiException authorityUnavailable(Throwable cause) {
        return new HrisTimeApiException(HrisTimeProblemCode.AUTHORITY_UNAVAILABLE, cause);
    }

    public static HrisTimeApiException from(BaseException failure) {
        Objects.requireNonNull(failure, "failure");
        return new HrisTimeApiException(
                HrisTimeProblemCode.from(failure.getErrorCode()), failure);
    }
}
