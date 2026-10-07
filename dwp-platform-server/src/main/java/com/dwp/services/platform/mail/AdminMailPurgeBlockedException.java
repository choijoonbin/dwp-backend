package com.dwp.services.platform.mail;

/** Fail-closed signal shared by purge persistence, admission, and worker boundaries. */
final class AdminMailPurgeBlockedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    AdminMailPurgeBlockedException(String code) {
        super(code);
    }
}
