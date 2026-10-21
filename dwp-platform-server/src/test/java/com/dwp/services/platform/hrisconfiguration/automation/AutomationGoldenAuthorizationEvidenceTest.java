package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventInboxRepository;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.CommandSeal;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.Filter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executable golden evidence for the authorization invariants inherited by SYS-006.
 *
 * <p>Every scenario contains its allow arm and its fail-closed arm. PostgreSQL is
 * intentional: RLS, deferred SoD triggers, inbox ordering and immutable receipt
 * seals are database-owned parts of the product contract.
 */
@Testcontainers(disabledWithoutDocker = true)
class AutomationGoldenAuthorizationEvidenceTest {

    private static final String PLATFORM_DATABASE = "sys006_golden_platform";
    private static final String AUTH_DATABASE = "sys006_golden_auth";
    private static final String SERVICE_TOKEN = "sys006-golden-service-token";
    private static final String PLATFORM_APP_ROLE = "sys006_golden_app";
    private static final String PLATFORM_APP_PASSWORD =
            "sys006-golden-app-password";
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);
    private static final String DIGEST_D = "d".repeat(64);
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-21T03:00:00Z");
    private static final String RECEIPT_ROUTE =
            "route.hcm.utility.explorer-command-receipt.data";
    private static final String PLATFORM_CONTEXT = "psc-" + DIGEST_C;
    private static final String PLATFORM_SCOPE = "scope-" + "d".repeat(32);
    private static final String PLATFORM_DECISION = "psr-" + DIGEST_D;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate platformJdbc;
    private static JdbcTemplate platformAppJdbc;
    private static JdbcTemplate authJdbc;
    private static TransactionTemplate platformTransactions;
    private static TransactionTemplate platformAppTransactions;
    private static TransactionTemplate authTransactions;

    @BeforeAll
    static void migrateActualPlatformAndAuthContracts() {
        JdbcTemplate admin = new JdbcTemplate(dataSource(
                POSTGRES.getDatabaseName(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        admin.execute("CREATE DATABASE " + PLATFORM_DATABASE);
        admin.execute("CREATE DATABASE " + AUTH_DATABASE);

        PGSimpleDataSource platformSource = dataSource(
                PLATFORM_DATABASE, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(platformSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("333")
                .cleanDisabled(false)
                .load()
                .migrate();
        platformJdbc = new JdbcTemplate(platformSource);
        platformTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(platformSource));
        platformJdbc.execute("""
                DO $role$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles WHERE rolname = 'sys006_golden_app'
                    ) THEN
                        CREATE ROLE sys006_golden_app LOGIN PASSWORD
                            'sys006-golden-app-password'
                            NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END
                $role$
                """);
        platformJdbc.execute("GRANT USAGE ON SCHEMA public TO "
                + PLATFORM_APP_ROLE);
        platformJdbc.execute("GRANT SELECT, UPDATE ON ALL TABLES IN SCHEMA public TO "
                + PLATFORM_APP_ROLE);
        PGSimpleDataSource platformAppSource = dataSource(
                PLATFORM_DATABASE, PLATFORM_APP_ROLE, PLATFORM_APP_PASSWORD);
        platformAppJdbc = new JdbcTemplate(platformAppSource);
        platformAppTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(platformAppSource));

        PGSimpleDataSource authSource = dataSource(
                AUTH_DATABASE, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(authSource)
                .locations(
                        "filesystem:../dwp-auth-server/src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("208")
                .cleanDisabled(false)
                .load()
                .migrate();
        authJdbc = new JdbcTemplate(authSource);
        authTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(authSource));
    }

    @Test
    void sysGold003TenantPublicIdIsVisibleOnlyInsideTheAuthorizedTenant()
            throws Exception {
        long tenantA = 76003;
        long tenantB = 76004;
        UUID definitionId = uuid("gold-003-definition-id");
        UUID publicId = uuid("gold-003-definition-public-id");
        UUID author = uuid("gold-003-author");
        platformJdbc.update("""
                INSERT INTO sys_automation_definitions (
                    definition_id, public_id, tenant_id, automation_key,
                    display_name, owner_domain, author_actor_ref,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'gold.003.tenant', 'Golden tenant evidence',
                        'HRM', ?, ?, ?)
                """, definitionId, publicId, tenantA, author, NOW, NOW);

        UUID subjectA = uuid("gold-003-subject-a");
        UUID subjectB = uuid("gold-003-subject-b");
        MockHttpServletRequest catalogA = automationRequest(
                "GET", "/v1/admin/hris/automations", tenantA, subjectA);
        MockHttpServletRequest catalogB = automationRequest(
                "GET", "/v1/admin/hris/automations", tenantB, subjectB);
        JdbcAutomationRepository repository = new JdbcAutomationRepository(
                platformAppJdbc, new ObjectMapper());
        AutomationService service = new AutomationService(
                repository, identity -> Optional.empty(), null,
                new AutomationCursorCodec(new ObjectMapper(), "k".repeat(32)),
                new ObjectMapper(), Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        AutomationController controller = new AutomationController(service);

        AutomationDtos.CatalogResponse tenantACatalog = platformAppTransactions.execute(
                ignored -> controller.catalog(
                        NOW, List.of("definitionPublicId", "automationKey"),
                        "FRESH_ONLY", 50, null, null,
                        List.of("AUTOMATION_DEFINITION"), catalogA)
                        .getBody().getData());
        AutomationDtos.CatalogResponse tenantBCatalog = platformAppTransactions.execute(
                ignored -> controller.catalog(
                        NOW, List.of("definitionPublicId", "automationKey"),
                        "FRESH_ONLY", 50, null, null,
                        List.of("AUTOMATION_DEFINITION"), catalogB)
                        .getBody().getData());
        assertThat(tenantACatalog.items())
                .extracting(item -> ((AutomationDtos.DefinitionItem) item)
                        .definitionPublicId())
                .containsExactly(publicId);
        assertThat(tenantBCatalog.items()).isEmpty();

        Integer tenantAVisibleRows = platformAppTransactions.execute(ignored -> {
            repository.bindTenantPrincipal(tenantA, subjectA);
            return platformAppJdbc.queryForObject("""
                    SELECT count(*) FROM sys_automation_definitions
                     WHERE public_id = ?
                    """, Integer.class, publicId);
        });
        Integer tenantBVisibleRows = platformAppTransactions.execute(ignored -> {
            repository.bindTenantPrincipal(tenantB, subjectB);
            return platformAppJdbc.queryForObject("""
                    SELECT count(*) FROM sys_automation_definitions
                     WHERE public_id = ?
                    """, Integer.class, publicId);
        });
        assertThat(tenantAVisibleRows).isEqualTo(1);
        assertThat(tenantBVisibleRows).isZero();

        MockHttpServletRequest tenantBPublicIdCommand = automationRequest(
                "POST", "/v1/admin/hris/automations/" + publicId + "/versions",
                tenantB, subjectB);
        assertThatThrownBy(() -> platformAppTransactions.executeWithoutResult(
                ignored -> controller.createVersion(
                        publicId, null, tenantBPublicIdCommand)))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND))
                .hasMessage("AUTOMATION_DEFINITION_NOT_FOUND")
                .hasMessageNotContaining(Long.toString(tenantA))
                .hasMessageNotContaining(subjectA.toString())
                .hasMessageNotContaining(publicId.toString());
    }

    @Test
    void sysGold008EffectiveUserSodAllowsDisjointAndRejectsOverlappingScopes() {
        long tenantId = authJdbc.queryForObject("""
                INSERT INTO com_tenants (code, name, status)
                VALUES ('sys006-gold-008', 'SYS-006 GOLD-008', 'ACTIVE')
                RETURNING tenant_id
                """, Long.class);
        long checker = authUser(tenantId, "gold-008-checker");
        long subject = authUser(tenantId, "gold-008-subject");
        long makerGroup = authGroup(tenantId, "gold-008-maker-group");
        authJdbc.update("""
                INSERT INTO com_group_members (
                    tenant_id, group_id, user_id, source_type)
                VALUES (?, ?, ?, 'LOCAL')
                """, tenantId, makerGroup, subject);

        UUID makerScope = authResourceSet(
                tenantId, "RS_SYS006_GOLD008_MAKER", "APP.HCM");
        UUID checkerScope = authResourceSet(
                tenantId, "RS_SYS006_GOLD008_CHECKER", "APP.PAYROLL");
        authAssignment(tenantId, "GROUP", Long.toString(makerGroup),
                "APP_ACCESS_MANAGER", makerScope, checker);
        authAssignment(tenantId, "USER", Long.toString(subject),
                "APP_ACCESS_APPROVER", checkerScope, checker);

        assertThat(authJdbc.queryForObject("""
                SELECT count(*) FROM auth_app_responsibility_sod_conflicts(?)
                """, Integer.class, tenantId)).isZero();
        assertThat(authJdbc.queryForObject("""
                SELECT count(*) FROM auth_open_app_responsibility_subjects
                 WHERE tenant_id = ? AND user_id = ?
                """, Integer.class, tenantId, subject)).isEqualTo(2);

        assertThatThrownBy(() -> authTransactions.executeWithoutResult(ignored -> {
            authJdbc.update("""
                    INSERT INTO com_admin_resource_set_members (
                        tenant_id, resource_set_id, resource_type,
                        resource_key, lifecycle_state)
                    VALUES (?, ?, 'APP', 'APP.HCM', 'ACTIVE')
                    """, tenantId, checkerScope);
            authJdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "effective-user separation-of-duties conflict");
        assertThat(authJdbc.queryForObject("""
                SELECT count(*) FROM com_admin_resource_set_members
                 WHERE tenant_id = ? AND resource_set_id = ?
                   AND resource_key = 'APP.HCM'
                """, Integer.class, tenantId, checkerScope)).isZero();
    }

    @Test
    void sysGold032InboxDeduplicatesAndNeverLetsOlderRevisionReopenAccess() {
        String consumer = "dwp-platform-server.sys006-gold-032";
        long tenantId = 76032;
        String aggregateId = "permission-subject:" + uuid("gold-032-subject");
        DomainEventInboxRepository inbox = new DomainEventInboxRepository(
                new NamedParameterJdbcTemplate(platformJdbc),
                new ObjectMapper().registerModule(new JavaTimeModule()));
        DomainEventEnvelope revisionOne = permissionEvent(
                tenantId, aggregateId, 1, uuid("gold-032-event-1"), "GRANTED");
        DomainEventEnvelope revisionTwo = permissionEvent(
                tenantId, aggregateId, 2, uuid("gold-032-event-2"), "REVOKED");
        platformJdbc.execute("""
                CREATE TABLE IF NOT EXISTS sys006_golden_permission_projection (
                    consumer_name VARCHAR(200) NOT NULL,
                    tenant_id BIGINT NOT NULL,
                    aggregate_id VARCHAR(300) NOT NULL,
                    authorization_revision BIGINT NOT NULL,
                    access_state VARCHAR(20) NOT NULL,
                    PRIMARY KEY (consumer_name, tenant_id, aggregate_id)
                )
                """);

        DomainEventInboxRepository.BeginResult earlyTwo = platformTransactions.execute(
                ignored -> inbox.begin(consumer, revisionTwo, "gold-worker",
                        Duration.ofSeconds(30)));
        assertThat(earlyTwo.state())
                .isEqualTo(DomainEventInboxRepository.BeginState.OUT_OF_ORDER);
        assertThat(permissionProjection(consumer, tenantId, aggregateId)).isEmpty();

        DomainEventInboxRepository.BeginResult appliedOne = applyPermissionEvent(
                inbox, consumer, revisionOne, "gold-worker");
        assertThat(appliedOne.state())
                .isEqualTo(DomainEventInboxRepository.BeginState.ACQUIRED);
        assertThat(permissionProjection(consumer, tenantId, aggregateId))
                .containsEntry("authorization_revision", 1L)
                .containsEntry("access_state", "GRANTED");

        platformJdbc.update("""
                UPDATE sys_domain_event_inbox SET available_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = ? AND event_id = ?
                """, consumer, revisionTwo.id());
        DomainEventInboxRepository.BeginResult appliedTwo = applyPermissionEvent(
                inbox, consumer, revisionTwo, "gold-worker");
        assertThat(appliedTwo.state())
                .isEqualTo(DomainEventInboxRepository.BeginState.ACQUIRED);

        DomainEventInboxRepository.BeginResult duplicate = applyPermissionEvent(
                inbox, consumer, revisionTwo, "gold-worker");
        DomainEventEnvelope lateOlder = permissionEvent(
                tenantId, aggregateId, 1, uuid("gold-032-event-late-old"), "GRANTED");
        DomainEventInboxRepository.BeginResult older = applyPermissionEvent(
                inbox, consumer, lateOlder, "gold-worker");

        assertThat(duplicate.state())
                .isEqualTo(DomainEventInboxRepository.BeginState.DUPLICATE);
        assertThat(older.state())
                .isEqualTo(DomainEventInboxRepository.BeginState.DUPLICATE);
        assertThat(permissionProjection(consumer, tenantId, aggregateId))
                .containsEntry("authorization_revision", 2L)
                .containsEntry("access_state", "REVOKED");
        assertThat(hasProjectedAccess(consumer, tenantId, aggregateId)).isFalse();
        assertThat(platformJdbc.queryForObject("""
                SELECT last_sequence FROM sys_domain_event_offsets
                 WHERE consumer_name = ? AND tenant_id = ?
                   AND event_source = 'dwp-auth-server'
                   AND aggregate_type = 'HRIS_PERMISSION_ASSIGNMENT'
                   AND aggregate_id = ?
                """, Long.class, consumer, tenantId, aggregateId)).isEqualTo(2L);
    }

    @Test
    void sysGold040ReceiptLookupReauthorizesAndReturnsOpaqueNotFoundOnDrift()
            throws Exception {
        long tenantId = 76040;
        UUID subject = uuid("gold-040-subject");
        CommandSeal seal = commandSeal(
                tenantId, subject, "gold-040-idempotency-key-0001", DIGEST_A,
                7, 11, uuid("gold-040-correlation"));
        JdbcAutomationRepository automation = new JdbcAutomationRepository(
                platformJdbc, new ObjectMapper());
        UUID receiptId = platformTransactions.execute(ignored -> {
            automation.bindTenantPrincipal(tenantId, subject);
            return automation.beginCommand(seal, NOW).receiptId();
        });

        Filter exact = receiptFilter(ReceiptAuthorityMode.EXACT, tenantId, subject);
        MockHttpServletRequest allowed = receiptRequest(tenantId, subject, receiptId);
        MockHttpServletResponse allowedResponse = new MockHttpServletResponse();
        MockFilterChain allowedChain = new MockFilterChain();
        exact.doFilter(allowed, allowedResponse, allowedChain);
        assertThat(allowedResponse.getStatus()).isEqualTo(200);
        assertThat(allowedChain.getRequest()).isSameAs(allowed);

        assertOpaqueNotFound(receiptFilter(
                ReceiptAuthorityMode.REVISION_DRIFT, tenantId, subject),
                receiptRequest(tenantId, subject, receiptId));

        assertOpaqueNotFound(receiptFilter(
                ReceiptAuthorityMode.PURPOSE_DRIFT, tenantId, subject),
                receiptRequest(tenantId, subject, receiptId));

        MockHttpServletRequest scopeOverride = receiptRequest(
                tenantId, subject, receiptId);
        scopeOverride.removeHeader("X-DWP-Context-Scope-Key");
        scopeOverride.addHeader("X-DWP-Context-Scope-Key",
                "scope-" + "e".repeat(32));
        assertOpaqueNotFound(exact, scopeOverride);

        assertOpaqueNotFound(exact, receiptRequest(
                tenantId + 1, subject, receiptId));

        MockHttpServletRequest revokedEntitlement = receiptRequest(
                tenantId, subject, receiptId);
        revokedEntitlement.removeHeader("X-DWP-Permissions");
        revokedEntitlement.addHeader("X-DWP-Permissions", "APP.HCM:VIEW");
        assertOpaqueNotFound(exact, revokedEntitlement);
    }

    @Test
    void sysGold045ReplayIsCallerBoundAndEveryReceiptSealIsImmutable() {
        long tenantId = 76045;
        UUID firstSubject = uuid("gold-045-subject-one");
        UUID secondSubject = uuid("gold-045-subject-two");
        String key = "gold-045-shared-idempotency-key";
        JdbcAutomationRepository repository = new JdbcAutomationRepository(
                platformJdbc, new ObjectMapper());
        CommandSeal first = commandSeal(
                tenantId, firstSubject, key, DIGEST_A, 3, 5,
                uuid("gold-045-correlation-one"));
        CommandSeal second = commandSeal(
                tenantId, secondSubject, key, DIGEST_A, 3, 5,
                uuid("gold-045-correlation-two"));

        UUID firstReceipt = begin(repository, first).receiptId();
        assertThat(begin(repository, first).receiptId()).isEqualTo(firstReceipt);
        UUID secondReceipt = begin(repository, second).receiptId();
        assertThat(secondReceipt).isNotEqualTo(firstReceipt);

        CommandSeal conflicting = commandSeal(
                tenantId, firstSubject, key, DIGEST_B, 3, 5,
                uuid("gold-045-correlation-conflict"));
        assertThatThrownBy(() -> begin(repository, conflicting))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessage("AUTOMATION_IDEMPOTENCY_CONFLICT");

        Map<String, String> sealedMutations = Map.ofEntries(
                Map.entry("public_id", "gen_random_uuid()"),
                Map.entry("tenant_id", "tenant_id + 1"),
                Map.entry("command_type", "command_type || '.tampered'"),
                Map.entry("originating_action", "originating_action || '.tampered'"),
                Map.entry("subject_principal_public_id", "gen_random_uuid()"),
                Map.entry("population_scope_digest", "repeat('e', 64)"),
                Map.entry("authority_population_scope_digest", "repeat('e', 64)"),
                Map.entry("field_policy_revision", "field_policy_revision + 1"),
                Map.entry("authority_field_policy_revision",
                        "authority_field_policy_revision + 1"),
                Map.entry("purpose_code", "'TAMPERED_PURPOSE'"),
                Map.entry("authorization_revision", "authorization_revision + 1"),
                Map.entry("authority_authorization_revision",
                        "authority_authorization_revision + 1"),
                Map.entry("origin_route_contract_key",
                        "origin_route_contract_key || '.tampered'"),
                Map.entry("origin_context_key", "origin_context_key || '.tampered'"),
                Map.entry("origin_context_scope_key",
                        "origin_context_scope_key || '.tampered'"),
                Map.entry("origin_decision_revision",
                        "origin_decision_revision || '.tampered'"),
                Map.entry("idempotency_key", "idempotency_key || '.tampered'"),
                Map.entry("request_digest", "repeat('e', 64)"),
                Map.entry("correlation_id", "gen_random_uuid()"));
        sealedMutations.forEach((column, expression) ->
                assertThatThrownBy(() -> platformJdbc.update("""
                        UPDATE sys_hris_command_receipts
                           SET %s = %s
                         WHERE tenant_id = ? AND public_id = ?
                        """.formatted(column, expression), tenantId, firstReceipt))
                        .as("immutable receipt seal column %s", column)
                        .isInstanceOf(DataAccessException.class)
                        .hasMessageContaining(
                                "authorization and idempotency seal is immutable"));
        assertThatThrownBy(() -> platformJdbc.update("""
                DELETE FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND public_id = ?
                """, tenantId, firstReceipt))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be deleted");
        assertThat(platformJdbc.queryForObject("""
                SELECT count(*) FROM sys_hris_command_receipts
                 WHERE tenant_id = ? AND originating_action = ?
                   AND idempotency_key = ?
                """, Integer.class, tenantId,
                AutomationService.CREATE_DEFINITION, key)).isEqualTo(2);
    }

    private static MockHttpServletRequest automationRequest(
            String method, String path, long tenantId, UUID subject)
            throws Exception {
        AutomationOwnerAuthorityFilter filter = new AutomationOwnerAuthorityFilter(
                SERVICE_TOKEN,
                new ObjectMapper().registerModule(new JavaTimeModule()));
        AutomationOwnerAuthorityFilter.Binding binding =
                AutomationOwnerAuthorityFilter.bindingContracts().stream()
                        .filter(candidate -> candidate.method().equals(method)
                                && path.matches(candidate.path().pattern()))
                        .findFirst().orElseThrow();
        MockHttpServletRequest request = new MockHttpServletRequest(
                method, path);
        request.addHeader("X-DWP-Service-Token", SERVICE_TOKEN);
        request.addHeader("X-DWP-Route-Contract-Key", binding.routeKey());
        request.addHeader("X-DWP-Current-Decision-Revision", "psr-" + DIGEST_A);
        request.addHeader("X-DWP-Current-Revalidate-At",
                OffsetDateTime.now().plusMinutes(5).toString());
        request.addHeader("X-DWP-Permissions",
                "APP.HCM:VIEW," + binding.profiles().getFirst().permission());
        request.addHeader("X-DWP-Roles", binding.profiles().getFirst().role());
        request.addHeader("X-DWP-Resource-Roles",
                binding.profiles().getFirst().resourceRole());
        request.addHeader("X-DWP-Tenant-ID", Long.toString(tenantId));
        request.addHeader("X-DWP-User-ID", "84");
        request.addHeader("X-DWP-Subject-Principal-Public-ID", subject.toString());
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader("X-DWP-Population-Kind", "CONFIG_SCOPE");
        request.addHeader("X-DWP-Context-Key", "psc-" + DIGEST_B);
        request.addHeader("X-DWP-Context-Scope-Key", "tenant:" + tenantId);
        request.addHeader("X-DWP-Field-Policy-Revision", "7");
        request.addHeader("X-DWP-Authorization-Revision", "11");
        request.addHeader("X-Correlation-ID", UUID.randomUUID().toString());
        request.addHeader("X-Purpose-Code", "HRIS_SYSTEM_ADMIN");
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        if (binding.action()) {
            request.addHeader("X-DWP-Expected-Decision-Revision",
                    "psr-" + DIGEST_A);
            request.addHeader("Idempotency-Key",
                    "gold-003-command-key-0001");
        }
        if (binding.cas()) {
            request.addHeader("X-DWP-Expected-Object-Version", "1");
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        return request;
    }

    private static DomainEventInboxRepository.BeginResult applyPermissionEvent(
            DomainEventInboxRepository inbox,
            String consumer,
            DomainEventEnvelope event,
            String worker) {
        return platformTransactions.execute(ignored -> {
            DomainEventInboxRepository.BeginResult begun = inbox.begin(
                    consumer, event, worker, Duration.ofSeconds(30));
            if (begun.state() == DomainEventInboxRepository.BeginState.ACQUIRED) {
                platformJdbc.update("""
                        INSERT INTO sys006_golden_permission_projection (
                            consumer_name, tenant_id, aggregate_id,
                            authorization_revision, access_state)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (consumer_name, tenant_id, aggregate_id)
                        DO UPDATE SET
                            authorization_revision = EXCLUDED.authorization_revision,
                            access_state = EXCLUDED.access_state
                        WHERE sys006_golden_permission_projection
                                .authorization_revision
                            < EXCLUDED.authorization_revision
                        """, consumer, event.tenantId(), event.aggregateId(),
                        event.aggregateSequence(),
                        event.data().path("accessState").asText());
                inbox.complete(consumer, event, begun.lockToken());
            }
            return begun;
        });
    }

    private static Map<String, Object> permissionProjection(
            String consumer, long tenantId, String aggregateId) {
        return platformJdbc.query("""
                SELECT authorization_revision, access_state
                  FROM sys006_golden_permission_projection
                 WHERE consumer_name = ? AND tenant_id = ? AND aggregate_id = ?
                """, rs -> rs.next() ? Map.<String, Object>of(
                        "authorization_revision",
                        rs.getLong("authorization_revision"),
                        "access_state", rs.getString("access_state"))
                        : Map.<String, Object>of(),
                consumer, tenantId, aggregateId);
    }

    private static boolean hasProjectedAccess(
            String consumer, long tenantId, String aggregateId) {
        return platformJdbc.queryForObject("""
                SELECT access_state = 'GRANTED'
                  FROM sys006_golden_permission_projection
                 WHERE consumer_name = ? AND tenant_id = ? AND aggregate_id = ?
                """, Boolean.class, consumer, tenantId, aggregateId);
    }

    private static DomainEventEnvelope permissionEvent(
            long tenantId, String aggregateId, long sequence, UUID eventId,
            String accessState) {
        return new DomainEventEnvelope(
                "1.0", eventId, "dwp-auth-server",
                "dwp.auth.hris-permission-changed.v1", 1,
                Instant.parse("2026-10-21T03:00:00Z").plusSeconds(sequence),
                "/tenants/" + tenantId + "/hris-permissions/" + aggregateId,
                tenantId, "HRIS_PERMISSION_ASSIGNMENT", aggregateId, sequence,
                "gold-032-correlation", null,
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                new ObjectMapper().createObjectNode()
                        .put("authorizationRevision", sequence)
                        .put("accessState", accessState),
                Map.of());
    }

    private static CommandSeal commandSeal(
            long tenantId, UUID subject, String key, String requestDigest,
            long fieldRevision, long authorizationRevision, UUID correlation) {
        return new CommandSeal(
                tenantId, subject, "CREATE_AUTOMATION_DEFINITION",
                AutomationService.CREATE_DEFINITION,
                "route.hcm.configuration.automation-definition-create.action",
                "psc-" + DIGEST_A, "tenant:" + tenantId,
                "psr-" + DIGEST_B, DIGEST_C, fieldRevision,
                authorizationRevision, "HRIS_SYSTEM_ADMIN", key,
                requestDigest, correlation);
    }

    private static AutomationDtos.CommandReceipt begin(
            JdbcAutomationRepository repository, CommandSeal seal) {
        return platformTransactions.execute(ignored -> {
            repository.bindTenantPrincipal(
                    seal.tenantId(), seal.subjectPrincipalPublicId());
            return repository.beginCommand(seal, NOW);
        });
    }

    private static Filter receiptFilter(
            ReceiptAuthorityMode mode, long tenantId, UUID subject)
            throws Exception {
        Class<?> repositoryType = Class.forName(
                "com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerRepository");
        Class<?> jdbcRepositoryType = Class.forName(
                "com.dwp.services.platform.hrisconfiguration.explorer.JdbcHrisExplorerRepository");
        Constructor<?> repositoryConstructor = jdbcRepositoryType
                .getDeclaredConstructor(JdbcTemplate.class);
        repositoryConstructor.setAccessible(true);
        Object explorerRepository = repositoryConstructor.newInstance(platformJdbc);

        Class<?> sourceType = Class.forName(
                "com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthoritySource");
        Object source = Proxy.newProxyInstance(
                sourceType.getClassLoader(), new Class<?>[]{sourceType},
                (proxy, method, arguments) -> platformReceiptAuthority(
                        proxy, method, arguments, mode, tenantId, subject));
        StaticListableBeanFactory platformSources = new StaticListableBeanFactory();
        platformSources.addBean("sys006GoldenPlatformReceiptAuthority", source);
        ObjectProvider<?> platformProvider = platformSources.getBeanProvider(sourceType);

        Class<?> platformAdapterType = Class.forName(
                "com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthorityAdapter");
        Constructor<?> platformAdapterConstructor = platformAdapterType
                .getDeclaredConstructor(
                        ObjectProvider.class, repositoryType, Clock.class);
        platformAdapterConstructor.setAccessible(true);
        Object platformAdapter = platformAdapterConstructor.newInstance(
                platformProvider, explorerRepository,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        Class<?> explorerSourceType = Class.forName(
                "com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource");
        Class<?> explorerReceiptSourceType = Class.forName(
                "com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerReceiptAuthoritySource");
        StaticListableBeanFactory emptyExplorerSources = new StaticListableBeanFactory();
        StaticListableBeanFactory emptyReceiptSources = new StaticListableBeanFactory();
        Class<?> ownerAdapterType = Class.forName(
                "com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerOwnerEvidenceAdapter");
        Constructor<?> ownerAdapterConstructor = ownerAdapterType
                .getDeclaredConstructor(ObjectProvider.class, ObjectProvider.class,
                        repositoryType, Clock.class);
        ownerAdapterConstructor.setAccessible(true);
        Object ownerAdapter = ownerAdapterConstructor.newInstance(
                emptyExplorerSources.getBeanProvider(explorerSourceType),
                emptyReceiptSources.getBeanProvider(explorerReceiptSourceType),
                explorerRepository, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));

        Class<?> filterType = Class.forName(
                "com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerOwnerEvidenceFilter");
        Constructor<?> filterConstructor = filterType.getDeclaredConstructor(
                String.class, ownerAdapterType, platformAdapterType,
                HcmPlatformPepRegistry.class);
        filterConstructor.setAccessible(true);
        return (Filter) filterConstructor.newInstance(
                SERVICE_TOKEN, ownerAdapter, platformAdapter,
                new HcmPlatformPepRegistry(new ObjectMapper()));
    }

    private static Object platformReceiptAuthority(
            Object proxy, Method method, Object[] arguments,
            ReceiptAuthorityMode mode, long tenantId, UUID subject)
            throws Exception {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "SYS-006 golden receipt authority";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> null;
            };
        }
        if ("resolveForwardedReceipt".equals(method.getName())) {
            Object request = arguments[0];
            if ((long) accessor(request, "tenantId") != tenantId
                    || !subject.equals(accessor(request,
                    "subjectPrincipalPublicId"))
                    || !PLATFORM_CONTEXT.equals(accessor(request, "contextKey"))
                    || !PLATFORM_SCOPE.equals(accessor(request, "contextScopeKey"))
                    || !PLATFORM_DECISION.equals(accessor(
                    request, "currentDecisionRevision"))) {
                return Optional.empty();
            }
            return Optional.of(record(
                    "com.dwp.services.platform.hrisconfiguration.explorer."
                            + "HrisPlatformReceiptAuthoritySource$ForwardedReceiptAuthority",
                    tenantId, subject, PLATFORM_CONTEXT, PLATFORM_SCOPE,
                    PLATFORM_DECISION, NOW.plusMinutes(5)));
        }
        if ("resolve".equals(method.getName())) {
            Object stored = arguments[2];
            long currentAuthorizationRevision =
                    (long) accessor(stored, "authorityAuthorizationRevision")
                            + (mode == ReceiptAuthorityMode.REVISION_DRIFT ? 1 : 0);
            String currentPurpose = mode == ReceiptAuthorityMode.PURPOSE_DRIFT
                    ? "DIFFERENT_PURPOSE" : (String) accessor(stored, "purposeCode");
            return Optional.of(record(
                    "com.dwp.services.platform.hrisconfiguration.explorer."
                            + "HrisPlatformReceiptAuthoritySource$CurrentReceiptAuthority",
                    tenantId, subject,
                    accessor(stored, "originatingAction"),
                    accessor(stored, "authorityPopulationScopeDigest"),
                    accessor(stored, "authorityFieldPolicyRevision"),
                    currentPurpose, currentAuthorizationRevision,
                    accessor(stored, "originRouteContractKey"),
                    accessor(stored, "originContextKey"),
                    accessor(stored, "originContextScopeKey"),
                    accessor(stored, "originDecisionRevision"),
                    PLATFORM_CONTEXT, PLATFORM_SCOPE, PLATFORM_DECISION,
                    NOW.plusMinutes(5)));
        }
        if ("resolveAfterExplorerForwarded".equals(method.getName())) {
            return Optional.empty();
        }
        throw new UnsupportedOperationException(method.toString());
    }

    private static MockHttpServletRequest receiptRequest(
            long tenantId, UUID subject, UUID receiptId) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/admin/hris/command-receipts/" + receiptId);
        request.addHeader("X-DWP-Service-Token", SERVICE_TOKEN);
        request.addHeader("X-DWP-Tenant-ID", Long.toString(tenantId));
        request.addHeader("X-DWP-User-ID", "84");
        request.addHeader("X-DWP-Person-Public-ID", subject.toString());
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader("X-DWP-Permissions", "DATA.HRIS_COMMAND_RECEIPT:VIEW");
        request.addHeader("X-DWP-Roles", "HRIS_CONFIG_AUTHOR");
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        request.addHeader("X-DWP-Current-Decision-Revision", PLATFORM_DECISION);
        request.addHeader("X-DWP-Route-Contract-Key", RECEIPT_ROUTE);
        request.addHeader("X-DWP-Context-Key", PLATFORM_CONTEXT);
        request.addHeader("X-DWP-Context-Scope-Key", PLATFORM_SCOPE);
        request.addHeader("X-DWP-Current-Revalidate-At",
                NOW.plusMinutes(5).toString());
        return request;
    }

    private static void assertOpaqueNotFound(
            Filter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
    }

    private static Object accessor(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object record(String className, Object... arguments)
            throws Exception {
        Class<?> type = Class.forName(className);
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    private static long authUser(long tenantId, String name) {
        return authJdbc.queryForObject("""
                INSERT INTO com_users (tenant_id, display_name, email, status)
                VALUES (?, ?, ?, 'ACTIVE') RETURNING user_id
                """, Long.class, tenantId, name, name + "@sys006-golden.test");
    }

    private static long authGroup(long tenantId, String key) {
        return authJdbc.queryForObject("""
                INSERT INTO com_groups (
                    tenant_id, group_key, display_name, source_type, status)
                VALUES (?, ?, ?, 'LOCAL', 'ACTIVE') RETURNING group_id
                """, Long.class, tenantId, key, key);
    }

    private static UUID authResourceSet(
            long tenantId, String key, String resourceKey) {
        authJdbc.update("""
                INSERT INTO com_resources (tenant_id, type, key, name, enabled)
                VALUES (?, 'APP', ?, ?, TRUE)
                ON CONFLICT (tenant_id, type, key) DO NOTHING
                """, tenantId, resourceKey, resourceKey);
        UUID id = UUID.randomUUID();
        authJdbc.update("""
                INSERT INTO com_admin_resource_sets (
                    resource_set_id, tenant_id, resource_set_key, name,
                    resource_type, lifecycle_state)
                VALUES (?, ?, ?, ?, 'APP', 'ACTIVE')
                """, id, tenantId, key, key);
        authJdbc.update("""
                INSERT INTO com_admin_resource_set_members (
                    tenant_id, resource_set_id, resource_type,
                    resource_key, lifecycle_state)
                VALUES (?, ?, 'APP', ?, 'ACTIVE')
                """, tenantId, id, resourceKey);
        return id;
    }

    private static UUID authAssignment(
            long tenantId, String principalType, String principalRef,
            String responsibility, UUID resourceSetId, long actorId) {
        UUID id = UUID.randomUUID();
        authJdbc.update("""
                INSERT INTO com_admin_role_assignments (
                    admin_role_assignment_id, tenant_id, principal_type,
                    principal_ref, responsibility_code, resource_set_id,
                    assignment_source, lifecycle_state, valid_from, valid_to,
                    review_due_at, justification, approved_by, approved_at,
                    decision_reason, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'MANUAL', 'ACTIVE',
                        statement_timestamp() - INTERVAL '1 day',
                        statement_timestamp() + INTERVAL '30 days',
                        statement_timestamp() + INTERVAL '20 days',
                        'SYS-GOLD-008 direct executable evidence.', ?,
                        statement_timestamp(), 'Independent golden checker.', ?, ?)
                """, id, tenantId, principalType, principalRef,
                responsibility, resourceSetId, actorId, actorId, actorId);
        return id;
    }

    private static PGSimpleDataSource dataSource(
            String database, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[]{POSTGRES.getHost()});
        source.setPortNumbers(new int[]{POSTGRES.getFirstMappedPort()});
        source.setDatabaseName(database);
        source.setUser(username);
        source.setPassword(password);
        return source;
    }

    private static UUID uuid(String material) {
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private enum ReceiptAuthorityMode {
        EXACT,
        REVISION_DRIFT,
        PURPOSE_DRIFT
    }
}
