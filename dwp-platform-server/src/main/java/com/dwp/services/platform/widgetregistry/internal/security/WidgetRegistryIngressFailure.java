package com.dwp.services.platform.widgetregistry.internal.security;

import org.springframework.http.HttpStatus;

enum WidgetRegistryIngressFailure {
    ROUTE_NOT_FOUND(HttpStatus.NOT_FOUND, "WIDGET_REGISTRY_INTERNAL_ROUTE_NOT_FOUND",
            "The internal Widget Registry route is not available."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "WIDGET_REGISTRY_INTERNAL_METHOD_NOT_ALLOWED",
            "The method is not allowed for this internal Widget Registry route."),
    TLS_REQUIRED(HttpStatus.FORBIDDEN, "WIDGET_REGISTRY_INTERNAL_TLS_REQUIRED",
            "A protected internal transport is required."),
    PROVISIONING_TOKEN_FORBIDDEN(HttpStatus.UNAUTHORIZED, "WIDGET_REGISTRY_PROVISIONING_TOKEN_FORBIDDEN",
            "The generic provisioning credential is not accepted for Widget Registry requests."),
    AUTHORITY_HEADERS_FORBIDDEN(HttpStatus.BAD_REQUEST, "WIDGET_REGISTRY_AUTHORITY_HEADERS_FORBIDDEN",
            "Browser and gateway authority headers are not accepted on the Widget Registry internal plane."),
    DUAL_PROOF_REQUIRED(HttpStatus.UNAUTHORIZED, "WIDGET_REGISTRY_DUAL_PROOF_REQUIRED",
            "A service token and request-bound Provider assertion are required."),
    SERVICE_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "WIDGET_REGISTRY_SERVICE_TOKEN_INVALID",
            "The Widget Registry service identity is invalid."),
    ASSERTION_INVALID(HttpStatus.UNAUTHORIZED, "WIDGET_REGISTRY_ASSERTION_INVALID",
            "The Widget Registry Provider assertion is invalid."),
    REQUEST_BINDING_INVALID(HttpStatus.BAD_REQUEST, "WIDGET_REGISTRY_REQUEST_BINDING_INVALID",
            "The Widget Registry request binding is invalid."),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "WIDGET_REGISTRY_PAYLOAD_TOO_LARGE",
            "The Widget Registry request body exceeds the allowed size."),
    ASSERTION_REPLAYED(HttpStatus.CONFLICT, "WIDGET_REGISTRY_ASSERTION_REPLAYED",
            "The Widget Registry Provider assertion has already been accepted."),
    TRUST_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "WIDGET_REGISTRY_TRUST_UNAVAILABLE",
            "The Widget Registry trust authority is unavailable.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    WidgetRegistryIngressFailure(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    String message() {
        return message;
    }
}
