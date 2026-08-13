package com.dwp.services.platform.servicecenter;

public final class ServiceCenterTypes {

    private ServiceCenterTypes() {
    }

    public enum CatalogLifecycle {
        DRAFT,
        ACTIVE,
        RETIRED
    }

    public enum RequestStatus {
        DRAFT,
        SUBMITTED,
        TRIAGED,
        IN_PROGRESS,
        AWAITING_REQUESTER,
        RESOLVED,
        CLOSED,
        CANCELLED
    }

    public enum RequestPriority {
        LOW,
        NORMAL,
        HIGH,
        URGENT
    }

    public enum DataClassification {
        PUBLIC,
        INTERNAL,
        CONFIDENTIAL,
        RESTRICTED
    }
}
