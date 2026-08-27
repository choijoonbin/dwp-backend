package com.dwp.services.platform.codecatalog;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MeetingAndMailOrganizationCodeContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V205__govern_meeting_and_mail_organization_code_contracts.sql");

    private static final List<String> MEETING_DATABASE_CONTRACTS = List.of(
            "sys_audit_outbox.status|DEAD,FAILED,PENDING,PUBLISHED,SENDING",
            "sys_domain_event_inbox.status|DEAD,DEFERRED,DUPLICATE,FAILED,PROCESSING,RECEIVED,REPLAY_PENDING,SUCCEEDED",
            "sys_domain_event_outbox.status|DEAD,FAILED,PENDING,PUBLISHED,SENDING",
            "sys_domain_event_replay_audit.direction|INBOX,OUTBOX",
            "vm_meeting_artifacts.artifact_state|AVAILABLE,DELETED,FAILED,NONE,PROCESSING,UNAVAILABLE",
            "vm_meeting_artifacts.artifact_type|ATTENDANCE,CHAT_EXPORT,RECORDING,SUMMARY,TRANSCRIPT",
            "vm_meeting_chat_messages.message_state|ACTIVE,DELETED",
            "vm_meeting_chat_messages.sender_role|ATTENDEE,CO_HOST,GUEST,ORGANIZER,PRESENTER",
            "vm_meeting_collaboration_commands.command_type|CHAT_DELETE,CHAT_SEND,HAND_ACKNOWLEDGE,HAND_CLEAR,HAND_DISMISS,HAND_LOWER,HAND_RAISE",
            "vm_meeting_content_commands.command_outcome|ACCEPTED,BLOCKED",
            "vm_meeting_content_commands.command_type|NOTICE_ACK,PLAN_UPDATE,RECORDING_REQUEST,RECORDING_STOP",
            "vm_meeting_content_notices.notice_state|PUBLISHED,SUPERSEDED",
            "vm_meeting_content_plans.plan_state|BLOCKED,DISABLED,READY",
            "vm_meeting_events.event_type|ADMITTED,CANCELLED,CREATED,DENIED,ENDED,JOINED,JOIN_REQUESTED,LEFT,POLICY_UPDATED,SCHEDULED,STARTED,TOKEN_ISSUED,UNMUTE_REQUESTED",
            "vm_meeting_hand_events.event_type|ACKNOWLEDGED,CLEARED,DISMISSED,LOWERED,RAISED",
            "vm_meeting_hand_requests.request_state|ACKNOWLEDGED,CLEARED,DISMISSED,LOWERED,RAISED",
            "vm_meeting_hand_requests.requester_role|ATTENDEE,CO_HOST,GUEST,ORGANIZER,PRESENTER",
            "vm_meeting_participants.attendance_state|ADMITTED,DENIED,INVITED,JOINED,LEFT,REQUESTED",
            "vm_meeting_participants.participant_role|ATTENDEE,CO_HOST,GUEST,ORGANIZER,PRESENTER",
            "vm_meeting_recording_sessions.recording_state|FAILED,RECORDING,REQUESTED,STARTING,STOPPED,STOP_REQUESTED",
            "vm_meetings.access_scope|INTERNAL,INVITED,PUBLIC_CODE",
            "vm_meetings.lifecycle_state|CANCELLED,DRAFT,ENDED,LIVE,LOBBY,SCHEDULED",
            "vm_meetings.meeting_kind|FORMAL_MEETING",
            "vm_people_snapshot.lifecycle_state|ACTIVE,INACTIVE",
            "vm_tenant_policies.recording_policy|ADMIN_REQUIRED,HOST_OPT_IN,NEVER",
            "vm_tenant_policies.unmute_control|REQUEST_ONLY");

    private static final List<String> TYPED_BINDINGS = List.of(
            "AUTH.PRODUCT_SURFACE.ACCESS_MODE|dwp-approval-server|ApprovalPilotPepRegistry.ActiveAccessMode",
            "MEETING.VIDEO_MEETING.ACCESS_SCOPE|dwp-meeting-server|VideoMeetingModels.AccessScope",
            "MEETING.VIDEO_MEETING.ATTENDANCE_STATE|dwp-meeting-server|VideoMeetingModels.AttendanceState",
            "MEETING.VIDEO_MEETING.CHAT_MESSAGE_STATE|dwp-meeting-server|VideoMeetingCollaborationModels.ChatMessageState",
            "MEETING.VIDEO_MEETING.CONTENT_BLOCKER_CODE|dwp-meeting-server|VideoMeetingContentModels.BlockerCode",
            "MEETING.VIDEO_MEETING.CONTENT_NOTICE_STATE|dwp-meeting-server|VideoMeetingContentModels.NoticeState",
            "MEETING.VIDEO_MEETING.CONTENT_PLAN_STATE|dwp-meeting-server|VideoMeetingContentModels.PlanState",
            "MEETING.VIDEO_MEETING.HAND_REQUEST_STATE|dwp-meeting-server|VideoMeetingCollaborationModels.HandRequestState",
            "MEETING.VIDEO_MEETING.LIFECYCLE_STATE|dwp-meeting-server|VideoMeetingModels.LifecycleState",
            "MEETING.VIDEO_MEETING.PARTICIPANT_ROLE|dwp-meeting-server|VideoMeetingModels.ParticipantRole",
            "MEETING.VIDEO_MEETING.RECORDING_STATE|dwp-meeting-server|VideoMeetingContentModels.RecordingState",
            "PLATFORM.MAIL_FOLDERS.COLOR_TOKEN|dwp-platform-server|MailOrganizationTypes.FolderColor",
            "PLATFORM.MAIL_FOLDERS.PROVIDER_SYNC_STATE|dwp-platform-server|MailOrganizationTypes.ProviderSyncState",
            "PLATFORM.MAIL_ORGANIZATION.LIFECYCLE_ACTION|dwp-platform-server|MailOrganizationTypes.LifecycleAction",
            "PLATFORM.MAIL_ORGANIZATION.RULE_ACTION_TYPE|dwp-platform-server|MailOrganizationTypes.RuleActionType",
            "PLATFORM.MAIL_ORGANIZATION.RULE_FIELD|dwp-platform-server|MailOrganizationTypes.RuleField",
            "PLATFORM.MAIL_ORGANIZATION.RULE_OPERATOR|dwp-platform-server|MailOrganizationTypes.RuleOperator",
            "PLATFORM.MAIL_RULES.MATCH_MODE|dwp-platform-server|MailOrganizationTypes.RuleMatchMode",
            "PLATFORM.MAIL_RULES.SYNCHRONIZATION_STATE|dwp-platform-server|MailOrganizationTypes.ProviderSyncState",
            "PLATFORM.WIDGET_REGISTRY.ASSERTION_KIND|dwp-platform-server|WidgetRegistryTrustPorts.AssertionKind",
            "PLATFORM.WIDGET_REGISTRY.INGRESS_FAILURE|dwp-platform-server|WidgetRegistryIngressFailure",
            "PLATFORM.WIDGET_REGISTRY.INTERNAL_ROUTE|dwp-platform-server|WidgetRegistryInternalRoutes.Route",
            "PLATFORM.WIDGET_REGISTRY.INTERNAL_ROUTE_RESOLUTION_STATUS|dwp-platform-server|WidgetRegistryInternalRoutes.ResolutionStatus",
            "PLATFORM.WIDGET_REGISTRY.REPLAY_DECISION|dwp-platform-server|WidgetRegistryTrustPorts.ReplayDecision",
            "PLATFORM.WIDGET_REGISTRY.VERIFICATION_FAILURE|dwp-platform-server|WidgetRegistryTrustPorts.VerificationFailure");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static PGSimpleDataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void configureDataSource() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void cleanLatestRegistersEveryMeetingCheckAndTypedContractExactly() {
        cleanAndMigrateLatest();

        assertThat(latestSuccessfulVersion()).isEqualTo(205);
        assertThat(meetingDatabaseContracts())
                .containsExactlyElementsOf(MEETING_DATABASE_CONTRACTS);
        assertThat(v205TypedBindings())
                .containsExactlyInAnyOrderElementsOf(TYPED_BINDINGS);
        assertThat(widgetBindingUsages()).containsExactlyInAnyOrder(
                "WidgetRegistryIngressFailure|API_CONTRACT",
                "WidgetRegistryInternalRoutes.ResolutionStatus|BEHAVIOR",
                "WidgetRegistryInternalRoutes.Route|BEHAVIOR",
                "WidgetRegistryTrustPorts.AssertionKind|API_CONTRACT",
                "WidgetRegistryTrustPorts.ReplayDecision|BEHAVIOR",
                "WidgetRegistryTrustPorts.VerificationFailure|BEHAVIOR");
        assertCodeSet(
                "MEETING.VIDEO_MEETING.CONTENT_BLOCKER_CODE",
                "AUDIT,CONSENT,E2EE,EGRESS,KMS,LLM,MEDIA_PROVIDER,MEETINGS_DISABLED,"
                        + "MEETING_NOT_LIVE,PLAN_RECORDING_DISABLED,POLICY_NEVER,"
                        + "RECORDING_NOT_ACTIVE,STORAGE,STT");
        assertCodeSet(
                "PLATFORM.MAIL_ORGANIZATION.RULE_FIELD",
                "BODY,HAS_ATTACHMENT,IMPORTANCE,RECIPIENT,SENDER,SUBJECT");
        assertCodeSet(
                "PLATFORM.MAIL_ORGANIZATION.LIFECYCLE_ACTION",
                "ARCHIVE,DELETE_FOREVER,MOVE,RESTORE,SPAM,TRASH");
        assertCodeSet(
                "PLATFORM.WIDGET_REGISTRY.REPLAY_DECISION",
                "ACCEPTED,REPLAYED,UNAVAILABLE");
        assertCodeSet(
                "PLATFORM.WIDGET_REGISTRY.INTERNAL_ROUTE",
                "EXECUTE_COMMAND,GET_COMMAND_COMPLETION,GET_DEFINITION,"
                        + "GET_EVIDENCE,GET_RELEASE_CHANNEL,GET_RETIREMENT_IMPACT,"
                        + "GET_VERSION,LIST_DEFINITIONS,LIST_DEFINITION_VERSIONS,"
                        + "LIST_EVIDENCE,LIST_RUNTIME_CONTROLS,"
                        + "SEAL_COMMAND_NOT_EXECUTED");
    }

    @Test
    void forwardMigrationIsIdempotentWithoutSyntheticRevisionBumps()
            throws Exception {
        cleanAndMigrateThroughV204();

        executeForwardMigration();
        String fingerprint = v205Fingerprint();
        executeForwardMigration();

        assertThat(v205Fingerprint()).isEqualTo(fingerprint);
        assertThat(meetingDatabaseContracts())
                .containsExactlyElementsOf(MEETING_DATABASE_CONTRACTS);
    }

    @Test
    void ownershipOrSourceCollisionFailsClosed() {
        cleanAndMigrateThroughV204();
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility, lifecycle_state)
                VALUES (
                    'MEETING.VIDEO_MEETING.CHAT_MESSAGE_STATE',
                    'foreign-service', 'Foreign chat state', 'Collision',
                    'SYSTEM', 'TYPED_CONTRACT', 'Foreign.ChatMessageState',
                    'STATE_MACHINE', 'ADMIN_ONLY', 'ACTIVE')
                """);

        assertThatThrownBy(
                MeetingAndMailOrganizationCodeContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ownership, source, or canonical metadata drifted");
    }

    @Test
    void reusableValueDriftFailsClosed() {
        cleanAndMigrateThroughV204();
        jdbc.update("""
                UPDATE sys_code_values
                   SET lifecycle_state = 'RETIRED'
                 WHERE code_set_key = 'MEETING.VIDEO_MEETING.ACCESS_SCOPE'
                   AND code = 'PUBLIC_CODE'
                """);

        assertThatThrownBy(
                MeetingAndMailOrganizationCodeContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("code-set values drifted");
    }

    @Test
    void conflictingTypedSourceBindingFailsClosed() {
        cleanAndMigrateThroughV204();
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility, lifecycle_state)
                VALUES (
                    'APPROVAL.FOREIGN.ACCESS_MODE', 'dwp-approval-server',
                    'Foreign access mode', 'Collision', 'SYSTEM',
                    'TYPED_CONTRACT', 'ForeignApprovalAccessMode', 'SECURITY',
                    'ADMIN_ONLY', 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type, lifecycle_state)
                VALUES (
                    'APPROVAL.FOREIGN.ACCESS_MODE', 'dwp-approval-server',
                    'API_CONTRACT', 'ApprovalPilotPepRegistry.ActiveAccessMode',
                    'TYPED_CONTRACT', 'ACTIVE')
                """);

        assertThatThrownBy(
                MeetingAndMailOrganizationCodeContractRegistryPostgresIntegrationTest
                        ::executeForwardMigration)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("conflicting typed-contract binding");
    }

    private static void cleanAndMigrateThroughV204() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("204")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void cleanAndMigrateLatest() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("205")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void executeForwardMigration() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute(Files.readString(MIGRATION));
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static int latestSuccessfulVersion() {
        return jdbc.queryForObject("""
                SELECT MAX(version::INTEGER)
                  FROM flyway_schema_history
                 WHERE success
                """, Integer.class);
    }

    private static List<String> meetingDatabaseContracts() {
        return jdbc.queryForList("""
                SELECT binding.source_reference || '|' ||
                       string_agg(code_value.code, ',' ORDER BY code_value.code)
                  FROM sys_code_sets code_set
                  JOIN sys_code_bindings binding
                    ON binding.code_set_key = code_set.code_set_key
                   AND binding.consumer_service = 'dwp-meeting-server'
                   AND binding.usage_type = 'DATABASE_COLUMN'
                   AND binding.enforcement_type = 'CHECK'
                   AND binding.lifecycle_state = 'ACTIVE'
                  JOIN sys_code_values code_value
                    ON code_value.code_set_key = code_set.code_set_key
                   AND code_value.lifecycle_state = 'ACTIVE'
                 WHERE code_set.lifecycle_state = 'ACTIVE'
                 GROUP BY binding.source_reference
                 ORDER BY binding.source_reference
                """, String.class);
    }

    private static List<String> v205TypedBindings() {
        return jdbc.queryForList("""
                SELECT binding.code_set_key || '|' ||
                       binding.consumer_service || '|' ||
                       binding.source_reference
                  FROM sys_code_bindings binding
                 WHERE binding.enforcement_type = 'TYPED_CONTRACT'
                   AND binding.lifecycle_state = 'ACTIVE'
                   AND (
                       binding.source_reference LIKE
                           'VideoMeetingCollaborationModels.%'
                       OR binding.source_reference LIKE
                           'VideoMeetingContentModels.%'
                       OR binding.source_reference LIKE
                           'VideoMeetingModels.%'
                       OR binding.source_reference LIKE
                           'MailOrganizationTypes.%'
                       OR binding.source_reference LIKE
                           'WidgetRegistryInternalRoutes.%'
                       OR binding.source_reference LIKE
                           'WidgetRegistryTrustPorts.%'
                       OR binding.source_reference = 'WidgetRegistryIngressFailure'
                       OR binding.source_reference =
                           'ApprovalPilotPepRegistry.ActiveAccessMode')
                 ORDER BY binding.code_set_key, binding.consumer_service,
                          binding.source_reference
                """, String.class);
    }

    private static List<String> widgetBindingUsages() {
        return jdbc.queryForList("""
                SELECT source_reference || '|' || usage_type
                  FROM sys_code_bindings
                 WHERE consumer_service = 'dwp-platform-server'
                   AND enforcement_type = 'TYPED_CONTRACT'
                   AND lifecycle_state = 'ACTIVE'
                   AND (source_reference LIKE 'WidgetRegistryInternalRoutes.%'
                        OR source_reference LIKE 'WidgetRegistryTrustPorts.%'
                        OR source_reference = 'WidgetRegistryIngressFailure')
                 ORDER BY source_reference
                """, String.class);
    }

    private static void assertCodeSet(String codeSetKey, String expectedCodes) {
        assertThat(jdbc.queryForObject("""
                SELECT string_agg(code, ',' ORDER BY code)
                  FROM sys_code_values
                 WHERE code_set_key = ?
                   AND lifecycle_state = 'ACTIVE'
                """, String.class, codeSetKey)).isEqualTo(expectedCodes);
    }

    private static String v205Fingerprint() {
        return jdbc.queryForObject("""
                SELECT string_agg(
                           code_set.code_set_key || ':' ||
                           code_set.schema_version::TEXT || ':' ||
                           code_set.updated_at::TEXT,
                           '|' ORDER BY code_set.code_set_key)
                  FROM sys_code_sets code_set
                 WHERE code_set.code_set_key LIKE 'MEETING.%'
                    OR code_set.code_set_key LIKE
                       'PLATFORM.MAIL_ORGANIZATION.%'
                    OR code_set.code_set_key LIKE
                       'PLATFORM.WIDGET_REGISTRY.%'
                """, String.class);
    }
}
