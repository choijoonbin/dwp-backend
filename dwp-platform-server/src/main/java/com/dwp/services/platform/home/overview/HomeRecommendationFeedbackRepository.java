package com.dwp.services.platform.home.overview;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Repository
public class HomeRecommendationFeedbackRepository {

    private final JdbcTemplate jdbc;

    public HomeRecommendationFeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> suppressedKeys(Long tenantId, Long userId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT recommendation_key
                  FROM usr_home_recommendation_feedback
                 WHERE tenant_id = ?
                   AND user_id = ?
                   AND feedback_type IN ('NOT_RELEVANT', 'DISMISSED')
                """, String.class, tenantId, userId));
    }

    public void save(
            Long tenantId,
            Long userId,
            String recommendationKey,
            String feedbackType,
            String source,
            String ruleVersion) {
        jdbc.update("""
                INSERT INTO usr_home_recommendation_feedback (
                    feedback_id, tenant_id, user_id, recommendation_key,
                    feedback_type, source, rule_version)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, recommendation_key)
                DO UPDATE SET
                    feedback_type = EXCLUDED.feedback_type,
                    source = EXCLUDED.source,
                    rule_version = EXCLUDED.rule_version,
                    updated_at = CURRENT_TIMESTAMP
                """,
                UUID.randomUUID(), tenantId, userId, recommendationKey,
                feedbackType, source, ruleVersion);
    }
}
