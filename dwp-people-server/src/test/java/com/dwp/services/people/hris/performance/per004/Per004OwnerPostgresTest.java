package com.dwp.services.people.hris.performance.per004;

import com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class Per004OwnerPostgresTest {
    private static final long TENANT = 42;
    private static final String SCOPE = "c".repeat(64);
    private static final String FORM = "dwp.hris.performance.review-form.v1";
    private static final String SCALE = "dwp.hris.performance.rating-scale.v1";
    private static final String POLICY = "dwp.hris.performance.policy.v1";
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private Per004OwnerService service;
    private Per004ArtifactProtection protection;
    private final AtomicReference<Per004OwnerService.EvaluatedSample> sampleOverride = new AtomicReference<>();
    private boolean sampleUnavailable;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID subject = UUID.randomUUID();
    private final UUID configuration = UUID.randomUUID();

    @BeforeEach void setUp() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        jdbc.execute("CREATE SCHEMA hris_performance");
        com.dwp.services.people.hris.performance.support
                .PerformanceMigrationRuntimeRoleFixture.prepare(source);
        Flyway.configure(getClass().getClassLoader()).dataSource(source)
                .locations("classpath:db/performance-migration")
                .defaultSchema("hris_performance").schemas("hris_performance")
                .table("flyway_performance_schema_history").load().migrate();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        Map<UUID, byte[]> plaintext = new ConcurrentHashMap<>();
        protection = mock(Per004ArtifactProtection.class);
        when(protection.protect(eq(TENANT), any(UUID.class), anyString(), any(byte[].class)))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(1);
                    plaintext.put(id, invocation.getArgument(3));
                    return new Per004ArtifactProtection.Protected(new byte[29], UUID.randomUUID());
                });
        when(protection.reveal(eq(TENANT), any(UUID.class), anyString(), any(byte[].class), any(UUID.class)))
                .thenAnswer(invocation -> plaintext.get(invocation.getArgument(1)));
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("per014Fixture", (Per004OwnerService.TemplateProvenanceVerifier)
                (tenant, schema, template, digest) -> tenant == TENANT
                        && (FORM.equals(schema) || SCALE.equals(schema))
                        && digest.equals("d".repeat(64)));
        sampleUnavailable = false;
        sampleOverride.set(null);
        factory.addBean("sampleFixture", (Per004OwnerService.SampleScopeSimulator)
                (tuple, ref, payload) -> {
                    if (sampleUnavailable) throw new IllegalStateException("SAMPLE_UNAVAILABLE");
                    Per004OwnerService.EvaluatedSample override = sampleOverride.get();
                    return override == null ? new Per004OwnerService.EvaluatedSample(
                            tuple.tenantId(), tuple.configurationId(), ref, "e".repeat(64),
                            10, 3, "b".repeat(64)) : override;
                });
        service = new Per004OwnerService(jdbc, mapper, new Per004PayloadCodec(mapper),
                protection, factory.getBeanProvider(Per004OwnerService.TemplateProvenanceVerifier.class),
                factory.getBeanProvider(Per004OwnerService.SampleScopeSimulator.class));
    }

    @Test void exactPreparationReplayFinalizeValidationAndSimulationAreImmutable() throws Exception {
        OwnerReceipt form = prepare(FORM, formJson(), configuration);
        OwnerReceipt scale = prepare(SCALE, scaleJson(), configuration);
        OwnerReceipt finalForm = finalize(form, 1);
        OwnerReceipt finalScale = finalize(scale, 2);
        OwnerReceipt policy = prepare(POLICY, policyJson(form, scale), configuration);
        OwnerReceipt finalPolicy = finalize(policy, 3);
        assertThat(form.kind()).isEqualTo(ReceiptKind.PREPARATION);
        assertThat(finalForm.priorOwnerReceiptId()).isEqualTo(form.ownerReceiptId());
        assertThat(finalScale.priorOwnerReceiptId()).isEqualTo(scale.ownerReceiptId());
        assertThat(finalPolicy.tuple().version()).isEqualTo(3);
        OwnerReceipt validation = transaction.execute(status -> service.validate(
                new ValidateRequest(finalPolicy.tuple(), finalPolicy.ownerReceiptId())));
        OwnerReceipt validationReplay = transaction.execute(status -> service.validate(
                new ValidateRequest(finalPolicy.tuple(), finalPolicy.ownerReceiptId())));
        assertThat(validationReplay).isEqualTo(validation);
        OwnerReceipt simulation = transaction.execute(status -> service.simulate(
                new SimulateRequest(finalPolicy.tuple(), validation.ownerReceiptId(),
                        "fixture-scope", "e".repeat(64))));
        OwnerReceipt simulationReplay = transaction.execute(status -> service.simulate(
                new SimulateRequest(finalPolicy.tuple(), validation.ownerReceiptId(),
                        "fixture-scope", "e".repeat(64))));
        assertThat(simulation.kind()).isEqualTo(ReceiptKind.SIMULATION);
        assertThat(simulation.impactDigest()).matches("[a-f0-9]{64}");
        assertThat(simulationReplay).isEqualTo(simulation);
        assertThatThrownBy(() -> transaction.execute(status -> service.simulate(
                new SimulateRequest(finalPolicy.tuple(), validation.ownerReceiptId(),
                        "conflicting-scope", "e".repeat(64)))))
                .hasMessageContaining("PER004_BINDING_CONFLICT");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM hris_performance.prf_per004_owner_evidence_receipts
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM hris_performance.prf_command_receipts
                 WHERE command_type = 'PER004_OWNER_EVIDENCE'
                """, Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM hris_performance.prf_outbox_events
                 WHERE aggregate_type = 'PER004_OWNER_EVIDENCE'
                """, Integer.class)).isEqualTo(8);
    }

    @Test void tupleDigestAndPriorReceiptMismatchFailWithoutNewEvidence() throws Exception {
        OwnerReceipt form = prepare(FORM, formJson(), configuration);
        OwnerReceipt finalized = finalize(form, 1);
        OwnerTuple wrong = new OwnerTuple(TENANT, configuration, 1L, "PER", FORM, 1,
                form.tuple().payloadReference(), "f".repeat(64), SCOPE);
        assertThatThrownBy(() -> transaction.execute(status -> service.validate(
                new ValidateRequest(wrong, finalized.ownerReceiptId()))))
                .hasMessageContaining("PER004_BINDING_CONFLICT");
        assertThatThrownBy(() -> transaction.execute(status -> service.validate(
                new ValidateRequest(finalized.tuple(), UUID.randomUUID()))))
                .hasMessageContaining("PER004_BINDING_CONFLICT");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM hris_performance.prf_per004_owner_evidence_receipts
                """, Integer.class)).isZero();
    }

    @Test void simulationRejectsUntrustedSampleMismatchAndOwnerOutage() throws Exception {
        OwnerReceipt form = prepare(FORM, formJson(), configuration);
        OwnerReceipt finalized = finalize(form, 1);
        OwnerReceipt validation = transaction.execute(status -> service.validate(
                new ValidateRequest(finalized.tuple(), finalized.ownerReceiptId())));
        sampleOverride.set(new Per004OwnerService.EvaluatedSample(TENANT, configuration,
                "fixture-scope", "f".repeat(64), 10, 3, "b".repeat(64)));
        assertThatThrownBy(() -> transaction.execute(status -> service.simulate(
                new SimulateRequest(finalized.tuple(), validation.ownerReceiptId(),
                        "fixture-scope", "e".repeat(64)))))
                .hasMessageContaining("PER004_BINDING_CONFLICT");
        sampleOverride.set(null);
        sampleUnavailable = true;
        assertThatThrownBy(() -> transaction.execute(status -> service.simulate(
                new SimulateRequest(finalized.tuple(), validation.ownerReceiptId(),
                        "fixture-scope", "e".repeat(64)))))
                .hasMessageContaining("PER004_TRUST_UNAVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM hris_performance.prf_per004_owner_evidence_receipts
                 WHERE receipt_kind = 'SIMULATION'
                """, Integer.class)).isZero();
    }

    @Test void quarantineNeedsExactSignedFinalAbsenceAndDurableFence() throws Exception {
        PrepareRequest request = new PrepareRequest(TENANT, configuration, subject, FORM, 1, SCOPE,
                "per004-recovery-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(),
                mapper.readTree(formJson()));
        OwnerReceipt prepared = transaction.execute(status -> service.prepare(request));
        CreateIdentity identity = new CreateIdentity(TENANT, configuration, subject,
                "configuration.version.create", request.createIdempotencyKey(),
                request.createRequestDigest(), prepared.ownerReceiptId());
        AtomicReference<CreateLookupResponse> lookup = new AtomicReference<>();
        StaticListableBeanFactory sources = new StaticListableBeanFactory();
        sources.addBean("lookup", (Per004PreparationRecovery.AuthoritativeCreateLookupPort)
                exact -> lookup.get());
        sources.addBean("signature", (Per004PreparationRecovery.LookupEvidenceVerifier)
                response -> "signed-exact".equals(response.signedBindingEvidence()));
        Per004PreparationRecovery recovery = new Per004PreparationRecovery(jdbc,
                sources.getBeanProvider(Per004PreparationRecovery.AuthoritativeCreateLookupPort.class),
                sources.getBeanProvider(Per004PreparationRecovery.LookupEvidenceVerifier.class));
        lookup.set(new CreateLookupResponse(identity, LookupState.UNKNOWN, null, null,
                null, null, OffsetDateTime.now(ZoneOffset.UTC), null));
        assertThat(quarantine(recovery, identity)).isFalse();
        lookup.set(new CreateLookupResponse(identity, LookupState.BOUND, 1L, UUID.randomUUID(),
                null, "e".repeat(64), OffsetDateTime.now(ZoneOffset.UTC), "signed-exact"));
        assertThat(quarantine(recovery, identity)).isFalse();
        lookup.set(new CreateLookupResponse(identity, LookupState.ABSENT_FINAL, null, null,
                null, "e".repeat(64), OffsetDateTime.now(ZoneOffset.UTC), "signed-exact"));
        assertThat(quarantine(recovery, identity)).isFalse();
        lookup.set(new CreateLookupResponse(identity, LookupState.ABSENT_FINAL, null, null,
                UUID.randomUUID(), "e".repeat(64), OffsetDateTime.now(ZoneOffset.UTC), "forged"));
        assertThat(quarantine(recovery, identity)).isFalse();
        lookup.set(new CreateLookupResponse(identity, LookupState.ABSENT_FINAL, null, null,
                UUID.randomUUID(), "e".repeat(64), OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2),
                "signed-exact"));
        assertThat(quarantine(recovery, identity)).isFalse();
        lookup.set(new CreateLookupResponse(identity, LookupState.ABSENT_FINAL, null, null,
                UUID.randomUUID(), "e".repeat(64), OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2),
                "signed-exact"));
        assertThat(quarantine(recovery, identity)).isFalse();
        UUID fence = UUID.randomUUID();
        lookup.set(new CreateLookupResponse(identity, LookupState.ABSENT_FINAL, null, null,
                fence, "e".repeat(64), OffsetDateTime.now(ZoneOffset.UTC), "signed-exact"));
        assertThat(quarantine(recovery, identity)).isTrue();
        assertThat(quarantine(recovery, identity)).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM hris_performance.prf_per004_artifact_preparations
                 WHERE status = 'QUARANTINED' AND quarantine_fence_id = ?
                """, Integer.class, fence)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM hris_performance.prf_per004_configuration_version_bindings
                """, Integer.class)).isZero();
    }

    @Test void forcedRlsAndAppendOnlyBindingEvidenceRejectCrossTenantAndMutation() throws Exception {
        OwnerReceipt prepared = prepare(FORM, formJson(), configuration);
        OwnerReceipt finalized = finalize(prepared, 1);
        transaction.execute(status -> service.validate(
                new ValidateRequest(finalized.tuple(), finalized.ownerReceiptId())));
        String role = "per004_rls_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.execute("CREATE ROLE " + role);
        try {
            jdbc.execute("GRANT USAGE ON SCHEMA hris_performance TO " + role);
            for (String table : new String[]{"prf_per004_artifact_preparations",
                    "prf_per004_configuration_version_bindings", "prf_per004_owner_evidence_receipts"}) {
                jdbc.execute("GRANT SELECT, UPDATE ON hris_performance." + table + " TO " + role);
                assertThat(countAs(role, table, null)).isZero();
                assertThat(countAs(role, table, 43L)).isZero();
                assertThat(countAs(role, table, TENANT)).isEqualTo(1);
            }
        } finally {
            jdbc.execute("DROP OWNED BY " + role);
            jdbc.execute("DROP ROLE " + role);
        }
        assertThatThrownBy(() -> transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('dwp.tenant_id', '42', TRUE)", String.class);
            jdbc.update("""
                    UPDATE hris_performance.prf_per004_configuration_version_bindings
                       SET payload_digest = ? WHERE tenant_id = 42 AND configuration_id = ?
                    """, "e".repeat(64), configuration);
            return null;
        })).hasMessageContaining("PER_COMPENSATION_APPEND_ONLY");
        assertThatThrownBy(() -> transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('dwp.tenant_id', '42', TRUE)", String.class);
            jdbc.update("""
                    UPDATE hris_performance.prf_per004_owner_evidence_receipts
                       SET evidence_digest = ? WHERE tenant_id = 42 AND configuration_id = ?
                    """, "e".repeat(64), configuration);
            return null;
        })).hasMessageContaining("PER_COMPENSATION_APPEND_ONLY");
        assertThatThrownBy(() -> transaction.execute(status -> {
            jdbc.queryForObject("SELECT set_config('dwp.tenant_id', '42', TRUE)", String.class);
            jdbc.update("""
                    UPDATE hris_performance.prf_per004_artifact_preparations
                       SET status = 'QUARANTINED', quarantine_fence_id = ?,
                           quarantine_evidence_digest = ?, resolved_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = 42 AND configuration_id = ?
                    """, UUID.randomUUID(), "f".repeat(64), configuration);
            return null;
        })).hasMessageContaining("PER004_PREPARATION_IMMUTABLE_OR_ILLEGAL_TRANSITION");
    }

    @Test void samePlatformRowAndExactLocalBindingAreRequiredForCreate() throws Exception {
        OwnerReceipt form = prepare(FORM, formJson(), configuration);
        OwnerReceipt scale = prepare(SCALE, scaleJson(), configuration);
        finalize(form, 1);
        finalize(scale, 2);
        OwnerReceipt policy = prepare(POLICY, policyJson(form, scale), configuration);
        OwnerReceipt bound = finalize(policy, 3);
        String route = "route.hcm.configuration.configuration-version-create.action";
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        FeatureDecision feature = new FeatureDecision(new FeatureRequest(TENANT, subject, route,
                configuration, "PER", SCOPE, 7, UUID.randomUUID()), FEATURE, FEATURE_ROUTE,
                "ACTIVE", "ALLOWED", "feature-1", now.minusSeconds(1), now.plusSeconds(30));
        var context = new Per004WorkbenchProjection.TrustedContext(TENANT, subject, SCOPE,
                "scope-1", "auth-1", 7, UUID.randomUUID(), Map.of(
                    "POLICY_VERSION", Per004WorkbenchProjection.FieldDecision.VIEW,
                    "PERFORMANCE_POLICY", Per004WorkbenchProjection.FieldDecision.VIEW,
                    "EFFECTIVE_STATUS", Per004WorkbenchProjection.FieldDecision.VIEW),
                Set.of(route), Map.of(configuration, feature), true);
        OwnerProjection latest = new OwnerProjection(configuration, 3L, 11L,
                "NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE", POLICY, SCOPE, now, null,
                true, true, "performance.policy", null, 1,
                bound.tuple().payloadReference(), bound.tuple().payloadDigest());
        AtomicReference<OwnerProjection> platformRow = new AtomicReference<>(latest);
        AtomicReference<Boolean> platformUnavailable = new AtomicReference<>(false);
        StaticListableBeanFactory providers = new StaticListableBeanFactory();
        providers.addBean("scope", (Per004WorkbenchController.TrustedConfigurationScopePort)
                (trusted, limit, cursor) -> List.of(new ProjectionTarget(configuration, SCOPE)));
        providers.addBean("platform", (Per004WorkbenchController.PlatformOwnerProjectionPort)
                (tenant, request) -> {
                    if (platformUnavailable.get()) throw new IllegalStateException("PLATFORM_UNAVAILABLE");
                    return new OwnerProjectionResponse(request.asOf(), platformRow.get() == null
                            ? List.of() : List.of(platformRow.get()), "CURRENT", null);
                });
        var queries = new Per004WorkbenchQueries(jdbc, protection,
                new Per004PayloadCodec(mapper), mapper,
                providers.getBeanProvider(Per004WorkbenchController.PlatformOwnerProjectionPort.class),
                providers.getBeanProvider(Per004WorkbenchController.TrustedConfigurationScopePort.class));
        var result = transaction.execute(status -> queries.query(
                Per004WorkbenchProjection.Variant.PERFORMANCE, context, 100, null));
        assertThat(result.path("queryState").path("state").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("items").get(0).path("fieldGroups").size()).isEqualTo(3);
        assertThat(result.path("items").get(0).path("availableActions")).hasSize(1);
        assertThat(result.path("items").get(0).path("availableActions").get(0)
                .path("resolvedHref").asText()).isEqualTo(
                        "/api/platform/v1/admin/hris/configurations/" + configuration + "/versions");
        platformRow.set(new OwnerProjection(configuration, 3L, 11L,
                "PUBLISHED", POLICY, SCOPE, now, null, true, true,
                "performance.policy", null, 1,
                bound.tuple().payloadReference(), bound.tuple().payloadDigest()));
        var rawTerminal = transaction.execute(status -> queries.query(
                Per004WorkbenchProjection.Variant.PERFORMANCE, context, 100, null));
        assertThat(rawTerminal.path("items").get(0).path("availableActions")).isEmpty();
        platformRow.set(new OwnerProjection(configuration, 3L, 11L,
                "NO_MUTABLE_VERSION_AND_CREATE_ELIGIBLE", POLICY, SCOPE, now, null,
                true, true, "performance.policy", null, 1,
                bound.tuple().payloadReference(), "f".repeat(64)));
        var mismatch = transaction.execute(status -> queries.query(
                Per004WorkbenchProjection.Variant.PERFORMANCE, context, 100, null));
        assertThat(mismatch.path("queryState").path("state").asText()).isEqualTo("PARTIAL");
        assertThat(mismatch.path("items")).isEmpty();
        platformRow.set(null);
        var absent = transaction.execute(status -> queries.query(
                Per004WorkbenchProjection.Variant.PERFORMANCE, context, 100, null));
        assertThat(absent.path("queryState").path("state").asText()).isEqualTo("UNAVAILABLE");
        assertThat(absent.path("items")).isEmpty();
        platformUnavailable.set(true);
        var outage = transaction.execute(status -> queries.query(
                Per004WorkbenchProjection.Variant.PERFORMANCE, context, 100, null));
        assertThat(outage.path("queryState").path("state").asText()).isEqualTo("UNAVAILABLE");
        assertThat(outage.path("items")).isEmpty();
    }

    private int countAs(String role, String table, Long tenantId) throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement setRole = connection.prepareStatement("SET LOCAL ROLE " + role)) {
                setRole.execute();
            }
            if (tenantId != null) {
                try (PreparedStatement tenant = connection.prepareStatement(
                        "SELECT set_config('dwp.tenant_id', ?, TRUE)")) {
                    tenant.setString(1, tenantId.toString());
                    tenant.execute();
                }
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM hris_performance." + table)) {
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private boolean quarantine(Per004PreparationRecovery recovery, CreateIdentity identity) {
        return Boolean.TRUE.equals(transaction.execute(
                status -> recovery.quarantineIfFinallyAbsent(identity)));
    }

    private OwnerReceipt prepare(String schema, String payload, UUID config) throws Exception {
        PrepareRequest request = new PrepareRequest(TENANT, config, subject, schema, 1, SCOPE,
                "per004-key-" + UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(),
                mapper.readTree(payload));
        OwnerReceipt first = transaction.execute(status -> service.prepare(request));
        OwnerReceipt replay = transaction.execute(status -> service.prepare(request));
        assertThat(replay).isEqualTo(first);
        PrepareRequest conflict = new PrepareRequest(TENANT, config, subject, schema, 1, SCOPE,
                request.createIdempotencyKey(), "b".repeat(64), UUID.randomUUID(), request.ownerPayload());
        assertThatThrownBy(() -> transaction.execute(status -> service.prepare(conflict)))
                .hasMessageContaining("PER004_BINDING_CONFLICT");
        return first;
    }
    private OwnerReceipt finalize(OwnerReceipt prepared, long version) {
        OwnerTuple tuple = new OwnerTuple(TENANT, prepared.tuple().configurationId(), version,
                "PER", prepared.tuple().schemaKey(), 1, prepared.tuple().payloadReference(),
                prepared.tuple().payloadDigest(), SCOPE);
        FinalizeRequest request = new FinalizeRequest(tuple, prepared.ownerReceiptId(), UUID.randomUUID(), 0);
        OwnerReceipt first = transaction.execute(status -> service.finalizeBinding(request));
        OwnerReceipt replay = transaction.execute(status -> service.finalizeBinding(request));
        assertThat(replay).isEqualTo(first);
        FinalizeRequest conflict = new FinalizeRequest(tuple, prepared.ownerReceiptId(),
                UUID.randomUUID(), 0);
        assertThatThrownBy(() -> transaction.execute(status -> service.finalizeBinding(conflict)))
                .hasMessageContaining("PER004_BINDING_CONFLICT");
        return first;
    }
    private String formJson() {
        return """
                {"schemaVersion":1,"templateVersionId":"%s","templateDigest":"%s","title":"Review",
                 "sections":[{"key":"outcomes","title":"Outcomes","items":[
                   {"key":"impact","label":"Impact","answerKind":"TEXT","required":true}]}]}
                """.formatted(UUID.randomUUID(), "d".repeat(64));
    }
    private String scaleJson() {
        return """
                {"schemaVersion":1,"templateVersionId":"%s","templateDigest":"%s","title":"Rating",
                 "levels":[{"key":"low","label":"Low","score":1},{"key":"high","label":"High","score":5}]}
                """.formatted(UUID.randomUUID(), "d".repeat(64));
    }
    private String policyJson(OwnerReceipt form, OwnerReceipt scale) {
        return """
                {"schemaVersion":1,"form":{"artifactPublicId":"%s","payloadDigest":"%s"},
                 "ratingScale":{"artifactPublicId":"%s","payloadDigest":"%s"},
                 "minimumReviewers":2,"allowSelfReview":false}
                """.formatted(id(form), form.tuple().payloadDigest(), id(scale), scale.tuple().payloadDigest());
    }
    private static UUID id(OwnerReceipt receipt) {
        return UUID.fromString(receipt.tuple().payloadReference().substring(
                HrisReferenceConfigurationV1.ARTIFACT_REFERENCE_PREFIX.length()));
    }
}
