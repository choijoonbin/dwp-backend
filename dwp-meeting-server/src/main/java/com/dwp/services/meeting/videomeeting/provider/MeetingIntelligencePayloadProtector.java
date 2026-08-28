package com.dwp.services.meeting.videomeeting.provider;

import java.util.UUID;

/** Application-layer encryption boundary for report content. */
public interface MeetingIntelligencePayloadProtector {

    boolean available();

    boolean ready();

    String protect(long tenantId, UUID reportId, byte[] plaintext);

    byte[] unprotect(long tenantId, UUID reportId, String protectedPayload);
}
