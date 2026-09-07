package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Operation;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.OriginalAccess;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Request;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Response;
import com.dwp.services.meeting.videomeeting.domain.MeetingFollowupCandidateProjector.Candidate;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.Audience;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.IntelligenceReport;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingIntelligenceModels.ReportState;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligencePayloadProtector;
import com.dwp.services.meeting.videomeeting.provider.MeetingIntelligenceProvider.Analysis;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Resolves only human-published action-item terms. Transcript content and citations never cross
 * this boundary, and arbitrary reassignment remains closed until People eligibility is verified.
 */
@Service
public class MeetingFollowupSourceService {

    private final VideoMeetingRepository meetings;
    private final VideoMeetingIntelligenceRepository intelligence;
    private final MeetingIntelligencePayloadProtector protector;
    private final MeetingContentAccessPolicy access;
    private final MeetingFollowupCurrentAuthority authority;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public MeetingFollowupSourceService(
            VideoMeetingRepository meetings,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingIntelligencePayloadProtector protector,
            MeetingContentAccessPolicy access,
            MeetingFollowupCurrentAuthority authority,
            ObjectMapper mapper) {
        this(meetings, intelligence, protector, access, authority, mapper, Clock.systemUTC());
    }

    MeetingFollowupSourceService(
            VideoMeetingRepository meetings,
            VideoMeetingIntelligenceRepository intelligence,
            MeetingIntelligencePayloadProtector protector,
            MeetingContentAccessPolicy access,
            MeetingFollowupCurrentAuthority authority,
            ObjectMapper mapper,
            Clock clock) {
        this.meetings = meetings;
        this.intelligence = intelligence;
        this.protector = protector;
        this.access = access;
        this.authority = authority;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Response resolve(Request request) {
        if (!validRequest(request)) {
            return Response.denied(request, "INVALID_SOURCE_REQUEST", null,
                    OriginalAccess.UNAVAILABLE);
        }
        MeetingFollowupCurrentAuthority.Decision authorityDecision =
                authority.authorize(request);
        if (!authorityDecision.allowed()) {
            return Response.denied(request, authorityDecision.denial().name(), null,
                    OriginalAccess.UNAVAILABLE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        IntelligenceReport report = intelligence.report(
                        request.tenantId(), request.source().meetingId(),
                        request.source().reportId())
                .orElse(null);
        if (report == null) {
            return Response.denied(request, "SOURCE_NOT_FOUND", null,
                    OriginalAccess.UNAVAILABLE);
        }
        if (report.state() == ReportState.DELETED || report.expiredAt(now)) {
            return Response.denied(request, "SOURCE_DELETED", report.version(),
                    OriginalAccess.DELETED);
        }
        if (report.state() != ReportState.PUBLISHED
                || report.audience() != Audience.MEETING_PARTICIPANTS) {
            return Response.denied(request, "SOURCE_NOT_PUBLISHED", report.version(),
                    OriginalAccess.FORBIDDEN);
        }
        IntelligenceReport latest = intelligence.latestPublishedReport(
                        request.tenantId(), request.source().meetingId(), now)
                .orElse(null);
        if (latest == null || !latest.reportId().equals(report.reportId())
                || latest.version() != report.version()) {
            return Response.denied(request, "SOURCE_SUPERSEDED", report.version(),
                    OriginalAccess.DELETED);
        }
        Participant actor = meetings.participant(
                        request.tenantId(), request.source().meetingId(),
                        request.actorUserId())
                .filter(Participant::admitted)
                .orElse(null);
        if (actor == null || !access.canView(actor, report, false)) {
            return Response.denied(request, "SOURCE_ACCESS_FORBIDDEN", report.version(),
                    OriginalAccess.FORBIDDEN);
        }
        if (request.action() == Operation.CREATE
                && request.expectedSourceVersion() != report.version()) {
            return Response.denied(request, "SOURCE_VERSION_CONFLICT",
                    report.version(), OriginalAccess.AVAILABLE);
        }
        if (request.action() == Operation.REASSIGN) {
            return Response.denied(request, "TARGET_ELIGIBILITY_UNVERIFIED",
                    report.version(), OriginalAccess.AVAILABLE);
        }
        Analysis analysis = open(report);
        if (analysis == null) {
            return Response.denied(request, "SOURCE_PROTECTION_UNAVAILABLE", report.version(),
                    OriginalAccess.UNAVAILABLE);
        }
        Candidate candidate = MeetingFollowupCandidateProjector.candidates(report, analysis)
                .stream()
                .filter(value -> value.candidateId().equals(request.source().candidateId()))
                .findFirst()
                .orElse(null);
        if (candidate == null) {
            return Response.denied(request, "CANDIDATE_NOT_FOUND", report.version(),
                    OriginalAccess.UNAVAILABLE);
        }
        if (request.action() == Operation.CREATE) {
            return new Response(
                    request.tenantId(), request.actorUserId(), request.source(), request.action(),
                    true, null, candidate.sourceVersion(), OriginalAccess.AVAILABLE,
                    true, new MeetingFollowupSourceDtos.ApprovedTask(
                            request.actorUserId(), candidate.confirmedText(), null,
                            "NORMAL", null));
        }
        return new Response(
                request.tenantId(), request.actorUserId(), request.source(), request.action(),
                true, null, candidate.sourceVersion(), OriginalAccess.AVAILABLE,
                false, null);
    }

    private Analysis open(IntelligenceReport report) {
        if (!protector.available() || !protector.ready()
                || report.encryptedPayload() == null || report.payloadSha256() == null) {
            return null;
        }
        byte[] plaintext = null;
        try {
            plaintext = protector.unprotect(
                    report.tenantId(), report.reportId(), report.encryptedPayload());
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(plaintext));
            if (!MessageDigest.isEqual(
                    digest.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    report.payloadSha256().getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII))) {
                return null;
            }
            return mapper.readValue(plaintext, Analysis.class);
        } catch (Exception exception) {
            return null;
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private boolean validRequest(Request request) {
        if (request == null || request.tenantId() <= 0 || request.actorUserId() <= 0
                || request.source() == null || request.source().meetingId() == null
                || request.source().reportId() == null
                || request.source().candidateId() == null || request.action() == null) {
            return false;
        }
        return switch (request.action()) {
            case READ -> request.targetAssigneeUserId() == null
                    && request.expectedSourceVersion() == null;
            case CREATE -> request.targetAssigneeUserId() == null
                    && request.expectedSourceVersion() != null
                    && request.expectedSourceVersion() >= 0;
            case REASSIGN -> request.targetAssigneeUserId() != null
                    && request.targetAssigneeUserId() > 0
                    && request.expectedSourceVersion() == null;
        };
    }
}
