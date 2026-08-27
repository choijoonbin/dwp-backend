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
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class LatestJavaEnumContractRegistryPostgresIntegrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V198__govern_latest_java_enum_contracts.sql");

    private static final String EXPECTED_CONTRACTS = """
            AUTH.PRODUCT_AUTHORIZATION.OPERATION_LANE|APPROVAL,ACTIVATION,UNKNOWN
            AUTH.GOVERNED_ROUTE.DECISION|ALLOWED,ROUTE_DENIED,EXPIRED,STEP_UP_REQUIRED,SOD_CONFLICT,AUTHORITY_UNAVAILABLE
            AUTH.PRODUCT_SURFACE.ACCESS_MODE|NORMAL,ELEVATED,PROVIDER_SUPPORT
            AUTH.PRODUCT_SURFACE.ACCESS_SOURCE|ENTITLEMENT,RELATIONSHIP,MANAGEMENT,SUPPORT
            AUTH.PRODUCT_SURFACE.ACTIVATION_STATE|ACTIVE,ELIGIBLE,EXPIRED,REVOKED
            AUTH.PRODUCT_SURFACE.CAPABILITY_AUTHORITY_MODE|PERMISSION,PERMISSION_AND_RELATIONSHIP,PERMISSION_OR_RELATIONSHIP
            AUTH.PRODUCT_SURFACE.DECISION|ALLOWED,APP_DENIED,SURFACE_DENIED,ROUTE_DENIED,SCOPE_SELECTION_REQUIRED,SCOPE_INVALID,EXPIRED,ACTIVATION_REQUIRED,STEP_UP_REQUIRED,SOD_CONFLICT,SUPPORT_SCOPE_DENIED,AUTHORITY_UNAVAILABLE
            AUTH.PRODUCT_SURFACE.POLICY_AUTHORITY_MODE|ENTITLEMENT,RELATIONSHIP,ENTITLEMENT_AND_RELATIONSHIP,SUPPORT_SESSION
            AUTH.PRODUCT_SURFACE.RESPONSIBILITY_REQUIREMENT|REQUIRED,NOT_REQUIRED,LEGACY_OVERSIGHT
            AUTH.ACCESS_REVIEW.PREDICATE_STATE|ALLOWED,NOT_AVAILABLE,STALE_VERSION,ALREADY_DECIDED
            AUTH.OIDC_STATE.PURPOSE|LOGIN,STEP_UP
            GATEWAY.PRODUCT_ROUTE.MATCH_STATUS|GOVERNED,LEGACY_EXEMPT,UNGOVERNED,INVALID,AMBIGUOUS
            GATEWAY.PRODUCT_SURFACE.AUTHORITY_STATUS|NOT_EVALUATED,AVAILABLE,UNAVAILABLE
            GATEWAY.PRODUCT_SURFACE.FORWARDING_ENDPOINT|CONTEXTS,PRODUCT_EVALUATION,GOVERNED_EVALUATION
            GATEWAY.PRODUCT_SURFACE.ROLLOUT_APPROVAL_STATUS|CREATED,UPDATED,UNCHANGED,OUT_OF_ORDER,REVISION_CONFLICT,INVALID_DECISION,CORRUPT,UNAVAILABLE
            GATEWAY.PRODUCT_SURFACE.ROLLOUT_LOAD_STATUS|FOUND,MISSING,MIGRATION_REQUIRED,CORRUPT,UNAVAILABLE
            MEETING.VIDEO_MEETING.ACCESS_SCOPE|INTERNAL,INVITED,PUBLIC_CODE
            MEETING.VIDEO_MEETING.ATTENDANCE_STATE|INVITED,REQUESTED,ADMITTED,DENIED,JOINED,LEFT
            MEETING.VIDEO_MEETING.LIFECYCLE_STATE|DRAFT,SCHEDULED,LOBBY,LIVE,ENDED,CANCELLED
            MEETING.VIDEO_MEETING.PARTICIPANT_ROLE|ORGANIZER,CO_HOST,PRESENTER,ATTENDEE,GUEST
            NOTIFICATION.CHANGE_CAUSE|MATERIALIZED,USER_TRIAGE,SYSTEM_RECONCILIATION,TARGET_LIFECYCLE
            PEOPLE.HR.DATA_BOUNDARY|TEAM,ORGANIZATION_SET,TEAM_AND_ORGANIZATION_SET,TENANT
            PEOPLE.PRODUCT_SURFACE.ELIGIBILITY_DECISION|ALLOWED,SURFACE_DENIED,SCOPE_INVALID,AUTHORITY_UNAVAILABLE
            PEOPLE.WORKFORCE_CANDIDATE.ELIGIBILITY|ELIGIBLE,INELIGIBLE
            PLATFORM.CALENDAR.ACCESS_LEVEL|OWNER,MANAGE,EDIT,VIEW_DETAILS,VIEW_FREE_BUSY,EVENT_ATTENDEE,NONE
            PLATFORM.CALENDAR.SOURCE_KIND|OWNED,COMPANY,SHARED,TEAM,RESOURCE
            PLATFORM.CALENDAR.EVENT_DETAIL_LEVEL|FULL,FREE_BUSY
            PLATFORM.HOME_PREFERENCE.INTEGRITY_STATUS|VALID,RECONCILED,RECOVERED
            PLATFORM.PRODUCT_SURFACE_TELEMETRY.POLICY_KIND|READ_ONLY,UPSTREAM_LOCK,SEGREGATION_OF_DUTIES,STEP_UP,SUPPORT,EXPIRY
            PLATFORM.PRODUCT_SURFACE_TELEMETRY.REASON_CODE|APP_DENIED,SURFACE_DENIED,ROUTE_DENIED,SCOPE_SELECTION_REQUIRED,SCOPE_INVALID,EXPIRED,ACTIVATION_REQUIRED,STEP_UP_REQUIRED,SOD_CONFLICT,SUPPORT_SCOPE_DENIED,AUTHORITY_UNAVAILABLE,NETWORK_ERROR,CANCELLED,VALIDATION_ERROR
            PLATFORM.PRODUCT_SURFACE_TELEMETRY.TASK_KIND|WORK,OPERATIONS,CONFIGURATION,ADMINISTRATION,GOVERNANCE,DESIGN,INTEGRATION,REPORTING,REVIEW
            PLATFORM.APPROVALS.AUTHORIZATION_MODE|LEGACY,ENFORCED
            PROVIDER.TENANT_MUTATION.COMPLETION|NOT_READY,SUCCEEDED,COMPENSATED,RECONCILIATION_REQUIRED
            PROVIDER.TENANT_MUTATION.FAILURE_DISPOSITION|RETRY_SCHEDULED,COMPENSATION_SCHEDULED,RECONCILIATION_REQUIRED,LOST_LEASE
            PLATFORM.CAL_CALENDARS.SUBSCRIPTION_POLICY|REQUIRED,DEFAULT_ON,OPTIONAL
            PLATFORM.CAL_EVENTS.IMPORTANCE|LOW,NORMAL,HIGH
            PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.DEVICE_CLASS|DESKTOP,TABLET,MOBILE
            PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.ELAPSED_BUCKET|LT_1S,S1_TO_5,S5_TO_15,S15_TO_30,S30_TO_60,M1_TO_5,GTE_5M
            PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.SCOPE_KIND|TENANT,SELF,TEAM,ORG_UNIT,LEGAL_ENTITY,DOMAIN,RESOURCE_SET,RESOURCE,POLICY_NODE,TARGET_POPULATION,SUPPORT_SESSION
            """;

    private static final String NEW_CODE_SETS = """
            AUTH.PRODUCT_AUTHORIZATION.OPERATION_LANE
            AUTH.GOVERNED_ROUTE.DECISION
            AUTH.PRODUCT_SURFACE.ACCESS_MODE
            AUTH.PRODUCT_SURFACE.ACCESS_SOURCE
            AUTH.PRODUCT_SURFACE.ACTIVATION_STATE
            AUTH.PRODUCT_SURFACE.CAPABILITY_AUTHORITY_MODE
            AUTH.PRODUCT_SURFACE.DECISION
            AUTH.PRODUCT_SURFACE.POLICY_AUTHORITY_MODE
            AUTH.PRODUCT_SURFACE.RESPONSIBILITY_REQUIREMENT
            AUTH.ACCESS_REVIEW.PREDICATE_STATE
            AUTH.OIDC_STATE.PURPOSE
            GATEWAY.PRODUCT_ROUTE.MATCH_STATUS
            GATEWAY.PRODUCT_SURFACE.AUTHORITY_STATUS
            GATEWAY.PRODUCT_SURFACE.FORWARDING_ENDPOINT
            GATEWAY.PRODUCT_SURFACE.ROLLOUT_APPROVAL_STATUS
            GATEWAY.PRODUCT_SURFACE.ROLLOUT_LOAD_STATUS
            MEETING.VIDEO_MEETING.ACCESS_SCOPE
            MEETING.VIDEO_MEETING.ATTENDANCE_STATE
            MEETING.VIDEO_MEETING.LIFECYCLE_STATE
            MEETING.VIDEO_MEETING.PARTICIPANT_ROLE
            NOTIFICATION.CHANGE_CAUSE
            PEOPLE.HR.DATA_BOUNDARY
            PEOPLE.PRODUCT_SURFACE.ELIGIBILITY_DECISION
            PEOPLE.WORKFORCE_CANDIDATE.ELIGIBILITY
            PLATFORM.CALENDAR.ACCESS_LEVEL
            PLATFORM.CALENDAR.SOURCE_KIND
            PLATFORM.CALENDAR.EVENT_DETAIL_LEVEL
            PLATFORM.HOME_PREFERENCE.INTEGRITY_STATUS
            PLATFORM.PRODUCT_SURFACE_TELEMETRY.POLICY_KIND
            PLATFORM.PRODUCT_SURFACE_TELEMETRY.REASON_CODE
            PLATFORM.PRODUCT_SURFACE_TELEMETRY.TASK_KIND
            PLATFORM.APPROVALS.AUTHORIZATION_MODE
            PROVIDER.TENANT_MUTATION.COMPLETION
            PROVIDER.TENANT_MUTATION.FAILURE_DISPOSITION
            """;

    private static final String EXPECTED_BINDINGS = """
            AUTH.PRODUCT_AUTHORIZATION.OPERATION_LANE|dwp-auth-server|BEHAVIOR|ProductAuthorizationOperationsSecurityConfig.Lane
            AUTH.GOVERNED_ROUTE.DECISION|dwp-auth-server|API_CONTRACT|GovernedRouteAuthorityDtos.Decision
            AUTH.PRODUCT_SURFACE.ACCESS_MODE|dwp-auth-server|API_CONTRACT|ProductSurfaceAuthorityDtos.AccessMode
            AUTH.PRODUCT_SURFACE.ACCESS_SOURCE|dwp-auth-server|API_CONTRACT|ProductSurfaceAuthorityDtos.AccessSource
            AUTH.PRODUCT_SURFACE.ACTIVATION_STATE|dwp-auth-server|API_CONTRACT|ProductSurfaceAuthorityDtos.ActivationState
            AUTH.PRODUCT_SURFACE.CAPABILITY_AUTHORITY_MODE|dwp-auth-server|API_CONTRACT|ProductSurfaceAuthorityDtos.CapabilityAuthorityMode
            AUTH.PRODUCT_SURFACE.DECISION|dwp-auth-server|API_CONTRACT|ProductSurfaceAuthorityDtos.Decision
            AUTH.PRODUCT_SURFACE.POLICY_AUTHORITY_MODE|dwp-auth-server|API_CONTRACT|ProductSurfaceAuthorityDtos.PolicyAuthorityMode
            AUTH.PRODUCT_SURFACE.RESPONSIBILITY_REQUIREMENT|dwp-auth-server|API_CONTRACT|ProductSurfaceAuthorityDtos.ResponsibilityRequirement
            AUTH.ACCESS_REVIEW.PREDICATE_STATE|dwp-auth-server|BEHAVIOR|AccessReviewWorkService.PredicateState
            AUTH.OIDC_STATE.PURPOSE|dwp-auth-server|BEHAVIOR|OidcStateStore.Purpose
            GATEWAY.PRODUCT_ROUTE.MATCH_STATUS|dwp-gateway|BEHAVIOR|GeneratedProductRouteCatalog.MatchStatus
            AUTH.PRODUCT_SURFACE.ACCESS_MODE|dwp-gateway|API_CONTRACT|ProductSurfaceContextDtos.AccessMode
            AUTH.PRODUCT_SURFACE.ACCESS_SOURCE|dwp-gateway|API_CONTRACT|ProductSurfaceContextDtos.AccessSource
            GATEWAY.PRODUCT_SURFACE.AUTHORITY_STATUS|dwp-gateway|API_CONTRACT|ProductSurfaceContextDtos.AuthorityStatus
            AUTH.PRODUCT_SURFACE.CAPABILITY_AUTHORITY_MODE|dwp-gateway|API_CONTRACT|ProductSurfaceContextDtos.CapabilityAuthorityMode
            AUTH.PRODUCT_SURFACE.DECISION|dwp-gateway|API_CONTRACT|ProductSurfaceContextDtos.Decision
            AUTH.GOVERNED_ROUTE.DECISION|dwp-gateway|API_CONTRACT|ProductSurfaceContextDtos.GovernedDecision
            AUTH.PRODUCT_SURFACE.POLICY_AUTHORITY_MODE|dwp-gateway|API_CONTRACT|ProductSurfaceContextDtos.PolicyAuthorityMode
            GATEWAY.PRODUCT_SURFACE.FORWARDING_ENDPOINT|dwp-gateway|BEHAVIOR|ProductSurfaceForwardingGuardFilter.Endpoint
            GATEWAY.PRODUCT_SURFACE.ROLLOUT_APPROVAL_STATUS|dwp-gateway|BEHAVIOR|ProductSurfaceRolloutSafetyLatch.ApprovalStatus
            GATEWAY.PRODUCT_SURFACE.ROLLOUT_LOAD_STATUS|dwp-gateway|BEHAVIOR|ProductSurfaceRolloutSafetyLatch.LoadStatus
            MEETING.VIDEO_MEETING.ACCESS_SCOPE|dwp-meeting-server|API_CONTRACT|VideoMeetingModels.AccessScope
            MEETING.VIDEO_MEETING.ATTENDANCE_STATE|dwp-meeting-server|API_CONTRACT|VideoMeetingModels.AttendanceState
            MEETING.VIDEO_MEETING.LIFECYCLE_STATE|dwp-meeting-server|API_CONTRACT|VideoMeetingModels.LifecycleState
            MEETING.VIDEO_MEETING.PARTICIPANT_ROLE|dwp-meeting-server|API_CONTRACT|VideoMeetingModels.ParticipantRole
            NOTIFICATION.CHANGE_CAUSE|dwp-notification-server|BEHAVIOR|NotificationChangeCause
            PEOPLE.HR.DATA_BOUNDARY|dwp-people-server|API_CONTRACT|HrDtos.DataBoundary
            AUTH.PRODUCT_SURFACE.ACCESS_MODE|dwp-people-server|API_CONTRACT|ProductSurfaceEligibilityDtos.AccessMode
            PEOPLE.PRODUCT_SURFACE.ELIGIBILITY_DECISION|dwp-people-server|API_CONTRACT|ProductSurfaceEligibilityDtos.Decision
            PEOPLE.WORKFORCE_CANDIDATE.ELIGIBILITY|dwp-people-server|API_CONTRACT|WorkforceCandidateDtos.Eligibility
            PLATFORM.CALENDAR.ACCESS_LEVEL|dwp-platform-server|API_CONTRACT|CalendarTypes.CalendarAccessLevel
            PLATFORM.CALENDAR.SOURCE_KIND|dwp-platform-server|API_CONTRACT|CalendarTypes.CalendarSourceKind
            PLATFORM.CAL_CALENDARS.SUBSCRIPTION_POLICY|dwp-platform-server|API_CONTRACT|CalendarTypes.CalendarSubscriptionPolicy
            PLATFORM.CALENDAR.EVENT_DETAIL_LEVEL|dwp-platform-server|API_CONTRACT|CalendarTypes.EventDetailLevel
            PLATFORM.CAL_EVENTS.IMPORTANCE|dwp-platform-server|API_CONTRACT|CalendarTypes.EventImportance
            PLATFORM.HOME_PREFERENCE.INTEGRITY_STATUS|dwp-platform-server|API_CONTRACT|HomePreferenceDtos.HomePreferenceIntegrityStatus
            PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.DEVICE_CLASS|dwp-platform-server|API_CONTRACT|ProductSurfaceTelemetryDtos.DeviceClass
            PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.ELAPSED_BUCKET|dwp-platform-server|API_CONTRACT|ProductSurfaceTelemetryDtos.ElapsedBucket
            PLATFORM.PRODUCT_SURFACE_TELEMETRY.POLICY_KIND|dwp-platform-server|API_CONTRACT|ProductSurfaceTelemetryDtos.PolicyKind
            PLATFORM.PRODUCT_SURFACE_TELEMETRY.REASON_CODE|dwp-platform-server|API_CONTRACT|ProductSurfaceTelemetryDtos.ReasonCode
            PLATFORM.PLT_PRODUCT_SURFACE_UX_EVENT.SCOPE_KIND|dwp-platform-server|API_CONTRACT|ProductSurfaceTelemetryDtos.ScopeKind
            PLATFORM.PRODUCT_SURFACE_TELEMETRY.TASK_KIND|dwp-platform-server|API_CONTRACT|ProductSurfaceTelemetryDtos.TaskKind
            PLATFORM.APPROVALS.AUTHORIZATION_MODE|dwp-platform-server|BEHAVIOR|PlatformApprovalsAuthorizationContext.Mode
            PROVIDER.TENANT_MUTATION.COMPLETION|dwp-provider-server|BEHAVIOR|TenantMutationRepository.Completion
            PROVIDER.TENANT_MUTATION.FAILURE_DISPOSITION|dwp-provider-server|BEHAVIOR|TenantMutationRepository.FailureDisposition
            """;

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
    void freshMigrationRegistersEveryExactContractAndIsSemanticallyIdempotent()
            throws Exception {
        cleanAndMigrateThroughV197();

        executeForwardMigration();
        assertExactContracts();
        String fingerprint = registryFingerprint();

        executeForwardMigration();

        assertExactContracts();
        assertThat(registryFingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void upgradeReactivatesExpectedValuesAndRetiresStaleRegistryOnlyValues()
            throws Exception {
        cleanAndMigrateThroughV197();
        jdbc.update("""
                INSERT INTO sys_code_sets (
                    code_set_key, owner_service, display_name, description,
                    configuration_level, validation_source, source_reference,
                    contract_kind, runtime_visibility, lifecycle_state)
                VALUES (
                    'AUTH.PRODUCT_SURFACE.ACCESS_MODE', 'dwp-auth-server',
                    'Stale', 'Stale', 'SYSTEM', 'TYPED_CONTRACT',
                    'ProductSurfaceAuthorityDtos.AccessMode', 'REFERENCE',
                    'RUNTIME', 'RETIRED')
                """);
        jdbc.update("""
                INSERT INTO sys_code_values (
                    code_set_key, code, display_name, label_i18n,
                    behavior_metadata, sort_order, predefined, lifecycle_state)
                VALUES
                    ('AUTH.PRODUCT_SURFACE.ACCESS_MODE', 'NORMAL', 'NORMAL',
                     '{}', '{}', 10, TRUE, 'RETIRED'),
                    ('AUTH.PRODUCT_SURFACE.ACCESS_MODE', 'OBSOLETE', 'OBSOLETE',
                     '{}', '{}', 20, TRUE, 'ACTIVE'),
                    ('PLATFORM.CAL_EVENTS.IMPORTANCE', 'URGENT', 'URGENT',
                     '{}', '{}', 999, TRUE, 'ACTIVE')
                """);
        jdbc.update("""
                INSERT INTO sys_code_bindings (
                    code_set_key, consumer_service, usage_type,
                    source_reference, enforcement_type, lifecycle_state)
                VALUES (
                    'AUTH.PRODUCT_SURFACE.ACCESS_MODE', 'dwp-auth-server',
                    'API_CONTRACT', 'ProductSurfaceAuthorityDtos.AccessMode',
                    'TYPED_CONTRACT', 'RETIRED')
                """);

        executeForwardMigration();

        assertExactContracts();
        assertThat(lifecycleState(
                "AUTH.PRODUCT_SURFACE.ACCESS_MODE", "OBSOLETE"))
                .isEqualTo("RETIRED");
        assertThat(lifecycleState("PLATFORM.CAL_EVENTS.IMPORTANCE", "URGENT"))
                .isEqualTo("RETIRED");
    }

    private static void cleanAndMigrateThroughV197() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("197")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void assertExactContracts() {
        contracts().forEach(contract -> {
            List<String> activeCodes = jdbc.queryForList("""
                    SELECT code
                      FROM sys_code_values
                     WHERE code_set_key = ?
                       AND lifecycle_state = 'ACTIVE'
                     ORDER BY code
                    """, String.class, contract.codeSetKey());
            assertThat(activeCodes)
                    .as("active values for %s", contract.codeSetKey())
                    .containsExactlyElementsOf(contract.sortedCodes());
        });

        NEW_CODE_SETS.lines().filter(line -> !line.isBlank()).forEach(codeSetKey -> {
            CodeSetSafety safety = jdbc.queryForObject("""
                    SELECT configuration_level, validation_source,
                           runtime_visibility, lifecycle_state
                      FROM sys_code_sets
                     WHERE code_set_key = ?
                    """, (row, ignored) -> new CodeSetSafety(
                    row.getString("configuration_level"),
                    row.getString("validation_source"),
                    row.getString("runtime_visibility"),
                    row.getString("lifecycle_state")), codeSetKey);
            assertThat(safety).isEqualTo(new CodeSetSafety(
                    "SYSTEM", "TYPED_CONTRACT", "ADMIN_ONLY", "ACTIVE"));
        });

        EXPECTED_BINDINGS.lines().filter(line -> !line.isBlank()).forEach(line -> {
            String[] binding = line.split("\\|", 4);
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM sys_code_bindings
                     WHERE code_set_key = ?
                       AND consumer_service = ?
                       AND usage_type = ?
                       AND source_reference = ?
                       AND enforcement_type = 'TYPED_CONTRACT'
                       AND lifecycle_state = 'ACTIVE'
                    """, Integer.class,
                    binding[0], binding[1], binding[2], binding[3]);
            assertThat(count).as("binding %s", line).isEqualTo(1);
        });
    }

    private static List<Contract> contracts() {
        return EXPECTED_CONTRACTS.lines()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    String[] fields = line.split("\\|", 2);
                    List<String> codes = Arrays.stream(fields[1].split(","))
                            .sorted()
                            .toList();
                    return new Contract(fields[0], codes);
                })
                .toList();
    }

    private static String lifecycleState(String codeSetKey, String code) {
        return jdbc.queryForObject("""
                SELECT lifecycle_state
                  FROM sys_code_values
                 WHERE code_set_key = ? AND code = ?
                """, String.class, codeSetKey, code);
    }

    private static String registryFingerprint() {
        return jdbc.queryForObject("""
                SELECT md5(
                    COALESCE((
                        SELECT string_agg(to_jsonb(code_set)::TEXT, '|'
                                          ORDER BY code_set_key)
                          FROM sys_code_sets code_set), '') || '#' ||
                    COALESCE((
                        SELECT string_agg(to_jsonb(code_value)::TEXT, '|'
                                          ORDER BY code_set_key, code)
                          FROM sys_code_values code_value), '') || '#' ||
                    COALESCE((
                        SELECT string_agg(to_jsonb(binding)::TEXT, '|'
                                          ORDER BY code_binding_id)
                          FROM sys_code_bindings binding), ''))
                """, String.class);
    }

    private static void executeForwardMigration() throws Exception {
        String migration = Files.readString(MIGRATION);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute(migration);
            connection.commit();
        }
    }

    private record Contract(String codeSetKey, List<String> sortedCodes) {
    }

    private record CodeSetSafety(
            String configurationLevel,
            String validationSource,
            String runtimeVisibility,
            String lifecycleState) {
    }
}
