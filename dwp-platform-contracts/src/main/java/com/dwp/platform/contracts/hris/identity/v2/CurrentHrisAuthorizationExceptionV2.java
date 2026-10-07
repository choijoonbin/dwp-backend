package com.dwp.platform.contracts.hris.identity.v2;

/** Fail-closed, redacted error. No native identifiers, SQL or provider cause is returned. */
public final class CurrentHrisAuthorizationExceptionV2 extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        MISSING_ADAPTER, INVOCATION_INVALID, CLOCK_INVALID, OWNER_UNAVAILABLE,
        AUTHORITY_STALE, AUTHORITY_MISMATCH, AUTHORITY_CHANGED, APP_DENIED,
        PERMISSION_DENIED, DUTY_DENIED, SOD_DENIED, SCOPE_DENIED,
        PURPOSE_DENIED, FIELD_DENIED, TARGET_INVALID, TARGET_CHANGED
    }
    private final Code code;
    public CurrentHrisAuthorizationExceptionV2(Code code) {
        super("current HRIS authorization rejected or unavailable");
        this.code = code;
    }
    public Code code() { return code; }
}
