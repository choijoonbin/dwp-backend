package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.NotificationVersionCodec;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.cursor.NotificationCursorCodec.InboxCursor;
import com.dwp.services.notification.domain.NotificationModels.Detail;
import com.dwp.services.notification.domain.NotificationModels.InboxItem;
import com.dwp.services.notification.domain.NotificationModels.InboxView;
import com.dwp.services.notification.domain.NotificationModels.NotificationAction;
import com.dwp.services.notification.domain.NotificationModels.NotificationReason;
import com.dwp.services.notification.domain.NotificationModels.NotificationSource;
import com.dwp.services.notification.domain.NotificationModels.TimelineEntry;
import com.dwp.services.notification.domain.NotificationModels.TargetResolution;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Repository
public class NotificationQueryRepository {

    static final String INBOX_SELECT = """
            SELECT user_notification.notification_id,
                   notification.thread_key,
                   user_notification.first_activity_at,
                   user_notification.occurrence_count,
                   user_notification.actor_ref,
                   user_notification.action_payload::text AS action_payload,
                   user_notification.safe_body,
                   user_notification.target_ref,
                   user_notification.target_state,
                   user_notification.target_state_reason,
                   notification.expires_at,
                   type.type_key,
                   type.owner_app_key,
                   type_version.data_classification,
                   user_notification.safe_title,
                   user_notification.safe_preview,
                   user_notification.reason_code,
                   user_notification.effective_priority,
                   user_notification.action_required,
                   user_notification.read_at,
                   user_notification.saved_at,
                   user_notification.completed_at,
                   user_notification.snoozed_until,
                   user_notification.due_at,
                   user_notification.last_activity_at,
                   user_notification.change_version,
                   user_notification.version
              FROM ntf_user_notifications user_notification
              JOIN ntf_notifications notification
                ON notification.tenant_id = user_notification.tenant_id
               AND notification.notification_id = user_notification.notification_id
              JOIN ntf_notification_type_versions type_version
                ON type_version.type_version_id = notification.type_version_id
              JOIN ntf_notification_types type
                ON type.type_id = type_version.type_id
            """;

    private static final List<String> CHANNELS =
            List.of("IN_APP", "EMAIL", "WEB_PUSH", "MOBILE_PUSH", "TEAMS", "SLACK");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public NotificationQueryRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public CounterSnapshot counter(NotificationRequestContext.Actor actor) {
        List<CounterSnapshot> rows = jdbc.query("""
                SELECT unread_count, actionable_unread_count, urgent_count,
                       counter_version, min_available_change_version, updated_at
                  FROM ntf_user_counters
                 WHERE tenant_id = :tenantId AND user_id = :userId
                """, actorParams(actor), (resultSet, rowNumber) -> new CounterSnapshot(
                resultSet.getLong("unread_count"),
                resultSet.getLong("actionable_unread_count"),
                resultSet.getLong("urgent_count"),
                resultSet.getLong("counter_version"),
                resultSet.getLong("min_available_change_version"),
                instant(resultSet, "updated_at")));
        return rows.isEmpty()
                ? new CounterSnapshot(0, 0, 0, 0, 0, Instant.EPOCH)
                : rows.get(0);
    }

