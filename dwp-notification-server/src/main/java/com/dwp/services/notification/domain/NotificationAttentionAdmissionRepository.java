package com.dwp.services.notification.domain;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class NotificationAttentionAdmissionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationAttentionAdmissionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<RuleCandidate> findActive(
            long tenantId,
            Set<Long> userIds,
            Set<String> scopeHashes,
            Instant now) {
        if (userIds.isEmpty() || scopeHashes.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT rule.user_id, rule.rule_id, rule.scope_kind,
                       rule.scope_key_hash, rule.effect, rule.version, rule.source
                  FROM ntf_user_attention_rules rule
                  LEFT JOIN ntf_user_attention_rule_channels channel
                    ON channel.tenant_id = rule.tenant_id
                   AND channel.user_id = rule.user_id
                   AND channel.rule_id = rule.rule_id
                   AND channel.channel = 'IN_APP'
                 WHERE rule.tenant_id = :tenantId
                   AND rule.user_id IN (:userIds)
                   AND rule.scope_key_hash IN (:scopeHashes)
                   AND rule.enabled
                   AND (rule.starts_at IS NULL OR rule.starts_at <= :now)
                   AND (rule.expires_at IS NULL OR rule.expires_at > :now)
                   AND (channel.rule_id IS NULL OR channel.enabled)
                 ORDER BY rule.user_id, rule.rule_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userIds", userIds)
                .addValue("scopeHashes", scopeHashes)
                .addValue("now", Timestamp.from(now)),
                (resultSet, rowNumber) -> new RuleCandidate(
                        resultSet.getLong("user_id"),
                        resultSet.getObject("rule_id", UUID.class),
                        resultSet.getString("scope_kind"),
                        resultSet.getString("scope_key_hash").trim(),
                        resultSet.getString("effect"),
                        resultSet.getLong("version"),
                        resultSet.getString("source")));
    }

    record RuleCandidate(
            long userId,
            UUID ruleId,
            String scopeKind,
            String scopeKeyHash,
            String effect,
            long version,
            String source) {
    }
}
