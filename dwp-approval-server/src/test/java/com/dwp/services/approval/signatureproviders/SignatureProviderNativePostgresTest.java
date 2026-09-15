package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderPolicyDtos.*;
import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class SignatureProviderNativePostgresTest {
    private static final String SHA = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-09-14T03:00:00Z");
    private static final UUID PERSON_99 = UUID.nameUUIDFromBytes("person-99".getBytes(StandardCharsets.UTF_8));
    private static final UUID PERSON_100 = UUID.nameUUIDFromBytes("person-100".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner", "signature-provider-native-v35");

    private DataSource source;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private SignatureProviderCanonical canonical;
    private SignatureProviderPersistence persistence;
    private SignatureProviderPolicyRepository policies;
    private SignatureProviderDiagnosticsRepository diagnostics;
    private ExternalSignatureRepository requests;
    private FixedAuthority authority;
    private UUID approvedRequestId;
    private UUID otherApprovedRequestId;

    @BeforeEach
    void migrateFreshDatabase() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(PG.getJdbcUrl());
        dataSource.setUser(PG.getUsername());
        dataSource.setPassword(PG.getPassword());
        source = dataSource;
        var flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        var cleaner = new JdbcTemplate(source);
        cleaner.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        cleaner.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        canonical = new SignatureProviderCanonical(new ObjectMapper().findAndRegisterModules());
        persistence = new SignatureProviderPersistence(new NamedParameterJdbcTemplate(source),
                canonical, Clock.fixed(NOW, ZoneOffset.UTC));
        var compiler = new SignatureProviderPolicyCompiler(new ObjectMapper().findAndRegisterModules());
        policies = new SignatureProviderPolicyRepository(persistence, compiler);
        diagnostics = new SignatureProviderDiagnosticsRepository(persistence);
        requests = new ExternalSignatureRepository(persistence);
        authority = new FixedAuthority(99, PERSON_99);
        jdbc.queryForObject("SELECT seed_approval_tenant(42)", Object.class);
        approvedRequestId = UUID.randomUUID();
        otherApprovedRequestId = UUID.randomUUID();
        insertApprovedRequest(approvedRequestId);
        insertApprovedRequest(otherApprovedRequestId);
    }

    private void insertApprovedRequest(UUID requestId) {
        jdbc.update("""
                INSERT INTO apr_requests(
                    request_id,tenant_id,request_number,workflow_version_id,form_version_id,
                    title,requester_user_id,requester_person_public_id,status,data_classification,
                    management_resource_set_key,version)
                SELECT ?,42,?,workflow_version_id,form_version_id,'External signature source',
                       99,?,'APPROVED','INTERNAL','RS_APPROVALS',3
                  FROM apr_workflow_versions CROSS JOIN apr_form_versions
                 WHERE apr_workflow_versions.tenant_id=42 AND apr_form_versions.tenant_id=42
                 LIMIT 1
                """, requestId, "EXT-SIGN-" + requestId, PERSON_99);
        jdbc.update("""
                INSERT INTO apr_request_payloads(
                    tenant_id,request_id,payload,payload_sha256,schema_version)
                VALUES(42,?,'{"summary":"External signature source"}'::jsonb,?,1)
                """, requestId, SHA);
    }

    @Test
    void persistsPublishedPolicyAndIdempotentActorOwnedExternalRequest() {
        var provider = activateProvider(ProviderKind.DOCUSIGN);
        var runtime = new TestRuntime(provider.target());
        var projection = new SignatureProviderProjection(persistence, policies, diagnostics, runtime);
        var compiler = new SignatureProviderPolicyCompiler(new ObjectMapper().findAndRegisterModules());

        var initial = tx(() -> policies.initialize(authority.require(
                SignatureProviderOperation.POLICY_INITIALIZE), compiler.disabledInitialRules()));
        Rules enabled = enabledRules(provider.configuration());
        var draft = tx(() -> policies.save(authority.require(
                SignatureProviderOperation.POLICY_DRAFT), initial, enabled));
        authority.actor(100, PERSON_100);
        var published = tx(() -> policies.publish(authority.require(
                SignatureProviderOperation.POLICY_PUBLISH), draft, SHA));
        authority.actor(99, PERSON_99);

        var service = new ApprovalExternalSignatureService(authority,
                mock(ApprovalSignatureProviderHighGuard.class), persistence, policies,
                projection, requests, runtime);
        assertThat(tx(() -> projection.overview(authority.require(
                SignatureProviderOperation.DIAGNOSTICS))).phases())
                .allMatch(phase -> phase.gateState() == GateState.ELIGIBLE);
        UUID requestId = approvedRequestId;
        var operation = SignatureProviderOperation.EXTERNAL_CREATE;
        var current = authority.require(operation);
        ExternalSource requestSource = tx(() -> requests.source(current, requestId, false));
        var providerSource = tx(() -> persistence.source(current, false));
        var input = new ExternalCreateInput(requestSource.requestVersion(),
                providerSource.revision(), providerSource.sha256(), provider.target(), "create-1");

        List<ExternalReceipt> concurrent = concurrently(() -> tx(() -> service.create(requestId, input)));
        ExternalReceipt first = concurrent.getFirst();
        ExternalReceipt retried = concurrent.getLast();
        assertThat(retried).isEqualTo(first);
        assertThat(first.signatureRequest().state()).isEqualTo(ExternalState.PREPARED);
        assertThat(first.signatureRequest().policyVersionId())
                .isEqualTo(published.published().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_requests",
                Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_events",
                Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_signature_native_commands",
                Long.class)).isOne();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO apr_external_signature_requests(
                    tenant_id,resource_set_key,signature_request_id,request_id,owner_user_id,
                    provider_id,provider_version,provider_sha256,configuration_id,configuration_version,
                    configuration_sha256,policy_id,policy_version_id,policy_sha256,source,source_sha256,
                    state,version,remote_reference_sha256,reason_codes,created_at,updated_at)
                SELECT tenant_id,resource_set_key,?,request_id,owner_user_id,
                       provider_id,provider_version,provider_sha256,configuration_id,configuration_version,
                       configuration_sha256,policy_id,policy_version_id,policy_sha256,
                       jsonb_set(source,'{sourceSha256}',to_jsonb(CAST(? AS text))),?,
                       state,version,remote_reference_sha256,reason_codes,created_at,updated_at
                  FROM apr_external_signature_requests
                 WHERE tenant_id=42 AND signature_request_id=?
                """, UUID.randomUUID(), "b".repeat(64), "b".repeat(64),
                first.signatureRequest().signatureRequestId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertError(ErrorCode.RESOURCE_CONFLICT,
                () -> tx(() -> service.create(otherApprovedRequestId, input)));
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO apr_external_signature_events(
                    tenant_id,resource_set_key,signature_request_id,owner_user_id,event_id,
                    sequence,action,state,reason_codes,occurred_at)
                VALUES(42,'RS_APPROVALS',?,99,?,1,'REFRESH','PREPARED','[]'::jsonb,?)
                """, first.signatureRequest().signatureRequestId(), UUID.randomUUID(),
                java.sql.Timestamp.from(NOW))).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO apr_external_signature_artifacts(
                    tenant_id,resource_set_key,signature_request_id,owner_user_id,artifact_id,
                    artifact_kind,media_type,content_sha256,size_bytes,storage_locator_sha256,
                    object_version_sha256,retain_until,evidence_id,evidence_sha256,recorded_at)
                VALUES(42,'RS_APPROVALS',?,99,?,'SIGNED_PDF','application/pdf',?,1,?,?,?, ?,?,?)
                """, first.signatureRequest().signatureRequestId(), UUID.randomUUID(), SHA, SHA, SHA,
                java.sql.Timestamp.from(NOW.plusSeconds(86_400)), UUID.randomUUID(), SHA,
                java.sql.Timestamp.from(NOW))).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_external_signature_requests
                   SET state='COMPLETED_VERIFIED',version=version+1,
                       remote_reference_sha256=?,updated_at=clock_timestamp()
                 WHERE tenant_id=42 AND signature_request_id=?
                """, SHA, first.signatureRequest().signatureRequestId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);

        var changed = new ExternalCreateInput(requestSource.requestVersion() + 1,
                providerSource.revision(), providerSource.sha256(), provider.target(), "create-1");
        assertError(ErrorCode.RESOURCE_CONFLICT, () -> tx(() -> service.create(requestId, changed)));
        assertActorRls(first.signatureRequest().signatureRequestId());

        jdbc.update("""
                UPDATE apr_signature_providers
                   SET lifecycle_state='DISABLED',version=version+1
                 WHERE tenant_id=42 AND provider_id=?
                """, provider.id());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_external_signature_requests
                   SET state='FAILED',version=version+1,reason_codes='["PROVIDER_DISABLED"]'::jsonb,
                       updated_at=clock_timestamp()
                 WHERE tenant_id=42 AND signature_request_id=?
                """, first.signatureRequest().signatureRequestId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        var disabled = tx(() -> projection.overview(authority.require(
                SignatureProviderOperation.DIAGNOSTICS)));
        assertThat(disabled.kpis().externalGateState()).isEqualTo(GateState.BLOCKED);
        assertThat(disabled.providers()).filteredOn(card -> card.providerId().equals(provider.id()))
                .singleElement().satisfies(card -> {
                    assertThat(card.readiness()).isEqualTo(Readiness.DISABLED);
                    assertThat(card.gateReasonCodes()).containsExactly("PROVIDER_DISABLED");
                    assertThat(card.credentialVerified()).isFalse();
                });
    }

    @Test
    void serializesDifferentCommandKeysAgainstOneExactSourceRevision() throws Exception {
        var provider = activateProvider(ProviderKind.DOCUSIGN);
        CountDownLatch enteredRuntime = new CountDownLatch(1);
        CountDownLatch releaseRuntime = new CountDownLatch(1);
        var runtime = new BlockingProbeRuntime(provider.target(), enteredRuntime, releaseRuntime);
        var compiler = new SignatureProviderPolicyCompiler(new ObjectMapper().findAndRegisterModules());
        var service = new ApprovalSignatureProviderService(authority,
                mock(ApprovalSignatureProviderHighGuard.class), persistence, policies,
                diagnostics, new SignatureProviderProjection(persistence, policies, diagnostics, runtime),
                compiler, runtime);
        var current = authority.require(SignatureProviderOperation.PROBE);
        var sourceState = tx(() -> persistence.source(current, false));
        var firstInput = new ProbeInput(sourceState.revision(), sourceState.sha256(), false,
                List.of(provider.target()), "probe-fence-a");
        var secondInput = new ProbeInput(sourceState.revision(), sourceState.sha256(), false,
                List.of(provider.target()), "probe-fence-b");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProbeRun> first = executor.submit(() -> tx(() -> service.probe(firstInput)));
            assertThat(enteredRuntime.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> second = executor.submit(() -> catchThrowable(
                    () -> tx(() -> service.probe(secondInput))));
            releaseRuntime.countDown();

            assertThat(first.get(20, TimeUnit.SECONDS).state()).isEqualTo(ProbeState.COMPLETE);
            Throwable failure = second.get(20, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(BaseException.class);
            assertThat(((BaseException) failure).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        } finally {
            releaseRuntime.countDown();
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_signature_provider_probe_runs",
                Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_signature_provider_probe_observations",
                Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_signature_native_commands",
                Long.class)).isOne();
    }

    @Test
    void missingRuntimeCannotCreateProbeEvidenceOrCommandReceipt() {
        var current = authority.require(SignatureProviderOperation.PROBE);
        var compiler = new SignatureProviderPolicyCompiler(new ObjectMapper().findAndRegisterModules());
        tx(() -> policies.initialize(current, compiler.disabledInitialRules()));
        var sourceState = tx(() -> persistence.source(current, false));
        var target = sourceState.providers().stream()
                .filter(item -> item.kind() == ProviderKind.DOCUSIGN).findFirst().orElseThrow().target();
        var unavailable = new UnavailableSignatureProviderRuntime();
        var projection = new SignatureProviderProjection(persistence, policies, diagnostics, unavailable);
        var service = new ApprovalSignatureProviderService(authority,
                mock(ApprovalSignatureProviderHighGuard.class), persistence, policies,
                diagnostics, projection, compiler, unavailable);
        var external = new ApprovalExternalSignatureService(authority,
                mock(ApprovalSignatureProviderHighGuard.class), persistence, policies,
                projection, requests, unavailable);
        var probeInput = new ProbeInput(sourceState.revision(), sourceState.sha256(),
                false, List.of(target), "probe-1");
        var kmsInput = new KmsProbeInput(sourceState.revision(), sourceState.sha256(),
                target, "kms-1");
        var wormInput = new WormInspectionInput(sourceState.revision(), sourceState.sha256(),
                target, UUID.randomUUID(), "worm-1");
        var requestSource = tx(() -> requests.source(authority.require(
                SignatureProviderOperation.EXTERNAL_CREATE), approvedRequestId, false));
        var externalInput = new ExternalCreateInput(requestSource.requestVersion(),
                sourceState.revision(), sourceState.sha256(), target, "external-1");

        assertError(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> tx(() -> service.probe(probeInput)));
        assertError(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> tx(() -> service.probeKms(kmsInput)));
        assertError(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> tx(() -> service.inspectWorm(wormInput)));
        assertError(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> tx(() -> external.create(approvedRequestId, externalInput)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_signature_provider_probe_runs",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_signature_provider_probe_observations",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_signature_provider_inspections",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_external_signature_requests",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_signature_native_commands",
                Long.class)).isZero();
    }

    @Test
    void databaseRejectsMutableEvidenceAndInvalidHeadTransitions() {
        var compiler = new SignatureProviderPolicyCompiler(new ObjectMapper().findAndRegisterModules());
        var state = tx(() -> policies.initialize(authority.require(
                SignatureProviderOperation.POLICY_INITIALIZE), compiler.disabledInitialRules()));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_signature_provider_policy_versions SET rules_sha256=?
                 WHERE tenant_id=42 AND policy_id=?
                """, "b".repeat(64), state.policyId())).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> tx(() -> {
            jdbc.update("""
                    UPDATE apr_signature_provider_policy_heads SET version=version+2
                     WHERE tenant_id=42 AND policy_id=?
                    """, state.policyId());
            return null;
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);
        String malformed = canonical.json(compiler.disabledInitialRules())
                .replace("\"configurationBinding\":null", "\"configurationBinding\":\"forged\"");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO apr_signature_provider_policy_versions(
                    tenant_id,resource_set_key,policy_id,version_id,revision,rules,rules_sha256,
                    maker_user_id,maker_person_public_id,editor_user_id,editor_person_public_id,created_at)
                VALUES(42,'RS_APPROVALS',?,?,99,CAST(? AS jsonb),?,99,?,99,?,?)
                """, state.policyId(), UUID.randomUUID(), malformed, SHA,
                PERSON_99, PERSON_99, java.sql.Timestamp.from(NOW)))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void expiredProductionEvidenceCannotAuthorizePolicyPublication() {
        var provider = activateProvider(ProviderKind.DOCUSIGN);
        var compiler = new SignatureProviderPolicyCompiler(new ObjectMapper().findAndRegisterModules());
        var projection = new SignatureProviderProjection(persistence, policies, diagnostics,
                new TestRuntime(provider.target(), NOW.minusSeconds(1)));
        var current = authority.require(SignatureProviderOperation.POLICY_DRAFT);
        var initial = tx(() -> policies.initialize(current, compiler.disabledInitialRules()));
        var draft = tx(() -> policies.save(current, initial, enabledRules(provider.configuration())));

        assertError(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> tx(() -> {
                    projection.requirePublishable(current, draft);
                    return null;
                }));
    }

    @Test
    void nativeSchemaAndExternalForeignKeyNamesAreStableForRetentionV36() {
        assertThat(jdbc.queryForList("""
                SELECT tablename FROM pg_catalog.pg_tables
                 WHERE schemaname='public' AND tablename LIKE 'apr_external_signature_%'
                 ORDER BY tablename
                """, String.class)).containsExactly(
                "apr_external_signature_artifacts",
                "apr_external_signature_events",
                "apr_external_signature_requests");
        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_catalog.pg_constraint
                 WHERE contype='f' AND conrelid IN (
                     'apr_external_signature_requests'::regclass,
                     'apr_external_signature_events'::regclass,
                     'apr_external_signature_artifacts'::regclass)
                 ORDER BY conname
                """, String.class)).containsExactly(
                "fk_apr_external_signature_artifact_request",
                "fk_apr_external_signature_event_request",
                "fk_apr_external_signature_request_approval",
                "fk_apr_external_signature_request_policy_version",
                "fk_apr_external_signature_request_provider");
        assertThat(jdbc.queryForList("""
                SELECT c.relname
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                 WHERE n.nspname='public'
                   AND c.relname LIKE 'apr_%signature%'
                   AND c.relrowsecurity AND c.relforcerowsecurity
                 ORDER BY c.relname
                """, String.class)).contains(
                "apr_external_signature_artifacts",
                "apr_external_signature_events",
                "apr_external_signature_requests",
                "apr_signature_native_commands",
                "apr_signature_provider_inspections",
                "apr_signature_provider_policy_heads",
                "apr_signature_provider_policy_publications",
                "apr_signature_provider_policy_versions",
                "apr_signature_provider_probe_observations",
                "apr_signature_provider_probe_runs");
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM pg_catalog.pg_namespace n
                  CROSS JOIN LATERAL aclexplode(
                      COALESCE(n.nspacl, acldefault('n', n.nspowner))) privilege
                 WHERE n.nspname='apr_signature_native' AND privilege.grantee=0
                """, Long.class)).isZero();
    }

    private Rules enabledRules(SourcePin configuration) {
        return new Rules(true, List.of(ProviderKind.DOCUSIGN), List.of("INTERNAL"),
                true, true, false, false, false, false,
                365L, 3_600L, null, configuration);
    }

    private SignatureProviderPersistence.Registration activateProvider(ProviderKind kind) {
        var current = authority.require(SignatureProviderOperation.DIAGNOSTICS);
        UUID providerId = tx(() -> persistence.registrations(current, true).stream()
                .filter(item -> item.kind() == kind).findFirst().orElseThrow().id());
        jdbc.update("""
                UPDATE apr_signature_providers
                   SET lifecycle_state='ACTIVE',version=version+1
                 WHERE tenant_id=42 AND provider_id=?
                """, providerId);
        return tx(() -> persistence.registrations(current, true).stream()
                .filter(item -> item.id().equals(providerId)).findFirst().orElseThrow());
    }

    private void assertActorRls(UUID id) {
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='signature_v35_reader') "
                + "THEN CREATE ROLE signature_v35_reader NOLOGIN NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        jdbc.execute("GRANT SELECT ON apr_external_signature_requests,apr_signature_native_commands "
                + "TO signature_v35_reader");
        try (Connection connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET ROLE signature_v35_reader");
            statement.execute("SET dwp.approval.signature.tenant='42'");
            statement.execute("SET dwp.approval.signature.scope='RS_APPROVALS'");
            statement.execute("SET dwp.approval.signature.actor='99'");
            try (var rows = statement.executeQuery("SELECT count(*) FROM apr_external_signature_requests "
                    + "WHERE signature_request_id='" + id + "'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isOne();
            }
            statement.execute("SET dwp.approval.signature.actor='100'");
            try (var rows = statement.executeQuery("SELECT count(*) FROM apr_external_signature_requests")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM apr_signature_native_commands")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private <T> T tx(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private <T> List<T> concurrently(Supplier<T> work) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<T> task = () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent start timed out");
            return work.get();
        };
        try {
            Future<T> first = executor.submit(task);
            Future<T> second = executor.submit(task);
            if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent workers did not start");
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new AssertionError(failure);
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertError(ErrorCode expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        Throwable thrown = catchThrowable(work);
        assertThat(thrown).isInstanceOf(BaseException.class);
        assertThat(((BaseException) thrown).getErrorCode()).isEqualTo(expected);
    }

    private static final class FixedAuthority implements SignatureProviderCurrentAuthority {
        private long userId;
        private UUID personId;

        private FixedAuthority(long userId, UUID personId) {
            actor(userId, personId);
        }

        void actor(long value, UUID person) {
            userId = value;
            personId = person;
        }

        @Override
        public Current require(SignatureProviderOperation operation) {
            var actor = new ApprovalRequestContext.Actor(userId, 42L, personId, "Signer",
                    Set.of("APPROVAL_OPERATOR"), Set.of("APP.APPROVALS:VIEW", operation.permission()));
            var decision = new ApprovalDecisionRevisionContext.Evidence("psr-" + SHA,
                    OffsetDateTime.now().plusMinutes(5), "signature-context", "signature-scope",
                    operation.routeContractKey(), "110");
            var scope = new ApprovalManagementScopeContext.Evidence("signature-scope", "RS_APPROVALS");
            var route = new ApprovalPilotPepRegistry.RouteAuthority(operation.routeContractKey(),
                    operation.routeKind(), "APPROVAL_SIGNATURE_NATIVE", operation.readOnly(),
                    Set.of("predicate.approval.signature-native.v1"),
                    "capability.approval.signature-native." + operation.name().toLowerCase(Locale.ROOT),
                    operation.highRisk() ? "STEPUP-MGMT-HIGH-V1" : "ROLLOUT-EXACT-V1",
                    "SOD-SIGNATURE", operation.highRisk(), null, null, null, null, null,
                    operation.permission(), null);
            return new Current(actor, decision, scope, route);
        }

        @Override
        public void unchanged(Current original) {
            if (original.actor().userId() != userId || !original.actor().personPublicId().equals(personId))
                throw SignatureProviderErrors.forbidden();
        }
    }

    private static final class TestRuntime implements SignatureProviderRuntime {
        private final RuntimeProvider provider;

        private TestRuntime(ProviderTarget target) {
            this(target, NOW.plusSeconds(3_600));
        }

        private TestRuntime(ProviderTarget target, Instant validUntil) {
            Instant observedAt = validUntil.isAfter(NOW) ? NOW : validUntil.minusSeconds(3_600);
            Check check = new Check("PROVIDER_ACCOUNT", ObservationState.PASS, List.of(), observedAt,
                    validUntil, UUID.randomUUID(), SHA);
            provider = new RuntimeProvider(target, Environment.PRODUCTION, true, true, true,
                    true, SHA, SHA, "SIGNED_WEBHOOK", "SERVER_SECRET_REFERENCE",
                    Readiness.VERIFIED_PRODUCTION, observedAt, List.of(check));
        }

        @Override public List<RuntimeProvider> current(long tenantId, String scope) {
            return List.of(provider);
        }
        @Override public List<ProbeResult> probe(long tenantId, String scope, ProbeInput input) {
            throw new AssertionError("Probe not expected");
        }
        @Override public Kms probeKms(long tenantId, String scope, KmsProbeInput input) {
            throw new AssertionError("KMS probe not expected");
        }
        @Override public Worm inspectWorm(long tenantId, String scope, WormInspectionInput input) {
            throw new AssertionError("WORM inspection not expected");
        }
        @Override public ExternalTransition handover(long tenantId, String scope,
                ExternalRequest current, String key) { throw new AssertionError("Handover not expected"); }
        @Override public ExternalTransition refresh(long tenantId, String scope,
                ExternalRequest current, String key) { throw new AssertionError("Refresh not expected"); }
        @Override public ExternalTransition cancel(long tenantId, String scope,
                ExternalRequest current, String key) { throw new AssertionError("Cancel not expected"); }
    }

    private static final class BlockingProbeRuntime implements SignatureProviderRuntime {
        private final TestRuntime delegate;
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private BlockingProbeRuntime(ProviderTarget target, CountDownLatch entered,
                CountDownLatch release) {
            this.delegate = new TestRuntime(target);
            this.entered = entered;
            this.release = release;
        }

        @Override public List<RuntimeProvider> current(long tenantId, String scope) {
            return delegate.current(tenantId, scope);
        }

        @Override public List<ProbeResult> probe(long tenantId, String scope, ProbeInput input) {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS))
                    throw new AssertionError("Probe release timed out");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return input.targets().stream().map(target -> new ProbeResult(target.providerId(), target,
                    ProbeOutcome.PASS, NOW, null, List.of(), List.of(new Check(
                    "PROVIDER_ACCOUNT", ObservationState.PASS, List.of(), NOW,
                    NOW.plusSeconds(3_600), UUID.randomUUID(), SHA)))).toList();
        }

        @Override public Kms probeKms(long tenantId, String scope, KmsProbeInput input) {
            throw new AssertionError("KMS probe not expected");
        }
        @Override public Worm inspectWorm(long tenantId, String scope, WormInspectionInput input) {
            throw new AssertionError("WORM inspection not expected");
        }
        @Override public ExternalTransition handover(long tenantId, String scope,
                ExternalRequest current, String key) { throw new AssertionError("Handover not expected"); }
        @Override public ExternalTransition refresh(long tenantId, String scope,
                ExternalRequest current, String key) { throw new AssertionError("Refresh not expected"); }
        @Override public ExternalTransition cancel(long tenantId, String scope,
                ExternalRequest current, String key) { throw new AssertionError("Cancel not expected"); }
    }
}
