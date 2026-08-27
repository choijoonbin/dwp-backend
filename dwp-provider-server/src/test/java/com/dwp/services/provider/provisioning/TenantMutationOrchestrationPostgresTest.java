package com.dwp.services.provider.provisioning;

import com.dwp.core.exception.BaseException;
import com.dwp.core.provisioning.ProviderTenantCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TenantMutationOrchestrationPostgresTest {

    private static final UUID TENANT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static ObjectMapper objectMapper;
    private static TenantMutationRepository repository;
    private static TransactionTemplate transactions;
    private static long operatorId;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        repository = new TenantMutationRepository(
                jdbc, objectMapper, new TenantMutationCompensationPlanner(jdbc, objectMapper));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        operatorId = jdbc.queryForObject(
                "SELECT MIN(provider_operator_id) FROM prv_operators", Long.class);
    }

    @BeforeEach
    void resetTenant() {
        jdbc.update("DELETE FROM prv_tenant_command_outbox");
        jdbc.update("DELETE FROM prv_tenant_mutations");
        jdbc.update("""
                UPDATE prv_tenants
                   SET lifecycle_state = 'SUSPENDED', onboarding_state = 'READY',
                       entitlement_revision = 0, version = 0
                 WHERE provider_tenant_id = ?
                """, TENANT_ID);
    }

    @Test
    void twoWorkersNeverOwnTheSameCommandAndExpiredLeaseRecoversTheSameCommandId() {
        TenantMutationRepository.Mutation mutation = tx(() -> repository.create(activation()));

        TenantMutationRepository.CommandLease first = tx(() -> repository.claimNext(
                mutation.mutationId(), "worker-a", Duration.ofSeconds(30)));
        TenantMutationRepository.CommandLease concurrent = tx(() -> repository.claimNext(
                mutation.mutationId(), "worker-b", Duration.ofSeconds(30)));

        assertThat(first.targetService()).isEqualTo("PLATFORM");
        assertThat(concurrent).isNull();
        jdbc.update("""
                UPDATE prv_tenant_command_outbox
                   SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE command_id = ?
                """, first.commandId());
        tx(repository::releaseExpiredLeases);
        TenantMutationRepository.CommandLease recovered = tx(() -> repository.claimNext(
                mutation.mutationId(), "worker-b", Duration.ofSeconds(30)));

        assertThat(recovered.commandId()).isEqualTo(first.commandId());
        assertThat(recovered.attemptCount()).isEqualTo(2);
        assertThat(recovered.leaseToken()).isNotEqualTo(first.leaseToken());
    }

    @Test
    void transientFailureUsesDurableBackoffBeforeRetryingTheSameCommand() {
        TenantMutationRepository.Mutation mutation = tx(() -> repository.create(activation()));
        TenantMutationRepository.CommandLease first = claim(mutation.mutationId());

        assertThat(tx(() -> repository.markFailed(
                first, 8, false, "REMOTE_UNAVAILABLE", "Temporary network failure.")))
                .isEqualTo(TenantMutationRepository.FailureDisposition.RETRY_SCHEDULED);
        assertThat(jdbc.queryForObject("""
                SELECT next_attempt_at > CURRENT_TIMESTAMP
                  FROM prv_tenant_command_outbox WHERE command_id = ?
                """, Boolean.class, first.commandId())).isTrue();
        assertThat(claim(mutation.mutationId())).isNull();

        jdbc.update("""
                UPDATE prv_tenant_command_outbox
                   SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE command_id = ?
                """, first.commandId());
        TenantMutationRepository.CommandLease retry = claim(mutation.mutationId());
        assertThat(retry.commandId()).isEqualTo(first.commandId());
        assertThat(retry.attemptCount()).isEqualTo(2);
    }

    @Test
    void providerTenantBecomesActiveOnlyAfterPlatformPeopleAndAuthReceipts() {
        TenantMutationRepository.Mutation mutation = tx(() -> repository.create(activation()));
        for (String expectedTarget : List.of("PLATFORM", "PEOPLE", "AUTH")) {
            TenantMutationRepository.CommandLease command = claim(mutation.mutationId());
            assertThat(command.targetService()).isEqualTo(expectedTarget);
            assertThat(tenantLifecycle()).isEqualTo("SUSPENDED");
            tx(() -> repository.markApplied(command, receipt(command)));
        }

        // Simulate a process crash after the final provider acknowledgement but
        // before the local aggregate commit; recovery must discover it without
        // another pending command to claim.
        assertThat(tx(repository::completeNextReady))
                .isEqualTo(TenantMutationRepository.Completion.SUCCEEDED);
        assertThat(tenantLifecycle()).isEqualTo("ACTIVE");
    }

    @Test
    void activationFailureSuppressesAuthGrantAndCompensatesOnlyAlreadyActiveServices() {
        TenantMutationRepository.Mutation mutation = tx(() -> repository.create(activation()));
        TenantMutationRepository.CommandLease platform = claim(mutation.mutationId());
        tx(() -> repository.markApplied(platform, receipt(platform)));
        TenantMutationRepository.CommandLease people = claim(mutation.mutationId());

        TenantMutationRepository.FailureDisposition disposition = tx(() -> repository.markFailed(
                people, 8, true, "PEOPLE_CONFLICT", "People rejected the target revision."));

        assertThat(disposition)
                .isEqualTo(TenantMutationRepository.FailureDisposition.COMPENSATION_SCHEDULED);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_tenant_command_outbox
                 WHERE mutation_id = ? AND target_service = 'AUTH' AND NOT compensation
                """, String.class, mutation.mutationId())).isEqualTo("RECONCILIATION_REQUIRED");
        TenantMutationRepository.CommandLease compensation = claim(mutation.mutationId());
        assertThat(compensation.compensation()).isTrue();
        assertThat(compensation.targetService()).isEqualTo("PLATFORM");
        assertThat(compensation.payload().path("lifecycleState").asText()).isEqualTo("SUSPENDED");
        assertThat(compensation.expectedRevision()).isEqualTo(platform.targetRevision());
        tx(() -> repository.markApplied(compensation, receipt(compensation)));

        assertThat(tx(() -> repository.completeIfReady(mutation.mutationId())))
                .isEqualTo(TenantMutationRepository.Completion.COMPENSATED);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_tenant_command_outbox
                 WHERE mutation_id = ? AND target_service = 'AUTH' AND lifecycle_state = 'APPLIED'
                """, Integer.class, mutation.mutationId())).isZero();
    }

    @Test
    void mixedEntitlementChangeRevokesInAuthFirstAndGrantsInAuthLast() {
        jdbc.update("UPDATE prv_tenant_entitlements SET lifecycle_state = 'RETIRED'");
        jdbc.update("""
                UPDATE prv_tenant_entitlements assignment
                   SET lifecycle_state = 'ACTIVE'
                  FROM prv_entitlement_catalog entitlement
                 WHERE assignment.entitlement_id = entitlement.entitlement_id
                   AND assignment.provider_tenant_id = ?
                   AND entitlement.entitlement_key IN ('core.workspace', 'core.people')
                """, TENANT_ID);
        ObjectNode previous = objectMapper.createObjectNode();
        previous.putArray("entitlementKeys").add("core.people").add("core.workspace");
        ObjectNode desired = objectMapper.createObjectNode();
        desired.putArray("entitlementKeys").add("core.control-center").add("core.people");
        desired.put("justification", "Mixed entitlement test");
        ObjectNode revoke = objectMapper.createObjectNode();
        revoke.putArray("entitlementKeys").add("core.people");
        ObjectNode finalKeys = objectMapper.createObjectNode();
        finalKeys.putArray("entitlementKeys").add("core.control-center").add("core.people");
        TenantMutationRepository.MutationRequest request = new TenantMutationRepository.MutationRequest(
                TENANT_ID, "ENTITLEMENTS", "mixed-entitlements", hash(desired), 0,
                previous, desired, operatorId, "corr-mixed",
                List.of(
                        new TenantMutationRepository.CommandSpec("AUTH", "ENTITLEMENTS", revoke),
                        new TenantMutationRepository.CommandSpec("PLATFORM", "ENTITLEMENTS", finalKeys),
                        new TenantMutationRepository.CommandSpec("AUTH", "ENTITLEMENTS", finalKeys)));

        TenantMutationRepository.Mutation mutation = tx(() -> repository.create(request));
        List<String> targets = jdbc.queryForList("""
                SELECT target_service || ':' || expected_revision || '>' || target_revision
                  FROM prv_tenant_command_outbox WHERE mutation_id = ? ORDER BY command_order
                """, String.class, mutation.mutationId());

        assertThat(targets).containsExactly("AUTH:0>1", "PLATFORM:0>1", "AUTH:1>2");
        assertThat(jdbc.queryForObject("""
                SELECT payload -> 'entitlementKeys' FROM prv_tenant_command_outbox
                 WHERE mutation_id = ? AND command_order = 1
                """, String.class, mutation.mutationId())).isEqualTo("[\"core.people\"]");
    }

    @Test
    void sameHashReplaysButHashMismatchAndSecondActiveMutationFailClosed() {
        TenantMutationRepository.MutationRequest request = activation();
        TenantMutationRepository.Mutation first = tx(() -> repository.create(request));
        TenantMutationRepository.Mutation replay = tx(() -> repository.create(request));
        assertThat(replay.mutationId()).isEqualTo(first.mutationId());

        ObjectNode changed = objectMapper.createObjectNode()
                .put("lifecycleState", "ACTIVE")
                .put("justification", "Changed payload");
        TenantMutationRepository.MutationRequest mismatch = new TenantMutationRepository.MutationRequest(
                TENANT_ID, "LIFECYCLE", request.idempotencyKey(), hash(changed), 0,
                request.previousPayload(), changed, operatorId, "corr-mismatch", request.commands());
        assertThatThrownBy(() -> tx(() -> repository.create(mismatch)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("different payload");

        TenantMutationRepository.MutationRequest second = new TenantMutationRepository.MutationRequest(
                TENANT_ID, "LIFECYCLE", "second-active", request.payloadSha256(), 0,
                request.previousPayload(), request.desiredPayload(), operatorId,
                "corr-second", request.commands());
        assertThatThrownBy(() -> tx(() -> repository.create(second)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("already active");
    }

    @Test
    void unsafeSuspendFailureRequiresReconciliationAndWritesAuditEvidence() {
        ObjectNode previous = objectMapper.createObjectNode().put("lifecycleState", "ACTIVE");
        ObjectNode desired = objectMapper.createObjectNode()
                .put("lifecycleState", "SUSPENDED")
                .put("justification", "Containment test");
        ObjectNode payload = objectMapper.createObjectNode().put("lifecycleState", "SUSPENDED");
        TenantMutationRepository.MutationRequest request = new TenantMutationRepository.MutationRequest(
                TENANT_ID, "LIFECYCLE", "suspend-reconcile", hash(desired), 0,
                previous, desired, operatorId, "corr-reconcile",
                List.of(
                        new TenantMutationRepository.CommandSpec("AUTH", "LIFECYCLE", payload),
                        new TenantMutationRepository.CommandSpec("PLATFORM", "LIFECYCLE", payload),
                        new TenantMutationRepository.CommandSpec("PEOPLE", "LIFECYCLE", payload)));
        TenantMutationRepository.Mutation mutation = tx(() -> repository.create(request));
        TenantMutationRepository.CommandLease auth = claim(mutation.mutationId());

        assertThat(tx(() -> repository.markFailed(
                auth, 1, true, "AUTH_CONFLICT", "Auth rejected suspension.")))
                .isEqualTo(TenantMutationRepository.FailureDisposition.RECONCILIATION_REQUIRED);
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state FROM prv_tenant_mutations WHERE mutation_id = ?
                """, String.class, mutation.mutationId())).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_audit_events
                 WHERE action = 'provider.tenant-mutation.reconciliation-required'
                   AND correlation_id = 'corr-reconcile'
                """, Integer.class)).isEqualTo(1);
    }

    private TenantMutationRepository.MutationRequest activation() {
        ObjectNode previous = objectMapper.createObjectNode().put("lifecycleState", "SUSPENDED");
        ObjectNode desired = objectMapper.createObjectNode()
                .put("lifecycleState", "ACTIVE")
                .put("justification", "Approved activation");
        ObjectNode payload = objectMapper.createObjectNode().put("lifecycleState", "ACTIVE");
        return new TenantMutationRepository.MutationRequest(
                TENANT_ID, "LIFECYCLE", "activate:0", hash(desired), 0,
                previous, desired, operatorId, "corr-activate",
                List.of(
                        new TenantMutationRepository.CommandSpec("PLATFORM", "LIFECYCLE", payload),
                        new TenantMutationRepository.CommandSpec("PEOPLE", "LIFECYCLE", payload),
                        new TenantMutationRepository.CommandSpec("AUTH", "LIFECYCLE", payload)));
    }

    private TenantMutationRepository.CommandLease claim(UUID mutationId) {
        return tx(() -> repository.claimNext(
                mutationId, "test-worker", Duration.ofSeconds(30)));
    }

    private ProviderTenantCommand.Receipt receipt(TenantMutationRepository.CommandLease command) {
        return new ProviderTenantCommand.Receipt(
                command.commandId(), command.providerTenantId(), command.commandType(),
                command.expectedRevision(), command.targetRevision(), command.payloadSha256(),
                objectMapper.createObjectNode().put("state", "applied"), Instant.now(), false);
    }

    private String hash(ObjectNode payload) {
        return ProviderTenantCommand.payloadSha256(objectMapper, payload);
    }

    private String tenantLifecycle() {
        return jdbc.queryForObject(
                "SELECT lifecycle_state FROM prv_tenants WHERE provider_tenant_id = ?",
                String.class, TENANT_ID);
    }

    private <T> T tx(Supplier<T> action) {
        return transactions.execute(ignored -> action.get());
    }

    private void tx(Runnable action) {
        transactions.executeWithoutResult(ignored -> action.run());
    }
}
