package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos;
import com.dwp.services.meeting.videomeeting.domain.MeetingPreparationMaterialAccessModels.PreparedAccess;
import com.dwp.services.meeting.videomeeting.provider.MeetingPreparationMaterialProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingPreparationMaterialProvider.AccessRequest;
import com.dwp.services.meeting.videomeeting.provider.MeetingPreparationMaterialProvider.AccessTicket;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MeetingPreparationMaterialAccessService {

    private final MeetingPreparationMaterialProvider provider;
    private final MeetingPreparationMaterialAccessTransactions transactions;

    public MeetingPreparationMaterialAccessService(
            MeetingPreparationMaterialProvider provider,
            MeetingPreparationMaterialAccessTransactions transactions) {
        this.provider = provider;
        this.transactions = transactions;
    }

    public VideoMeetingPreparationDtos.MaterialAccessTicketResponse issueAccessTicket(
            UUID meetingId,
            UUID materialId,
            VideoMeetingPreparationDtos.MaterialAccessRequest request,
            String correlationId) {
        if (request == null || request.expectedVersion() == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The expected material version is required.");
        }
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        PreparedAccess prepared = transactions.prepare(
                subject, meetingId, materialId, request.expectedVersion(),
                safeCorrelation(correlationId));
        AccessTicket ticket;
        try {
            var material = prepared.material();
            ticket = provider.issueAccessTicket(new AccessRequest(
                    subject.tenantId(), meetingId, materialId, subject.userId(),
                    material.referenceProvider(), material.opaqueReference(),
                    material.sourceVersion(), material.classification(), material.contentType(),
                    material.contentSha256(), prepared.referenceBindingSha256(),
                    material.version(), prepared.expiresNoLaterThan(), prepared.correlationId()));
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "The preparation material access broker is unavailable.");
        }
        return transactions.complete(prepared, ticket);
    }

    private String safeCorrelation(String value) {
        String candidate = value == null ? "" : value.trim();
        return candidate.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,159}$")
                ? candidate : "meeting-material:" + UUID.randomUUID();
    }
}
