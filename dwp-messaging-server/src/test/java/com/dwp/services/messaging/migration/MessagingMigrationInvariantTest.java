package com.dwp.services.messaging.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MessagingMigrationInvariantTest {

    @Test
    void bindsRepliesToTheSameTenantAndConversation() throws IOException {
        String migration = resource("db/migration/V7__complete_messaging_interactions_and_realtime.sql");

        assertThat(migration)
                .contains("fk_msg_reply_parent_same_conversation")
                .contains("FOREIGN KEY (tenant_id, conversation_id, reply_to_message_id)")
                .contains("REFERENCES msg_messages (tenant_id, conversation_id, message_id)")
                .contains("ck_msg_reply_not_self");
    }

    @Test
    void createsDurableOrderedRealtimeEventLog() throws IOException {
        String migration = resource("db/migration/V7__complete_messaging_interactions_and_realtime.sql");

        assertThat(migration)
                .contains("CREATE TABLE msg_realtime_events")
                .contains("event_sequence BIGINT GENERATED ALWAYS AS IDENTITY")
                .contains("ix_msg_realtime_tenant_sequence")
                .contains("payload JSONB NOT NULL");
    }

    @Test
    void createsConversationSequenceAndIdempotencyInvariants() throws IOException {
        String migration = resource(
                "db/migration/V8__messaging_sequence_idempotency_and_monotonic_read.sql");

        assertThat(migration)
                .contains("ROW_NUMBER() OVER")
                .contains("UNIQUE (tenant_id, conversation_id, sequence)")
                .contains("CREATE TABLE msg_conversation_sequences")
                .contains("CREATE TABLE msg_idempotency_keys")
                .contains("request_hash CHAR(64) NOT NULL")
                .contains("PRIMARY KEY (tenant_id, user_id, operation, idempotency_key)");
    }

    @Test
    void makesReadCursorMonotonicAndRealtimeLogCanonical() throws IOException {
        String migration = resource(
                "db/migration/V8__messaging_sequence_idempotency_and_monotonic_read.sql");

        assertThat(migration)
                .contains("last_read_sequence BIGINT NOT NULL DEFAULT 0")
                .contains("fk_msg_member_read_cursor_message")
                .contains("ADD COLUMN message_sequence BIGINT")
                .contains("Canonical transactional messaging domain-event log");
    }

    @Test
    void createsFromJoinMembershipVisibilityBoundary() throws IOException {
        String migration = resource(
                "db/migration/V10__govern_conversation_membership_history.sql");

        assertThat(migration)
                .contains("history_start_sequence BIGINT")
                .contains("membership_started_at TIMESTAMPTZ")
                .contains("ck_msg_member_read_not_before_history")
                .contains("FROM_JOIN visibility boundary");
    }

    @Test
    void offersAnExplicitAllMessagesConversationOverride() throws IOException {
        String migration = resource(
                "db/migration/V13__add_explicit_all_message_notification_level.sql");

        assertThat(migration)
                .contains("DROP CONSTRAINT ck_msg_member_notification")
                .contains("'DEFAULT', 'ALL', 'MENTIONS', 'MUTE'");
    }

    @Test
    void governsPersonalDisplayPreferencesWithoutSharingPresentationState() throws IOException {
        String migration = resource(
                "db/migration/V15__add_governed_messaging_display_preferences.sql");

        assertThat(migration)
                .contains("CREATE TABLE msg_tenant_appearance_policies")
                .contains("allow_personal_backgrounds BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("allow_theme_sharing BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("CREATE TABLE msg_user_display_preferences")
                .contains("CREATE TABLE msg_user_conversation_display_preferences")
                .contains("FOREIGN KEY (tenant_id, conversation_id)")
                .contains("theme_key IN ('INHERIT', 'DEFAULT', 'MIST', 'SAGE', 'ROSE')");
    }

    @Test
    void permitsAttachmentOnlyBodiesAtStorageBoundary() throws IOException {
        String migration = resource(
                "db/migration/V15__add_governed_messaging_display_preferences.sql");

        assertThat(migration)
                .contains("DROP CONSTRAINT ck_msg_body_length")
                .contains("length(btrim(body)) BETWEEN 0 AND 20000")
                .contains("Blank text is valid only for attachment-only messages");
    }

    @Test
    void governsRetentionWithFailClosedHoldsLeasesAndAttachmentDeletionEvidence()
            throws IOException {
        String migration = resource(
                "db/migration/V18__govern_message_retention_execution.sql");

        assertThat(migration)
                .contains("retention_purged_at TIMESTAMPTZ")
                .contains("CREATE TABLE msg_retention_holds")
                .contains("CREATE UNIQUE INDEX ux_msg_retention_running_tenant")
                .contains("CREATE TABLE msg_retention_attachment_deletions")
                .contains("state IN ('PENDING', 'COMPLETED')")
                .contains("Content purge timestamp")
                .contains("ENFORCE is blocked unless hold authority readiness is explicit");
    }

    @Test
    void indexesPeopleDirectoryKeysetAndFilterSearches() throws IOException {
        String migration = resource("db/migration/V20__scale_messaging_people_directory.sql");

        assertThat(migration)
                .contains("ix_msg_people_directory_page")
                .contains("tenant_id, display_name, user_id")
                .contains("ix_msg_people_job_title_search")
                .contains("ix_msg_people_organization_search")
                .contains("gin_trgm_ops");
    }

    @Test
    void governsContinuityPayloadRetentionAndWorkerEvidence() throws IOException {
        String migration = resource(
                "db/migration/V22__govern_continuity_retention_and_scheduled_operations.sql");

        assertThat(migration)
                .contains("payload_purged_at TIMESTAMPTZ")
                .contains("status IN ('SENT', 'FAILED', 'CANCELLED')")
                .contains("body = ''")
                .contains("CREATE TABLE msg_scheduled_delivery_worker_heartbeats")
                .contains("last_completed_at TIMESTAMPTZ")
                .contains("Payload-free, cross-instance scheduled delivery cycle evidence");
    }

    @Test
    void indexesTenantScopedConversationKeysetTraversal() throws IOException {
        String migration = resource(
                "db/migration/V23__add_conversation_keyset_indexes.sql");

        assertThat(migration)
                .contains("ix_msg_members_conversation_keyset")
                .contains("tenant_id, user_id, pinned DESC, favorite DESC, conversation_id")
                .contains("WHERE lifecycle_state = 'ACTIVE'")
                .contains("ix_msg_conversations_activity_keyset")
                .contains("tenant_id, last_message_at DESC NULLS LAST, conversation_id");
    }

    @Test
    void addsDurableSelfOwnedPresenceSharingConsent() throws IOException {
        String migration = resource("db/migration/V25__add_presence_sharing_privacy.sql");

        assertThat(migration)
                .contains("presence_sharing_enabled BOOLEAN NOT NULL DEFAULT TRUE")
                .contains("FALSE is enforced before every heartbeat")
                .contains("withdraws any current lease");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing test resource: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
