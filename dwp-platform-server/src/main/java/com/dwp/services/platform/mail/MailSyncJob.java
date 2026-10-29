package com.dwp.services.platform.mail;

import java.util.UUID;

/** Durable Mail synchronization work claimed by a worker lease. */
record MailSyncJob(
        UUID jobId,
        long tenantId,
        UUID operationId,
        UUID connectionId,
        long actorId,
        long connectionVersion,
        String triggerKind,
        int attemptCount,
        int maximumAttempts,
        String correlationId) { }
