package com.dwp.services.meeting.videomeeting.domain;

public final class MeetingInvitationDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String failureCode;
    private final boolean retryable;

    public MeetingInvitationDeliveryException(String failureCode, boolean retryable) {
        super("Notification did not accept the meeting invitation intent.");
        if (failureCode == null || !failureCode.matches("^[A-Z][A-Z0-9_]{2,47}$")) {
            throw new IllegalArgumentException("Invitation failure code is invalid.");
        }
        this.failureCode = failureCode;
        this.retryable = retryable;
    }

    public String failureCode() {
        return failureCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
