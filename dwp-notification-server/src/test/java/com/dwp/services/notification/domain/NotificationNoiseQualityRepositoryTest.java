package com.dwp.services.notification.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationNoiseQualityRepositoryTest {

    @Test
    void aggregatesOnlyLowCardinalityMetadataBehindSqlPrivacyThreshold() {
        assertThat(NotificationNoiseQualityRepository.AGGREGATE_SQL)
                .contains("FROM ntf_notification_quality_facts fact")
                .contains("owner_app_key AS app_key")
                .contains("fact.type_key")
                .contains("fact.owner_team")
                .contains("HAVING COUNT(DISTINCT user_id) >= :minimumCohortSize")
                .contains("LOWER(fact.owner_team) LIKE :searchPattern")
                .contains("attention_effect = 'MUTE'")
                .contains("decision = 'ADMITTED' AND collapsed")
                .contains("ntf_notification_quality_completion_facts")
                .contains("finding_code = :risk")
                .contains("finding_severity = :severity")
                .contains("policy.policy_id::text")
                .contains("revision.template_revision_id::text")
                .contains("template_version.type_id = eligible.contract_id")
                .contains("GROUP BY contract_id, owner_app_key, type_key, owner_team")
                .contains("LIMIT :limit")
                .doesNotContain("ntf_user_attention_rules", "ntf_user_notifications")
                .doesNotContain(
                        "safe_title", "safe_preview", "safe_body", "search_text",
                        "actor_ref", "subject_ref", "target_ref", "display_label");
    }

    @Test
    void fatigueCohortUsesOnlyRecipientCountsAndBoundedOccurrenceFacts() {
        assertThat(NotificationNoiseQualityRepository.FATIGUE_SQL)
                .contains("COUNT(*)")
                .contains("FROM ntf_notification_quality_facts fact")
                .contains("fact.decision = 'ADMITTED'")
                .contains("HAVING COUNT(*) >= :occurrenceThreshold")
                .contains(":occurrenceThreshold")
                .doesNotContain("occurrence_count", "ntf_user_notifications")
                .doesNotContain(
                        "safe_title", "safe_preview", "safe_body", "search_text",
                        "actor_ref", "subject_ref", "target_ref", "display_label");
    }

    @Test
    void trendWithholdsEveryBucketBelowTheSamePrivacyThreshold() {
        assertThat(NotificationNoiseQualityRepository.TREND_SQL)
                .contains("date_trunc(:bucket")
                .contains("fact.contract_id IN (:contractIds)")
                .contains("HAVING COUNT(DISTINCT user_id) >= :minimumCohortSize")
                .contains("attention_effect = 'MUTE'")
                .doesNotContain("ntf_user_attention_rules", "ntf_user_notifications")
                .doesNotContain(
                        "safe_title", "safe_preview", "safe_body", "search_text",
                        "actor_ref", "subject_ref", "target_ref", "display_label");
    }

    @Test
    void fourEyesEvidenceRequiresDistinctRecordedActorsForEveryPublishedPolicy() {
        assertThat(NotificationNoiseQualityRepository.FOUR_EYES_SQL)
                .contains("approved_by IS NOT NULL")
                .contains("created_by IS NOT NULL")
                .contains("approved_by <> created_by")
                .contains("FILTER (WHERE state = 'PUBLISHED')");
    }
}
