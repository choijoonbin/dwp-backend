package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.MeetingRecordingAccessDtos;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingAccessModels.PreparedAccess;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider.AccessRequest;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider.AccessTicket;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MeetingRecordingAccessService {

    private final MeetingRecordingProvider provider;
    private final MeetingRecordingAccessTransactions transactions;

    public MeetingRecordingAccessService(
            MeetingRecordingProvider provider,
            MeetingRecordingAccessTransactions transactions) {
        this.provider = provider;
        this.transactions = transactions;
    }

    public MeetingRecordingAccessDtos.AccessTicketResponse issueAccessTicket(
            UUID meetingId,
            UUID artifactId,
            MeetingRecordingAccessDtos.AccessTicketCommand request,
            String correlationId) {
        if (request == null || request.expectedArtifactVersion() == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The expected artifact version is required.");
        }
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        PreparedAccess prepared = transactions.prepare(
                subject, meetingId, artifactId, request.expectedArtifactVersion(),
                safeCorrelation(correlationId));
        AccessTicket ticket;
        try {
            ticket = provider.issueAccessTicket(new AccessRequest(
                    subject.tenantId(), meetingId, artifactId, subject.userId(),
                    prepared.artifact().storageProvider(), prepared.artifact().objectKey(),
                    prepared.artifact().contentType(), prepared.artifact().sha256(),
                    prepared.artifact().version(),
                    prepared.expiresNoLaterThan(), prepared.correlationId()));
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The recording access broker is unavailable.");
        }
        return transactions.complete(prepared, ticket);
    }

    private String safeCorrelation(String value) {
        String candidate = value == null ? "" : value.trim();
        return candidate.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$")
                ? candidate : "meeting-recording:" + UUID.randomUUID();
    }
}
