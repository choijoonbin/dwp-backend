package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
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

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.WorkplaceResourceCommandDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceResourceCommandProvider.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceResourceCommandPostgresTest {
    private static final Instant FIXED = Instant.parse("2026-09-17T03:00:00Z");
    private static final long ACTOR = 76_001L;
    private static final AtomicLong TENANTS = new AtomicLong(9_986_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load().migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Test
    void verifiedProviderCommandPersistsUnknownThenReconcilesExactlyOnce() {
        Fixture fixture = fixture(true);
        ControlledProvider provider = new ControlledProvider();
        WorkplaceResourceCommandService service = service(provider);

        CommandContext context = tx(() -> service.context(
                fixture.tenantId(), ACTOR, fixture.bookingId()));
        assertThat(context.actions()).singleElement().satisfies(action -> {
            assertThat(action.commandType()).isEqualTo(ResourceCommandType.NFC_KEY_RESEND);
            assertThat(action.providerState()).isEqualTo(ResourceCommandProviderState.READY);
            assertThat(action.availability()).isEqualTo(ResourceCommandAvailability.AVAILABLE);
        });

        CommandPreview preview = tx(() -> service.preview(
                fixture.tenantId(), ACTOR, fixture.bookingId(),
                new PreviewRequest(ResourceCommandType.NFC_KEY_RESEND, 7L, Map.of())));
        assertThat(preview.eligible()).isTrue();
        assertThat(preview.impact()).containsExactly("CURRENT_NFC_KEY_WILL_BE_REPLACED");

        ExecuteRequest executeRequest = new ExecuteRequest(
                preview.previewId(), 7L, "Replace a lost NFC key", true);
        CommandReceipt unknown = tx(() -> service.execute(
                fixture.tenantId(), ACTOR, fixture.bookingId(), "resource-command-key",
                "screen-16", executeRequest));
        CommandReceipt executeReplay = tx(() -> service.execute(
                fixture.tenantId(), ACTOR, fixture.bookingId(), "resource-command-key",
                "screen-16", executeRequest));

        assertThat(unknown.state()).isEqualTo(ResourceCommandState.RESULT_UNKNOWN);
        assertThat(unknown.requeryRequired()).isTrue();
        assertThat(executeReplay.commandId()).isEqualTo(unknown.commandId());
        assertThat(executeReplay.idempotentReplay()).isTrue();
        assertThat(provider.executeCalls).hasValue(1);

        ReconcileRequest reconcileRequest = new ReconcileRequest(
                "Confirm provider result", true);
        CommandReceipt succeeded = tx(() -> service.reconcile(
                fixture.tenantId(), ACTOR, fixture.bookingId(), unknown.commandId(),
                "resource-command-reconcile-key", "screen-16", reconcileRequest));
        CommandReceipt reconcileReplay = tx(() -> service.reconcile(
                fixture.tenantId(), ACTOR, fixture.bookingId(), unknown.commandId(),
                "resource-command-reconcile-key", "screen-16", reconcileRequest));

        assertThat(succeeded.state()).isEqualTo(ResourceCommandState.SUCCEEDED);
        assertThat(succeeded.requeryRequired()).isFalse();
        assertThat(reconcileReplay.state()).isEqualTo(ResourceCommandState.SUCCEEDED);
        assertThat(reconcileReplay.idempotentReplay()).isTrue();
        assertThat(provider.statusCalls).hasValue(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id=? AND aggregate_type='WP_RESOURCE_COMMAND'
                   AND action IN ('workplace.resource.command.executed',
                                  'workplace.resource.command.reconciled')
                """, Long.class, fixture.tenantId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_resource_command_reconciliations
                 WHERE tenant_id=? AND command_id=?
                """, Long.class, fixture.tenantId(), unknown.commandId())).isEqualTo(1L);
    }

    @Test
    void providerTruthAndOwnershipFailClosedAndIdempotencyCannotChangeMeaning() {
        Fixture fixture = fixture(false);
        ControlledProvider provider = new ControlledProvider();
        WorkplaceResourceCommandService service = service(provider);

        CommandContext context = tx(() -> service.context(
                fixture.tenantId(), ACTOR, fixture.bookingId()));
        assertThat(context.actions()).singleElement().satisfies(action -> {
            assertThat(action.providerState())
                    .isEqualTo(ResourceCommandProviderState.CONFIGURED_UNVERIFIED);
            assertThat(action.availability())
                    .isEqualTo(ResourceCommandAvailability.PROVIDER_NOT_READY);
        });
        CommandPreview preview = tx(() -> service.preview(
                fixture.tenantId(), ACTOR, fixture.bookingId(),
                new PreviewRequest(ResourceCommandType.NFC_KEY_RESEND, 7L, Map.of())));
        assertThat(preview.eligible()).isFalse();
        assertThatThrownBy(() -> tx(() -> service.execute(
                fixture.tenantId(), ACTOR, fixture.bookingId(), "blocked-command-key", null,
                new ExecuteRequest(preview.previewId(), 7L, "Blocked provider", true))))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> tx(() -> service.context(
                fixture.tenantId(), ACTOR + 1, fixture.bookingId())))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(provider.executeCalls).hasValue(0);
    }

    private static WorkplaceResourceCommandService service(ControlledProvider provider) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new WorkplaceResourceCommandService(
                new WorkplaceResourceCommandRepository(jdbc, mapper), provider, mapper,
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    private static Fixture fixture(boolean verified) {
        long tenantId = TENANTS.incrementAndGet();
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO sys_service_tenants(
                    provider_tenant_id,tenant_id,tenant_key,display_name,lifecycle_state,
                    data_region,isolation_model,created_by,updated_by)
                VALUES(?,?,?,'Resource command','ACTIVE','kr','POOL',?,?)
                """, UUID.randomUUID(), tenantId, "resource-command-" + tenantId, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies(tenant_id) VALUES(?)", tenantId);
        jdbc.update("""
                INSERT INTO wp_sites(site_id,tenant_id,site_code,name_ko,name_en,time_zone)
                VALUES(?,?,?,'서울','Seoul','Asia/Seoul')
                """, siteId, tenantId, "COMMAND_" + tenantId);
        jdbc.update("""
                INSERT INTO wp_floors(
                    floor_id,tenant_id,site_id,floor_number,name_ko,name_en,lifecycle_state)
                VALUES(?,?,?,16,'16층','16F','ACTIVE')
                """, floorId, tenantId, siteId);
        jdbc.update("""
                INSERT INTO wp_resources(
                    resource_id,tenant_id,floor_id,resource_code,name_ko,name_en,
                    resource_type,booking_mode)
                VALUES(?,?,?,?,'집중 좌석','Focus desk','DESK','RESERVABLE')
                """, resourceId, tenantId, floorId, "COMMAND_RESOURCE_" + tenantId);
        jdbc.update("""
                INSERT INTO wp_bookings(
                    booking_id,tenant_id,resource_id,user_id,booked_for_display_name,purpose,
                    starts_at,ends_at,booking_status,version,policy_snapshot,
                    policy_snapshot_hash,require_check_in_snapshot,
                    check_in_lead_minutes_snapshot,auto_release_minutes_snapshot,
                    booking_retention_days_snapshot)
                VALUES(?,?,?,?,'Resource owner','Screen 16 command',?,?,'RESERVED',7,
                       '{}'::jsonb,encode(digest('{}'::jsonb::text,'sha256'),'hex'),
                       FALSE,15,0,365)
                """, bookingId, tenantId, resourceId, ACTOR,
                now.minusMinutes(10), now.plusHours(1));
        jdbc.update("""
                INSERT INTO wp_navigation_provider_truth(
                    provider_truth_id,tenant_id,capability,provider_code,configuration_version,
                    observed_configuration_version,reported_state,evidence_reference,
                    source_at,received_at,last_success_at,configured)
                VALUES(?,?,'NFC','nfc-provider',12,?,?,?, ?,?,?,TRUE)
                """, UUID.randomUUID(), tenantId, verified ? 12L : null,
                verified ? "HEALTHY" : null,
                verified ? "provider-evidence-screen-16" : null,
                verified ? now.minusMinutes(1) : null,
                verified ? now.minusMinutes(1) : null,
                verified ? now.minusMinutes(1) : null);
        return new Fixture(tenantId, bookingId);
    }

    private static <T> T tx(Supplier<T> action) {
        return transaction.execute(ignored -> action.get());
    }

    private record Fixture(long tenantId, UUID bookingId) { }

    private static final class ControlledProvider implements WorkplaceResourceCommandProvider {
        private final AtomicInteger executeCalls = new AtomicInteger();
        private final AtomicInteger statusCalls = new AtomicInteger();

        @Override
        public Optional<ProviderBinding> binding(String providerCode, long configurationVersion) {
            return Optional.of(new ProviderBinding(providerCode, configurationVersion,
                    "secret-manager://workplace/test-nfc"));
        }

        @Override
        public boolean ready(ProviderBinding binding) {
            return true;
        }

        @Override
        public ProviderOutcome execute(ProviderCommand command) {
            executeCalls.incrementAndGet();
            return new ProviderOutcome(ResourceCommandState.RESULT_UNKNOWN,
                    "provider-operation-16", "PROVIDER_TIMEOUT");
        }

        @Override
        public ProviderOutcome status(ProviderCommand command) {
            statusCalls.incrementAndGet();
            return new ProviderOutcome(ResourceCommandState.SUCCEEDED,
                    "provider-operation-16", "KEY_RESENT");
        }
    }
}
