package com.dwp.services.platform.mail;

/** Counts expired worker leases returned to retry or dead-letter state. */
record MailSyncLeaseRecovery(int retryWait, int deadLetter) { }
