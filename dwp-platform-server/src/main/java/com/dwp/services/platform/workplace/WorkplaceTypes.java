package com.dwp.services.platform.workplace;

public final class WorkplaceTypes {

    private WorkplaceTypes() {
    }

    public enum SiteType { HEADQUARTERS, SHARED_OFFICE, SATELLITE, CLIENT_SITE }

    public enum SiteState { ACTIVE, MAINTENANCE, CLOSED }

    public enum FloorState { DRAFT, ACTIVE, CLOSED }

    public enum ResourceType {
        ROOM, DESK, LOCKER, PARKING, FOCUS_POD, PHONE_BOOTH, EQUIPMENT
    }

    public enum BookingMode { RESERVABLE, DROP_IN, ASSIGNED, UNAVAILABLE }

    public enum ResourceState { AVAILABLE, MAINTENANCE, RETIRED }

    public enum BookingStatus { RESERVED, CHECKED_IN, COMPLETED, NO_SHOW, RELEASED, CANCELLED }
}
