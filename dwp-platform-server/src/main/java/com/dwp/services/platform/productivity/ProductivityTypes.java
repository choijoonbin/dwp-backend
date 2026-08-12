package com.dwp.services.platform.productivity;

public final class ProductivityTypes {

    private ProductivityTypes() {
    }

    public enum ProviderType { MICROSOFT_GRAPH }

    public enum AuthMode { DELEGATED }

    public enum ConnectorLifecycle { DRAFT, ACTIVE, SUSPENDED, RETIRED }

    public enum ConnectorHealth {
        CONFIGURATION_REQUIRED,
        HEALTHY,
        DEGRADED,
        AUTHENTICATION_REQUIRED,
        UNAVAILABLE
    }

    public enum PolicyState { REVIEW_REQUIRED, APPROVED, BLOCKED }

    public enum ConsentState {
        NOT_CONNECTED,
        CONNECTED,
        REAUTHORIZATION_REQUIRED,
        REVOKED
    }

    public enum ResourceKind { MAIL, CALENDAR }

    public enum StreamState {
        READY,
        SYNCING,
        STALE,
        RESET_REQUIRED,
        AUTHENTICATION_REQUIRED,
        SUSPENDED
    }

    public enum SyncMode { INITIAL, DELTA, RESET }

    public enum SyncRunState { RUNNING, SUCCEEDED, PARTIAL, FAILED, BLOCKED }
}
