package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingIntelligenceDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.Audience;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ContentGrant;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ContentPermission;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceRun;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportView;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReviewDecision;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AttendanceState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Capability;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.ExecutionContext;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Request;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.TranscriptSegment;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource;
import com.dwp.services.meeting.videomeeting.provider.MeetingTranscriptSource.ReadContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.commandKey;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.correlation;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHash;
import static com.dwp.services.meeting.videomeeting.domain.VideoMeetingCommandPolicy.requestHashesMatch;

@Service
public class VideoMeetingIntelligenceService {

    private final VideoMeetingRepository meetings;
    private final VideoMeetingIntelligenceRepository intelligence;
    private final MeetingIntelligenceProvider provider;
    private final MeetingTranscriptSource transcripts;
    private final MeetingIntelligencePayloadProtector protector;
    private final MeetingIntelligenceOutputValidator validator;
    private final MeetingContentAccessPolicy access;
    private final VideoMeetingAuditRecorder audit;
    private final MeetingIntelligenceRunTransactions runTransactions;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public VideoMeetingIntelligenceService(
            VideoMeetingRepository meetings,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingIntelligenceProvider provider,
            MeetingTranscriptSource transcripts,
            MeetingIntelligencePayloadProtector protector,
            MeetingIntelligenceOutputValidator validator,
            MeetingContentAccessPolicy access,
            MeetingIntelligenceRunTransactions runTransactions,
            VideoMeetingAuditRecorder audit,
            ObjectMapper mapper) {
        this(meetings, intelligence, provider, transcripts, protector,
                validator, access, runTransactions,
                audit, mapper, Clock.systemUTC());
    }

