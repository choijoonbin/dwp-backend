package com.dwp.services.people.integration;

public class HrisConnectorBlockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reasonCode;

    public HrisConnectorBlockedException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
