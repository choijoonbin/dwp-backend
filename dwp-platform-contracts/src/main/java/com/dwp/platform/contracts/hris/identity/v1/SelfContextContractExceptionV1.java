package com.dwp.platform.contracts.hris.identity.v1;

/** Generic errors intentionally omit credential, principal and worker details. */
public final class SelfContextContractExceptionV1 extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        ADAPTER_UNAVAILABLE, QUERY_INVALID, AUTHORITY_INVALID, APP_ENTITLEMENT_REQUIRED,
        SEPARATION_OF_DUTIES_DENIED, AUTH_BINDING_INVALID, AUTH_BINDING_REVOKED,
        AUTH_BINDING_STALE, OWNER_RESPONSE_INVALID, OWNER_UNAVAILABLE,
        SELF_SCOPE_UNRESOLVED, SELF_CONTEXT_FORBIDDEN
    }

    private final Code code;

    public SelfContextContractExceptionV1(Code code) {
        super("Self-context contract rejected: " + code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
