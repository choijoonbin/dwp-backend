package com.dwp.services.platform.communication;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
class CommunicationActionQuery {

    private final JdbcTemplate jdbc;

    CommunicationActionQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    ActionSnapshot snapshot(
            Long tenantId,
            Long userId,
            List<String> roles,
            OffsetDateTime now,
            int limit) {
        List<String> effectiveRoles = roles.isEmpty() ? List.of("__NO_ROLE__") : roles;
        String rolePlaceholders = String.join(",", effectiveRoles.stream().map(ignored -> "?").toList());
        List<Object> parameters = parameters(tenantId, userId, effectiveRoles, now);
        CommunicationDtos.FeedSummary summary = jdbc.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE reader.dismissed_at IS NULL) AS total,
                       COUNT(*) FILTER (
                           WHERE reader.dismissed_at IS NULL
                             AND reader.first_opened_at IS NULL) AS unread,
                       COUNT(*) FILTER (
                           WHERE reader.dismissed_at IS NULL
                             AND announcement.acknowledgement_required = TRUE
                             AND reader.acknowledged_at IS NULL) AS required,
                       COUNT(*) FILTER (WHERE reader.saved_at IS NOT NULL) AS saved,
                       COUNT(*) FILTER (
                           WHERE reader.dismissed_at IS NULL
                             AND announcement.severity = 'CRITICAL'
                             AND reader.first_opened_at IS NULL) AS critical_unread,
                       COUNT(*) FILTER (
                           WHERE reader.dismissed_at IS NULL
                             AND (
                                 (announcement.acknowledgement_required = TRUE
                                  AND reader.acknowledged_at IS NULL)
                                 OR
                                 (announcement.severity = 'CRITICAL'
                                  AND reader.first_opened_at IS NULL))) AS actionable
                  FROM adm_announcements announcement
                  LEFT JOIN sys_announcement_engagements reader
                    ON reader.tenant_id = announcement.tenant_id
                   AND reader.announcement_id = announcement.announcement_id
                   AND reader.user_id = ?
                 WHERE announcement.tenant_id = ?
                   AND announcement.lifecycle_state = 'PUBLISHED'
                   AND (announcement.starts_at IS NULL OR announcement.starts_at <= ?)
                   AND (announcement.ends_at IS NULL OR announcement.ends_at > ?)
                   AND (
                       announcement.audience_type = 'ALL'
                       OR (
                           announcement.audience_type = 'ROLE'
                           AND UPPER(announcement.audience_value) IN (%s)))
                """.formatted(rolePlaceholders), (result, ignored) ->
                        new CommunicationDtos.FeedSummary(
                                result.getLong("total"),
                                result.getLong("unread"),
                                result.getLong("required"),
                                result.getLong("saved"),
                                result.getLong("critical_unread"),
                                result.getLong("actionable")), parameters.toArray());

        List<Object> actionParameters = new ArrayList<>(parameters);
        actionParameters.add(Math.max(1, Math.min(limit, 48)));
        List<Long> actionableIds = jdbc.query("""
                SELECT announcement.announcement_id
                  FROM adm_announcements announcement
                  LEFT JOIN sys_announcement_engagements reader
                    ON reader.tenant_id = announcement.tenant_id
                   AND reader.announcement_id = announcement.announcement_id
                   AND reader.user_id = ?
                 WHERE announcement.tenant_id = ?
                   AND announcement.lifecycle_state = 'PUBLISHED'
                   AND (announcement.starts_at IS NULL OR announcement.starts_at <= ?)
                   AND (announcement.ends_at IS NULL OR announcement.ends_at > ?)
                   AND (
                       announcement.audience_type = 'ALL'
                       OR (
                           announcement.audience_type = 'ROLE'
                           AND UPPER(announcement.audience_value) IN (%s)))
                   AND reader.dismissed_at IS NULL
                   AND (
                       (announcement.acknowledgement_required = TRUE
                        AND reader.acknowledged_at IS NULL)
                       OR
                       (announcement.severity = 'CRITICAL'
                        AND reader.first_opened_at IS NULL))
                 ORDER BY CASE
                              WHEN announcement.severity = 'CRITICAL'
                               AND reader.first_opened_at IS NULL THEN 0
                              ELSE 1
                          END,
                          CASE WHEN announcement.acknowledgement_due_at IS NULL THEN 1 ELSE 0 END,
                          announcement.acknowledgement_due_at,
                          announcement.pinned DESC,
                          announcement.published_at DESC NULLS LAST,
                          announcement.announcement_id DESC
                 LIMIT ?
                """.formatted(rolePlaceholders),
                (result, ignored) -> result.getLong("announcement_id"),
                actionParameters.toArray());
        return new ActionSnapshot(summary, actionableIds);
    }

    private List<Object> parameters(
            Long tenantId,
            Long userId,
            List<String> roles,
            OffsetDateTime now) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(userId);
        parameters.add(tenantId);
        parameters.add(now);
        parameters.add(now);
        parameters.addAll(roles);
        return parameters;
    }

    record ActionSnapshot(
            CommunicationDtos.FeedSummary summary,
            List<Long> actionableIds) {
    }
}
