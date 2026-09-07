package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingPreparationDtos;
import com.dwp.services.meeting.videomeeting.audit.VideoMeetingAuditRecorder;
import com.dwp.services.meeting.videomeeting.domain.MeetingPreparationMaterialAccessModels.Material;
import com.dwp.services.meeting.videomeeting.domain.MeetingPreparationMaterialAccessModels.PreparedAccess;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.provider.MeetingPreparationMaterialHttpProperties;
import com.dwp.services.meeting.videomeeting.provider.MeetingPreparationMaterialProvider.AccessTicket;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
class MeetingPreparationMaterialAccessTransactions {

    private final VideoMeetingRepository meetings;
    private final MeetingPreparationMaterialAccessRepository materials;
    private final VideoMeetingAuditRecorder audit;
    private final MeetingPreparationMaterialHttpProperties properties;
    private final Clock clock;

    @Autowired
    MeetingPreparationMaterialAccessTransactions(
            VideoMeetingRepository meetings,
            MeetingPreparationMaterialAccessRepository materials,
            VideoMeetingAuditRecorder audit,
            MeetingPreparationMaterialHttpProperties properties) {
        this(meetings, materials, audit, properties, Clock.systemUTC());
    }

    MeetingPreparationMaterialAccessTransactions(
            VideoMeetingRepository meetings,
            MeetingPreparationMaterialAccessRepository materials,
            VideoMeetingAuditRecorder audit,
            MeetingPreparationMaterialHttpProperties properties,
            Clock clock) {
        this.meetings = meetings;
        this.materials = materials;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PreparedAccess prepare(
            MeetingRequestContext.Subject subject,
            UUID meetingId,
            UUID materialId,
            long expectedVersion,
            String correlationId) {
        requireViewer(subject);
        Meeting meeting = accessible(subject, meetingId);
        Material material = material(subject, meetingId, materialId);
        requireAvailable(material, expectedVersion);
        OffsetDateTime expiresNoLaterThan = minimum(
                material.retentionUntil(), now().plus(accessTicketTtl()));
        String referenceBinding = referenceBinding(material);
        audit.collaboration(
                subject, meeting, "meeting.preparation-material.access-requested",
                "MEETING_PREPARATION_MATERIAL", material.materialId().toString(),
                correlationId, false,
                Map.of("materialVersion", material.version(),
                        "referenceProvider", material.referenceProvider(),
                        "classification", material.classification(),
                        "expiresNoLaterThan", expiresNoLaterThan.toString()));
        return new PreparedAccess(
                subject, meeting, material, expiresNoLaterThan, referenceBinding, correlationId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public VideoMeetingPreparationDtos.MaterialAccessTicketResponse complete(
            PreparedAccess prepared,
            AccessTicket ticket) {
        requireViewer(prepared.subject());
        Meeting meeting = accessible(prepared.subject(), prepared.meeting().meetingId());
        Material current = material(
                prepared.subject(), meeting.meetingId(), prepared.material().materialId());
        requireAvailable(current, prepared.material().version());
        if (!sameLocator(prepared.material(), current)
                || !constantEquals(prepared.referenceBindingSha256(), referenceBinding(current))
                || !validTicket(prepared, current, ticket)) {
            throw unavailable("The preparation material access ticket is invalid.");
        }
        audit.collaboration(
                prepared.subject(), meeting, "meeting.preparation-material.access-issued",
                "MEETING_PREPARATION_MATERIAL", current.materialId().toString(),
                prepared.correlationId(), false,
                Map.of("materialVersion", current.version(),
                        "referenceProvider", current.referenceProvider(),
                        "classification", current.classification(),
                        "expiresAt", ticket.expiresAt().toString()));
        return new VideoMeetingPreparationDtos.MaterialAccessTicketResponse(
                current.meetingId(), current.materialId(), current.version(),
                ticket.accessUri().toString(),
                ticket.expiresAt(), current.contentType(), current.displayName());
    }

    private Meeting accessible(MeetingRequestContext.Subject subject, UUID meetingId) {
        return meetings.lockAccessibleMeeting(subject.tenantId(), meetingId, subject.userId())
                .orElseThrow(() -> notFound("The preparation material was not found."));
    }

    private Material material(
            MeetingRequestContext.Subject subject, UUID meetingId, UUID materialId) {
        return materials.forUpdate(subject.tenantId(), meetingId, materialId)
                .orElseThrow(() -> notFound("The preparation material was not found."));
    }

    private void requireViewer(MeetingRequestContext.Subject subject) {
        if (subject == null || !subject.permissions().contains("APP.MEETINGS:VIEW")) {
            throw notFound("The preparation material was not found.");
        }
    }

    private void requireAvailable(Material material, long expectedVersion) {
        if (material.version() != expectedVersion) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The preparation material changed. Refresh and retry.");
        }
        if (!"ACTIVE".equals(material.lifecycleState())
                || material.retentionUntil() == null
                || !material.retentionUntil().isAfter(now())) {
            throw unavailable("The preparation material is not available.");
        }
    }

    private boolean validTicket(PreparedAccess prepared, Material current, AccessTicket ticket) {
        return ticket != null && ticket.materialId() != null && ticket.accessUri() != null
                && ticket.expiresAt() != null
                && ticket.materialId().equals(current.materialId())
                && ticket.requesterUserId() == prepared.subject().userId()
                && ticket.materialVersion() == current.version()
                && constantEquals(
                        prepared.referenceBindingSha256(), ticket.referenceBindingSha256())
                && validAccessUri(ticket.accessUri(), current, prepared.referenceBindingSha256())
                && ticket.expiresAt().isAfter(now())
                && !ticket.expiresAt().isAfter(prepared.expiresNoLaterThan())
                && !ticket.expiresAt().isAfter(current.retentionUntil());
    }

    private boolean sameLocator(Material first, Material second) {
        return Objects.equals(first.referenceProvider(), second.referenceProvider())
                && Objects.equals(first.opaqueReference(), second.opaqueReference())
                && Objects.equals(first.sourceVersion(), second.sourceVersion())
                && Objects.equals(first.classification(), second.classification())
                && Objects.equals(first.contentType(), second.contentType())
                && Objects.equals(first.contentSha256(), second.contentSha256())
                && Objects.equals(first.retentionUntil(), second.retentionUntil());
    }

    private boolean validAccessUri(URI candidate, Material material, String referenceBinding) {
        if (candidate == null || candidate.toString().length() > 8_192) return false;
        String host = candidate.getHost() == null
                ? "" : candidate.getHost().toLowerCase(Locale.ROOT);
        Set<String> allowlist = properties.getAccessTicketAllowedHosts().stream()
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        String prefix = properties.getAccessTicketPathPrefix() == null
                ? "" : properties.getAccessTicketPathPrefix().trim();
        String rawQuery = candidate.getRawQuery();
        String decoded = candidate.getPath()
                + (candidate.getQuery() == null ? "" : "?" + candidate.getQuery());
        return "https".equalsIgnoreCase(candidate.getScheme())
                && !host.isBlank() && candidate.getUserInfo() == null
                && candidate.getFragment() == null
                && (candidate.getPort() == -1 || candidate.getPort() == 443)
                && prefix.matches("^/[A-Za-z0-9._~/-]{1,200}/$")
                && !prefix.contains("//") && !prefix.contains("/../")
                && !prefix.contains("/./") && candidate.getPath() != null
                && candidate.getPath().startsWith(prefix)
                && candidate.getPath().length() > prefix.length()
                && !host.equals("localhost") && !host.endsWith(".local")
                && !host.matches("^[0-9a-f:.]+$") && allowlist.contains(host)
                && (rawQuery == null || rawQuery.matches(
                        "^(token|ticket)=[A-Za-z0-9._~-]{16,4096}$"))
                && !candidate.toString().contains(material.opaqueReference())
                && !decoded.contains(material.opaqueReference())
                && !candidate.toString().contains(referenceBinding)
                && (material.contentSha256() == null
                        || !candidate.toString().contains(material.contentSha256()));
    }

    private String referenceBinding(Material material) {
        String canonical = material.tenantId() + "\n" + material.meetingId() + "\n"
                + material.materialId() + "\n" + material.referenceProvider() + "\n"
                + material.opaqueReference() + "\n"
                + Objects.toString(material.sourceVersion(), "") + "\n"
                + material.classification() + "\n" + material.contentType() + "\n"
                + Objects.toString(material.contentSha256(), "") + "\n" + material.version();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.");
        }
    }

    private boolean constantEquals(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
    }

    private Duration accessTicketTtl() {
        Duration ttl = properties.getAccessTicketTtl();
        if (ttl == null || ttl.compareTo(Duration.ofSeconds(30)) < 0
                || ttl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw unavailable("The preparation material broker is not configured.");
        }
        return ttl;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private OffsetDateTime minimum(OffsetDateTime first, OffsetDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private BaseException notFound(String message) {
        return new BaseException(ErrorCode.ENTITY_NOT_FOUND, message);
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, message);
    }
}
