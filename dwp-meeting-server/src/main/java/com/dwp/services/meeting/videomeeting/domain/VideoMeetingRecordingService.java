package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingContentDtos;
import com.dwp.services.meeting.videomeeting.domain.MeetingRecordingCommandModels.Preparation;
import com.dwp.services.meeting.videomeeting.provider.MeetingMediaProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingRecordingProvider;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.commandKey;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;

/** Executes provider I/O strictly between independently committed, fenced transactions. */
@Service
public class VideoMeetingRecordingService {

    private final MeetingRecordingCommandTransactions transactions;
    private final MeetingRecordingProvider recordingProvider;
    private final MeetingMediaProvider mediaProvider;
    private final MeetingContentDependencies dependencies;

    public VideoMeetingRecordingService(
            MeetingRecordingCommandTransactions transactions,
            MeetingRecordingProvider recordingProvider,
            MeetingMediaProvider mediaProvider,
            MeetingContentDependencies dependencies) {
        this.transactions = transactions;
        this.recordingProvider = recordingProvider;
        this.mediaProvider = mediaProvider;
        this.dependencies = dependencies;
    }

    public VideoMeetingContentDtos.RecordingCommandResult requestRecording(
            UUID meetingId,
            VideoMeetingContentDtos.RequestRecordingCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        String key = commandKey(idempotencyKey);
        String hash = requestHash(meetingId, request.expectedPlanVersion());
        String canonicalCorrelation = correlation(correlationId);
        MeetingRecordingProvider.Capability recordingCapability =
                recordingProvider.capability();
        Preparation prepared = transactions.prepareStart(
                subject, meetingId, request.expectedPlanVersion(), key, hash,
                canonicalCorrelation, dependencies.status(recordingCapability),
                mediaProvider.capability(),
                recordingCapability);
        if (!prepared.execute()) return prepared.replay();
        MeetingRecordingProvider.Receipt receipt;
        try {
            receipt = recordingProvider.start(providerCommand(prepared, canonicalCorrelation));
            validateReceipt(prepared, receipt, "STARTED");
        } catch (RuntimeException providerFailure) {
            fail(subject, prepared, providerFailure);
            throw unavailable();
        }
        return transactions.succeed(subject, prepared, receipt.providerCommandId());
    }

    public VideoMeetingContentDtos.RecordingCommandResult stopRecording(
            UUID meetingId,
            VideoMeetingContentDtos.StopRecordingCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        String key = commandKey(idempotencyKey);
        String hash = requestHash(meetingId, request.expectedSessionVersion());
        String canonicalCorrelation = correlation(correlationId);
        MeetingRecordingProvider.Capability recordingCapability =
                recordingProvider.capability();
        Preparation prepared = transactions.prepareStop(
                subject, meetingId, request.expectedSessionVersion(), key, hash,
                canonicalCorrelation, dependencies.status(recordingCapability),
                recordingCapability);
        if (!prepared.execute()) return prepared.replay();
        MeetingRecordingProvider.Receipt receipt;
        try {
            receipt = recordingProvider.stop(providerCommand(prepared, canonicalCorrelation));
            validateReceipt(prepared, receipt, "STOPPED");
        } catch (RuntimeException providerFailure) {
            fail(subject, prepared, providerFailure);
            throw unavailable();
        }
        return transactions.succeed(subject, prepared, receipt.providerCommandId());
    }

    private MeetingRecordingProvider.Command providerCommand(
            Preparation prepared, String correlationId) {
        String roomName = prepared.meeting().roomName();
        if (roomName == null || roomName.isBlank()) throw unavailable();
        return new MeetingRecordingProvider.Command(
                prepared.command().tenantId(), prepared.command().meetingId(),
                prepared.command().recordingSessionId(), prepared.session().planVersion(),
                prepared.session().noticeId(), roomName, correlationId);
    }

    private void fail(
            MeetingRequestContext.Subject subject,
            Preparation prepared,
            RuntimeException providerFailure) {
        try {
            transactions.fail(subject, prepared, "RECORDING_PROVIDER_FAILURE");
        } catch (RuntimeException terminalFailure) {
            providerFailure.addSuppressed(terminalFailure);
        }
    }

    private void validateReceipt(
            Preparation prepared,
            MeetingRecordingProvider.Receipt receipt,
            String expectedState) {
        if (receipt == null
                || !prepared.command().recordingSessionId().equals(
                        receipt.recordingSessionId())
                || !expectedState.equals(receipt.commandState())
                || receipt.providerCommandId() == null
                || !receipt.providerCommandId().matches(
                        "^[A-Za-z0-9][A-Za-z0-9._:-]{2,159}$")) {
            throw new IllegalStateException(
                    "The recording provider receipt is not terminal and bound.");
        }
    }

    private BaseException unavailable() {
        return new BaseException(
                ErrorCode.EXTERNAL_SERVICE_ERROR,
                "The governed recording provider is unavailable.");
    }
}
