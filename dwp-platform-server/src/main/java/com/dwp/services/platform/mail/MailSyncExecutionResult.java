package com.dwp.services.platform.mail;

/** Aggregate outcome of a bounded synchronization execution. */
record MailSyncExecutionResult(
        int accounts,
        int batches,
        int insertedMessages,
        int duplicateMessages,
        int blockedAttachments,
        boolean continuationRequired) {

    MailSyncExecutionResult(
            int accounts,
            int batches,
            int insertedMessages,
            int duplicateMessages,
            int blockedAttachments) {
        this(accounts, batches, insertedMessages, duplicateMessages,
                blockedAttachments, false);
    }
}
