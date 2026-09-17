package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationQueryRepository.InboxContextFilter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

final class NotificationInboxFilterSql {

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
                   user_notification.attention_effect,
                   CASE UPPER(COALESCE(
                       NULLIF(type_version.contract_payload ->> 'interruptionLevel', ''),
                       'ACTIVE'
                   ))
                       WHEN 'PASSIVE' THEN 'PASSIVE'
                       WHEN 'ACTIVE' THEN 'ACTIVE'
                       WHEN 'TIME_SENSITIVE' THEN 'TIME_SENSITIVE'
                       WHEN 'CRITICAL' THEN 'CRITICAL'
                       ELSE 'ACTIVE'
                   END AS interruption_level,
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

    private static final List<String> INCLUDED_TYPE_ORDER =
            List.of("DIRECT", "MENTION", "ASSIGNED", "SUBSCRIPTION", "MANDATORY_POLICY");
    private static final Map<String, List<String>> INCLUDED_REASON_CODES = Map.of(
            "DIRECT", List.of("DIRECT", "DIRECT_RECIPIENT"),
            "MENTION", List.of("MENTION", "MENTIONED"),
            "ASSIGNED", List.of("ROLE"),
            "SUBSCRIPTION", List.of("SUBSCRIPTION", "SUBSCRIBED"),
            "MANDATORY_POLICY", List.of("MANDATORY_POLICY", "MANDATORY"));

    private NotificationInboxFilterSql() {
    }

    static List<String> canonicalIncludedTypes(List<String> values) {
        List<String> includedTypes = values == null ? List.of() : List.copyOf(values);
        if (includedTypes.size() > 5
                || new LinkedHashSet<>(includedTypes).size() != includedTypes.size()
                || includedTypes.stream().anyMatch(type -> !INCLUDED_TYPE_ORDER.contains(type))) {
            throw new NotificationException(
                    NotificationErrorCode.INVALID_INPUT,
                    "Included notification types must be five or fewer unique supported values.");
        }
        return includedTypes.stream()
                .sorted(Comparator.comparingInt(INCLUDED_TYPE_ORDER::indexOf))
                .toList();
    }

    static void appendIncludedTypes(
            StringBuilder predicates,
            MapSqlParameterSource params,
            List<String> includedTypes) {
        if (includedTypes.isEmpty()) return;
        List<String> reasonCodes = includedTypes.stream()
                .flatMap(type -> INCLUDED_REASON_CODES.get(type).stream())
                .distinct()
                .toList();
        predicates.append(" AND UPPER(user_notification.reason_code) IN (:includedReasonCodes)\n");
        params.addValue("includedReasonCodes", reasonCodes);
    }

    static void appendContexts(
            StringBuilder predicates,
            MapSqlParameterSource params,
            List<InboxContextFilter> contexts) {
        if (contexts.isEmpty()) return;
        Map<String, List<InboxContextFilter>> byKind = contexts.stream()
                .collect(Collectors.groupingBy(
                        InboxContextFilter::kind, LinkedHashMap::new, Collectors.toList()));
        int parameterIndex = 0;
        // The stable contract is OR within one kind and AND across distinct kinds.
        for (List<InboxContextFilter> alternatives : byKind.values()) {
            predicates.append(" AND (\n");
            for (int index = 0; index < alternatives.size(); index++) {
                if (index > 0) predicates.append(" OR\n");
                appendContextCandidate(
                        predicates, params, alternatives.get(index), parameterIndex++);
            }
            predicates.append(" )\n");
        }
    }

    private static void appendContextCandidate(
            StringBuilder predicates,
            MapSqlParameterSource params,
            InboxContextFilter contextFilter,
            int index) {
        String key = "contextKey" + index;
        String hash = "contextKeyHash" + index;
        String direct = switch (contextFilter.kind()) {
            case "ACTOR" -> "user_notification.actor_ref = :" + key;
            case "THREAD" -> "notification.thread_key = :" + key;
            case "RESOURCE" -> ":" + key
                    + " IN (user_notification.subject_ref, user_notification.target_ref)";
            case "TOPIC_TOKEN" -> "FALSE";
            default -> throw new IllegalStateException("Validated context kind changed.");
        };
        String contextKinds = switch (contextFilter.kind()) {
            case "ACTOR" -> "'PERSON'";
            case "THREAD" -> "'CONVERSATION', 'THREAD', 'CHANNEL'";
            case "RESOURCE" -> "'PROJECT', 'WORK_ITEM'";
            case "TOPIC_TOKEN" -> "'TOPIC'";
            default -> throw new IllegalStateException("Validated context kind changed.");
        };
        predicates.append("  ((").append(direct).append(") OR EXISTS (\n")
                .append("       SELECT 1 FROM ntf_recipient_notification_contexts context\n")
                .append("        WHERE context.tenant_id = user_notification.tenant_id\n")
                .append("          AND context.user_id = user_notification.user_id\n")
                .append("          AND context.notification_id = user_notification.notification_id\n")
                .append("          AND context.matchable\n")
                .append("          AND context.kind IN (").append(contextKinds).append(")\n")
                .append("          AND context.context_key_hash = :").append(hash).append("\n")
                .append("          AND context.context_key = :").append(key).append("\n")
                .append("   ))\n");
        params.addValue(key, contextFilter.key());
        params.addValue(hash, NotificationAttentionScope.sha256(contextFilter.key()));
    }
}
