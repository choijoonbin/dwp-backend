package com.dwp.services.notification.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMigrationInvariantTest {

    @Test
    void tracksRecipientScopedTargetLifecycleAndKeepsRlsForced() throws IOException {
        String migration = resource(
                "db/migration/V20__track_notification_target_lifecycle.sql");

        assertThat(migration)
                .contains("ADD COLUMN target_state")
                .contains("ADD COLUMN target_state_reason")
                .contains("'AVAILABLE', 'DELETED', 'FORBIDDEN'")
                .contains("ix_ntf_user_notifications_target")
                .contains("FORCE ROW LEVEL SECURITY");
    }

    @Test
    void bulkUndoReceiptsAreShortLivedAndUserScoped() throws IOException {
        String migration = resource(
                "db/migration/V19__add_notification_bulk_undo_receipts.sql");

        assertThat(migration)
                .contains("CREATE TABLE ntf_bulk_undo_receipts")
                .contains("CREATE TABLE ntf_bulk_undo_items")
                .contains("expires_at TIMESTAMPTZ NOT NULL")
                .contains("expected_version BIGINT NOT NULL")
                .contains("user_id = ntf_current_user_id()")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("ON DELETE CASCADE");
    }

    @Test
    void forcesRlsThroughExplicitRuntimeRolesAndSessionContext() throws IOException {
        String migration = resource("db/migration/V2__enforce_notification_row_security.sql");

        assertThat(migration)
                .contains("CREATE ROLE dwp_notification_api")
                .contains("CREATE ROLE dwp_notification_worker")
                .contains("current_user = role_name")
                .contains("current_setting('dwp.tenant_id', TRUE)")
                .contains("current_setting('dwp.user_id', TRUE)")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("NOBYPASSRLS");
    }

    @Test
    void bindsEveryMaterializedTenantReferenceWithCompositeForeignKeys() throws IOException {
        String foundation = resource("db/migration/V1__create_notification_foundation.sql");
        String hardening = resource(
                "db/migration/V4__bind_tenant_owned_notification_foreign_keys.sql");

        assertThat(foundation)
                .contains("FOREIGN KEY (tenant_id, notification_id)")
                .contains("FOREIGN KEY (tenant_id, user_id, rule_id)");
        assertThat(hardening)
                .contains("fk_ntf_intent_scope_tenant_type_version")
                .contains("fk_ntf_notification_scope_tenant_type_version")
                .contains("fk_ntf_user_notification_scope_tenant_template")
                .contains("fk_ntf_delivery_scope_tenant_template")
                .contains("CHECK (type_scope_tenant_id = 0 OR type_scope_tenant_id = tenant_id)");
    }

    @Test
    void separatesTheRuntimeLoginFromTheMigrationOwner() throws IOException {
        String migration = resource(
                "db/migration/V5__separate_notification_runtime_identity.sql");

        assertThat(migration)
                .contains("${notificationRuntimeRole}")
                .contains("rolsuper")
                .contains("rolbypassrls")
                .contains("must not own application objects")
                .contains("GRANT dwp_notification_api, dwp_notification_worker")
                .contains("REVOKE ALL ON ALL TABLES");
    }

    @Test
    void keepsEventAndThreadIdentityStableAcrossContractVersions() throws IOException {
        String migration = resource(
                "db/migration/V6__stabilize_notification_event_identity.sql");

        assertThat(migration)
                .contains("UNIQUE (tenant_id, source_event_id, type_key)")
                .contains("ON ntf_notifications (tenant_id, type_key, thread_key)")
                .contains("contract identity")
                .contains("thread identity");
    }

    @Test
    void limitsApiOutboxConflictDetectionToTenantScopedKeyColumns() throws IOException {
        String migration = resource(
                "db/migration/V7__allow_tenant_scoped_outbox_conflict_detection.sql");

        assertThat(migration)
                .contains("SELECT (tenant_id, event_key)")
                .contains("TO dwp_notification_api")
                .contains("FOR SELECT")
                .contains("tenant_id = ntf_current_tenant_id()")
                .doesNotContain("GRANT SELECT ON ntf_outbox_events");
    }

    @Test
    void localSeedContainsVariedActualNotificationsForPilotUser() throws IOException {
        String seed = resource("db/local-seed/R__seed_local_notification_foundation.sql");

        assertThat(seed)
                .contains("INSERT INTO ntf_notifications")
                .contains("INSERT INTO ntf_user_notifications")
                .contains("900018")
                .contains("APPROVAL.REQUEST_SUBMITTED")
                .contains("HCM.LEAVE_APPROVED")
                .contains("SPACE.MENTION")
                .contains("'MENTION'")
                .contains("'DONE'");
    }

    @Test
    void governsTenantPolicyVersionsWithOneOpenDraftAndIndependentApprovalEvidence()
            throws IOException {
        String migration = resource(
                "db/migration/V9__govern_tenant_notification_policy_changes.sql");

        assertThat(migration)
                .contains("created_by BIGINT")
                .contains("approved_by BIGINT")
                .contains("approved_at TIMESTAMPTZ")
                .contains("change_reason VARCHAR(500)")
                .contains("supersedes_policy_id")
                .contains("CREATE UNIQUE INDEX uq_ntf_policy_open_draft")
                .contains("WHERE state = 'DRAFT' AND tenant_id IS NOT NULL");
    }

    @Test
    void registersTheExplicitMessagingChannelSubscriptionContract() throws IOException {
        String migration = resource(
                "db/migration/V10__register_messaging_channel_message_contract.sql");

        assertThat(migration)
                .contains("MESSAGING.CHANNEL_MESSAGE")
                .contains("messaging.message.sent.v1")
                .contains("\"interruptionLevel\":\"PASSIVE\"")
                .contains("messaging-channel-ko-v1")
                .contains("messaging-channel-en-v1");
    }

    @Test
    void provisionsTheDurableAuditOutboxForTheRestrictedRuntimeIdentity()
            throws IOException {
        String migration = resource(
                "db/migration/V11__add_notification_audit_outbox.sql");
        String conflictGrant = resource(
                "db/migration/V21__allow_audit_outbox_conflict_detection.sql");

        assertThat(migration)
                .contains("CREATE TABLE sys_audit_outbox")
                .contains("event_id UUID NOT NULL UNIQUE")
                .contains("idx_ntf_audit_outbox_delivery")
                .contains("notificationRuntimeRole")
                .contains("GRANT SELECT, INSERT, UPDATE, DELETE")
                .contains("GRANT INSERT ON sys_audit_outbox TO dwp_notification_api")
                .contains("TO dwp_notification_worker")
                .contains("PENDING", "SENDING", "FAILED", "PUBLISHED", "DEAD");
        assertThat(conflictGrant)
                .contains("GRANT SELECT (event_id)")
                .contains("ON sys_audit_outbox")
                .contains("TO dwp_notification_api")
                .doesNotContain("GRANT SELECT ON", "SELECT (event_id, payload)");
    }

    @Test
    void addsValidatedUserOwnedNotificationPresentationPreferences() throws IOException {
        String migration = resource(
                "db/migration/V12__add_user_notification_experience_preferences.sql");

        assertThat(migration)
                .contains("experience_preferences JSONB NOT NULL")
                .contains("HIGH_PRIORITY_ONLY")
                .contains("TITLE_ONLY")
                .contains("jsonb_typeof(experience_preferences) = 'object'")
                .contains("Extensible user-owned presentation controls");
    }

    @Test
    void governsTenantTemplateOverridesAsImmutableMakerCheckerRevisions() throws IOException {
        String migration = resource(
                "db/migration/V13__govern_tenant_notification_template_overrides.sql");

        assertThat(migration)
                .contains("CREATE TABLE ntf_tenant_template_revisions")
                .contains("created_by BIGINT NOT NULL")
                .contains("approved_by BIGINT")
                .contains("approved_by <> created_by")
                .contains("CREATE UNIQUE INDEX uq_ntf_tenant_template_open_draft")
                .contains("WHERE state = 'DRAFT'")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("template_override_revision_id")
                .contains("Published or retired notification template revisions are immutable");
    }

    @Test
    void snapshotsRecipientVisibleContentOnTheUserProjection() throws IOException {
        String migration = resource(
                "db/migration/V17__isolate_recipient_notification_content.sql");

        assertThat(migration)
                .contains("DISABLE ROW LEVEL SECURITY")
                .contains("ADD COLUMN safe_body TEXT")
                .contains("ADD COLUMN action_payload JSONB")
                .contains("ADD COLUMN first_activity_at TIMESTAMPTZ")
                .contains("ADD COLUMN occurrence_count BIGINT")
                .contains("FROM ntf_notifications notification")
                .contains("Recipient-scoped rendered body")
                .contains("FORCE ROW LEVEL SECURITY");
    }

    @Test
    void redactsLegacyContentThatCannotBeAttributedToOneRecipient() throws IOException {
        String migration = resource(
                "db/migration/V18__redact_legacy_recipient_projection_content.sql");

        assertThat(migration)
                .contains("SET actor_ref = NULL")
                .contains("safe_body = ''")
                .contains("action_payload = '{}'::jsonb")
                .contains("first_activity_at = last_activity_at")
                .contains("FORCE ROW LEVEL SECURITY");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing test resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
