package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

final class EmployeeChangeApprovalProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final String reason;

    EmployeeChangeApprovalProtocolException(int status, String reason) {
        super(reason);
        this.status = status;
        this.reason = reason;
    }

    int status() {
        return status;
    }

    String reason() {
        return reason;
    }

    static EmployeeChangeApprovalProtocolException badRequest(String reason) {
        return new EmployeeChangeApprovalProtocolException(400, reason);
    }

    static EmployeeChangeApprovalProtocolException conflict(String reason) {
        return new EmployeeChangeApprovalProtocolException(409, reason);
    }

    static EmployeeChangeApprovalProtocolException forbidden(String reason) {
        return new EmployeeChangeApprovalProtocolException(403, reason);
    }

    static EmployeeChangeApprovalProtocolException tooEarly(String reason) {
        return new EmployeeChangeApprovalProtocolException(425, reason);
    }

    static EmployeeChangeApprovalProtocolException payloadTooLarge(String reason) {
        return new EmployeeChangeApprovalProtocolException(413, reason);
    }
}
