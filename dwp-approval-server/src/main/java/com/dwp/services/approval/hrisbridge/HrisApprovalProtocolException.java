package com.dwp.services.approval.hrisbridge;

final class HrisApprovalProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String reason;

    HrisApprovalProtocolException(int status, String reason) {
        super(reason);
        this.status = status;
        this.reason = reason;
    }

    int status() { return status; }
    String reason() { return reason; }

    static HrisApprovalProtocolException badRequest(String reason) {
        return new HrisApprovalProtocolException(400, reason);
    }

    static HrisApprovalProtocolException conflict(String reason) {
        return new HrisApprovalProtocolException(409, reason);
    }
}