    VideoMeetingIntelligenceService(
            VideoMeetingRepository meetings,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingIntelligenceProvider provider,
            MeetingTranscriptSource transcripts,
            MeetingIntelligencePayloadProtector protector,
            MeetingIntelligenceOutputValidator validator,
            MeetingContentAccessPolicy access,
            MeetingIntelligenceRunTransactions runTransactions,
            VideoMeetingAuditRecorder audit,
            ObjectMapper mapper,
            Clock clock) {
        this.meetings = meetings;
        this.intelligence = intelligence;
        this.provider = provider;
        this.transcripts = transcripts;
        this.protector = protector;
        this.validator = validator;
        this.access = access;
        this.runTransactions = runTransactions;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    public VideoMeetingIntelligenceDtos.RunResponse createRun(
            UUID meetingId,
            VideoMeetingIntelligenceDtos.CreateRunCommand request,
            String idempotencyKey,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        String key = commandKey(idempotencyKey);
        String hash = requestHash(
                meetingId, request.sourceArtifactId(), request.outputLanguage(),
                request.expectedContentPlanVersion(),
                VideoMeetingIntelligenceModels.PROFILE,
                VideoMeetingIntelligenceModels.SCHEMA_VERSION);
        OffsetDateTime now = now();
        UUID runId = UUID.randomUUID();
        String canonicalCorrelationId = correlation(correlationId);
        MeetingIntelligenceRunTransactions.PreparedExecution prepared =
                runTransactions.prepare(
                        subject, meetingId, request, key, hash, runId, now);
        IntelligenceRun running = prepared.run();
        if (!prepared.execute()) {
            UUID reportId = intelligence.reportForRun(
                            subject.tenantId(), meetingId, running.runId())
                    .map(IntelligenceReport::reportId).orElse(null);
            return VideoMeetingIntelligenceDtos.RunResponse.from(running, reportId);
        }
        try {
            runTransactions.ensureExecutionReadiness();
        } catch (RuntimeException exception) {
            return failPrepared(
                    subject, prepared, "DEPENDENCIES_NOT_READY", canonicalCorrelationId);
        }
        ExecutionContext providerContext = new ExecutionContext(
                subject.tenantId(), meetingId, running.runId(), canonicalCorrelationId);
        Capability capability;
        try {
            capability = capability(
                    prepared.source().processingRegion(), providerContext);
        } catch (RuntimeException exception) {
            return failPrepared(
                    subject, prepared, "PROVIDER_NOT_READY", canonicalCorrelationId);
        }

        List<TranscriptSegment> transcript;
        try {
            transcript = transcripts.read(new ReadContext(
                    subject.tenantId(), meetingId, running.runId(),
                    prepared.source().artifactId(), prepared.source().sha256(),
                    canonicalCorrelationId));
            validator.validateTranscript(transcript);
        } catch (RuntimeException exception) {
            return failPrepared(
                    subject, prepared, "TRANSCRIPT_READ_FAILED", canonicalCorrelationId);
        }

        Analysis analysis;
        try {
            analysis = provider.analyze(providerContext, new Request(
                    running.analysisProfile(), running.outputLanguage(),
                    running.sourceSha256(), transcript));
            validator.validate(analysis, transcript);
        } catch (RuntimeException exception) {
            return failPrepared(
                    subject, prepared, "INVALID_OR_UNAVAILABLE_PROVIDER_OUTPUT",
                    canonicalCorrelationId);
        }

        UUID reportId = UUID.randomUUID();
        byte[] plaintext = null;
        String encrypted;
        String payloadSha256;
        try {
            plaintext = mapper.writeValueAsBytes(analysis);
            payloadSha256 = sha256(plaintext);
            encrypted = protector.protect(subject.tenantId(), reportId, plaintext);
        } catch (RuntimeException | JsonProcessingException exception) {
            return failPrepared(
                    subject, prepared, "REPORT_PROTECTION_FAILED", canonicalCorrelationId);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
        MeetingIntelligenceRunTransactions.FinalizedExecution finalized =
                runTransactions.succeed(
                        subject, prepared, canonicalCorrelationId,
                        capability.providerCode(), capability.model(), reportId,
                        encrypted, payloadSha256, prepared.retentionUntil(),
                        subject.userId(), now());
        return VideoMeetingIntelligenceDtos.RunResponse.from(
                finalized.run(), finalized.report() == null
                        ? null : finalized.report().reportId());
    }

    @Transactional(readOnly = true)
    public VideoMeetingIntelligenceDtos.RunResponse run(UUID meetingId, UUID runId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = accessibleMeeting(subject, meetingId);
        Participant viewer = member(subject, meeting);
        if (!viewer.canHost()) throw notFound("The intelligence run was not found.");
        IntelligenceRun run = intelligence.run(subject.tenantId(), meetingId, runId)
                .orElseThrow(() -> notFound("The intelligence run was not found."));
        UUID reportId = intelligence.reportForRun(subject.tenantId(), meetingId, runId)
                .map(IntelligenceReport::reportId).orElse(null);
        return VideoMeetingIntelligenceDtos.RunResponse.from(run, reportId);
    }

    @Transactional(readOnly = true)
    public VideoMeetingIntelligenceDtos.ReportResponse report(UUID meetingId, UUID reportId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = accessibleMeeting(subject, meetingId);
        Participant viewer = member(subject, meeting);
        IntelligenceReport report = report(subject, meetingId, reportId);
        boolean grant = intelligence.hasPermission(
                subject.tenantId(), meetingId, reportId, subject.userId(),
                List.of(ContentPermission.VIEW, ContentPermission.REVIEW, ContentPermission.MANAGE),
                now());
        if (!access.canView(viewer, report, grant)) {
            throw notFound("The intelligence report was not found.");
        }
        return response(report);
    }

    @Transactional(readOnly = true)
    public VideoMeetingIntelligenceDtos.ReportResponse latestReport(UUID meetingId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = accessibleMeeting(subject, meetingId);
        Participant viewer = member(subject, meeting);
        OffsetDateTime now = now();
        IntelligenceReport report = intelligence.latestVisibleReport(
                        subject.tenantId(), meetingId, subject.userId(), viewer.canHost(), now)
                .orElseThrow(() -> notFound("The intelligence report was not found."));
        boolean grant = intelligence.hasPermission(
                subject.tenantId(), meetingId, report.reportId(), subject.userId(),
                List.of(ContentPermission.VIEW, ContentPermission.REVIEW, ContentPermission.MANAGE),
                now);
        if (!access.canView(viewer, report, grant)) {
            throw notFound("The intelligence report was not found.");
        }
        return response(report);
    }

    @Transactional(readOnly = true)
    public VideoMeetingIntelligenceDtos.ReportResponse latestPublishedReport(UUID meetingId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = accessibleMeeting(subject, meetingId);
        Participant viewer = member(subject, meeting);
        IntelligenceReport report = intelligence.latestPublishedReport(
                        subject.tenantId(), meetingId, now())
                .orElseThrow(() -> notFound("The published intelligence report was not found."));
        if (report.state() != ReportState.PUBLISHED
                || report.audience() != Audience.MEETING_PARTICIPANTS
                || !access.canView(viewer, report, false)) {
            throw notFound("The published intelligence report was not found.");
        }
        return response(report);
    }

    @Transactional
    public VideoMeetingIntelligenceDtos.ReportResponse review(
            UUID meetingId,
            UUID reportId,
            VideoMeetingIntelligenceDtos.ReviewCommand request,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant reviewer = member(subject, meeting);
        IntelligenceReport current = report(subject, meetingId, reportId);
        boolean grant = intelligence.hasPermission(
                subject.tenantId(), meetingId, reportId, subject.userId(),
                List.of(ContentPermission.REVIEW, ContentPermission.MANAGE), now());
        if (!access.canReview(reviewer, grant)) throw forbidden("Report review access is required.");
        requireVersion(current, request.expectedVersion());
        if (current.state() != ReportState.DRAFT || current.expiredAt(now())) {
            throw conflict("Only an unexpired draft report can be reviewed.");
        }
        IntelligenceRun run = intelligence.run(subject.tenantId(), meetingId, current.runId())
                .orElseThrow(() -> conflict("The report execution evidence is unavailable."));
        if (run.requestedBy() == subject.userId()) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The intelligence requester cannot approve or reject the same report.");
        }
        ReviewDecision decision = ReviewDecision.valueOf(request.decision());
        OffsetDateTime reviewedAt = now();
        IntelligenceReport reviewed = intelligence.review(
                current, decision, subject.userId(), reviewedAt);
        intelligence.saveReview(
                reviewed, current.version(), current.payloadSha256(), decision,
                request.reasonCode(), subject.userId(), reviewedAt);
        auditReport(subject, meeting, reviewed,
                "meeting.intelligence." + decision.name().toLowerCase(), correlationId);
        return response(reviewed);
    }

    @Transactional
    public VideoMeetingIntelligenceDtos.ReportResponse publish(
            UUID meetingId,
            UUID reportId,
            VideoMeetingIntelligenceDtos.VersionCommand request,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant publisher = member(subject, meeting);
        IntelligenceReport current = report(subject, meetingId, reportId);
        boolean grant = intelligence.hasPermission(
                subject.tenantId(), meetingId, reportId, subject.userId(),
                List.of(ContentPermission.MANAGE), now());
        if (!access.canManage(publisher, grant)) throw forbidden("Report management access is required.");
        requireVersion(current, request.expectedVersion());
        if (current.state() != ReportState.APPROVED || current.expiredAt(now())) {
            throw conflict("Only an unexpired approved report can be published.");
        }
        IntelligenceReport published = intelligence.publish(current, subject.userId(), now());
        auditReport(subject, meeting, published, "meeting.intelligence.published", correlationId);
        return response(published);
    }

    @Transactional
    public VideoMeetingIntelligenceDtos.ReportResponse delete(
            UUID meetingId,
            UUID reportId,
            long expectedVersion,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant manager = member(subject, meeting);
        IntelligenceReport current = report(subject, meetingId, reportId);
        boolean grant = intelligence.hasPermission(
                subject.tenantId(), meetingId, reportId, subject.userId(),
                List.of(ContentPermission.MANAGE), now());
        if (!access.canManage(manager, grant)) throw forbidden("Report management access is required.");
        requireVersion(current, expectedVersion);
        if (current.legalHold()) throw conflict("A legal hold prevents report deletion.");
        IntelligenceReport deleted = intelligence.delete(current, subject.userId(), now());
        auditReport(subject, meeting, deleted, "meeting.intelligence.deleted", correlationId);
        return VideoMeetingIntelligenceDtos.ReportResponse.from(
                new ReportView(deleted, null, intelligence.reviews(
                        subject.tenantId(), meetingId, reportId)));
    }

    @Transactional(readOnly = true)
    public VideoMeetingIntelligenceDtos.ReviewerAssignmentsResponse reviewerAssignments(
            UUID meetingId,
            UUID reportId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = accessibleMeeting(subject, meetingId);
        Participant manager = member(subject, meeting);
        if (!manager.canHost()) {
            throw forbidden("Only a meeting host can manage report reviewers.");
        }
        IntelligenceReport report = report(subject, meetingId, reportId);
        if (report.state() == ReportState.DELETED || report.expiredAt(now())) {
            throw conflict("Reviewers cannot be managed for an unavailable report.");
        }
        IntelligenceRun run = intelligence.run(subject.tenantId(), meetingId, report.runId())
                .orElseThrow(() -> conflict(
                        "The report execution evidence is unavailable."));
        List<VideoMeetingIntelligenceDtos.ReviewerCandidateResponse> candidates =
                meetings.participants(subject.tenantId(), meetingId).stream()
                        .filter(participant -> participant.userId() != null)
                        .filter(Participant::admitted)
                        .map(participant ->
                                VideoMeetingIntelligenceDtos.ReviewerCandidateResponse.from(
                                        participant, subject.userId(), run.requestedBy()))
                        .toList();
        List<VideoMeetingIntelligenceDtos.GrantResponse> grants =
                intelligence.activeGrants(
                                subject.tenantId(), meetingId, reportId, now()).stream()
                        .map(VideoMeetingIntelligenceDtos.GrantResponse::from)
                        .toList();
        return new VideoMeetingIntelligenceDtos.ReviewerAssignmentsResponse(
                reportId, report.version(), candidates, grants);
    }

    @Transactional
    public VideoMeetingIntelligenceDtos.GrantResponse grant(
            UUID meetingId,
            UUID reportId,
            long principalUserId,
            VideoMeetingIntelligenceDtos.GrantCommand request,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant manager = member(subject, meeting);
        if (!manager.canHost()) throw forbidden("Only a meeting host can grant report access.");
        IntelligenceReport report = report(subject, meetingId, reportId);
        Long expectedReportVersion = request.expectedReportVersion();
        if (expectedReportVersion == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The expected report version is required.");
        }
        requireVersion(report, expectedReportVersion);
        if (report.state() == ReportState.DELETED || report.expiredAt(now())) {
            throw conflict("Access cannot be granted to an unavailable report.");
        }
        ContentPermission permission;
        try {
            permission = ContentPermission.valueOf(request.permission());
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE, "The content permission is invalid.");
        }
        if ((permission == ContentPermission.REVIEW
                || permission == ContentPermission.MANAGE)
                && report.state() != ReportState.DRAFT) {
            throw conflict(
                    "Reviewer or manager access can only be assigned to a draft report.");
        }
        Participant principal = meetings.participant(
                        subject.tenantId(), meetingId, principalUserId)
                .filter(candidate -> candidate.userId() != null && candidate.admitted())
                .orElseThrow(() -> notFound("The report principal is not a meeting member."));
        if (principal.userId() == subject.userId()) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "A report manager cannot grant report access to themselves.");
        }
        IntelligenceRun run = intelligence.run(subject.tenantId(), meetingId, report.runId())
                .orElseThrow(() -> conflict(
                        "The report execution evidence is unavailable."));
        if ((permission == ContentPermission.REVIEW
                || permission == ContentPermission.MANAGE)
                && run.requestedBy() == principal.userId()) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The intelligence requester cannot be assigned as a reviewer or manager.");
        }
        ContentGrant grant = intelligence.grant(
                report, principal.userId(), permission, request.expiresAt(),
                request.reasonCode(), subject.userId(), now());
        audit.collaboration(
                subject, meeting, "meeting.intelligence.access-granted",
                "MEETING_INTELLIGENCE_REPORT", reportId.toString(),
                correlation(correlationId), true,
                Map.of("principalUserId", principal.userId(),
                        "permission", permission.name(), "reportVersion", report.version()));
        return VideoMeetingIntelligenceDtos.GrantResponse.from(grant);
    }

    @Transactional
    public void revoke(
            UUID meetingId,
            UUID reportId,
            long principalUserId,
            String permissionValue,
            long expectedReportVersion,
            String correlationId) {
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Meeting meeting = meetings.lockMeeting(subject.tenantId(), meetingId);
        Participant manager = member(subject, meeting);
        if (!manager.canHost()) throw forbidden("Only a meeting host can revoke report access.");
        IntelligenceReport report = report(subject, meetingId, reportId);
        requireVersion(report, expectedReportVersion);
        ContentPermission permission;
        try {
            permission = ContentPermission.valueOf(permissionValue);
        } catch (RuntimeException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The content permission is invalid.");
        }
        intelligence.revoke(report, principalUserId, permission, subject.userId(), now());
        audit.collaboration(
                subject, meeting, "meeting.intelligence.access-revoked",
                "MEETING_INTELLIGENCE_REPORT", reportId.toString(),
                correlation(correlationId), true,
                Map.of("principalUserId", principalUserId,
                        "permission", permission.name(), "reportVersion", report.version()));
    }

    private Capability capability(
            String requiredRegion, ExecutionContext providerContext) {
        Capability capability;
        try {
            capability = provider.capability(providerContext);
        } catch (RuntimeException exception) {
            throw unavailable("Meeting intelligence provider readiness is unavailable.", exception);
        }
        if (capability == null || !capability.enterpriseSafe(requiredRegion)
                || !safeCode(capability.providerCode(), 48)
                || !safeCode(capability.model(), 120)) {
            throw unavailable("Meeting intelligence provider is not enterprise-ready.", null);
        }
        return capability;
    }

    private VideoMeetingIntelligenceDtos.ReportResponse response(IntelligenceReport report) {
        if (report.state() == ReportState.DELETED || report.encryptedPayload() == null
                || report.expiredAt(now())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "The intelligence report is no longer available.");
        }
        try {
            byte[] plaintext = protector.unprotect(
                    report.tenantId(), report.reportId(), report.encryptedPayload());
            try {
                if (!requestHashesMatch(sha256(plaintext), report.payloadSha256())) {
                    throw new IllegalStateException("Report payload integrity check failed.");
                }
                Analysis analysis = mapper.readValue(plaintext, Analysis.class);
                return VideoMeetingIntelligenceDtos.ReportResponse.from(new ReportView(
                                report, analysis, intelligence.reviews(
                                report.tenantId(), report.meetingId(), report.reportId())),
                        canCurrentViewerReview(report));
            } finally {
                Arrays.fill(plaintext, (byte) 0);
            }
        } catch (RuntimeException | IOException exception) {
            throw unavailable("The encrypted intelligence report could not be opened.", exception);
        }
    }

    private boolean canCurrentViewerReview(IntelligenceReport report) {
        if (report.state() != ReportState.DRAFT || report.expiredAt(now())) return false;
        MeetingRequestContext.Subject subject = MeetingRequestContext.get();
        Participant viewer = meetings.participant(
                        subject.tenantId(), report.meetingId(), subject.userId())
                .filter(candidate -> candidate.attendanceState() != AttendanceState.DENIED)
                .orElse(null);
        boolean reviewGrant = intelligence.hasPermission(
                subject.tenantId(), report.meetingId(), report.reportId(), subject.userId(),
                List.of(ContentPermission.REVIEW, ContentPermission.MANAGE), now());
        IntelligenceRun run = intelligence.run(
                        subject.tenantId(), report.meetingId(), report.runId())
                .orElse(null);
        return access.canReview(viewer, reviewGrant)
                && run != null && run.requestedBy() != subject.userId();
    }

    private Meeting accessibleMeeting(
            MeetingRequestContext.Subject subject, UUID meetingId) {
        return meetings.accessibleMeeting(subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(() -> notFound("The meeting was not found."));
    }

    private Participant member(MeetingRequestContext.Subject subject, Meeting meeting) {
        return meetings.participant(subject.tenantId(), meeting.meetingId(), subject.userId())
                .filter(candidate -> candidate.attendanceState() != AttendanceState.DENIED)
                .orElseThrow(() -> notFound("The meeting was not found."));
    }

    private IntelligenceReport report(
            MeetingRequestContext.Subject subject, UUID meetingId, UUID reportId) {
        return intelligence.report(subject.tenantId(), meetingId, reportId)
                .orElseThrow(() -> notFound("The intelligence report was not found."));
    }

    private void requireVersion(IntelligenceReport report, long expectedVersion) {
        if (report.version() != expectedVersion) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The intelligence report changed. Refresh and retry.");
        }
    }

    private VideoMeetingIntelligenceDtos.RunResponse failPrepared(
            MeetingRequestContext.Subject subject,
            MeetingIntelligenceRunTransactions.PreparedExecution prepared,
            String failureCode,
            String correlationId) {
        MeetingIntelligenceRunTransactions.FinalizedExecution finalized =
                runTransactions.fail(
                        subject, prepared.meeting(), correlationId,
                        prepared.run(), failureCode, now());
        return VideoMeetingIntelligenceDtos.RunResponse.from(
                finalized.run(), finalized.report() == null
                        ? null : finalized.report().reportId());
    }

    private void auditReport(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            IntelligenceReport report,
            String action,
            String correlationId) {
        audit.collaboration(
                subject, meeting, action, "MEETING_INTELLIGENCE_REPORT",
                report.reportId().toString(), correlation(correlationId), true,
                Map.of("reportState", report.state().name(),
                        "audience", report.audience().name(),
                        "reportVersion", report.version(),
                        "legalHold", report.legalHold()));
    }

    private boolean safeCode(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength
                && value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]*$");
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private BaseException notFound(String message) {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, message);
    }

    private BaseException unavailable(String message, Throwable cause) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }
}
