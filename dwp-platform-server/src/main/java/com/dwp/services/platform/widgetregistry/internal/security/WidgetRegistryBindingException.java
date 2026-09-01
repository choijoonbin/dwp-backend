package com.dwp.services.platform.widgetregistry.internal.security;

/** Shared fail-closed binding failure for the widget-registry ingress pipeline. */
final class WidgetRegistryBindingException extends Exception {
    private static final long serialVersionUID = 1L;
    private final WidgetRegistryIngressFailure failure;

    WidgetRegistryBindingException(WidgetRegistryIngressFailure failure) {
        super(failure.message());
        this.failure = failure;
    }

    WidgetRegistryBindingException(
            WidgetRegistryIngressFailure failure,
            Throwable cause) {
        super(failure.message(), cause);
        this.failure = failure;
    }

    WidgetRegistryIngressFailure failure() {
        return failure;
    }
}
