package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.CreateDefinitionCommand;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.CreateScheduleCommand;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.CreateVersionCommand;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.LifecycleCommand;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.LeaseGrant;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedCompensation;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedOriginal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.RuntimeRun;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationOwnerAuthorityFilter.Profile;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.RunRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.ScheduleRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.VersionRecord;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.SignedArtifactVerifierPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.RunState.CANCELLED;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.RunState.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Executable positive and negative evidence for SYS-GOLD-019/049/050/053. */
@Testcontainers(disabledWithoutDocker = true)
class AutomationGoldenLifecycleEvidenceTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-21T00:00:00Z");
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String E = "e".repeat(64);
    private static final String F = "f".repeat(64);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static ObjectMapper json;
    private static JdbcAutomationRepository repository;

    @BeforeAll
    static void migratePlatformToSys006() {
        PGSimpleDataSource source = dataSource();
        Flyway flyway = Flyway.configure()
                .dataSource(source)
                .schemas("public")
                .defaultSchema("public")
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("333")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        source.setCurrentSchema("public");
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        json = new ObjectMapper().findAndRegisterModules();
        repository = new JdbcAutomationRepository(jdbc, json);
    }

    @Test
    void sysGold019UnresolvedSideEffectCancelRequiresReconciliationAndCannotForceTerminal() {
        long tenantId = 749019;
        RunFixture fixture = seedLeasedRun(tenantId, "gold019", true);
        OffsetDateTime now = repository.databaseNow();

        inTransaction(() -> {
            RuntimeRun runtime = repository.lockRuntimeRun(
                    tenantId, fixture.runPublicId());
            LeaseGrant worker = worker(runtime);
            PreparedOriginal prepared = repository.prepareOriginal(
                    runtime, worker, A, now);
            assertThat(prepared.items()).hasSize(1);

            RunRecord requested = repository.cancelRun(
                    repository.lockRun(tenantId, fixture.runPublicId()),
                    "unresolved side effect requires evidence", now.plusSeconds(1));

            assertThat(requested.state()).isEqualTo(RUNNING);
            assertThat(requested.state()).isNotEqualTo(CANCELLED);
            assertThat(requested.reconciliationRequiredReason())
                    .isEqualTo("CANCEL_EFFECT_EVIDENCE_REQUIRED");
            assertThat(jdbc.queryForMap("""
                    SELECT lifecycle_state, effect_state, compensation_state
                      FROM sys_automation_run_items
                     WHERE tenant_id = ? AND run_id = ?
                    """, tenantId, fixture.runId()))
                    .containsEntry("lifecycle_state", "RUNNING")
                    .containsEntry("effect_state", "PREPARED")
                    .containsEntry("compensation_state", "NOT_REQUIRED");

            RuntimeRun awaitingEvidence = repository.lockRuntimeRun(
                    tenantId, fixture.runPublicId());
            UUID reconcilerToken = uuid("gold019-reconciler");
            LeaseGrant reconciler = repository.acquireReconcilerLease(
                    awaitingEvidence, reconcilerToken, now.plusMinutes(5),
                    now.plusSeconds(2));
            RuntimeRun fenced = repository.lockRuntimeRun(
                    tenantId, fixture.runPublicId());
            RuntimeRun reconciled = repository.reconcileOriginal(
                    fenced, reconciler, fixture.itemPublicId(),
                    prepared.items().getFirst().effectIdempotencyKey(),
                    prepared.items().getFirst().effectRequestDigest(),
                    new AutomationRuntimePorts.ReconciliationEvidence(
                            "NO_EFFECT_CONFIRMED", null, E,
                            now.plusSeconds(2), now.plusMinutes(5)),
                    now.plusSeconds(2));
            RuntimeRun terminal = repository.appendCheckpointAndRecalculate(
                    reconciled, reconciler, E, E, now.plusSeconds(3));

            assertThat(terminal.run().state()).isEqualTo(CANCELLED);
            assertThat(jdbc.queryForMap("""
                    SELECT lifecycle_state, effect_state
                      FROM sys_automation_run_items
                     WHERE tenant_id = ? AND run_id = ?
                    """, tenantId, fixture.runId()))
                    .containsEntry("lifecycle_state", "CANCELLED")
                    .containsEntry("effect_state", "NO_EFFECT_CONFIRMED");
            return null;
        });
    }

    @Test
    void sysGold049DefinitionVersionLifecycleAcceptsOnlySignedCurrentCompatibleFourEyesEvidence() {
        long tenantId = 749049;
        UUID author = uuid("gold049-author");
        UUID checker = uuid("gold049-checker");
        SignedManifest signed = signedManifest();
        SignedArtifactVerifierPort verifier = acceptingVerifier(signed);
        AutomationService service = service(
                identity -> Optional.of(signed), verifier);

        var created = inTransaction(() -> service.createDefinition(
                authority(tenantId, author, null, null, "gold049-definition"),
                new CreateDefinitionCommand(
                        "gold.lifecycle.definition", "Golden lifecycle", null,
                        AutomationDtos.OwnerDomain.HRM)));
        UUID definitionPublicId = idAtEnd(created.resultRef());
        CreateVersionCommand command = versionCommand();

        inTransaction(() -> service.createVersion(
                authority(tenantId, author, 1L, null, "gold049-v1-create"),
                definitionPublicId, command));
        inTransaction(() -> service.transitionVersion(
                authority(tenantId, author, 1L, null, "gold049-v1-validate"),
                definitionPublicId, 1, lifecycle(A), "VALIDATE"));
        inTransaction(() -> service.transitionVersion(
                authority(tenantId, author, 2L, null, "gold049-v1-submit"),
                definitionPublicId, 1, lifecycle(B), "SUBMIT"));
        inTransaction(() -> service.transitionVersion(
                authority(tenantId, checker, 3L, F, "gold049-v1-approve"),
                definitionPublicId, 1, lifecycle(C), "APPROVE"));
        inTransaction(() -> service.transitionVersion(
                authority(tenantId, checker, 4L, F, "gold049-v1-activate"),
                definitionPublicId, 1, lifecycle(D), "ACTIVATE"));

        VersionRecord active = inTransaction(() -> repository.lockVersion(
                tenantId, definitionPublicId, 1));
        DefinitionRecord activeDefinition = inTransaction(() ->
                repository.lockDefinition(tenantId, definitionPublicId));
        assertThat(active.state()).isEqualTo(AutomationDtos.VersionState.ACTIVE);
        assertThat(active.approvedByActorRef()).isEqualTo(checker);
        assertThat(active.compatibilityBaselineDigest()).isEqualTo(E);
        assertThat(activeDefinition.lifecycleState()).isEqualTo("ACTIVE");
        assertThat(activeDefinition.activeVersionNumber()).isEqualTo(1L);

        int versionCount = versionCount(tenantId, definitionPublicId);
        SignedArtifactVerifierPort unsignedVerifier = mock(
                SignedArtifactVerifierPort.class);
        when(unsignedVerifier.verifyDraft(any())).thenThrow(
                new IllegalStateException("AUTOMATION_SIGNATURE_REQUIRED"));
        AutomationService unsignedService = service(
                identity -> Optional.of(signed), unsignedVerifier);
        assertThatThrownBy(() -> inTransaction(() -> unsignedService.createVersion(
                authority(tenantId, author, 2L, null, "gold049-unsigned"),
                definitionPublicId, command)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("AUTOMATION_SIGNATURE_REQUIRED");
        assertThat(versionCount(tenantId, definitionPublicId)).isEqualTo(versionCount);

        SignedArtifactVerifierPort incompatibleVerifier = mock(
                SignedArtifactVerifierPort.class);
        when(incompatibleVerifier.verifyDraft(any())).thenThrow(
                new IllegalStateException("AUTOMATION_HANDLER_CONTRACT_INCOMPATIBLE"));
        AutomationService incompatibleService = service(
                identity -> Optional.of(signed), incompatibleVerifier);
        assertThatThrownBy(() -> inTransaction(() -> incompatibleService.createVersion(
                authority(tenantId, author, 2L, null, "gold049-incompatible"),
                definitionPublicId, command)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("AUTOMATION_HANDLER_CONTRACT_INCOMPATIBLE");
        assertThat(versionCount(tenantId, definitionPublicId)).isEqualTo(versionCount);

        AutomationService unknownHandlerService = service(
                identity -> Optional.empty(), verifier);
        inTransaction(() -> unknownHandlerService.createVersion(
                authority(tenantId, author, 2L, null, "gold049-unknown-create"),
                definitionPublicId, command));
        assertThatThrownBy(() -> inTransaction(() ->
                unknownHandlerService.transitionVersion(
                        authority(tenantId, author, 1L, null,
                                "gold049-unknown-validate"),
                        definitionPublicId, 2, lifecycle(A), "VALIDATE")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "AUTOMATION_HANDLER_MANIFEST_NOT_CURRENT_ACTIVE");
        assertThat(inTransaction(() -> repository.lockVersion(
                tenantId, definitionPublicId, 2)).state())
                .isEqualTo(AutomationDtos.VersionState.DRAFT);

        inTransaction(() -> service.createVersion(
                authority(tenantId, author, 2L, null, "gold049-v3-create"),
                definitionPublicId, command));
        inTransaction(() -> service.transitionVersion(
                authority(tenantId, author, 1L, null, "gold049-v3-validate"),
                definitionPublicId, 3, lifecycle(A), "VALIDATE"));
        inTransaction(() -> service.transitionVersion(
                authority(tenantId, author, 2L, null, "gold049-v3-submit"),
                definitionPublicId, 3, lifecycle(B), "SUBMIT"));
        assertThatThrownBy(() -> inTransaction(() -> service.transitionVersion(
                authority(tenantId, author, 3L, F, "gold049-self-approve"),
                definitionPublicId, 3, lifecycle(C), "APPROVE")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("AUTOMATION_MAKER_CHECKER_REQUIRED");
        assertThat(inTransaction(() -> repository.lockVersion(
                tenantId, definitionPublicId, 3)).state())
                .isEqualTo(AutomationDtos.VersionState.PENDING_APPROVAL);
    }

    @Test
    void sysGold050ScheduleLifecyclePinsVersionTimezoneConcurrencyAndInputDigest() {
        long tenantId = 749050;
        UUID author = uuid("gold050-author");
        UUID checker = uuid("gold050-checker");
        ActiveDefinitionFixture fixture = seedActiveDefinition(
                tenantId, "gold050", author, checker);
        SignedManifest signed = signedManifest();
        SignedArtifactVerifierPort verifier = acceptingVerifier(signed);
        AutomationService service = service(identity -> Optional.of(signed), verifier);
        AutomationRuntimeService runtime = new AutomationRuntimeService(
                repository, repository, identity -> Optional.of(signed), verifier,
                List.of(), List.of(), json);
        String tzdb = ZoneRulesProvider.getVersions("Asia/Seoul").lastKey();
        CreateScheduleCommand create = new CreateScheduleCommand(
                1, "0 0 9 * * MON-FRI", "Asia/Seoul", tzdb,
                "template:gold050", D,
                "tenant:{tenantId}:definition:{definitionPublicId}:scope:{scopeType}",
                E);

        var created = inTransaction(() -> service.createSchedule(
                authority(tenantId, author, 1L, null, "gold050-create"),
                fixture.definitionPublicId(), create));
        UUID schedulePublicId = idAtEnd(created.resultRef());

        inTransaction(() -> service.transitionSchedule(
                authority(tenantId, author, 1L, null, "gold050-submit"),
                schedulePublicId, lifecycle(A), "SUBMIT"));
        assertThatThrownBy(() -> inTransaction(() -> service.transitionSchedule(
                authority(tenantId, author, 2L, F, "gold050-self-approve"),
                schedulePublicId, lifecycle(B), "APPROVE")))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("AUTOMATION_MAKER_CHECKER_REQUIRED");
        inTransaction(() -> service.transitionSchedule(
                authority(tenantId, checker, 2L, F, "gold050-approve"),
                schedulePublicId, lifecycle(B), "APPROVE"));
        ScheduleRecord approved = inTransaction(() ->
                repository.lockSchedule(tenantId, schedulePublicId));
        assertThat(approved.state())
                .isEqualTo(AutomationDtos.ScheduleState.APPROVED);
        assertScheduleFireDenied(runtime, schedulerAuthority(
                        tenantId, checker, "gold050-preactive"),
                new AutomationRuntimeService.FireRequest(
                        schedulePublicId, approved.objectRevision(), 0,
                        NOW.plusHours(1), NOW, "TENANT", null,
                        create.inputTemplateRef(), D, 1),
                "AUTOMATION_SCHEDULE_FIRE_CAS_CONFLICT");
        assertResourceConflict(() -> inTransaction(() -> service.transitionSchedule(
                authority(tenantId, checker, 2L, F, "gold050-stale-activate"),
                schedulePublicId, lifecycle(C), "ACTIVATE")),
                "AUTOMATION_OBJECT_REVISION_CONFLICT");
        inTransaction(() -> service.transitionSchedule(
                authority(tenantId, checker, 3L, F, "gold050-activate"),
                schedulePublicId, lifecycle(C), "ACTIVATE"));

        ScheduleRecord active = inTransaction(() ->
                repository.lockSchedule(tenantId, schedulePublicId));
        assertThat(active.state()).isEqualTo(AutomationDtos.ScheduleState.ACTIVE);
        assertThat(active.pinnedVersionNumber()).isEqualTo(1L);
        assertThat(active.timezone()).isEqualTo("Asia/Seoul");
        assertThat(active.timezoneDatabaseRevision()).isEqualTo(tzdb);
        Map<String, Object> persisted = jdbc.queryForMap("""
                SELECT input_template_ref, input_template_digest,
                       concurrency_key_template, schedule_policy_digest
                  FROM sys_automation_schedules
                 WHERE tenant_id = ? AND public_id = ?
                """, tenantId, schedulePublicId);
        assertThat(persisted)
                .containsEntry("input_template_ref", "template:gold050")
                .containsEntry("input_template_digest", D)
                .containsEntry("concurrency_key_template",
                        "tenant:{tenantId}:definition:{definitionPublicId}:scope:{scopeType}")
                .containsEntry("schedule_policy_digest", E);

        AutomationRuntimeService.FireRequest mismatchedInput = fireRequest(
                active, create.inputTemplateRef(), F, 0);
        assertScheduleFireDenied(runtime, schedulerAuthority(
                        tenantId, checker, "gold050-input-mismatch"),
                mismatchedInput, "AUTOMATION_SCHEDULE_INPUT_DIGEST_MISMATCH");

        AutomationRuntimeService.FireRequest mismatchedReference = fireRequest(
                active, "template:gold050-substituted", D, 0);
        assertScheduleFireDenied(runtime, schedulerAuthority(
                        tenantId, checker, "gold050-reference-mismatch"),
                mismatchedReference,
                "AUTOMATION_SCHEDULE_INPUT_REFERENCE_MISMATCH");

        AutomationRuntimeService.FireRequest validFire = fireRequest(
                active, create.inputTemplateRef(), D, 0);
        var fired = inTransaction(() -> runtime.fireSchedule(
                schedulerAuthority(tenantId, checker, "gold050-fire"), validFire));
        assertThat(fired.skippedMissed()).isFalse();
        String expectedConcurrency = AutomationDomainPolicy.renderConcurrencyKey(
                create.concurrencyKeyTemplate(), tenantId,
                fixture.definitionPublicId(), "TENANT", null);
        assertThat(jdbc.queryForMap("""
                SELECT r.input_ref, r.input_digest, r.concurrency_key_digest,
                       r.version_number, s.timezone, s.timezone_database_revision,
                       s.fire_cursor_revision
                  FROM sys_automation_runs r
                  JOIN sys_automation_schedules s
                    ON s.tenant_id = r.tenant_id AND s.schedule_id = r.schedule_id
                 WHERE r.tenant_id = ? AND r.schedule_fire_key = ?
                """, tenantId, fired.fireKey()))
                .containsEntry("input_ref", "template:gold050")
                .containsEntry("input_digest", D)
                .containsEntry("concurrency_key_digest", expectedConcurrency)
                .containsEntry("version_number", 1L)
                .containsEntry("timezone", "Asia/Seoul")
                .containsEntry("timezone_database_revision", tzdb)
                .containsEntry("fire_cursor_revision", 1L);

        ScheduleRecord afterFire = inTransaction(() ->
                repository.lockSchedule(tenantId, schedulePublicId));
        assertScheduleFireDenied(runtime, schedulerAuthority(
                        tenantId, checker, "gold050-stale-fire"),
                fireRequest(afterFire, create.inputTemplateRef(), D, 0),
                "AUTOMATION_SCHEDULE_FIRE_CAS_CONFLICT");

        inTransaction(() -> service.transitionSchedule(
                authority(tenantId, checker, 4L, F, "gold050-suspend"),
                schedulePublicId, lifecycle(D), "SUSPEND"));
        ScheduleRecord suspended = inTransaction(() ->
                repository.lockSchedule(tenantId, schedulePublicId));
        assertThat(suspended.state())
                .isEqualTo(AutomationDtos.ScheduleState.SUSPENDED);
        assertScheduleFireDenied(runtime, schedulerAuthority(
                        tenantId, checker, "gold050-suspended-fire"),
                fireRequest(suspended, create.inputTemplateRef(), D, 1),
                "AUTOMATION_SCHEDULE_FIRE_CAS_CONFLICT");
    }

    @Test
    void sysGold053PreEffectCancelAndPostEffectCompensationAreAuditableAndDeduplicated() {
        RunFixture preEffect = seedLeasedRun(749053, "gold053-pre", true);
        OffsetDateTime now = repository.databaseNow();
        RunRecord preCancelled = inTransaction(() -> repository.cancelRun(
                repository.lockRun(749053, preEffect.runPublicId()),
                "cancel before external effect", now));
        assertThat(preCancelled.state()).isEqualTo(CANCELLED);
        assertThat(jdbc.queryForMap("""
                SELECT lifecycle_state, effect_state, compensation_state
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                """, 749053, preEffect.runId()))
                .containsEntry("lifecycle_state", "CANCELLED")
                .containsEntry("effect_state", "NOT_STARTED")
                .containsEntry("compensation_state", "NOT_REQUIRED");

        long postTenant = 749054;
        RunFixture postEffect = seedLeasedRun(postTenant, "gold053-post", true);
        inTransaction(() -> {
            RuntimeRun runtime = repository.lockRuntimeRun(
                    postTenant, postEffect.runPublicId());
            LeaseGrant worker = worker(runtime);
            PreparedOriginal prepared = repository.prepareOriginal(
                    runtime, worker, A, now.plusSeconds(1));
            var item = prepared.items().getFirst();
            AutomationRunItemReceiptV1 committed = new AutomationRunItemReceiptV1(
                    postEffect.runPublicId(), runtime.run().objectRevision(),
                    item.itemPublicId(), item.itemSequence(), item.subjectRef(),
                    "SUCCEEDED", runtime.attemptNumber(), item.acceptedAt(),
                    item.acceptedAt().plusSeconds(1), "result:gold053", B,
                    null, false, null, runtime.correlationId());
            repository.finalizeOriginal(
                    runtime, worker, prepared, List.of(committed),
                    now.plusSeconds(2));
            return null;
        });

        RunRecord cancellationRequested = inTransaction(() -> repository.cancelRun(
                repository.lockRun(postTenant, postEffect.runPublicId()),
                "compensate committed effect", now.plusSeconds(3)));
        assertThat(cancellationRequested.state()).isEqualTo(RUNNING);
        assertThat(cancellationRequested.reconciliationRequiredReason())
                .isEqualTo("CANCEL_COMPENSATION_REQUIRED");
        assertThat(jdbc.queryForMap("""
                SELECT effect_state, compensation_state
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                """, postTenant, postEffect.runId()))
                .containsEntry("effect_state", "COMMITTED")
                .containsEntry("compensation_state", "PENDING");

        inTransaction(() -> {
            RuntimeRun awaitingCompensation = repository.lockRuntimeRun(
                    postTenant, postEffect.runPublicId());
            LeaseGrant reconciler = repository.acquireReconcilerLease(
                    awaitingCompensation, uuid("gold053-post-reconciler"),
                    now.plusMinutes(5), now.plusSeconds(4));
            RuntimeRun fenced = repository.lockRuntimeRun(
                    postTenant, postEffect.runPublicId());
            PreparedCompensation prepared = repository.prepareCompensation(
                    fenced, reconciler, postEffect.itemPublicId(),
                    "gold053-compensation-key", C, now.plusSeconds(5));

            assertThat(prepared.originalEffectReceiptDigest()).matches("^[a-f0-9]{64}$");
            assertThatThrownBy(() -> repository.prepareCompensation(
                    fenced, reconciler, postEffect.itemPublicId(),
                    "gold053-duplicate-compensation-key", D,
                    now.plusSeconds(5)))
                    .isExactlyInstanceOf(BaseException.class)
                    .satisfies(failure -> assertThat(
                            ((BaseException) failure).getErrorCode())
                            .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                    .hasMessage("AUTOMATION_COMPENSATION_PREPARE_CONFLICT");

            RuntimeRun compensated = repository.finalizeCompensation(
                    fenced, reconciler, prepared, "COMMITTED", F, null,
                    now.plusSeconds(6));
            assertThatThrownBy(() -> repository.finalizeCompensation(
                    compensated, reconciler, prepared, "COMMITTED", F, null,
                    now.plusSeconds(7)))
                    .isExactlyInstanceOf(BaseException.class)
                    .satisfies(failure -> assertThat(
                            ((BaseException) failure).getErrorCode())
                            .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                    .hasMessage("AUTOMATION_COMPENSATION_FINALIZE_CAS_CONFLICT");

            RuntimeRun terminal = repository.appendCheckpointAndRecalculate(
                    compensated, reconciler, F, F, now.plusSeconds(8));
            assertThat(terminal.run().state()).isEqualTo(CANCELLED);
            return null;
        });

        assertThat(jdbc.queryForMap("""
                SELECT compensation_state, compensation_effect_state,
                       compensation_receipt_digest,
                       compensation_resolution_mode
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                """, postTenant, postEffect.runId()))
                .containsEntry("compensation_state", "SUCCEEDED")
                .containsEntry("compensation_effect_state", "COMMITTED")
                .containsEntry("compensation_receipt_digest", F)
                .containsEntry("compensation_resolution_mode", "DISPATCH_FINALIZE");
    }

    private static void assertScheduleFireDenied(
            AutomationRuntimeService runtime,
            AutomationRuntimeService.SchedulerAuthority authority,
            AutomationRuntimeService.FireRequest request,
            String expectedMessage) {
        assertThatThrownBy(() -> inTransaction(() ->
                runtime.fireSchedule(authority, request)))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage);
    }

    private static void assertResourceConflict(
            Runnable action,
            String expectedMessage) {
        assertThatThrownBy(action::run)
                .isExactlyInstanceOf(BaseException.class)
                .satisfies(failure -> {
                    BaseException conflict = (BaseException) failure;
                    assertThat(conflict.getErrorCode())
                            .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(conflict.getErrorCode().getHttpStatus().value())
                            .isEqualTo(409);
                })
                .hasMessage(expectedMessage);
    }

    private static AutomationRuntimeService.FireRequest fireRequest(
            ScheduleRecord schedule,
            String inputReference,
            String inputDigest,
            long expectedFireCursorRevision) {
        return new AutomationRuntimeService.FireRequest(
                schedule.publicId(), schedule.objectRevision(),
                expectedFireCursorRevision, schedule.nextFireAt(),
                schedule.nextFireAt().minusSeconds(1), "TENANT", null,
                inputReference, inputDigest, 1);
    }

    private static AutomationService service(
            AutomationHandlerRegistryPort registry,
            SignedArtifactVerifierPort verifier) {
        return new AutomationService(
                repository, registry, verifier,
                new AutomationCursorCodec(
                        json, "sys006-golden-lifecycle-cursor-secret-32"),
                json, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    private static SignedArtifactVerifierPort acceptingVerifier(
            SignedManifest signed) {
        SignedArtifactVerifierPort verifier = mock(SignedArtifactVerifierPort.class);
        VerifiedArtifact verified = verified(signed);
        when(verifier.verifyDraft(any())).thenReturn(verified);
        when(verifier.revalidate(any(), any(), any(), any())).thenReturn(verified);
        return verifier;
    }

    private static VerifiedArtifact verified(SignedManifest signed) {
        return new VerifiedArtifact(
                signed, "trusted-keys-golden-r1", D,
                "compatibility-golden-v1", E, F, NOW.plusDays(1));
    }

    private static SignedManifest signedManifest() {
        AutomationHandlerManifestV1 manifest = new AutomationHandlerManifestV1(
                "hris.hrm.test", "dwp-hrm-server", "AutomationInput.v1",
                "schema:input:v1", "schema:receipt:v1", F,
                List.of("TENANT"), "FAILED_ITEMS_ONLY", 3, "ACTIVE");
        return new SignedManifest(
                manifest, "golden-signing-key", A, B, C, 1);
    }

    private static CreateVersionCommand versionCommand() {
        return new CreateVersionCommand(
                json.createObjectNode().put("kind", "golden"), A,
                json.createObjectNode().put("retry", "FAILED_ITEMS_ONLY"), B,
                "tenant:{tenantId}:definition:{definitionPublicId}:scope:{scopeType}",
                "compensation:golden", C, "dwp-hrm-server", "hris.hrm.test",
                "AutomationInput.v1", 1, D, "golden-signing-key",
                "golden-signature", F, "schema:input:v1", A,
                "schema:receipt:v1", B, List.of("TENANT"),
                AutomationDtos.RetryMode.FAILED_ITEMS_ONLY, 3);
    }

    private static LifecycleCommand lifecycle(String digest) {
        return new LifecycleCommand(digest, "golden lifecycle evidence");
    }

    private static Authority authority(
            long tenantId,
            UUID subject,
            Long expectedRevision,
            String stepUp,
            String idempotencyKey) {
        return new Authority(
                tenantId, tenantId, subject,
                Set.of(new Profile(
                        "CONFIG.HRIS_POLICY:UPDATE", "HRIS_CONFIG_AUTHOR",
                        "HRIS_DUTY_POLICY_AUTHOR")),
                "route.hcm.configuration.automation-golden.action",
                "psr-golden-" + tenantId, NOW.plusDays(1), A,
                1, 1, "psc-" + B, "tenant:" + tenantId,
                uuid(idempotencyKey + "-correlation"), "HRIS_SYSTEM_ADMIN",
                "sys006-golden-" + idempotencyKey, expectedRevision, stepUp,
                Set.of(), Set.of());
    }

    private static AutomationRuntimeService.SchedulerAuthority schedulerAuthority(
            long tenantId, UUID subject, String correlationSeed) {
        return new AutomationRuntimeService.SchedulerAuthority(
                tenantId, subject,
                "internal.hris.sys006.scheduler", "TENANT",
                "tenant:" + tenantId, "scheduler-decision-r1", A,
                1, 1, "HRIS_SYSTEM_ADMIN", uuid(correlationSeed));
    }

    private static ActiveDefinitionFixture seedActiveDefinition(
            long tenantId, String discriminator, UUID author, UUID checker) {
        UUID definitionId = uuid(discriminator + "-definition-id");
        UUID definitionPublicId = uuid(discriminator + "-definition-public");
        UUID versionId = uuid(discriminator + "-version-id");
        jdbc.update("""
                INSERT INTO sys_automation_definitions (
                    definition_id, public_id, tenant_id, automation_key,
                    display_name, owner_domain, author_actor_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'HRM', ?, ?, ?)
                """, definitionId, definitionPublicId, tenantId,
                "gold." + discriminator, "Golden " + discriminator,
                author, NOW, NOW);
        insertActiveVersion(tenantId, definitionId, versionId, author, checker);
        activateDefinition(tenantId, definitionId);
        return new ActiveDefinitionFixture(definitionId, definitionPublicId, versionId);
    }

    private static RunFixture seedLeasedRun(
            long tenantId, String discriminator, boolean compensatable) {
        UUID definitionId = uuid(discriminator + "-definition-id");
        UUID definitionPublicId = uuid(discriminator + "-definition-public");
        UUID versionId = uuid(discriminator + "-version-id");
        UUID runId = uuid(discriminator + "-run-id");
        UUID runPublicId = uuid(discriminator + "-run-public");
        UUID attemptId = uuid(discriminator + "-attempt-id");
        UUID itemId = uuid(discriminator + "-item-id");
        UUID itemPublicId = uuid(discriminator + "-item-public");
        UUID actor = uuid(discriminator + "-actor");
        UUID correlation = uuid(discriminator + "-correlation");
        UUID workerToken = uuid(discriminator + "-worker-token");
        OffsetDateTime now = repository.databaseNow();
        OffsetDateTime leasedUntil = now.plusMinutes(10);

        jdbc.update("""
                INSERT INTO sys_automation_definitions (
                    definition_id, public_id, tenant_id, automation_key,
                    display_name, owner_domain, author_actor_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'HRM', ?, ?, ?)
                """, definitionId, definitionPublicId, tenantId,
                "gold." + discriminator, "Golden " + discriminator,
                actor, now, now);
        insertActiveVersion(tenantId, definitionId, versionId, actor,
                uuid(discriminator + "-checker"));
        activateDefinition(tenantId, definitionId);
        jdbc.update("""
                INSERT INTO sys_automation_runs (
                    run_id, public_id, tenant_id, definition_id, version_id,
                    version_number, lifecycle_state, scope_type, requested_at,
                    requested_by_actor_ref, idempotency_key, correlation_id,
                    input_ref, input_digest, expected_item_count,
                    concurrency_key_digest, retry_mode, max_attempts,
                    current_attempt, item_total, command_receipt_id,
                    worker_fence_epoch, worker_lease_token, worker_leased_until,
                    worker_lease_fence_snapshot, worker_control_fence_snapshot,
                    control_fence_epoch, created_by_actor_ref, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, 'LEASED', 'TENANT', ?, ?, ?, ?,
                        'input:golden', ?, 1, ?, 'FAILED_ITEMS_ONLY', 3,
                        1, 1, ?, 1, ?, ?, 1, 1, 1, ?, ?, ?)
                """, runId, runPublicId, tenantId, definitionId, versionId,
                now, "principal:" + actor,
                "golden-run-" + discriminator, correlation, A, B,
                uuid(discriminator + "-command-receipt"), workerToken,
                leasedUntil, actor, now, now);
        jdbc.update("""
                INSERT INTO sys_automation_run_attempts (
                    run_attempt_id, tenant_id, run_id, attempt_number,
                    selection_mode, selected_item_count, selected_item_digest,
                    lifecycle_state, worker_fence_epoch, worker_lease_token,
                    worker_leased_until, worker_lease_fence_epoch_snapshot,
                    worker_control_fence_epoch_snapshot, started_at, created_at)
                VALUES (?, ?, ?, 1, 'ALL_ITEMS', 1, ?, 'LEASED', 1, ?, ?, 1, 1, ?, ?)
                """, attemptId, tenantId, runId, D, workerToken, leasedUntil,
                now, now);
        jdbc.update("""
                INSERT INTO sys_automation_run_items (
                    run_item_id, public_id, tenant_id, run_id, item_sequence,
                    subject_ref, subject_reference_digest,
                    compensation_policy_ref, compensation_policy_digest, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
                """, itemId, itemPublicId, tenantId, runId,
                "subject:" + discriminator, E,
                compensatable ? "compensation:golden" : null,
                compensatable ? C : null, now);
        jdbc.update("""
                INSERT INTO sys_automation_item_attempts (
                    tenant_id, run_id, run_item_id, attempt_ordinal,
                    run_revision_snapshot, receipt_state, accepted_at,
                    retryable_snapshot, correlation_id, created_at)
                VALUES (?, ?, ?, 1, 1, 'ACCEPTED', ?, FALSE, ?, ?)
                """, tenantId, runId, itemId, now, correlation, now);
        return new RunFixture(
                definitionId, definitionPublicId, versionId, runId,
                runPublicId, attemptId, itemId, itemPublicId, workerToken);
    }

    private static void insertActiveVersion(
            long tenantId,
            UUID definitionId,
            UUID versionId,
            UUID author,
            UUID checker) {
        jdbc.update("""
                INSERT INTO sys_automation_versions (
                    version_id, tenant_id, definition_id, version_number,
                    lifecycle_state, definition_payload, definition_digest,
                    execution_policy, execution_policy_digest,
                    manual_concurrency_key_template, compensation_policy_ref,
                    compensation_policy_digest, manifest_retry_mode,
                    manifest_max_attempts, handler_owner_service, handler_key,
                    handler_contract_version, allowed_scope_types,
                    handler_manifest_state, handler_manifest_revision,
                    handler_manifest_digest, handler_signature_key_id,
                    handler_signature, handler_signature_digest,
                    input_schema_ref, input_schema_digest, receipt_schema_ref,
                    receipt_schema_digest, validation_state,
                    validated_by_actor_ref, validated_at, validation_receipt_id,
                    validation_evidence_digest, compatibility_baseline_version,
                    compatibility_baseline_digest, submitted_by_actor_ref,
                    submitted_at, submitted_subject_revision,
                    submission_receipt_id, submission_evidence_digest,
                    approved_by_actor_ref, approved_at, approved_subject_revision,
                    approval_receipt_id, approval_evidence_digest,
                    approval_step_up_evidence_digest, activated_by_actor_ref,
                    activated_at, activation_receipt_id,
                    activation_evidence_digest, author_actor_ref,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, 'ACTIVE', '{}'::jsonb, ?, '{}'::jsonb, ?,
                        'tenant:{tenantId}:definition:{definitionPublicId}:scope:{scopeType}',
                        'compensation:golden', ?, 'FAILED_ITEMS_ONLY', 3,
                        'dwp-hrm-server', 'hris.hrm.test', 'AutomationInput.v1',
                        ARRAY['TENANT']::TEXT[], 'ACTIVE', 1, ?,
                        'golden-signing-key', 'golden-signature', ?,
                        'schema:input:v1', ?, 'schema:receipt:v1', ?,
                        'VALID', ?, ?, ?, ?, 'compatibility-golden-v1', ?,
                        ?, ?, 1, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?)
                """, versionId, tenantId, definitionId, A, B, C, D, F, A, B,
                author, NOW, uuid(versionId + "-validation"), F, E,
                author, NOW, uuid(versionId + "-submission"), A,
                checker, NOW, uuid(versionId + "-approval"), B, F,
                checker, NOW, uuid(versionId + "-activation"), F,
                author, NOW, NOW);
    }

    private static void activateDefinition(long tenantId, UUID definitionId) {
        jdbc.update("""
                UPDATE sys_automation_definitions
                   SET lifecycle_state = 'ACTIVE', active_version_number = 1
                 WHERE tenant_id = ? AND definition_id = ?
                """, tenantId, definitionId);
    }

    private static LeaseGrant worker(RuntimeRun run) {
        return new LeaseGrant(
                AutomationDomainPolicy.AuthorityKind.WORKER,
                run.run().tenantId(), run.run().publicId(), run.attemptNumber(),
                run.workerLeaseToken(), run.workerFenceEpoch(),
                run.controlFenceEpoch(), run.workerLeasedUntil());
    }

    private static int versionCount(long tenantId, UUID definitionPublicId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_automation_versions v
                  JOIN sys_automation_definitions d
                    ON d.tenant_id = v.tenant_id
                   AND d.definition_id = v.definition_id
                 WHERE v.tenant_id = ? AND d.public_id = ?
                """, Integer.class, tenantId, definitionPublicId);
    }

    private static UUID idAtEnd(String ref) {
        return UUID.fromString(ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(ignored -> work.get());
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[]{POSTGRES.getHost()});
        source.setPortNumbers(new int[]{POSTGRES.getFirstMappedPort()});
        source.setDatabaseName(POSTGRES.getDatabaseName());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private static UUID uuid(String material) {
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }

    private record ActiveDefinitionFixture(
            UUID definitionId,
            UUID definitionPublicId,
            UUID versionId) {
    }

    private record RunFixture(
            UUID definitionId,
            UUID definitionPublicId,
            UUID versionId,
            UUID runId,
            UUID runPublicId,
            UUID attemptId,
            UUID itemId,
            UUID itemPublicId,
            UUID workerToken) {
    }
}
