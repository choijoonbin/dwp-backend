package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.Per004TestFixtures.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class Per004OwnerEvidencePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static JdbcReferenceConfigurationRepository repository;
    static JdbcPer004OwnerEvidenceStore evidence;
    static PGSimpleDataSource source;
    static final AtomicInteger tenantSequence = new AtomicInteger(1000);

    @BeforeAll static void migrateUpgradeFrom331() {
        source = new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        migration("public", "331").baseline();
        migration("public", "331").migrate();
        assertThat(migration("public", "332").migrate().migrationsExecuted).isEqualTo(1);
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = new JdbcReferenceConfigurationRepository(jdbc, MAPPER);
        evidence = new JdbcPer004OwnerEvidenceStore(jdbc);
        jdbc.execute("CREATE ROLE per004_tenant_runtime NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO per004_tenant_runtime");
        jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO per004_tenant_runtime");
    }

    @Test void exactLeaseCleanInstallAndForceRlsPreserveTenantIsolation() {
        migration("per004_clean", "332").baseline();
        assertThat(migration("per004_clean", "332").migrate().migrationsExecuted).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_class WHERE relname IN ('hris_reference_configuration_owner_receipts',
                    'hris_reference_configuration_owner_outbox','hris_reference_configuration_create_fences',
                    'hris_reference_configuration_workload_replays') AND relrowsecurity AND relforcerowsecurity
                """, Integer.class)).isEqualTo(8); // both isolated schemas
        long tenant = tenantSequence.incrementAndGet();
        var created = create(tenant, UUID.randomUUID(), "create-rls");
        assertThat(run(() -> {
            jdbc.execute("SET LOCAL ROLE per004_tenant_runtime");
            jdbc.queryForObject("SELECT set_config('dwp.tenant_id', ?, true)", String.class, Long.toString(tenant + 1));
            return jdbc.queryForObject("SELECT count(*) FROM hris_reference_configuration_owner_receipts", Integer.class);
        })).isZero();
        assertThat(created.receipt.aggregateVersion()).isEqualTo(1);
    }

    @Test void acknowledgementIsAtomicCasWithExactlyOneImmutableHistoryAndStableReplay() {
        long tenant = tenantSequence.incrementAndGet(); UUID id = UUID.randomUUID();
        Created created = create(tenant, id, "create-finalize");
        long historyBefore = history(tenant, id);
        var request = run(() -> evidence.pendingFinalization(tenant, id, 1));
        var finalReceipt = owner(ReceiptKind.FINALIZATION, request.tuple(), UUID.randomUUID(), request.ownerPreparationReceiptId());
        var runtime = runtime(null);
        var stale = new FinalizeRequest(request.tuple(), request.ownerPreparationReceiptId(), request.platformCreateReceiptId(), 0);
        assertThatThrownBy(() -> run(() -> runtime.acknowledge(stale, finalReceipt))).isInstanceOf(RuntimeException.class);
        assertThat(history(tenant, id)).isEqualTo(historyBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hris_reference_configuration_owner_receipts WHERE tenant_id = ?", Integer.class, tenant)).isEqualTo(1);
        var ack = run(() -> runtime.acknowledge(request, finalReceipt));
        assertThat(ack.aggregateVersion()).isEqualTo(2);
        assertThat(history(tenant, id)).isEqualTo(historyBefore + 1);
        assertThat(run(() -> runtime.acknowledge(request, finalReceipt))).isEqualTo(ack);
        var offsetReceipt = new OwnerReceipt(finalReceipt.kind(), finalReceipt.tuple(), finalReceipt.ownerService(),
                finalReceipt.ownerReceiptId(), finalReceipt.priorOwnerReceiptId(), finalReceipt.evidenceDigest(), finalReceipt.outcome(),
                finalReceipt.occurredAt().withOffsetSameInstant(java.time.ZoneOffset.ofHours(9)),
                finalReceipt.revalidateAt().withOffsetSameInstant(java.time.ZoneOffset.ofHours(9)), null);
        assertThat(run(() -> runtime.acknowledge(request, offsetReceipt))).isEqualTo(ack);
        assertThat(history(tenant, id)).isEqualTo(historyBefore + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hris_reference_configuration_owner_receipts WHERE tenant_id = ?", Integer.class, tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM hris_reference_configuration_versions WHERE tenant_id = ?", String.class, tenant)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hris_reference_configuration_owner_outbox WHERE tenant_id = ? AND acknowledged_at IS NOT NULL", Integer.class, tenant)).isEqualTo(1);
        var conflict = owner(ReceiptKind.FINALIZATION, request.tuple(), UUID.randomUUID(), request.ownerPreparationReceiptId());
        assertThatThrownBy(() -> run(() -> runtime.acknowledge(request, conflict))).isInstanceOf(RuntimeException.class);
        assertThat(history(tenant, id)).isEqualTo(historyBefore + 1);
        for (String table : List.of("hris_reference_configuration_owner_receipts", "hris_reference_configuration_version_history")) {
            assertThatThrownBy(() -> run(() -> jdbc.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant)))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> run(() -> jdbc.update("UPDATE hris_reference_configuration_versions SET owner_finalization_receipt_id = NULL, owner_finalization_evidence_digest = NULL WHERE tenant_id = ?", tenant)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> run(() -> jdbc.update("UPDATE hris_reference_configuration_versions SET aggregate_version = aggregate_version + 1 WHERE tenant_id = ?", tenant)))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> run(() -> jdbc.update("UPDATE hris_reference_configuration_versions SET owner_scope_digest = ?, aggregate_version = aggregate_version + 1 WHERE tenant_id = ?", "f".repeat(64), tenant)))
                .hasStackTraceContaining("PER004_OWNER_SCOPE_IMMUTABLE");
        String snapshots = jdbc.queryForObject("SELECT string_agg(snapshot::text, '') FROM hris_reference_configuration_version_history WHERE tenant_id = ?", String.class, tenant);
        assertThat(snapshots).contains("owner_preparation_receipt_id", "owner_finalization_evidence_digest")
                .doesNotContain("syntheticSecret", "transit-only", "ownerPayload");
        assertThat(created.receipt.requestDigest()).hasSize(64);
    }

    @Test void validationAndSimulationUseOnlyExactOwnerReceiptsAndRequireFinalization() {
        long tenant = tenantSequence.incrementAndGet(); UUID id = UUID.randomUUID();
        Created created = create(tenant, id, "create-owner-chain");
        var calls = new AtomicInteger();
        var owner = new OwnerPort() {
            public OwnerReceipt prepare(PrepareRequest r) { throw new AssertionError(); }
            public OwnerReceipt finalizeBinding(FinalizeRequest r) { throw new AssertionError(); }
            public OwnerReceipt validate(ValidateRequest r) { calls.incrementAndGet(); return owner(ReceiptKind.VALIDATION, r.tuple(), UUID.randomUUID(), r.ownerFinalizationReceiptId()); }
            public OwnerReceipt simulate(SimulateRequest r) { calls.incrementAndGet(); return owner(ReceiptKind.SIMULATION, r.tuple(), UUID.randomUUID(), r.ownerValidationReceiptId()); }
        };
        var runtime = runtime(owner);
        var service = new ReferenceConfigurationService(repository, MAPPER, CLOCK, runtime);
        assertThatThrownBy(() -> run(() -> service.validate(access(tenant, 101), command("validate", "validate-before-finalize", 1L),
                id, 1, new ValidationCommand(SCOPE, 1)))).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasValue(0);
        var request = run(() -> evidence.pendingFinalization(tenant, id, 1));
        run(() -> runtime.acknowledge(request, owner(ReceiptKind.FINALIZATION, request.tuple(), UUID.randomUUID(), request.ownerPreparationReceiptId())));
        run(() -> service.validate(access(tenant, 101), command("validate", "validate-owner-chain", 2L), id, 1, new ValidationCommand(SCOPE, 1)));
        var validation = run(() -> evidence.receipt(tenant, id, 1, ReceiptKind.VALIDATION).orElseThrow());
        run(() -> service.simulate(access(tenant, 101), command("simulate", "simulate-owner-chain", 3L), id, 1,
                new SimulationCommand(validation.ownerReceiptId(), "ref_sample_1234567890", PAYLOAD)));
        var detail = run(() -> repository.findVersion(tenant, access(tenant, 101).scope(), id, 1).orElseThrow());
        assertThat(detail.aggregateVersion()).isEqualTo(4);
        assertThat(detail.state()).isEqualTo(LifecycleState.SIMULATED);
        assertThat(detail.validation().receiptId()).isEqualTo(validation.ownerReceiptId());
        assertThat(detail.validation().evidenceDigest()).isEqualTo(EVIDENCE);
        assertThat(calls).hasValue(2);
        var projection = run(() -> evidence.project(tenant, new OwnerProjectionRequest(NOW,
                List.of(new ProjectionTarget(id, SCOPE)))));
        assertThat(projection).singleElement().satisfies(p -> {
            assertThat(p.lifecycleState()).isEqualTo("SIMULATED"); assertThat(p.aggregateVersion()).isEqualTo(4);
            assertThat(p.key()).isEqualTo(SCHEMA); assertThat(p.schemaVersion()).isEqualTo(1);
            assertThat(p.payloadReference()).isEqualTo(PER_REFERENCE); assertThat(p.payloadDigest()).isEqualTo(PAYLOAD);
            assertThat(MAPPER.valueToTree(p).has("effectiveTo")).isTrue();
            assertThat(p.scopeDigest()).isEqualTo(SCOPE);
        });
    }

    @Test void oneAggregateRevisionCannotBindFinalizationAndValidationTogether() {
        long tenant = tenantSequence.incrementAndGet(); UUID id = UUID.randomUUID();
        var created = create(tenant, id, "one-binding-create");
        UUID finalized = UUID.randomUUID(), validated = UUID.randomUUID();
        assertThatThrownBy(() -> run(() -> {
            cloneReceipt(tenant, finalized, "FINALIZATION", created.identity.ownerPreparationReceiptId());
            cloneReceipt(tenant, validated, "VALIDATION", finalized);
            return jdbc.update("""
                    UPDATE hris_reference_configuration_versions
                       SET owner_finalization_receipt_id = ?, owner_finalization_evidence_digest = ?,
                           owner_validation_receipt_id = ?, owner_validation_evidence_digest = ?,
                           aggregate_version = aggregate_version + 1
                     WHERE tenant_id = ? AND configuration_id = ? AND version = 1
                    """, finalized, EVIDENCE, validated, EVIDENCE, tenant, id);
        })).hasStackTraceContaining("PER004_ONE_OWNER_BINDING_PER_REVISION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hris_reference_configuration_owner_receipts WHERE tenant_id = ?", Integer.class, tenant)).isEqualTo(1);
        assertThat(history(tenant, id)).isEqualTo(2);
    }

    @Test void terminalVersionRetainsMetadataAndAuthoritativelyEnablesCreate() {
        long tenant = tenantSequence.incrementAndGet(); UUID id = UUID.randomUUID();
        create(tenant, id, "terminal-target-create");
        var query = new OwnerProjectionRequest(NOW, List.of(new ProjectionTarget(id, SCOPE)));
        var working = run(() -> evidence.project(tenant, query)).getFirst();
        assertThat(working.lifecycleState()).isEqualTo("DRAFT"); assertThat(working.createEligible()).isFalse();
        run(() -> {
            jdbc.update("UPDATE hris_reference_configuration_versions SET lifecycle_state = 'REJECTED', aggregate_version = aggregate_version + 1 WHERE tenant_id = ? AND configuration_id = ?", tenant, id);
            jdbc.update("""
                    INSERT INTO hris_reference_configuration_version_history
                        (tenant_id,configuration_id,version,aggregate_version,scope_type,scope_id,captured_at,snapshot)
                    SELECT tenant_id,configuration_id,version,aggregate_version,scope_type,scope_id,?,to_jsonb(v)
                      FROM hris_reference_configuration_versions v WHERE tenant_id = ? AND configuration_id = ?
                    """, NOW, tenant, id);
            return null;
        });
        var terminal = run(() -> evidence.project(tenant, query)).getFirst();
        assertThat(terminal.lifecycleState()).isEqualTo("REJECTED"); assertThat(terminal.createEligible()).isTrue();
        assertThat(terminal.version()).isEqualTo(1); assertThat(terminal.aggregateVersion()).isEqualTo(2);
        assertThat(terminal.payloadReference()).isEqualTo(PER_REFERENCE); assertThat(terminal.payloadDigest()).isEqualTo(PAYLOAD);
        var a = access(tenant, 101);
        String route = "route.hcm.configuration.configuration-version-create.action";
        var feature = active(new FeatureRequest(tenant, a.subjectPublicId(), route, id, "PER", SCOPE, a.authorizationRevision(), UUID.randomUUID()));
        var action = com.dwp.platform.contracts.hris.referenceconfiguration.Per004TargetActionsV2.settings(terminal, NOW, NOW,
                java.util.Set.of("POLICY_VERSION", "PERFORMANCE_POLICY", "EFFECTIVE_STATUS"), java.util.Set.of(route),
                feature, a.subjectPublicId(), tenant, a.authorizationRevision(), true);
        assertThat(action).singleElement().satisfies(value -> {
            assertThat(value.targetVersion()).isNull(); assertThat(value.expectedObjectVersion()).isNull();
            assertThat(value.targetConfigurationId()).isEqualTo(id);
        });
    }

    @Test void unknownWrongScopeWrongSchemaAndOtherDomainNeverBecomeCreateTargets() {
        long tenant = tenantSequence.incrementAndGet(); UUID id = UUID.randomUUID();
        var query = new OwnerProjectionRequest(NOW, List.of(new ProjectionTarget(id, SCOPE)));
        assertThat(run(() -> evidence.project(tenant, query))).isEmpty();
        create(tenant, id, "negative-target-create");
        assertThat(run(() -> evidence.project(tenant, new OwnerProjectionRequest(NOW,
                List.of(new ProjectionTarget(id, "f".repeat(64))))))).isEmpty();
        assertThat(run(() -> evidence.project(tenant + 1, query))).isEmpty();
        for (String[] variant : List.of(new String[]{"SYS", SCHEMA}, new String[]{"PER", "legacy-other-per"})) {
            long other = tenantSequence.incrementAndGet(); UUID otherId = UUID.randomUUID();
            run(() -> repository.createVersion(other, access(other, 101).scope(), otherId, 101,
                    new CreateVersionCommand(Domain.valueOf(variant[0]), NOW, null, variant[1], 1,
                            new PayloadReference("ref_owner_1234567890", PAYLOAD)), NOW));
            assertThat(run(() -> evidence.project(other, new OwnerProjectionRequest(NOW,
                    List.of(new ProjectionTarget(otherId, SCOPE)))))).isEmpty();
        }
    }

    @Test void perWorkingVersionRaceAdmitsOneButLegacyParallelDraftsRemainAllowed() throws Exception {
        long tenant = tenantSequence.incrementAndGet(); UUID id = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = executor.invokeAll(List.<java.util.concurrent.Callable<Integer>>of(
                    () -> attemptCreate(tenant, id, "race-create-1"), () -> attemptCreate(tenant, id, "race-create-2")));
            assertThat(futures.get(0).get() + futures.get(1).get()).isEqualTo(1);
        }
        for (Domain domain : List.of(Domain.SYS, Domain.PER)) {
            UUID legacy = UUID.randomUUID();
            var command = new CreateVersionCommand(domain, NOW, null, "legacy-" + UUID.randomUUID(), 1,
                    new PayloadReference("ref_owner_1234567890", PAYLOAD));
            run(() -> repository.createVersion(tenant, access(tenant, 101).scope(), legacy, 101, command, NOW));
            run(() -> repository.createVersion(tenant, access(tenant, 101).scope(), legacy, 101, command, NOW));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM hris_reference_configuration_versions WHERE tenant_id = ? AND configuration_id = ?", Integer.class, tenant, legacy)).isEqualTo(2);
        }
    }

    @Test void exactBoundLookupAndDurableAbsentFencePreventFutureCreateAndUnknownNeverFences() {
        long tenant = tenantSequence.incrementAndGet(); UUID id = UUID.randomUUID();
        Created created = create(tenant, id, "lookup-create");
        var bound = run(() -> evidence.lookup(created.identity, NOW));
        assertThat(bound.state()).isEqualTo(LookupState.BOUND);
        assertThat(bound.platformCreateReceiptId()).isEqualTo(created.receipt.receiptId());
        var wrong = new CreateIdentity(tenant, id, created.identity.createSubjectPrincipalPublicId(),
                "configuration.version.create", "lookup-create", "f".repeat(64), created.identity.ownerPreparationReceiptId());
        assertThat(run(() -> evidence.lookup(wrong, NOW)).state()).isEqualTo(LookupState.UNKNOWN);
        var absent = new CreateIdentity(tenant, UUID.randomUUID(), UUID.randomUUID(), "configuration.version.create",
                "absent-create", EVIDENCE, UUID.randomUUID());
        var fenced = run(() -> evidence.lookup(absent, NOW));
        assertThat(fenced.state()).isEqualTo(LookupState.ABSENT_FINAL);
        assertThat(fenced.durableAdmissionFenceId()).isNotNull();
        assertThatThrownBy(() -> run(() -> { evidence.admitCreate(absent); return null; })).isInstanceOf(RuntimeException.class);
        assertThat(run(() -> evidence.lookup(absent, NOW)).durableAdmissionFenceId()).isEqualTo(fenced.durableAdmissionFenceId());
    }

    @Test void deliveryRetriesAreBoundedAndQuarantineLeavesCommittedDraftIntact() {
        long tenant = tenantSequence.incrementAndGet(); UUID id = UUID.randomUUID();
        create(tenant, id, "retry-create");
        for (int i = 0; i < 10; i++) run(() -> { evidence.failedDelivery(tenant, id, 1, NOW); return null; });
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM hris_reference_configuration_owner_outbox WHERE tenant_id = ?", Integer.class, tenant)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hris_reference_configuration_owner_outbox WHERE tenant_id = ? AND quarantined_at IS NOT NULL", Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM hris_reference_configuration_versions WHERE tenant_id = ?", String.class, tenant)).isEqualTo("DRAFT");
        assertThatThrownBy(() -> run(() -> evidence.pendingFinalization(tenant, id, 1))).isInstanceOf(RuntimeException.class);
    }

    @Test void unknownFromUnboundSignerAndSigningFailureCommitNoAdmissionFence() {
        long tenant = tenantSequence.incrementAndGet();
        var id = new CreateIdentity(tenant, UUID.randomUUID(), UUID.randomUUID(), "configuration.version.create",
                "signer-create-identity", EVIDENCE, UUID.randomUUID());
        var endpoint = com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.Endpoint.CREATE_LOOKUP;
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", endpoint.path(null));
        var claims = new com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.ProofClaims(
                endpoint.callerWorkloadId(), endpoint.ownerWorkloadId(), endpoint.callerWorkloadId(),
                NOW.toInstant(), NOW.toInstant(), NOW.plusSeconds(30).toInstant(), UUID.randomUUID(), UUID.randomUUID(),
                endpoint.bindingId(), endpoint.purpose(), "POST", endpoint.path(null), tenant, EVIDENCE,
                com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT, "test-key");
        request.setAttribute(Per004WorkloadSecurityFilter.class.getName() + ".admission",
                new com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationWorkloadV1.VerifiedAdmission(claims, EVIDENCE));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        var controller = new Per004InternalConfigurationController(evidence, beans.getBeanProvider(LookupEvidenceSigner.class));
        assertThat(run(() -> controller.lookup(request, new CreateLookupRequest(id))).state()).isEqualTo(LookupState.UNKNOWN);
        assertThat(fenceCount(tenant)).isZero();
        for (LookupEvidenceSigner signer : List.<LookupEvidenceSigner>of(lookup -> { throw new IllegalStateException("signer-offline"); }, lookup -> "")) {
            beans.addBean("signer", signer);
            var failing = new Per004InternalConfigurationController(evidence, beans.getBeanProvider(LookupEvidenceSigner.class));
            assertThatThrownBy(() -> run(() -> failing.lookup(request, new CreateLookupRequest(id)))).isInstanceOf(RuntimeException.class);
            assertThat(fenceCount(tenant)).isZero();
        }
        run(() -> { evidence.admitCreate(id); return null; });
        beans.addBean("signer", (LookupEvidenceSigner) lookup -> "test-signed-exact-fence-" + lookup.evidenceDigest());
        var signed = new Per004InternalConfigurationController(evidence, beans.getBeanProvider(LookupEvidenceSigner.class));
        assertThat(run(() -> signed.lookup(request, new CreateLookupRequest(id))).state()).isEqualTo(LookupState.ABSENT_FINAL);
        assertThat(fenceCount(tenant)).isEqualTo(1);
    }

    private static int attemptCreate(long tenant, UUID id, String key) {
        try { create(tenant, id, key); return 1; } catch (RuntimeException failure) { return 0; }
    }
    private static Created create(long tenant, UUID id, String key) {
        String createKey = "per004-" + key;
        var preparationId = UUID.randomUUID();
        OwnerPort owner = new OwnerPort() {
            public OwnerReceipt prepare(PrepareRequest r) { return owner(ReceiptKind.PREPARATION,
                    new OwnerTuple(r.tenantId(), r.configurationId(), null, "PER", r.schemaKey(), r.schemaVersion(),
                            PER_REFERENCE, PAYLOAD, r.scopeDigest()), preparationId, null); }
            public OwnerReceipt finalizeBinding(FinalizeRequest r) { throw new AssertionError(); }
            public OwnerReceipt validate(ValidateRequest r) { throw new AssertionError(); }
            public OwnerReceipt simulate(SimulateRequest r) { throw new AssertionError(); }
        };
        var service = new ReferenceConfigurationService(repository, MAPPER, CLOCK, runtime(owner));
        CommandReceipt receipt = run(() -> service.createVersion(access(tenant, 101), command("create", createKey, null), id, typed()));
        assertThat(run(() -> service.createVersion(access(tenant, 101), command("create", createKey, null), id, typed()))).isEqualTo(receipt);
        return new Created(receipt, new CreateIdentity(tenant, id, access(tenant, 101).subjectPublicId(),
                "configuration.version.create", createKey, receipt.requestDigest(), preparationId));
    }
    private static Per004ConfigurationRuntime runtime(OwnerPort owners) {
        return new Per004ConfigurationRuntime(Per004TestFixtures::active, owners, evidence, MAPPER, CLOCK);
    }
    private static Flyway migration(String schema, String target) {
        return Flyway.configure().dataSource(source).locations("filesystem:src/main/resources/db/migration")
                .schemas(schema).defaultSchema(schema).baselineVersion("324").target(target).load();
    }
    private static long history(long tenant, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM hris_reference_configuration_version_history WHERE tenant_id = ? AND configuration_id = ?", Long.class, tenant, id);
    }
    private static int fenceCount(long tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM hris_reference_configuration_create_fences WHERE tenant_id = ?", Integer.class, tenant);
    }
    private static void cloneReceipt(long tenant, UUID id, String kind, UUID prior) {
        jdbc.update("""
                INSERT INTO hris_reference_configuration_owner_receipts (
                    tenant_id, owner_receipt_id, configuration_id, configuration_version, kind, domain,
                    schema_key, schema_version, payload_reference, payload_digest, scope_digest,
                    owner_service, prior_owner_receipt_id, evidence_digest, occurred_at, revalidate_at, impact_digest)
                SELECT tenant_id, ?, configuration_id, configuration_version, ?, domain,
                    schema_key, schema_version, payload_reference, payload_digest, scope_digest,
                    owner_service, owner_receipt_id, evidence_digest, occurred_at, revalidate_at, NULL
                  FROM hris_reference_configuration_owner_receipts WHERE tenant_id = ? AND owner_receipt_id = ?
                """, id, kind, tenant, prior);
    }
    private static <T> T run(Supplier<T> action) { return tx.execute(ignored -> action.get()); }
    private record Created(CommandReceipt receipt, CreateIdentity identity) { }
}
