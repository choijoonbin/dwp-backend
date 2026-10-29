package com.dwp.services.platform.mail;

/** Typed rejection raised while validating or materializing provider payloads. */
final class MailSyncMaterializationFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final String code;

    MailSyncMaterializationFailure(String code) {
        super(code);
        this.code = code;
    }

    String code() {
        return code;
    }
}
