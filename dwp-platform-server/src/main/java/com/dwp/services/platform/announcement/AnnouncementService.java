package com.dwp.services.platform.announcement;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.security.PlatformRoutePredicateEvaluator;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AnnouncementService {

    private static final Pattern ROLE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_:-]{0,79}");
    private static final Pattern CATEGORY_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
    private static final Pattern LOCALE_PATTERN = Pattern.compile("[a-z]{2}(-[A-Z]{2})?");

    private final AnnouncementRepository repository;
    private final JdbcTemplate jdbc;
    private final PlatformAuditService auditService;
    private final PlatformRoutePredicateEvaluator predicateEvaluator;

    public AnnouncementService(
            AnnouncementRepository repository,
            JdbcTemplate jdbc,
            PlatformAuditService auditService,
            PlatformRoutePredicateEvaluator predicateEvaluator) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.predicateEvaluator = predicateEvaluator;
    }

    @Transactional(readOnly = true)
    public List<AnnouncementDtos.AnnouncementResponse> listActive(
            Long tenantId,
            String rolesHeader) {
        List<String> roles = parseRoles(rolesHeader);
        return repository.findActive(
                        tenantId,
                        OffsetDateTime.now(ZoneOffset.UTC),
                        roles.isEmpty() ? List.of("__NO_ROLE__") : roles,
                        PageRequest.of(0, 10))
                .stream()
                .map(announcement -> response(announcement, EngagementMetrics.EMPTY))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AnnouncementDtos.AnnouncementResponse> listAdmin(Long tenantId) {
        List<Announcement> announcements =
                repository.findByTenantIdOrderByUpdatedAtDescAnnouncementIdDesc(tenantId);
        Map<Long, EngagementMetrics> metrics = engagementMetrics(tenantId);
        return announcements.stream()
                .map(announcement -> response(
                        announcement,
                        metrics.getOrDefault(
                                announcement.getAnnouncementId(), EngagementMetrics.EMPTY)))
                .toList();
    }

    @Transactional
    public void recordEngagement(
            Long tenantId,
            Long userId,
            String rolesHeader,
            Long announcementId,
            String engagementType) {
        Announcement announcement = require(tenantId, announcementId);
        requireVisible(announcement, parseRoles(rolesHeader));
        if ("ACTION".equals(engagementType) && announcement.getActionUrl() == null) {
            throw invalid("This announcement has no action to record.");
        }
        if ("VIEW".equals(engagementType)) {
            jdbc.update("""
                    INSERT INTO sys_announcement_engagements (
                        tenant_id, announcement_id, user_id,
                        first_seen_at, last_seen_at, seen_count)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
                    ON CONFLICT (tenant_id, announcement_id, user_id)
                    DO UPDATE SET
                        first_seen_at = COALESCE(
                            sys_announcement_engagements.first_seen_at,
                            EXCLUDED.first_seen_at),
                        last_seen_at = EXCLUDED.last_seen_at,
                        seen_count = sys_announcement_engagements.seen_count + 1
                    """, tenantId, announcementId, userId);
            return;
        }
        if ("ACTION".equals(engagementType)) {
            jdbc.update("""
                    INSERT INTO sys_announcement_engagements (
                        tenant_id, announcement_id, user_id,
                        first_action_at, last_action_at, action_count)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
                    ON CONFLICT (tenant_id, announcement_id, user_id)
                    DO UPDATE SET
                        first_action_at = COALESCE(
                            sys_announcement_engagements.first_action_at,
                            EXCLUDED.first_action_at),
                        last_action_at = EXCLUDED.last_action_at,
                        action_count = sys_announcement_engagements.action_count + 1
                    """, tenantId, announcementId, userId);
            return;
        }
        throw invalid("The announcement engagement type is invalid.");
    }

    @Transactional
    public AnnouncementDtos.AnnouncementResponse create(
            Long tenantId,
            Long actorId,
            String correlationId,
            AnnouncementDtos.CreateAnnouncementRequest request) {
        NormalizedDefinition definition = normalize(request.definition());
        Announcement announcement = Announcement.builder()
                .tenantId(tenantId)
                .lifecycleState(AnnouncementLifecycle.DRAFT)
                .build();
        apply(announcement, definition);
        Announcement saved = repository.saveAndFlush(announcement);
        auditService.success(
                tenantId,
                actorId,
                "announcement.created",
                "ANNOUNCEMENT",
                saved.getAnnouncementId().toString(),
                correlationId,
                null,
                snapshot(saved));
        return response(saved);
    }

    @Transactional
    public AnnouncementDtos.AnnouncementResponse update(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long announcementId,
            AnnouncementDtos.UpdateAnnouncementRequest request) {
        Announcement announcement = predicateEvaluator.requireAnnouncementObjectVersion(
                tenantId, announcementId, request.version());
        if (announcement.getLifecycleState() != AnnouncementLifecycle.DRAFT) {
            throw conflict(
                    "Published and archived announcements are immutable. Duplicate the content to create a new draft revision.");
        }
        Map<String, Object> before = snapshot(announcement);
        apply(announcement, normalize(request.definition()));
        Announcement saved = repository.saveAndFlush(announcement);
        auditService.success(
                tenantId,
                actorId,
                "announcement.updated",
                "ANNOUNCEMENT",
                announcementId.toString(),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    @Transactional
    public AnnouncementDtos.AnnouncementResponse publish(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long announcementId,
            Long version) {
        Announcement announcement = predicateEvaluator.requireAnnouncementObjectVersion(
                tenantId, announcementId, version);
        if (announcement.getLifecycleState() == AnnouncementLifecycle.ARCHIVED) {
            throw conflict("Archived announcements cannot be published.");
        }
        if (announcement.getLifecycleState() == AnnouncementLifecycle.PUBLISHED) {
            return response(announcement);
        }
        Map<String, Object> before = snapshot(announcement);
        announcement.setLifecycleState(AnnouncementLifecycle.PUBLISHED);
        announcement.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        announcement.setPublishedBy(actorId);
        Announcement saved = repository.saveAndFlush(announcement);
        auditService.success(
                tenantId,
                actorId,
                "announcement.published",
                "ANNOUNCEMENT",
                announcementId.toString(),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    @Transactional
    public AnnouncementDtos.AnnouncementResponse archive(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long announcementId,
            Long version) {
        Announcement announcement = predicateEvaluator.requireAnnouncementObjectVersion(
                tenantId, announcementId, version);
        if (announcement.getLifecycleState() == AnnouncementLifecycle.ARCHIVED) {
            return response(announcement);
        }
        Map<String, Object> before = snapshot(announcement);
        announcement.setLifecycleState(AnnouncementLifecycle.ARCHIVED);
        Announcement saved = repository.saveAndFlush(announcement);
        auditService.success(
                tenantId,
                actorId,
                "announcement.archived",
                "ANNOUNCEMENT",
                announcementId.toString(),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    private Announcement require(Long tenantId, Long announcementId) {
        return repository.findByAnnouncementIdAndTenantId(announcementId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private NormalizedDefinition normalize(AnnouncementDtos.AnnouncementDefinition definition) {
        String title = definition.title().trim();
        String message = definition.message().trim();
        String audienceValue = trimToNull(definition.audienceValue());
        if (definition.audienceType() == AnnouncementAudienceType.ALL) {
            audienceValue = null;
        } else {
            audienceValue = audienceValue == null ? null : audienceValue.toUpperCase(Locale.ROOT);
            if (audienceValue == null || !ROLE_PATTERN.matcher(audienceValue).matches()) {
                throw invalid("A valid role is required for a role-targeted announcement.");
            }
        }
        if (definition.startsAt() != null
                && definition.endsAt() != null
                && !definition.endsAt().isAfter(definition.startsAt())) {
            throw invalid("The announcement end time must be after its start time.");
        }
        String actionLabel = trimToNull(definition.actionLabel());
        String actionUrl = trimToNull(definition.actionUrl());
        if ((actionLabel == null) != (actionUrl == null)) {
            throw invalid("An announcement action requires both a label and URL.");
        }
        if (actionUrl != null) validateActionUrl(actionUrl);
        AnnouncementContentType contentType = definition.contentType() == null
                ? AnnouncementContentType.ANNOUNCEMENT
                : definition.contentType();
        String categoryKey = trimToNull(definition.categoryKey());
        categoryKey = categoryKey == null ? "COMPANY" : categoryKey.toUpperCase(Locale.ROOT);
        if (!CATEGORY_PATTERN.matcher(categoryKey).matches()) {
            throw invalid("The announcement category key is invalid.");
        }
        String body = trimToNull(definition.body());
        String coverImageUrl = trimToNull(definition.coverImageUrl());
        if (coverImageUrl != null) validateMediaUrl(coverImageUrl);
        String publisherName = trimToNull(definition.publisherName());
        publisherName = publisherName == null ? "DWP Communications" : publisherName;
        boolean acknowledgementRequired = Boolean.TRUE.equals(definition.acknowledgementRequired());
        OffsetDateTime acknowledgementDueAt = acknowledgementRequired
                ? definition.acknowledgementDueAt()
                : null;
        boolean dismissible = definition.dismissible() == null || definition.dismissible();
        if (acknowledgementRequired) dismissible = false;
        short readingMinutes = definition.readingMinutes() == null
                ? estimateReadingMinutes(body == null ? message : body)
                : definition.readingMinutes();
        if (readingMinutes < 1 || readingMinutes > 60) {
            throw invalid("Reading minutes must be between 1 and 60.");
        }
        String sourceLocale = trimToNull(definition.sourceLocale());
        sourceLocale = sourceLocale == null ? "ko" : sourceLocale;
        if (!LOCALE_PATTERN.matcher(sourceLocale).matches()) {
            throw invalid("The announcement source locale is invalid.");
        }
        return new NormalizedDefinition(
                title,
                message,
                definition.severity(),
                definition.audienceType(),
                audienceValue,
                definition.startsAt(),
                definition.endsAt(),
                definition.pinned(),
                actionLabel,
                actionUrl,
                contentType,
                categoryKey,
                body,
                coverImageUrl,
                publisherName,
                Boolean.TRUE.equals(definition.featured()),
                acknowledgementRequired,
                acknowledgementDueAt,
                dismissible,
                readingMinutes,
                sourceLocale);
    }

    private void validateActionUrl(String value) {
        if (value.startsWith("/")
                && !value.startsWith("//")
                && !value.contains("\\")
                && value.chars().noneMatch(Character::isISOControl)) {
            return;
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw invalid("Announcement actions must use a DWP path or HTTPS URL.");
            }
        } catch (URISyntaxException exception) {
            throw invalid("The announcement action URL is invalid.");
        }
    }

    private void validateMediaUrl(String value) {
        if (value.startsWith("/media/")
                && !value.contains("..")
                && !value.contains("\\")
                && value.chars().noneMatch(Character::isISOControl)) {
            return;
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw invalid("Announcement media must use a managed media path or HTTPS URL.");
            }
        } catch (URISyntaxException exception) {
            throw invalid("The announcement media URL is invalid.");
        }
    }

    private short estimateReadingMinutes(String value) {
        int characters = value == null ? 0 : value.codePointCount(0, value.length());
        return (short) Math.max(1, Math.min(60, (characters + 549) / 550));
    }

    private void apply(Announcement announcement, NormalizedDefinition definition) {
        announcement.setTitle(definition.title());
        announcement.setMessage(definition.message());
        announcement.setSeverity(definition.severity());
        announcement.setAudienceType(definition.audienceType());
        announcement.setAudienceValue(definition.audienceValue());
        announcement.setStartsAt(definition.startsAt());
        announcement.setEndsAt(definition.endsAt());
        announcement.setPinned(definition.pinned());
        announcement.setActionLabel(definition.actionLabel());
        announcement.setActionUrl(definition.actionUrl());
        announcement.setContentType(definition.contentType());
        announcement.setCategoryKey(definition.categoryKey());
        announcement.setBody(definition.body());
        announcement.setCoverImageUrl(definition.coverImageUrl());
        announcement.setPublisherName(definition.publisherName());
        announcement.setFeatured(definition.featured());
        announcement.setAcknowledgementRequired(definition.acknowledgementRequired());
        announcement.setAcknowledgementDueAt(definition.acknowledgementDueAt());
        announcement.setDismissible(definition.dismissible());
        announcement.setReadingMinutes(definition.readingMinutes());
        announcement.setSourceLocale(definition.sourceLocale());
    }

    private AnnouncementDtos.AnnouncementResponse response(Announcement announcement) {
        return response(announcement, EngagementMetrics.EMPTY);
    }

    private AnnouncementDtos.AnnouncementResponse response(
            Announcement announcement,
            EngagementMetrics metrics) {
        return new AnnouncementDtos.AnnouncementResponse(
                announcement.getAnnouncementId(),
                announcement.getTitle(),
                announcement.getMessage(),
                announcement.getSeverity(),
                announcement.getLifecycleState(),
                announcement.getAudienceType(),
                announcement.getAudienceValue(),
                announcement.getStartsAt(),
                announcement.getEndsAt(),
                announcement.getPinned(),
                announcement.getActionLabel(),
                announcement.getActionUrl(),
                announcement.getContentType(),
                announcement.getCategoryKey(),
                announcement.getBody(),
                announcement.getCoverImageUrl(),
                announcement.getPublisherName(),
                announcement.getFeatured(),
                announcement.getAcknowledgementRequired(),
                announcement.getAcknowledgementDueAt(),
                announcement.getDismissible(),
                announcement.getReadingMinutes(),
                announcement.getSourceLocale(),
                announcement.getPublishedAt(),
                announcement.getPublishedBy(),
                metrics.uniqueViewers(),
                metrics.views(),
                metrics.actionClicks(),
                metrics.acknowledgements(),
                announcement.getVersion() == null ? 0L : announcement.getVersion(),
                announcement.getUpdatedAt(),
                announcement.getUpdatedBy());
    }

    private Map<Long, EngagementMetrics> engagementMetrics(Long tenantId) {
        List<EngagementMetrics> values = jdbc.query("""
                SELECT announcement_id,
                       COUNT(*) FILTER (WHERE first_seen_at IS NOT NULL) AS unique_viewers,
                       COALESCE(SUM(seen_count), 0) AS views,
                       COALESCE(SUM(action_count), 0) AS action_clicks,
                       COUNT(*) FILTER (WHERE acknowledged_at IS NOT NULL) AS acknowledgements
                  FROM sys_announcement_engagements
                 WHERE tenant_id = ?
                 GROUP BY announcement_id
                """, (resultSet, rowNumber) -> new EngagementMetrics(
                        resultSet.getLong("announcement_id"),
                        resultSet.getLong("unique_viewers"),
                        resultSet.getLong("views"),
                        resultSet.getLong("action_clicks"),
                        resultSet.getLong("acknowledgements")), tenantId);
        Map<Long, EngagementMetrics> result = new LinkedHashMap<>();
        values.forEach(value -> result.put(value.announcementId(), value));
        return result;
    }

    private void requireVisible(Announcement announcement, List<String> roles) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        boolean scheduled = (announcement.getStartsAt() == null
                || !announcement.getStartsAt().isAfter(now))
                && (announcement.getEndsAt() == null || announcement.getEndsAt().isAfter(now));
        boolean audience = announcement.getAudienceType() == AnnouncementAudienceType.ALL
                || roles.contains(announcement.getAudienceValue());
        if (announcement.getLifecycleState() != AnnouncementLifecycle.PUBLISHED
                || !scheduled
                || !audience) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    private Map<String, Object> snapshot(Announcement announcement) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("title", announcement.getTitle());
        value.put("severity", announcement.getSeverity());
        value.put("lifecycleState", announcement.getLifecycleState());
        value.put("audienceType", announcement.getAudienceType());
        value.put("audienceValue", announcement.getAudienceValue());
        value.put("startsAt", announcement.getStartsAt());
        value.put("endsAt", announcement.getEndsAt());
        value.put("pinned", announcement.getPinned());
        value.put("actionUrl", announcement.getActionUrl());
        value.put("contentType", announcement.getContentType());
        value.put("categoryKey", announcement.getCategoryKey());
        value.put("featured", announcement.getFeatured());
        value.put("acknowledgementRequired", announcement.getAcknowledgementRequired());
        value.put("acknowledgementDueAt", announcement.getAcknowledgementDueAt());
        value.put("dismissible", announcement.getDismissible());
        value.put("version", announcement.getVersion() == null ? 0L : announcement.getVersion());
        return value;
    }

    private List<String> parseRoles(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) return List.of();
        return Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(value -> ROLE_PATTERN.matcher(value).matches())
                .distinct()
                .toList();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private record NormalizedDefinition(
            String title,
            String message,
            AnnouncementSeverity severity,
            AnnouncementAudienceType audienceType,
            String audienceValue,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            Boolean pinned,
            String actionLabel,
            String actionUrl,
            AnnouncementContentType contentType,
            String categoryKey,
            String body,
            String coverImageUrl,
            String publisherName,
            Boolean featured,
            Boolean acknowledgementRequired,
            OffsetDateTime acknowledgementDueAt,
            Boolean dismissible,
            Short readingMinutes,
            String sourceLocale) {
    }

    private record EngagementMetrics(
            Long announcementId,
            long uniqueViewers,
            long views,
            long actionClicks,
            long acknowledgements) {
        private static final EngagementMetrics EMPTY =
                new EngagementMetrics(null, 0L, 0L, 0L, 0L);
    }
}