    public ViewCounts viewCounts(NotificationRequestContext.Actor actor) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (
                           WHERE inbox_state = 'ACTIVE'
                             AND action_required
                             AND (snoozed_until IS NULL OR snoozed_until <= CURRENT_TIMESTAMP)
                       ) AS priority_count,
                       COUNT(*) FILTER (
                           WHERE inbox_state = 'ACTIVE'
                             AND (snoozed_until IS NULL OR snoozed_until <= CURRENT_TIMESTAMP)
                       ) AS all_count,
                       COUNT(*) FILTER (
                           WHERE inbox_state = 'ACTIVE'
                             AND reason_code IN ('MENTION', 'MENTIONED')
                             AND (snoozed_until IS NULL OR snoozed_until <= CURRENT_TIMESTAMP)
                       ) AS mention_count,
                       COUNT(*) FILTER (WHERE saved_at IS NOT NULL) AS saved_count,
                       COUNT(*) FILTER (
                           WHERE inbox_state = 'ACTIVE' AND snoozed_until > CURRENT_TIMESTAMP
                       ) AS snoozed_count,
                       COUNT(*) FILTER (WHERE inbox_state = 'DONE') AS done_count
                  FROM ntf_user_notifications
                 WHERE tenant_id = :tenantId AND user_id = :userId
                """, actorParams(actor), (resultSet, rowNumber) -> new ViewCounts(
                resultSet.getLong("priority_count"),
                resultSet.getLong("all_count"),
                resultSet.getLong("mention_count"),
                resultSet.getLong("saved_count"),
                resultSet.getLong("snoozed_count"),
                resultSet.getLong("done_count")));
    }

    public List<InboxRow> inbox(
            NotificationRequestContext.Actor actor,
            InboxView view,
            InboxFilters filters,
            int fetchLimit,
            InboxCursor cursor) {
        MapSqlParameterSource params = actorParams(actor).addValue("limit", fetchLimit);
        StringBuilder predicates = new StringBuilder(viewPredicate(view));
        if (cursor != null) {
            params.addValue("cursorTime", Timestamp.from(cursor.lastActivityAt()))
                    .addValue("cursorId", cursor.notificationId());
            predicates.append("""
                    AND (
                        user_notification.last_activity_at < :cursorTime
                        OR (
                            user_notification.last_activity_at = :cursorTime
                            AND user_notification.notification_id < :cursorId
                        )
                    )
                    """);
        }
        appendFilters(predicates, params, filters);
        String sql = INBOX_SELECT + """
                 WHERE user_notification.tenant_id = :tenantId
                   AND user_notification.user_id = :userId
                """ + predicates + """
                 ORDER BY user_notification.last_activity_at DESC,
                          user_notification.notification_id DESC
                 LIMIT :limit
                """;
        return jdbc.query(sql, params, this::mapInboxRow);
    }

    public Detail detail(NotificationRequestContext.Actor actor, UUID notificationId) {
        List<Detail> details = jdbc.query(INBOX_SELECT + """
                 WHERE user_notification.tenant_id = :tenantId
                   AND user_notification.user_id = :userId
                   AND user_notification.notification_id = :notificationId
                """, actorParams(actor).addValue("notificationId", notificationId),
                (resultSet, rowNumber) -> mapDetail(resultSet));
        if (details.isEmpty()) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
        }
        return details.get(0);
    }

    public TargetResolution resolveTarget(
            NotificationRequestContext.Actor actor,
            UUID notificationId) {
        List<TargetRow> rows = jdbc.query(INBOX_SELECT + """
                 WHERE user_notification.tenant_id = :tenantId
                   AND user_notification.user_id = :userId
                   AND user_notification.notification_id = :notificationId
                """, actorParams(actor).addValue("notificationId", notificationId),
                (resultSet, rowNumber) -> new TargetRow(
                        resultSet.getString("target_state"),
                        resultSet.getString("target_state_reason"),
                        instant(resultSet, "expires_at"),
                        actions(resultSet.getString("action_payload"))));
        if (rows.isEmpty()) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
        }
        TargetRow row = rows.get(0);
        if (row.expiresAt() != null && !row.expiresAt().isAfter(Instant.now())) {
            throw targetUnavailable("The source object has expired.");
        }
        if (!"AVAILABLE".equals(row.state())) {
            throw targetUnavailable(row.reason());
        }
        NotificationAction action = row.actions().stream()
                .filter(NotificationAction::enabled)
                .filter(candidate -> safeTargetHref(candidate.href()))
                .filter(NotificationAction::primary)
                .findFirst()
                .orElseGet(() -> row.actions().stream()
                        .filter(NotificationAction::enabled)
                        .filter(candidate -> safeTargetHref(candidate.href()))
                        .findFirst()
                        .orElseThrow(() -> targetUnavailable(
                                "The source application did not provide a safe target.")));
        return new TargetResolution(notificationId, "AVAILABLE", action);
    }

    public long currentVersion(NotificationRequestContext.Actor actor, UUID notificationId) {
        List<Long> versions = jdbc.query("""
                SELECT version
                  FROM ntf_user_notifications
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND notification_id = :notificationId
                """, actorParams(actor).addValue("notificationId", notificationId),
                (resultSet, rowNumber) -> resultSet.getLong("version"));
        if (versions.isEmpty()) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_NOT_FOUND);
        }
        return versions.get(0);
    }

    public List<ChangedProjection> changedAfter(
            NotificationRequestContext.Actor actor,
            long afterVersion,
            int fetchLimit) {
        return jdbc.query("""
                SELECT notification_id, change_version
                  FROM ntf_user_notifications
                 WHERE tenant_id = :tenantId
                   AND user_id = :userId
                   AND change_version > :afterVersion
                 ORDER BY change_version ASC, notification_id ASC
                 LIMIT :limit
                """, actorParams(actor)
                .addValue("afterVersion", afterVersion)
                .addValue("limit", fetchLimit), (resultSet, rowNumber) -> new ChangedProjection(
                resultSet.getObject("notification_id", UUID.class),
                resultSet.getLong("change_version")));
    }

    public List<CatalogType> catalogTypes(NotificationRequestContext.Actor actor) {
        return jdbc.query("""
                SELECT type.owner_app_key,
                       type.type_key,
                       COALESCE(
                           NULLIF(type_version.contract_payload ->> 'displayName', ''),
                           type.type_key
                       ) AS display_name,
                       NULLIF(type_version.contract_payload ->> 'description', '') AS description
                  FROM ntf_notification_types type
                  JOIN ntf_notification_type_versions type_version
                    ON type_version.type_id = type.type_id
                 WHERE type.lifecycle_state = 'ACTIVE'
                   AND type_version.lifecycle_state = 'ACTIVE'
                   AND (type.tenant_id IS NULL OR type.tenant_id = :tenantId)
                 ORDER BY type.owner_app_key, type.type_key
                """, actorParams(actor), (resultSet, rowNumber) -> new CatalogType(
                resultSet.getString("owner_app_key"),
                resultSet.getString("type_key"),
                resultSet.getString("display_name"),
                resultSet.getString("description")));
    }

    private Detail mapDetail(ResultSet resultSet) throws SQLException {
        InboxRow row = mapInboxRow(resultSet, 0);
        Instant expiresAt = instant(resultSet, "expires_at");
        boolean expired = expiresAt != null && !expiresAt.isAfter(Instant.now());
        String targetState = expired ? "EXPIRED" : resultSet.getString("target_state");
        String targetStateReason = expired
                ? "The source object has expired."
                : resultSet.getString("target_state_reason");
        String body = resultSet.getString("safe_body");
        String actorLabel = row.item().actorLabel();
        return new Detail(
                row.item(),
                reasonExplanation(row.item().reason()),
                row.item().receivedAt(),
                targetState,
                targetStateReason,
                List.of(new TimelineEntry(
                        "received:" + row.item().notificationId(),
                        "Notification received",
                        body,
                        row.item().receivedAt(),
                        actorLabel)));
    }

    private InboxRow mapInboxRow(ResultSet resultSet, int rowNumber) throws SQLException {
        UUID notificationId = resultSet.getObject("notification_id", UUID.class);
        String appKey = resultSet.getString("owner_app_key");
        String reasonCode = resultSet.getString("reason_code");
        String actorLabel = resultSet.getString("actor_ref");
        InboxItem item = new InboxItem(
                notificationId,
                resultSet.getString("thread_key"),
                resultSet.getLong("occurrence_count"),
                new NotificationSource(appKey, appName(appKey), appKey, appAccent(appKey)),
                resultSet.getString("type_key"),
                resultSet.getString("safe_title"),
                resultSet.getString("safe_preview"),
                actorLabel,
                resultSet.getString("effective_priority"),
                reason(reasonCode),
                instant(resultSet, "first_activity_at"),
                instant(resultSet, "last_activity_at"),
                instant(resultSet, "due_at"),
                instant(resultSet, "read_at"),
                instant(resultSet, "saved_at"),
                instant(resultSet, "completed_at"),
                instant(resultSet, "snoozed_until"),
                resultSet.getBoolean("action_required"),
                sensitive(resultSet.getString("data_classification")),
                actions(resultSet.getString("action_payload")),
                NotificationVersionCodec.external(resultSet.getLong("version")));
        return new InboxRow(item, resultSet.getLong("change_version"));
    }

    private void appendFilters(
            StringBuilder predicates,
            MapSqlParameterSource params,
            InboxFilters filters) {
        if (filters == null) return;
        if (filters.query() != null) {
            predicates.append(" AND LOWER(user_notification.search_text) LIKE :query ESCAPE '\\'\n");
            params.addValue("query", "%" + escapeLike(filters.query().toLowerCase(Locale.ROOT)) + "%");
        }
        if (filters.appKey() != null) {
            predicates.append(" AND type.owner_app_key = :appKey\n");
            params.addValue("appKey", filters.appKey());
        }
        if (filters.priority() != null) {
            predicates.append(" AND user_notification.effective_priority = :priority\n");
            params.addValue("priority", filters.priority());
        }
        if ("UNREAD".equals(filters.readState())) {
            predicates.append(" AND user_notification.read_at IS NULL\n");
        } else if ("READ".equals(filters.readState())) {
            predicates.append(" AND user_notification.read_at IS NOT NULL\n");
        }
        if (filters.reason() != null) {
            if ("DIRECT".equals(filters.reason())) {
                predicates.append(" AND user_notification.reason_code IN ('DIRECT', 'DIRECT_RECIPIENT')\n");
            } else {
                predicates.append(" AND user_notification.reason_code = :reason\n");
                params.addValue("reason", filters.reason());
            }
        }
        if (filters.from() != null) {
            predicates.append(" AND user_notification.last_activity_at >= :fromTime\n");
            params.addValue("fromTime", Timestamp.from(filters.from()));
        }
        if (filters.to() != null) {
            predicates.append(" AND user_notification.last_activity_at <= :toTime\n");
            params.addValue("toTime", Timestamp.from(filters.to()));
        }
    }

    private String viewPredicate(InboxView view) {
        return switch (view) {
            case PRIORITY -> """
                    AND user_notification.inbox_state = 'ACTIVE'
                    AND user_notification.action_required
                    AND (user_notification.snoozed_until IS NULL
                         OR user_notification.snoozed_until <= CURRENT_TIMESTAMP)
                    """;
            case ALL -> """
                    AND user_notification.inbox_state = 'ACTIVE'
                    AND (user_notification.snoozed_until IS NULL
                         OR user_notification.snoozed_until <= CURRENT_TIMESTAMP)
                    """;
            case MENTIONS -> """
                    AND user_notification.inbox_state = 'ACTIVE'
                    AND user_notification.reason_code IN ('MENTION', 'MENTIONED')
                    AND (user_notification.snoozed_until IS NULL
                         OR user_notification.snoozed_until <= CURRENT_TIMESTAMP)
                    """;
            case SAVED -> " AND user_notification.saved_at IS NOT NULL\n";
            case SNOOZED -> """
                    AND user_notification.inbox_state = 'ACTIVE'
                    AND user_notification.snoozed_until > CURRENT_TIMESTAMP
                    """;
            case DONE -> " AND user_notification.inbox_state = 'DONE'\n";
        };
    }

    private NotificationReason reason(String reasonCode) {
        String kind = switch (reasonCode == null ? "" : reasonCode.toUpperCase(Locale.ROOT)) {
            case "MENTION", "MENTIONED" -> "MENTION";
            case "ROLE" -> "ROLE";
            case "ORGANIZATION", "ORG" -> "ORGANIZATION";
            case "SUBSCRIPTION" -> "SUBSCRIPTION";
            case "MANDATORY_POLICY", "MANDATORY" -> "MANDATORY_POLICY";
            default -> "DIRECT";
        };
        String label = switch (kind) {
            case "MENTION" -> "You were mentioned";
            case "ROLE" -> "Relevant to your role";
            case "ORGANIZATION" -> "Relevant to your organization";
            case "SUBSCRIPTION" -> "From a subscription";
            case "MANDATORY_POLICY" -> "Required by policy";
            default -> "Sent directly to you";
        };
        return new NotificationReason(kind, label, reasonCode);
    }

    private String reasonExplanation(NotificationReason reason) {
        return switch (reason.kind()) {
            case "MENTION" -> "A participant mentioned you in the source application.";
            case "ROLE" -> "The source application selected recipients using an authorized role.";
            case "ORGANIZATION" -> "The source application selected recipients using organization membership.";
            case "SUBSCRIPTION" -> "This notification matches one of your subscriptions.";
            case "MANDATORY_POLICY" -> "An organization policy requires this notification.";
            default -> "The source application addressed this notification directly to your account.";
        };
    }

    private List<NotificationAction> actions(String payload) {
        if (payload == null || payload.isBlank()) return List.of();
        try {
            JsonNode root = objectMapper.readTree(payload);
            List<NotificationAction> result = new ArrayList<>();
            JsonNode candidates = root.path("actions");
            if (candidates.isArray()) {
                for (JsonNode node : candidates) addAction(result, node, result.isEmpty());
            } else {
                addAction(result, root, true);
            }
            return List.copyOf(result);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private void addAction(List<NotificationAction> result, JsonNode node, boolean primary) {
        String label = text(node, "label");
        if (label == null) return;
        String href = text(node, "href");
        if (href == null) href = text(node, "route");
        String actionKey = text(node, "actionKey");
        if (actionKey == null) actionKey = "OPEN";
        result.add(new NotificationAction(actionKey, label, href, true, null, primary));
    }

    static boolean safeTargetHref(String href) {
        return href != null
                && href.startsWith("/")
                && !href.startsWith("//")
                && href.chars().noneMatch(character -> character < 32);
    }

    private NotificationException targetUnavailable(String reason) {
        String message = reason == null || reason.isBlank()
                ? NotificationErrorCode.NOTIFICATION_TARGET_UNAVAILABLE.message()
                : reason;
        return new NotificationException(
                NotificationErrorCode.NOTIFICATION_TARGET_UNAVAILABLE,
                message);
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.path(key);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    static String appName(String appKey) {
        if (appKey == null || appKey.isBlank()) return "DWP";
        return switch (appKey.toLowerCase(Locale.ROOT)) {
            case "approvals" -> "Approvals";
            case "hcm", "people" -> "HR";
            case "space" -> "Space";
            case "messaging" -> "Messenger";
            case "platform" -> "Digital Workplace";
            default -> appKey.substring(0, 1).toUpperCase(Locale.ROOT) + appKey.substring(1);
        };
    }

    private String appAccent(String appKey) {
        if (appKey == null) return null;
        return switch (appKey.toLowerCase(Locale.ROOT)) {
            case "hcm", "people" -> "#188A72";
            case "space" -> "#C64862";
            case "approvals" -> "#2F66D8";
            default -> "#2457D6";
        };
    }

    private boolean sensitive(String classification) {
        if (classification == null) return false;
        return "CONFIDENTIAL".equals(classification) || "RESTRICTED".equals(classification);
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record TargetRow(
            String state,
            String reason,
            Instant expiresAt,
            List<NotificationAction> actions) {
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private MapSqlParameterSource actorParams(NotificationRequestContext.Actor actor) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId());
    }

    public record InboxFilters(
            String query,
            String appKey,
            String priority,
            String readState,
            String reason,
            Instant from,
            Instant to) {
    }

    public record InboxRow(InboxItem item, long changeVersion) {
    }

    public record ChangedProjection(UUID notificationId, long changeVersion) {
    }

    public record CounterSnapshot(
            long unread,
            long actionable,
            long urgent,
            long version,
            long minimumAvailableVersion,
            Instant updatedAt) {
    }

    public record ViewCounts(
            long priority,
            long all,
            long mentions,
            long saved,
            long snoozed,
            long done) {

        public Map<String, Long> asMap() {
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("PRIORITY", priority);
            counts.put("ALL", all);
            counts.put("MENTIONS", mentions);
            counts.put("SAVED", saved);
            counts.put("SNOOZED", snoozed);
            counts.put("DONE", done);
            return Map.copyOf(counts);
        }
    }

    public record CatalogType(
            String appKey,
            String typeKey,
            String displayName,
            String description) {
    }

    public static List<String> channels() {
        return CHANNELS;
    }
}
