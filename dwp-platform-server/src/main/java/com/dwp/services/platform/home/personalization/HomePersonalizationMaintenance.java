package com.dwp.services.platform.home.personalization;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(
        prefix = "dwp.platform.home.personalization-maintenance",
        name = "enabled",
        havingValue = "true")
public class HomePersonalizationMaintenance {
    private final JdbcTemplate jdbc;
    private final int revisionRetention;
    private final int previewProposalRetentionDays;
    private final int finalizedProposalRetentionDays;
    private final int revisionRetentionDays;
    private final int tombstoneRetentionDays;

    public HomePersonalizationMaintenance(
            JdbcTemplate jdbc,
            @Value("${dwp.platform.home.personalization-maintenance.revision-retention-per-object:100}")
            int revisionRetention,
            @Value("${dwp.platform.home.personalization-maintenance.preview-proposal-retention-days:7}")
            int previewProposalRetentionDays,
            @Value("${dwp.platform.home.personalization-maintenance.finalized-proposal-retention-days:90}")
            int finalizedProposalRetentionDays,
            @Value("${dwp.platform.home.personalization-maintenance.revision-retention-days:365}")
            int revisionRetentionDays,
            @Value("${dwp.platform.home.personalization-maintenance.tombstone-retention-days:365}")
            int tombstoneRetentionDays) {
        this.jdbc = jdbc;
        this.revisionRetention = Math.max(10, Math.min(revisionRetention, 500));
        this.previewProposalRetentionDays = bounded(previewProposalRetentionDays, 1, 90);
        this.finalizedProposalRetentionDays = bounded(finalizedProposalRetentionDays, 7, 730);
        this.revisionRetentionDays = bounded(revisionRetentionDays, 30, 3650);
        this.tombstoneRetentionDays = bounded(tombstoneRetentionDays, 30, 3650);
    }

    @Scheduled(cron = "${dwp.platform.home.personalization-maintenance.cron:0 41 3 * * *}")
    @Transactional
    public void maintain() {
        jdbc.update("""
                DELETE FROM usr_home_command_receipts
                 WHERE expires_at < CURRENT_TIMESTAMP
                """);
        jdbc.update("""
                DELETE FROM usr_home_composer_proposals
                 WHERE (state = 'PREVIEWED'
                        AND expires_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day'))
                    OR (state <> 'PREVIEWED'
                        AND updated_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day'))
                """, previewProposalRetentionDays, finalizedProposalRetentionDays);
        jdbc.update("""
                WITH ranked AS (
                    SELECT revision_id,
                           row_number() OVER (
                               PARTITION BY view_id ORDER BY revision_number DESC) AS rank
                      FROM usr_home_view_revisions)
                DELETE FROM usr_home_view_revisions revision
                 USING ranked
                 WHERE revision.revision_id = ranked.revision_id
                   AND ranked.rank > ?
                   AND revision.created_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                   AND NOT EXISTS (
                       SELECT 1 FROM usr_home_composer_proposals proposal
                        WHERE proposal.applied_revision_id = revision.revision_id
                           OR proposal.undone_revision_id = revision.revision_id)
                """, revisionRetention, revisionRetentionDays);
        jdbc.update("""
                WITH ranked AS (
                    SELECT template_revision_id,
                           row_number() OVER (
                               PARTITION BY template_id ORDER BY revision_number DESC) AS rank
                      FROM adm_home_template_revisions)
                DELETE FROM adm_home_template_revisions revision
                 USING ranked
                 WHERE revision.template_revision_id = ranked.template_revision_id
                   AND ranked.rank > ?
                   AND revision.created_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                """, revisionRetention, revisionRetentionDays);
        // Tombstones retain complete child/revision history for one year before hard deletion.
        jdbc.update("""
                DELETE FROM usr_home_views
                 WHERE deleted_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                """, tombstoneRetentionDays);
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
