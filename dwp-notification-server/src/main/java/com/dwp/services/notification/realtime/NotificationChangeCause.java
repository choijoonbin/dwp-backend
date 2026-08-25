package com.dwp.services.notification.realtime;

public enum NotificationChangeCause {
    MATERIALIZED(true),
    USER_TRIAGE(false),
    SYSTEM_RECONCILIATION(false),
    TARGET_LIFECYCLE(false);

    private final boolean arrivalEligible;

    NotificationChangeCause(boolean arrivalEligible) {
        this.arrivalEligible = arrivalEligible;
    }

    public boolean arrivalEligible() {
        return arrivalEligible;
    }
}
