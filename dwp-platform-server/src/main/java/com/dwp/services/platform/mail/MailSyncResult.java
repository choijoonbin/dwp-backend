package com.dwp.services.platform.mail;

/** Outcome of atomically materializing one provider synchronization batch. */
record MailSyncResult(
        int inserted,
        int duplicate,
        int blockedAttachments,
        boolean partial) { }
