package com.dwp.services.platform.communication;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.announcement.Announcement;
import com.dwp.services.platform.announcement.AnnouncementContentType;
import com.dwp.services.platform.announcement.AnnouncementRepository;
import com.dwp.services.platform.security.PlatformRoutePredicateEvaluator;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class CommunicationService {

    private static final Pattern ROLE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_:-]{0,79}");
    private static final int MAX_VISIBLE_ITEMS = 100;

    private final AnnouncementRepository repository;
    private final JdbcTemplate jdbc;
    private final PlatformRoutePredicateEvaluator predicateEvaluator;

    public CommunicationService(
            AnnouncementRepository repository,
            JdbcTemplate jdbc,
            PlatformRoutePredicateEvaluator predicateEvaluator) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.predicateEvaluator = predicateEvaluator;
    }

    @Transactional(readOnly = true)
    public CommunicationDtos.FeedResponse feed(
            Long tenantId,
            Long userId,
            String rolesHeader,
            String acceptLanguage,
            String scope,
            String query,
            String type,
            int size) {
        List<Announcement> visible = active(tenantId, rolesHeader);
        Map<Long, ReaderStateRow> states = readerStates(tenantId, userId);
        Map<Long, LocalizationRow> localizations = localizations(
                tenantId, visible, preferredLocale(acceptLanguage));
        Map<Long, CommunicationDtos.ReactionSummary> reactions = reactionSummaries(
                tenantId, userId, visible);
        List<CommunicationDtos.CommunicationItem> all = visible.stream()
                .map(item -> response(
                        item,
                        states.getOrDefault(item.getAnnouncementId(), ReaderStateRow.EMPTY),
                        localizations.get(item.getAnnouncementId()),
                        reactions.getOrDefault(
                                item.getAnnouncementId(), emptyReactionSummary())))
                .toList();

        List<CommunicationDtos.CommunicationItem> activeForSummary = all.stream()
                .filter(item -> !item.readerState().dismissed())
                .toList();
        CommunicationDtos.FeedSummary summary = new CommunicationDtos.FeedSummary(
                activeForSummary.size(),
                activeForSummary.stream().filter(item -> item.readerState().unread()).count(),
                activeForSummary.stream().filter(this::requiresAction).count(),
                all.stream().filter(item -> item.readerState().saved()).count());

        Predicate<CommunicationDtos.CommunicationItem> scopeFilter = scopeFilter(scope);
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        AnnouncementContentType contentType = parseContentType(type);
        List<CommunicationDtos.CommunicationItem> filtered = all.stream()
                .filter(scopeFilter)
                .filter(item -> contentType == null || item.contentType() == contentType)
                .filter(item -> matchesQuery(item, normalizedQuery))
                .toList();
        CommunicationDtos.CommunicationItem featured = filtered.stream()
                .filter(item -> item.featured() || item.pinned())
                .findFirst()
                .orElse(filtered.isEmpty() ? null : filtered.get(0));
        List<CommunicationDtos.CommunicationItem> items = filtered.stream()
                .filter(item -> featured == null || !item.communicationId().equals(featured.communicationId()))
                .limit(Math.max(1, Math.min(size, 48)))
                .toList();
        return new CommunicationDtos.FeedResponse(
                featured, items, summary, OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public CommunicationDtos.CommunicationItem detail(
            Long tenantId,
            Long userId,
            String rolesHeader,
            String acceptLanguage,
            Long communicationId) {
        Announcement announcement = requireVisibleDetail(
                tenantId, rolesHeader, communicationId);
        ReaderStateRow state = readerStates(tenantId, userId)
                .getOrDefault(communicationId, ReaderStateRow.EMPTY);
        LocalizationRow localization = localizations(
                tenantId, List.of(announcement), preferredLocale(acceptLanguage))
                .get(communicationId);
        CommunicationDtos.ReactionSummary reactions = reactionSummaries(
                tenantId, userId, List.of(announcement))
                .getOrDefault(communicationId, emptyReactionSummary());
        return response(announcement, state, localization, reactions);
    }

    @Transactional
    public void recordInteraction(
            Long tenantId,
            Long userId,
            String rolesHeader,
            Long communicationId,
            String eventType) {
        Announcement announcement = requireReaderAction(
                tenantId, rolesHeader, communicationId);
        String normalized = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
        if ("ACTION".equals(normalized) && announcement.getActionUrl() == null) {
            throw invalid("This communication has no action to record.");
        }
        if ("IMPRESSION".equals(normalized)) {
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
                    """, tenantId, communicationId, userId);
            return;
        }
        if ("OPEN".equals(normalized)) {
            jdbc.update("""
                    INSERT INTO sys_announcement_engagements (
                        tenant_id, announcement_id, user_id,
                        first_opened_at, last_opened_at, open_count)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
                    ON CONFLICT (tenant_id, announcement_id, user_id)
                    DO UPDATE SET
                        first_opened_at = COALESCE(
                            sys_announcement_engagements.first_opened_at,
                            EXCLUDED.first_opened_at),
                        last_opened_at = EXCLUDED.last_opened_at,
                        open_count = sys_announcement_engagements.open_count + 1,
                        dismissed_at = NULL
                    """, tenantId, communicationId, userId);
            return;
        }
        if ("ACTION".equals(normalized)) {
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
                    """, tenantId, communicationId, userId);
            return;
        }
        throw invalid("The communication event type is invalid.");
    }

    @Transactional
    public CommunicationDtos.ReaderPreferenceResponse updatePreference(
            Long tenantId,
            Long userId,
            String rolesHeader,
            Long communicationId,
            CommunicationDtos.ReaderPreferenceRequest request) {
        Announcement announcement = requireReaderAction(
                tenantId, rolesHeader, communicationId);
        if (Boolean.TRUE.equals(request.dismissed()) && !Boolean.TRUE.equals(announcement.getDismissible())) {
            throw invalid("This communication cannot be dismissed.");
        }
        ensureStateRow(tenantId, userId, communicationId);
        if (request.saved() != null) {
            jdbc.update("""
                    UPDATE sys_announcement_engagements
                       SET saved_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END
                     WHERE tenant_id = ? AND announcement_id = ? AND user_id = ?
                    """, request.saved(), tenantId, communicationId, userId);
        }
        if (request.dismissed() != null) {
            jdbc.update("""
                    UPDATE sys_announcement_engagements
                       SET dismissed_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END
                     WHERE tenant_id = ? AND announcement_id = ? AND user_id = ?
                    """, request.dismissed(), tenantId, communicationId, userId);
        }
        ReaderStateRow state = readerStates(tenantId, userId).get(communicationId);
        return new CommunicationDtos.ReaderPreferenceResponse(communicationId, readerState(state));
    }

    @Transactional
    public CommunicationDtos.ReaderPreferenceResponse acknowledge(
            Long tenantId,
            Long userId,
            String rolesHeader,
            Long communicationId) {
        Announcement announcement = requireReaderAction(
                tenantId, rolesHeader, communicationId);
        if (!Boolean.TRUE.equals(announcement.getAcknowledgementRequired())) {
            throw invalid("This communication does not require acknowledgement.");
        }
        ensureStateRow(tenantId, userId, communicationId);
        jdbc.update("""
                UPDATE sys_announcement_engagements
                   SET acknowledged_at = COALESCE(acknowledged_at, CURRENT_TIMESTAMP),
                       first_opened_at = COALESCE(first_opened_at, CURRENT_TIMESTAMP),
                       last_opened_at = CURRENT_TIMESTAMP,
                       dismissed_at = NULL
                 WHERE tenant_id = ? AND announcement_id = ? AND user_id = ?
                """, tenantId, communicationId, userId);
        ReaderStateRow state = readerStates(tenantId, userId).get(communicationId);
        return new CommunicationDtos.ReaderPreferenceResponse(communicationId, readerState(state));
    }

    @Transactional
    public CommunicationDtos.ReactionSummary updateReaction(
            Long tenantId,
            Long userId,
            String rolesHeader,
            Long communicationId,
            CommunicationDtos.ReactionRequest request) {
        Announcement announcement = requireReaderAction(
                tenantId, rolesHeader, communicationId);
        if (request.reaction() == null) {
            jdbc.update("""
                    DELETE FROM sys_announcement_reactions
                     WHERE tenant_id = ? AND announcement_id = ? AND user_id = ?
                    """, tenantId, communicationId, userId);
        } else {
            jdbc.update("""
                    INSERT INTO sys_announcement_reactions (
                        tenant_id, announcement_id, user_id, reaction_code)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (tenant_id, announcement_id, user_id)
                    DO UPDATE SET reaction_code = EXCLUDED.reaction_code,
                                  updated_at = CURRENT_TIMESTAMP
                    """, tenantId, communicationId, userId, request.reaction().name());
        }
        return reactionSummaries(tenantId, userId, List.of(announcement))
                .getOrDefault(communicationId, emptyReactionSummary());
    }

    private List<Announcement> active(Long tenantId, String rolesHeader) {
        List<String> roles = parseRoles(rolesHeader);
        return repository.findActive(
                tenantId,
                OffsetDateTime.now(ZoneOffset.UTC),
                roles.isEmpty() ? List.of("__NO_ROLE__") : roles,
                PageRequest.of(0, MAX_VISIBLE_ITEMS));
    }

    private Announcement requireVisibleDetail(
            Long tenantId,
            String rolesHeader,
            Long communicationId) {
        return predicateEvaluator.requireVisibleCommunication(
                tenantId, rolesHeader, communicationId);
    }

    private Announcement requireReaderAction(
            Long tenantId,
            String rolesHeader,
            Long communicationId) {
        return predicateEvaluator.requireCommunicationReaderAction(
                tenantId, rolesHeader, communicationId);
    }

    private Map<Long, ReaderStateRow> readerStates(Long tenantId, Long userId) {
        List<ReaderStateRow> rows = jdbc.query("""
                SELECT announcement_id, first_opened_at, saved_at,
                       acknowledged_at, dismissed_at
                  FROM sys_announcement_engagements
                 WHERE tenant_id = ? AND user_id = ?
                """, (result, ignored) -> new ReaderStateRow(
                result.getLong("announcement_id"),
                result.getObject("first_opened_at", OffsetDateTime.class),
                result.getObject("saved_at", OffsetDateTime.class),
                result.getObject("acknowledged_at", OffsetDateTime.class),
                result.getObject("dismissed_at", OffsetDateTime.class)), tenantId, userId);
        Map<Long, ReaderStateRow> states = new LinkedHashMap<>();
        rows.forEach(row -> states.put(row.announcementId(), row));
        return states;
    }

    private Map<Long, LocalizationRow> localizations(
            Long tenantId,
            List<Announcement> announcements,
            String locale) {
        if (announcements.isEmpty()) return Map.of();
        String language = locale.contains("-") ? locale.substring(0, locale.indexOf('-')) : locale;
        List<LocalizationRow> rows = jdbc.query("""
                SELECT announcement_id, locale, title, summary, body, action_label
                  FROM adm_announcement_localizations
                 WHERE tenant_id = ? AND locale IN (?, ?)
                 ORDER BY CASE WHEN locale = ? THEN 0 ELSE 1 END
                """, (result, ignored) -> new LocalizationRow(
                result.getLong("announcement_id"),
                result.getString("locale"),
                result.getString("title"),
                result.getString("summary"),
                result.getString("body"),
                result.getString("action_label")), tenantId, locale, language, locale);
        Map<Long, LocalizationRow> result = new LinkedHashMap<>();
        rows.forEach(row -> result.putIfAbsent(row.announcementId(), row));
        return result;
    }

    private CommunicationDtos.CommunicationItem response(
            Announcement announcement,
            ReaderStateRow state,
            LocalizationRow localization,
            CommunicationDtos.ReactionSummary reactions) {
        return new CommunicationDtos.CommunicationItem(
                announcement.getAnnouncementId(),
                localization == null ? announcement.getTitle() : localization.title(),
                localization == null ? announcement.getMessage() : localization.summary(),
                localization == null ? announcement.getBody() : localization.body(),
                announcement.getSeverity(),
                announcement.getContentType(),
                announcement.getCategoryKey(),
                announcement.getPublisherName(),
                announcement.getCoverImageUrl(),
                Boolean.TRUE.equals(announcement.getFeatured()),
                Boolean.TRUE.equals(announcement.getPinned()),
                Boolean.TRUE.equals(announcement.getAcknowledgementRequired()),
                announcement.getAcknowledgementDueAt(),
                Boolean.TRUE.equals(announcement.getDismissible()),
                announcement.getReadingMinutes() == null ? 2 : announcement.getReadingMinutes(),
                announcement.getSourceLocale(),
                localization != null && localization.actionLabel() != null
                        ? localization.actionLabel()
                        : announcement.getActionLabel(),
                announcement.getActionUrl(),
                announcement.getPublishedAt(),
                announcement.getEndsAt(),
                readerState(state),
                reactions);
    }

    private Map<Long, CommunicationDtos.ReactionSummary> reactionSummaries(
            Long tenantId,
            Long userId,
            List<Announcement> announcements) {
        if (announcements.isEmpty()) return Map.of();
        List<Long> announcementIds = announcements.stream()
                .map(Announcement::getAnnouncementId)
                .toList();
        String placeholders = String.join(",", announcementIds.stream().map(ignored -> "?").toList());
        List<Object> parameters = new ArrayList<>();
        parameters.add(userId);
        parameters.add(tenantId);
        parameters.addAll(announcementIds);
        List<ReactionCountRow> rows = jdbc.query("""
                SELECT announcement_id, reaction_code, COUNT(*) AS reaction_count,
                       BOOL_OR(user_id = ?) AS viewer_selected
                  FROM sys_announcement_reactions
                 WHERE tenant_id = ?
                   AND announcement_id IN (%s)
                 GROUP BY announcement_id, reaction_code
                """.formatted(placeholders), (result, ignored) -> new ReactionCountRow(
                result.getLong("announcement_id"),
                CommunicationReaction.valueOf(result.getString("reaction_code")),
                result.getLong("reaction_count"),
                result.getBoolean("viewer_selected")), parameters.toArray());

        Map<Long, EnumMap<CommunicationReaction, Long>> counts = new LinkedHashMap<>();
        Map<Long, CommunicationReaction> viewerReactions = new LinkedHashMap<>();
        rows.forEach(row -> {
            counts.computeIfAbsent(
                            row.announcementId(), ignored -> new EnumMap<>(CommunicationReaction.class))
                    .put(row.reaction(), row.count());
            if (row.viewerSelected()) viewerReactions.put(row.announcementId(), row.reaction());
        });
        Map<Long, CommunicationDtos.ReactionSummary> summaries = new LinkedHashMap<>();
        announcementIds.forEach(id -> {
            Map<CommunicationReaction, Long> values = counts.getOrDefault(
                    id, new EnumMap<>(CommunicationReaction.class));
            summaries.put(id, new CommunicationDtos.ReactionSummary(
                    Map.copyOf(values),
                    viewerReactions.get(id),
                    values.values().stream().mapToLong(Long::longValue).sum()));
        });
        return summaries;
    }

    private CommunicationDtos.ReactionSummary emptyReactionSummary() {
        return new CommunicationDtos.ReactionSummary(Map.of(), null, 0);
    }

    private CommunicationDtos.ReaderState readerState(ReaderStateRow state) {
        ReaderStateRow value = state == null ? ReaderStateRow.EMPTY : state;
        return new CommunicationDtos.ReaderState(
                value.openedAt() == null,
                value.savedAt() != null,
                value.acknowledgedAt() != null,
                value.dismissedAt() != null,
                value.openedAt(),
                value.savedAt(),
                value.acknowledgedAt());
    }

    private boolean requiresAction(CommunicationDtos.CommunicationItem item) {
        return item.acknowledgementRequired() && !item.readerState().acknowledged();
    }

    private Predicate<CommunicationDtos.CommunicationItem> scopeFilter(String scope) {
        String normalized = scope == null ? "for-you" : scope.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "all" -> item -> true;
            case "required" -> this::requiresAction;
            case "saved" -> item -> item.readerState().saved();
            case "for-you" -> item -> !item.readerState().dismissed();
            default -> throw invalid("The communication feed scope is invalid.");
        };
    }

    private AnnouncementContentType parseContentType(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) return null;
        try {
            return AnnouncementContentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("The communication content type is invalid.");
        }
    }

    private boolean matchesQuery(
            CommunicationDtos.CommunicationItem item,
            String normalizedQuery) {
        if (normalizedQuery.isBlank()) return true;
        return Stream.of(
                        item.title(), item.summary(), item.body(),
                        item.publisherName(), item.categoryKey())
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(normalizedQuery));
    }

    private void ensureStateRow(Long tenantId, Long userId, Long communicationId) {
        jdbc.update("""
                INSERT INTO sys_announcement_engagements (
                    tenant_id, announcement_id, user_id)
                VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, announcement_id, user_id) DO NOTHING
                """, tenantId, communicationId, userId);
    }

    private String preferredLocale(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) return "ko";
        String value = acceptLanguage.split(",", 2)[0].split(";", 2)[0].trim();
        if (!value.matches("[A-Za-z]{2}(-[A-Za-z]{2})?")) return "ko";
        String[] parts = value.split("-");
        return parts.length == 1
                ? parts[0].toLowerCase(Locale.ROOT)
                : parts[0].toLowerCase(Locale.ROOT) + "-" + parts[1].toUpperCase(Locale.ROOT);
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

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private record ReaderStateRow(
            Long announcementId,
            OffsetDateTime openedAt,
            OffsetDateTime savedAt,
            OffsetDateTime acknowledgedAt,
            OffsetDateTime dismissedAt) {
        private static final ReaderStateRow EMPTY = new ReaderStateRow(null, null, null, null, null);
    }

    private record LocalizationRow(
            Long announcementId,
            String locale,
            String title,
            String summary,
            String body,
            String actionLabel) {
    }

    private record ReactionCountRow(
            Long announcementId,
            CommunicationReaction reaction,
            long count,
            boolean viewerSelected) {
    }
}
