package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.media.TenantMediaStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceLineAdjustmentProvider.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

abstract class WorkplaceServicesPostgresTestSupport {
    protected static final Instant FIXED = Instant.parse("2026-09-17T00:00:00Z");
    protected static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    protected static final long ACTOR = 18_001L;
    protected static final AtomicLong TENANTS = new AtomicLong(9_958_000L);

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    protected static JdbcTemplate jdbc;
    protected static TransactionTemplate transaction;
    protected static ObjectMapper mapper;
    protected static WorkplaceServicesRepository repository;
    protected static WorkplaceServiceOperationsRepository operationsRepository;
    protected static WorkplaceServiceOperationsService operationsService;
    protected static WorkplaceServicesService service;
    protected static WorkplaceServiceOrderQueryService queryService;

    @BeforeAll
    protected static void migrateAndBuildActualService() {
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
        mapper = new ObjectMapper().findAndRegisterModules();
        repository = new WorkplaceServicesRepository(jdbc, mapper);
        operationsRepository = new WorkplaceServiceOperationsRepository(jdbc, mapper);
        operationsService = new WorkplaceServiceOperationsService(operationsRepository, mapper,
                List.of(), List.of(), transaction, Clock.fixed(FIXED, ZoneOffset.UTC));
        service = new WorkplaceServicesService(repository, mapper, operationsService,
                Clock.fixed(FIXED, ZoneOffset.UTC));
        queryService = new WorkplaceServiceOrderQueryService(repository, mapper,
                new WorkplaceServicesCursorCodec(
                        "screen18-postgres-cursor-secret-at-least-32-bytes",
                        Clock.fixed(FIXED, ZoneOffset.UTC)),
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    protected static UUID insertLegacyIssuedGrant(
            long tenantId, ServiceOrder order, long requester, String reference) {
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_service_ephemeral_access_grants (
                    access_grant_id,tenant_id,service_order_id,service_order_line_id,
                    provider_code,requester_user_id,provider_grant_reference,
                    credential_fingerprint,grant_state,reason,issued_at,expires_at,
                    adapter_type_snapshot,provider_configuration_version,
                    provider_credential_binding_reference,provider_operation_kind,
                    provider_operation_command_id,version,created_at,updated_at)
                VALUES (?,?,?,?, 'DWP_NATIVE_FULFILLMENT',?,?,?,'ISSUED',?,
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        NULL,NULL,NULL,'LEGACY_UNKNOWN',NULL,1,
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, grantId, tenantId, order.serviceOrderId(),
                order.lines().getFirst().serviceOrderLineId(), requester, reference,
                "a".repeat(64), "Legacy issued grant");
        return grantId;
    }

    protected static UUID insertUnknownGrant(
            long tenantId, ServiceOrder order, long requester, String reference,
            boolean legacy) {
        UUID grantId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        if (!legacy) {
            jdbc.update("""
                    INSERT INTO wp_service_operations_commands (
                        operations_command_id,tenant_id,actor_user_id,command_scope,
                        idempotency_key,request_fingerprint,resource_type,resource_id,
                        command_state,status_href,correlation_id,created_at,updated_at)
                    VALUES (?,?,?,'SERVICE_ACCESS_REVOKE',?,?,'SERVICE_ACCESS_GRANT',?,
                            'RESULT_UNKNOWN','/provider-recovery',?,CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP)
                    """, commandId, tenantId, requester, reference,
                    "f".repeat(64), grantId, "corr-" + reference);
        }
        jdbc.update("""
                INSERT INTO wp_service_ephemeral_access_grants (
                    access_grant_id,tenant_id,service_order_id,service_order_line_id,
                    provider_code,requester_user_id,provider_grant_reference,
                    credential_fingerprint,grant_state,reason,issued_at,expires_at,
                    adapter_type_snapshot,provider_configuration_version,
                    provider_credential_binding_reference,provider_operation_kind,
                    provider_operation_command_id,version,created_at,updated_at)
                VALUES (?,?,?,?, 'DWP_NATIVE_FULFILLMENT',?,?,?,'RESULT_UNKNOWN',?,
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        ?,?,?,?, ?,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, grantId, tenantId, order.serviceOrderId(),
                order.lines().getFirst().serviceOrderLineId(), requester, reference,
                "a".repeat(64), "Recovery test", legacy ? null : "DWP_NATIVE",
                legacy ? null : 1L, legacy ? null : "internal://test-native",
                legacy ? "LEGACY_UNKNOWN" : "REVOKE", legacy ? null : commandId);
        return grantId;
    }

    protected static void assertPageIds(List<UUID> first, List<UUID> second) {
        assertThat(first).hasSize(100);
        assertThat(second).hasSize(1);
        List<UUID> all = new java.util.ArrayList<>(first);
        all.addAll(second);
        assertThat(all).hasSize(101).doesNotHaveDuplicates();
    }

    protected static Object concurrentCancel(
            WorkplaceServiceLineAdjustmentService adjustments, Fixture fixture,
            ServiceOrder order, ServiceOrderLine line, String key,
            LineCancellationRequest request, CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return tx(() -> adjustments.cancel(fixture.tenant(), ACTOR,
                    order.serviceOrderId(), line.serviceOrderLineId(), key, request,
                    "corr-concurrent"));
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    protected static ServiceOrderCommandResult concurrentMessage(
            Fixture fixture, ServiceOrder order, MessageRequest request,
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return tx(() -> service.addMessage(fixture.tenant(), ACTOR,
                order.serviceOrderId(), false, "message-concurrent-key", request,
                "corr-message-concurrent"));
    }

    protected static ServiceOrder submitOrder(
            Fixture fixture, String serviceCode, int quantity,
            int cancellationCutoffMinutes) {
        return submitOrder(fixture, serviceCode, quantity,
                cancellationCutoffMinutes, NOW.plusDays(2));
    }

    protected static ServiceOrder submitOrder(
            Fixture fixture, String serviceCode, int quantity,
            int cancellationCutoffMinutes, OffsetDateTime startsAt) {
        UUID catalogItem = createCatalog(fixture, serviceCode, cancellationCutoffMinutes)
                .item().item().catalogItemId();
        UUID booking = workplaceBooking(fixture, startsAt);
        ServiceOrderPreview preview = tx(() -> service.preview(
                fixture.tenant(), ACTOR, booking,
                previewRequest(ReservationAuthority.WORKPLACE, 0, catalogItem, quantity)));
        return tx(() -> service.submit(fixture.tenant(), ACTOR, booking,
                "submit-" + fixture.tenant() + '-' + serviceCode,
                new SubmitRequest(preview.previewId(), 0, true,
                        "Submit " + serviceCode), "corr-" + serviceCode)).order();
    }

    protected static ServiceOrder submitFreeNativeOrder(Fixture fixture, String serviceCode) {
        return submitFreeNativeOrder(fixture, serviceCode, 1);
    }

    protected static ServiceOrder submitFreeNativeOrder(
            Fixture fixture, String serviceCode, int quantity) {
        return submitFreeNativeOrder(
                fixture, serviceCode, quantity, NOW.plusDays(2));
    }

    protected static ServiceOrder submitFreeNativeOrder(
            Fixture fixture, String serviceCode, int quantity, OffsetDateTime startsAt) {
        UUID catalogItem = createCatalog(fixture, serviceCode, 60).item().item().catalogItemId();
        jdbc.update("""
                UPDATE wp_service_catalog_items
                   SET unit_price = 0, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND catalog_item_id = ?
                """, NOW, fixture.tenant(), catalogItem);
        UUID booking = workplaceBooking(fixture, startsAt);
        ServiceOrderPreview preview = tx(() -> service.preview(
                fixture.tenant(), ACTOR, booking,
                previewRequest(ReservationAuthority.WORKPLACE, 0, catalogItem, quantity)));
        return tx(() -> service.submit(fixture.tenant(), ACTOR, booking,
                "submit-" + fixture.tenant() + '-' + serviceCode,
                new SubmitRequest(preview.previewId(), 0, true,
                        "Submit " + serviceCode), "corr-" + serviceCode)).order();
    }

    protected static LineCancellationPreviewRow cancellationPreview(
            Fixture fixture, OrderRow order, LineRow line, BigDecimal refundableAmount) {
        return new LineCancellationPreviewRow(UUID.randomUUID(), fixture.tenant(), ACTOR,
                order.orderId(), line.lineId(), order.version(), line.version(), 1,
                line.fulfilledQuantity(), line.cancelledQuantity(), line.catalogVersion(),
                line.providerConfigurationVersion(), line.unitPrice(), line.currency(),
                line.cancellationCutoffMinutes(), line.cancellationPolicyKo(),
                line.cancellationPolicyEn(), RefundScope.FULL, refundableAmount, true,
                "Database invariant", NOW.plusMinutes(10), NOW);
    }

    protected static final class FakeLineAdjustmentProvider
            implements WorkplaceServiceLineAdjustmentProvider {
        protected final AtomicReference<ProviderOutcome> cancelOutcome = new AtomicReference<>(
                new ProviderOutcome(OutcomeState.NOT_CONFIGURED, null,
                        BigDecimal.ZERO, null, "Not configured"));
        protected final AtomicReference<ProviderOutcome> reconcileOutcome = new AtomicReference<>(
                new ProviderOutcome(OutcomeState.RESULT_UNKNOWN, null,
                        BigDecimal.ZERO, null, "Still unknown"));
        protected final AtomicReference<RuntimeException> cancelFailure = new AtomicReference<>();
        protected final AtomicInteger cancelCalls = new AtomicInteger();
        protected final AtomicInteger reconcileCalls = new AtomicInteger();
        protected final Set<UUID> operationIds = ConcurrentHashMap.newKeySet();

        @Override
        public ProviderOutcome cancel(ProviderRequest request) {
            cancelCalls.incrementAndGet();
            operationIds.add(request.operationId());
            RuntimeException failure = cancelFailure.get();
            if (failure != null) throw failure;
            return cancelOutcome.get();
        }

        @Override
        public ProviderOutcome reconcile(
                ProviderRequest request, String providerOperationReference) {
            reconcileCalls.incrementAndGet();
            operationIds.add(request.operationId());
            return reconcileOutcome.get();
        }
    }

    protected static Fixture fixture() {
        long tenant = TENANTS.incrementAndGet();
        UUID site = UUID.randomUUID();
        UUID floor = UUID.randomUUID();
        UUID calendarResource = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        UUID calendar = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name,
                    lifecycle_state, data_region, isolation_model, created_by, updated_by)
                VALUES (?, ?, ?, 'Workplace services test', 'ACTIVE', 'kr', 'POOL', ?, ?)
                """, UUID.randomUUID(), tenant, "workplace-services-" + tenant, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies (tenant_id) VALUES (?)", tenant);
        jdbc.update("""
                INSERT INTO wp_sites (site_id, tenant_id, site_code, name_ko, name_en)
                VALUES (?, ?, ?, '서비스 테스트', 'Services test')
                """, site, tenant, "SITE_" + tenant);
        jdbc.update("""
                INSERT INTO wp_floors (
                    floor_id, tenant_id, site_id, floor_number, name_ko, name_en)
                VALUES (?, ?, ?, 18, '18층', '18F')
                """, floor, tenant, site);
        jdbc.update("""
                INSERT INTO cal_resources (
                    resource_id, tenant_id, resource_code, name_ko, name_en,
                    resource_type, site_name, capacity)
                VALUES (?, ?, ?, '서비스 회의실', 'Services room', 'ROOM', 'Services site', 12)
                """, calendarResource, tenant, "CAL_ROOM_" + tenant);
        jdbc.update("""
                INSERT INTO wp_resources (
                    resource_id, tenant_id, floor_id, calendar_resource_id,
                    resource_code, name_ko, name_en, resource_type, capacity)
                VALUES (?, ?, ?, ?, ?, '서비스 회의실', 'Services room', 'ROOM', 12)
                """, resource, tenant, floor, calendarResource, "ROOM_" + tenant);
        jdbc.update("""
                INSERT INTO cal_calendars (
                    calendar_id, tenant_id, calendar_key, owner_user_id,
                    name_ko, name_en, calendar_type)
                VALUES (?, ?, ?, ?, '개인 일정', 'Personal calendar', 'PERSONAL')
                """, calendar, tenant, "PERSONAL_" + tenant, ACTOR);
        jdbc.update("""
                INSERT INTO wp_service_provider_truth (
                    tenant_id, provider_code, configured, configuration_version,
                    observed_configuration_version, reported_state, evidence_reference,
                    observed_at, received_at)
                VALUES (?, 'DWP_NATIVE_FULFILLMENT', TRUE, 1, 1, 'HEALTHY',
                        'native-test-v1', ?, ?)
                """, tenant, NOW.minusMinutes(1), NOW.minusMinutes(1));
        jdbc.update("""
                INSERT INTO wp_service_provider_profiles (
                    provider_profile_id, tenant_id, provider_code, display_name_ko,
                    display_name_en, adapter_type, lifecycle_state, site_scope,
                    capabilities, support_metadata, credential_binding_reference,
                    configuration_version, version, created_at, updated_at)
                VALUES (?, ?, 'DWP_NATIVE_FULFILLMENT', 'DWP 기본 서비스',
                        'DWP native fulfillment', 'DWP_NATIVE', 'ACTIVE', '[]'::jsonb,
                        '["WORKPLACE_SERVICE_FULFILLMENT"]'::jsonb, '{}'::jsonb,
                        'internal://test-native', 1, 1, ?, ?)
                """, UUID.randomUUID(), tenant, NOW, NOW);
        return new Fixture(tenant, site, floor, resource, calendarResource, calendar);
    }

    protected static CatalogCommandResult createCatalog(
            Fixture fixture, String serviceCode, int cancellationCutoffMinutes) {
        CatalogCreateRequest request = new CatalogCreateRequest(serviceCode,
                ServiceCategory.CATERING, "회의 지원", "Meeting support",
                "예약 연계 서비스", "Reservation-linked service",
                "DWP_NATIVE_FULFILLMENT", List.of(fixture.site()), mapper.createArrayNode(),
                List.of("ROOM"), BigDecimal.valueOf(12_500), "KRW", 1, 10,
                0, cancellationCutoffMinutes, 30, 30, "시작 1시간 전까지 취소",
                "Cancel until one hour before",
                WorkplaceServiceOperationsDtos.CapacityMode.UNBOUNDED, 900,
                WorkplaceServiceOperationsDtos.InspectionMode.NONE,
                mapper.createArrayNode(), true, true, true,
                "Publish tenant-scoped service catalog");
        return tx(() -> service.createCatalogItem(fixture.tenant(), ACTOR,
                "catalog-" + fixture.tenant() + '-' + serviceCode, request,
                "corr-catalog"));
    }

    protected static CatalogCommandResult createGovernedCatalog(
            Fixture fixture, String serviceCode) {
        var checklist = mapper.createArrayNode();
        checklist.addObject().put("key", "roomReady").put("type", "BOOLEAN")
                .put("required", true);
        CatalogCreateRequest request = new CatalogCreateRequest(serviceCode,
                ServiceCategory.ROOM_LAYOUT, "검수형 회의실 준비", "Inspected room setup",
                "요청자 최종 확인이 필요한 회의실 준비", "Room setup with requester acceptance",
                "DWP_NATIVE_FULFILLMENT", List.of(fixture.site()), mapper.createArrayNode(),
                List.of("ROOM"), BigDecimal.valueOf(25_000), "KRW", 1, 10,
                0, 60, 30, 30, "시작 1시간 전까지 취소",
                "Cancel until one hour before", CapacityMode.BUCKETED, 900,
                InspectionMode.REQUESTER, checklist, true, true, true,
                "Publish capacity and inspection governed service");
        return tx(() -> service.createCatalogItem(fixture.tenant(), ACTOR,
                "catalog-governed-" + fixture.tenant() + '-' + serviceCode,
                request, "corr-catalog-governed"));
    }

    protected static PreviewRequest previewRequest(
            ReservationAuthority authority, long version, UUID catalogItemId, int quantity) {
        return new PreviewRequest(authority, version, 6, "CC-1800", "No stored PIN",
                List.of(new ServiceLineRequest(catalogItemId, quantity,
                        mapper.createObjectNode())));
    }

    protected static FulfillmentUpdateRequest fulfillment(
            long version, WorkState state, Long assigneeUserId, String reason) {
        return new FulfillmentUpdateRequest(version, state, assigneeUserId,
                "provider-task-18", null, null, null, 0, reason, true);
    }

    protected static UUID workplaceBooking(Fixture fixture, OffsetDateTime startsAt) {
        return jdbc.queryForObject("""
                INSERT INTO wp_bookings (
                    tenant_id, resource_id, user_id, booked_for_display_name,
                    starts_at, ends_at, booking_status, policy_snapshot,
                    policy_snapshot_hash, require_check_in_snapshot,
                    check_in_lead_minutes_snapshot, auto_release_minutes_snapshot,
                    booking_retention_days_snapshot)
                VALUES (?, ?, ?, 'Service requester', ?, ?, 'RESERVED', '{}'::jsonb,
                        encode(digest('{}'::jsonb::TEXT, 'sha256'), 'hex'),
                        FALSE, 15, 0, 365)
                RETURNING booking_id
                """, UUID.class, fixture.tenant(), fixture.resource(), ACTOR,
                startsAt, startsAt.plusHours(1));
    }

    protected static UUID calendarReservation(Fixture fixture, OffsetDateTime startsAt) {
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_events (
                    event_id, tenant_id, calendar_id, organizer_user_id,
                    organizer_name, title, starts_at, ends_at)
                VALUES (?, ?, ?, ?, 'Service requester', 'Service meeting', ?, ?)
                """, event, fixture.tenant(), fixture.calendar(), ACTOR,
                startsAt, startsAt.plusHours(1));
        jdbc.update("""
                INSERT INTO cal_event_attendees (
                    tenant_id, event_id, attendee_user_id, attendee_email,
                    attendee_name, response_status)
                VALUES (?, ?, ?, ?, 'Visible attendee', 'ACCEPTED')
                """, fixture.tenant(), event, ACTOR + 1, "attendee-" + fixture.tenant() + "@test.invalid");
        jdbc.update("""
                INSERT INTO cal_resource_bookings (
                    tenant_id, event_id, resource_id, starts_at, ends_at,
                    requested_by, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, fixture.tenant(), event, fixture.calendarResource(),
                startsAt, startsAt.plusHours(1), ACTOR, ACTOR, ACTOR);
        return event;
    }

    protected static void addCalendarResourceBooking(
            Fixture fixture, UUID event, OffsetDateTime startsAt) {
        UUID resource = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_resources (
                    resource_id, tenant_id, resource_code, name_ko, name_en,
                    resource_type, site_name, capacity)
                VALUES (?, ?, ?, '추가 서비스 회의실', 'Additional services room',
                        'ROOM', 'Services site', 8)
                """, resource, fixture.tenant(), "CAL_ROOM_EXTRA_" + fixture.tenant());
        jdbc.update("""
                INSERT INTO cal_resource_bookings (
                    tenant_id, event_id, resource_id, starts_at, ends_at,
                    requested_by, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, fixture.tenant(), event, resource, startsAt, startsAt.plusHours(1),
                ACTOR, ACTOR, ACTOR);
    }

    protected static <T> T tx(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    protected record Fixture(
            long tenant,
            UUID site,
            UUID floor,
            UUID resource,
            UUID calendarResource,
            UUID calendar) { }
}
