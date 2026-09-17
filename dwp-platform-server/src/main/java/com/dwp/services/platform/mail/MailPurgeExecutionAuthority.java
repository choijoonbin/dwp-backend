package com.dwp.services.platform.mail;

interface MailPurgeExecutionAuthority {

    Decision evaluate(long tenantId, long actorId, java.util.UUID jobId);

    record Decision(State state, String evidenceRef, String revision, String reasonCode) { }

    enum State { ALLOWED, DENIED, UNAVAILABLE }
}
