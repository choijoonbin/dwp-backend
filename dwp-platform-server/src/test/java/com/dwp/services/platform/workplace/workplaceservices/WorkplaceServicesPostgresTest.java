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

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceServicesPostgresTest {
    private static final Instant FIXED = Instant.parse("2026-09-17T00:00:00Z");
    private static final OffsetDateTime NOW = OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC);
    private static final long ACTOR = 18_001L;
    private static final AtomicLong TENANTS = new AtomicLong(9_958_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static ObjectMapper mapper;
    private static WorkplaceServicesRepository repository;
    private static WorkplaceServiceOperationsRepository operationsRepository;
    private static WorkplaceServiceOperationsService operationsService;
    private static WorkplaceServicesService service;
    private static WorkplaceServiceOrderQueryService queryService;

    @BeforeAll
    static void migrateAndBuildActualService() {
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

    @Test
    void v258MigratesAndWorkplaceAndCalendarReservationsRemainOwnerVersionScoped() {
        Fixture fixture = fixture();
        CatalogCommandResult catalog = createCatalog(fixture, "MEETING_SUPPORT", 60);
        UUID workplace = workplaceBooking(fixture, NOW.plusDays(2));
        UUID calendar = calendarReservation(fixture, NOW.plusDays(3));

        assertThat(jdbc.queryForObject("""
                SELECT success FROM flyway_schema_history WHERE version = '258'
                """, Boolean.class)).isTrue();
        assertThat(service.catalog(fixture.tenant(), ACTOR,
                ReservationAuthority.WORKPLACE, workplace))
                .satisfies(value -> {
                    assertThat(value.reservationVersion()).isZero();
                    assertThat(value.items()).extracting(ServiceCatalogItem::catalogItemId)
                            .contains(catalog.item().item().catalogItemId());
                });
        assertThat(service.catalog(fixture.tenant(), ACTOR,
                ReservationAuthority.CALENDAR, calendar))
                .satisfies(value -> {
                    assertThat(value.reservationVersion()).isZero();
                    assertThat(value.siteReference()).isEqualTo(fixture.site().toString());
                });
        assertThatThrownBy(() -> service.catalog(fixture.tenant(), ACTOR + 1,
                ReservationAuthority.CALENDAR, calendar))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        addCalendarResourceBooking(fixture, calendar, NOW.plusDays(3));
        assertThatThrownBy(() -> service.catalog(fixture.tenant(), ACTOR,
                ReservationAuthority.CALENDAR, calendar))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.catalog(fixture.tenant(), ACTOR + 2,
                ReservationAuthority.WORKPLACE, workplace))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> tx(() -> service.preview(
                fixture.tenant(), ACTOR, workplace,
                previewRequest(ReservationAuthority.WORKPLACE, 99,
                        catalog.item().item().catalogItemId(), 1))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
    }

    @Test
    void v262CapacityAssignmentAndRequesterInspectionCompleteQuantityOneOrder() {
        Fixture fixture = fixture();
        CatalogCommandResult catalog = createGovernedCatalog(fixture, "ROOM_SETUP_INSPECTED");
        UUID catalogItemId = catalog.item().item().catalogItemId();
        OffsetDateTime startsAt = NOW.plusDays(2);
        OffsetDateTime endsAt = startsAt.plusHours(1);

        CapacityUpsertResult capacity = tx(() -> operationsService.upsertCapacity(
                fixture.tenant(), ACTOR, catalogItemId,
                "capacity-" + fixture.tenant(), new CapacityUpsertRequest(
                        fixture.site().toString(), List.of(new CapacityBucketInput(
                                startsAt, endsAt, 4, "capacity-source-v1", NOW)),
                        true, "Publish verified onsite capacity"), "corr-capacity"));
        assertThat(capacity.capacity().complete()).isTrue();
        assertThat(capacity.capacity().buckets()).singleElement()
                .extracting(CapacityBucket::availableQuantity).isEqualTo(4);

        UUID booking = workplaceBooking(fixture, startsAt);
        ServiceOrderPreview preview = tx(() -> service.preview(fixture.tenant(), ACTOR, booking,
                previewRequest(ReservationAuthority.WORKPLACE, 0, catalogItemId, 1)));
        assertThat(preview.eligible()).isTrue();
        assertThat(preview.lines()).singleElement().satisfies(line -> {
            assertThat(line.capacityReservation()).isNotNull();
            assertThat(line.capacityReservation().holdIds()).hasSize(1);
            assertThat(line.inspectionMode()).isEqualTo(InspectionMode.REQUESTER);
        });
        ServiceOrder submitted = tx(() -> service.submit(fixture.tenant(), ACTOR, booking,
                "submit-governed-" + fixture.tenant(),
                new SubmitRequest(preview.previewId(), 0, true,
                        "Submit governed room setup"), "corr-governed-submit")).order();
        assertThat(operationsService.capacity(fixture.tenant(), catalogItemId,
                fixture.site().toString(), startsAt, endsAt).buckets())
                .singleElement().satisfies(bucket -> {
                    assertThat(bucket.committedQuantity()).isEqualTo(1);
                    assertThat(bucket.availableQuantity()).isEqualTo(3);
                });

        jdbc.update("""
                INSERT INTO wp_service_assignee_directory_entries (
                    tenant_id, directory_subject_id, public_display_name,
                    provider_codes, site_scope, capabilities, contact_available,
                    active, directory_version, source_observed_at, received_at,
                    fresh_until, updated_at)
                VALUES (?, 'operator-18', 'Alex Kim',
                        '["DWP_NATIVE_FULFILLMENT"]'::jsonb, ?::jsonb,
                        '["WORKPLACE_SERVICE_FULFILLMENT"]'::jsonb, TRUE,
                        TRUE, 'directory-v1', ?, ?, ?, ?)
                """, fixture.tenant(), mapper.createArrayNode()
                        .add(fixture.site().toString()).toString(),
                NOW, NOW, NOW.plusHours(1), NOW);
        ProviderProfile provider = operationsService.providers(fixture.tenant()).items().stream()
                .filter(item -> item.providerCode().equals("DWP_NATIVE_FULFILLMENT"))
                .findFirst().orElseThrow();
        AssigneeProjection assignee = operationsService.searchAssignees(fixture.tenant(),
                "FULFILLMENT", provider.providerProfileId(), fixture.site().toString(),
                "Alex", 20).items().getFirst();
        assertThat(assignee.directorySubjectId()).isEqualTo("operator-18");

        FulfillmentTask submittedTask = submitted.tasks().getFirst();
        AssigneeAssignmentResult assigned = tx(() -> operationsService.assign(
                fixture.tenant(), ACTOR, submitted.serviceOrderId(),
                submittedTask.fulfillmentTaskId(), "assign-" + fixture.tenant(),
                new AssigneeAssignmentRequest(assignee.directorySubjectId(),
                        submittedTask.version(), true, "Assign verified operator"),
                "corr-assign"));
        assertThat(assigned.taskVersion()).isEqualTo(submittedTask.version() + 1);

        ServiceOrder accepted = tx(() -> service.updateFulfillment(fixture.tenant(), ACTOR,
                submitted.serviceOrderId(), submittedTask.fulfillmentTaskId(),
                "accept-governed-" + fixture.tenant(),
                new FulfillmentUpdateRequest(assigned.taskVersion(), WorkState.ACCEPTED,
                        null, "provider-room-setup", null, null, null, 0,
                        "Accept governed task", true), "corr-governed-accept")).order();
        FulfillmentTask acceptedTask = accepted.tasks().getFirst();
        ServiceOrder preparing = tx(() -> service.updateFulfillment(fixture.tenant(), ACTOR,
                submitted.serviceOrderId(), acceptedTask.fulfillmentTaskId(),
                "prepare-governed-" + fixture.tenant(),
                new FulfillmentUpdateRequest(acceptedTask.version(), WorkState.IN_PREPARATION,
                        null, "provider-room-setup", null, null, null, 0,
                        "Room setup is ready for requester inspection", true),
                "corr-governed-prepare")).order();
        FulfillmentTask preparingTask = preparing.tasks().getFirst();
        UUID lineId = preparingTask.serviceOrderLineId();
        var checklist = mapper.createObjectNode().put("roomReady", true);
        InspectionCommandResult inspection = tx(() -> operationsService.inspect(
                fixture.tenant(), ACTOR, preparing.serviceOrderId(), lineId, false,
                "inspect-governed-" + fixture.tenant(), new InspectionAttemptRequest(
                        InspectionDecision.PASSED, checklist, List.of(), preparing.version(),
                        preparingTask.version(), true, "Requester accepted final setup"),
                "corr-governed-inspection"));
        assertThat(inspection.inspection().accepted()).isTrue();
        assertThat(inspection.inspection().fulfilledQuantityReady()).isTrue();

        ServiceOrder completed = tx(() -> service.updateFulfillment(fixture.tenant(), ACTOR,
                preparing.serviceOrderId(), preparingTask.fulfillmentTaskId(),
                "fulfill-governed-" + fixture.tenant(),
                new FulfillmentUpdateRequest(preparingTask.version(), WorkState.FULFILLED,
                        null, "provider-room-setup", null, null, "Completed after inspection", 1,
                        "Complete inspected setup", true), "corr-governed-fulfilled")).order();
        assertThat(completed.state()).isEqualTo(OrderState.FULFILLED);
        assertThat(completed.lines()).singleElement()
                .extracting(ServiceOrderLine::fulfilledQuantity).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT success FROM flyway_schema_history WHERE version = '262'
                """, Boolean.class)).isTrue();
    }

    @Test
    void previewSubmitPartialUnknownRecoveryAndCancellationAreDurableAndAudited() {
        Fixture fixture = fixture();
        UUID catalogItem = createCatalog(fixture, "CATERING_LIGHT", 60)
                .item().item().catalogItemId();
        UUID booking = workplaceBooking(fixture, NOW.plusDays(2));
        ServiceOrderPreview preview = tx(() -> service.preview(
                fixture.tenant(), ACTOR, booking,
                previewRequest(ReservationAuthority.WORKPLACE, 0, catalogItem, 2)));
        assertThat(preview.eligible()).isTrue();

        SubmitRequest submit = new SubmitRequest(preview.previewId(), 0, true,
                "Prepare the reserved room");
        ServiceOrderCommandResult created = tx(() -> service.submit(
                fixture.tenant(), ACTOR, booking, "submit-" + fixture.tenant(), submit,
                "corr-submit"));
        ServiceOrderCommandResult replay = tx(() -> service.submit(
                fixture.tenant(), ACTOR, booking, "submit-" + fixture.tenant(), submit,
                "ignored-correlation"));
        assertThat(replay.receipt().replayed()).isTrue();
        assertThat(replay.order().serviceOrderId()).isEqualTo(created.order().serviceOrderId());
        assertThatThrownBy(() -> tx(() -> service.submit(
                fixture.tenant(), ACTOR, booking, "submit-" + fixture.tenant(),
                new SubmitRequest(preview.previewId(), 0, true, "Different command"),
                "corr-conflict")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        UUID orderId = created.order().serviceOrderId();
        FulfillmentTask task = created.order().tasks().getFirst();
        ServiceOrderCommandResult accepted = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, orderId, task.fulfillmentTaskId(),
                "accepted-" + fixture.tenant(),
                new FulfillmentUpdateRequest(task.version(), WorkState.ACCEPTED,
                        ACTOR, "provider-task-18", null, null, null, 0,
                        "Accept fulfillment", true), "corr-accepted"));
        FulfillmentTask acceptedTask = accepted.order().tasks().getFirst();
        ServiceOrderCommandResult preparing = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, orderId, acceptedTask.fulfillmentTaskId(),
                "preparing-" + fixture.tenant(),
                new FulfillmentUpdateRequest(acceptedTask.version(), WorkState.IN_PREPARATION,
                        ACTOR, "provider-task-18", null, null, null, 0,
                        "Start preparation", true), "corr-preparing"));
        FulfillmentTask preparingTask = preparing.order().tasks().getFirst();
        ServiceOrderCommandResult partial = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, orderId, preparingTask.fulfillmentTaskId(),
                "partial-" + fixture.tenant(),
                new FulfillmentUpdateRequest(preparingTask.version(), WorkState.PARTIALLY_FULFILLED,
                        ACTOR, "provider-task-18", null, null, "One of two delivered", 1,
                        "Record partial delivery", true), "corr-partial"));
        assertThat(partial.order().state()).isEqualTo(OrderState.PARTIALLY_FULFILLED);

        FulfillmentTask partialTask = partial.order().tasks().getFirst();
        ServiceOrderCommandResult unknown = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, orderId, partialTask.fulfillmentTaskId(),
                "unknown-" + fixture.tenant(),
                new FulfillmentUpdateRequest(partialTask.version(), WorkState.RESULT_UNKNOWN,
                        ACTOR, "provider-task-18", null, null,
                        "Provider accepted the request but no terminal receipt was returned", 1,
                        "Recover by status query", true), "corr-unknown"));
        assertThat(unknown.receipt().state()).isEqualTo(CommandState.RESULT_UNKNOWN);
        ServiceOrder recovered = service.ownOrder(fixture.tenant(), ACTOR, orderId);
        assertThat(recovered.state()).isEqualTo(OrderState.RESULT_UNKNOWN);
        assertThat(recovered.tasks()).singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo(WorkState.RESULT_UNKNOWN);
            assertThat(value.resultDetail()).contains("no terminal receipt");
        });

        assertThatThrownBy(() -> tx(() -> service.cancel(
                fixture.tenant(), ACTOR, orderId, "cancel-" + fixture.tenant(),
                new CancelRequest(recovered.version(), true, "Meeting no longer needs services"),
                "corr-cancel")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND aggregate_type = 'WORKPLACE_SERVICE_ORDER'
                """, Long.class, fixture.tenant())).isEqualTo(5L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_service_order_outbox
                 WHERE tenant_id = ? AND aggregate_id = ?
                """, Long.class, fixture.tenant(), orderId)).isEqualTo(5L);
    }

    @Test
    void cancellationCutoffAndCrossTenantCatalogOrderTaskReferencesFailClosed() {
        Fixture owner = fixture();
        Fixture other = fixture();
        UUID catalogItem = createCatalog(owner, "LATE_SUPPORT", 60)
                .item().item().catalogItemId();
        UUID nearBooking = workplaceBooking(owner, NOW.plusMinutes(30));
        ServiceOrderPreview preview = tx(() -> service.preview(owner.tenant(), ACTOR, nearBooking,
                previewRequest(ReservationAuthority.WORKPLACE, 0, catalogItem, 1)));
        ServiceOrder order = tx(() -> service.submit(owner.tenant(), ACTOR, nearBooking,
                "near-submit-" + owner.tenant(),
                new SubmitRequest(preview.previewId(), 0, true, "Late support"),
                "corr-near")).order();
        jdbc.update("""
                UPDATE wp_service_catalog_items
                   SET lifecycle_state = 'INACTIVE', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND catalog_item_id = ?
                """, NOW, owner.tenant(), catalogItem);
        assertThatThrownBy(() -> tx(() -> service.cancel(owner.tenant(), ACTOR,
                order.serviceOrderId(), "near-cancel-" + owner.tenant(),
                new CancelRequest(order.version(), true, "Too late"), "corr-near-cancel")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        jdbc.update("""
                INSERT INTO wp_service_provider_truth (
                    tenant_id, provider_code, configured, configuration_version,
                    observed_configuration_version, reported_state, evidence_reference,
                    observed_at, received_at)
                VALUES (?, 'OWNER_ONLY', TRUE, 1, 1, 'HEALTHY', 'owner-only-v1', ?, ?)
                """, owner.tenant(), NOW, NOW);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wp_service_catalog_items (
                    catalog_item_id, tenant_id, service_code, category, name_ko, name_en,
                    provider_code, supported_resource_types, unit_price, currency,
                    minimum_quantity, maximum_quantity, order_cutoff_minutes,
                    cancellation_cutoff_minutes, cancellation_policy_ko,
                    cancellation_policy_en)
                VALUES (?, ?, 'CROSS_TENANT', 'AV', '교차', 'Cross tenant', 'OWNER_ONLY',
                        '["ROOM"]'::jsonb, 0, 'KRW', 1, 1, 0, 0, '없음', 'None')
                """, UUID.randomUUID(), other.tenant()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> repository.createOrder(new OrderRow(
                UUID.randomUUID(), other.tenant(), ACTOR, preview.previewId(),
                ReservationAuthority.WORKPLACE, nearBooking, 0, NOW.plusMinutes(30),
                NOW.plusMinutes(90), other.site().toString(), other.resource().toString(),
                1, null, BigDecimal.ZERO, "KRW", null, OrderState.SUBMITTED,
                ReservationImpact.NONE, false, null, null, 1, NOW, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);

        FulfillmentTask task = order.tasks().getFirst();
        assertThatThrownBy(() -> repository.createTask(new TaskRow(
                UUID.randomUUID(), other.tenant(), order.serviceOrderId(),
                task.serviceOrderLineId(), WorkState.SUBMITTED, "DWP_NATIVE_FULFILLMENT",
                null, null, null, null, NOW.plusMinutes(10), null,
                null, null, null, null, null, null, null,
                1, NOW, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> service.adminOrder(other.tenant(), order.serviceOrderId()))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void catalogScopeAndImmutablePreviewPolicyAreRevalidatedAtSubmitAndReconfirm() {
        Fixture fixture = fixture();
        UUID catalogItem = createCatalog(fixture, "SCOPED_SUPPORT", 60)
                .item().item().catalogItemId();
        UUID booking = workplaceBooking(fixture, NOW.plusDays(2));
        ServiceOrderPreview preview = tx(() -> service.preview(
                fixture.tenant(), ACTOR, booking,
                previewRequest(ReservationAuthority.WORKPLACE, 0, catalogItem, 1)));

        assertThat(preview.eligible()).isTrue();
        assertThat(preview.lines()).singleElement().satisfies(line -> {
            assertThat(line.catalogVersion()).isEqualTo(1L);
            assertThat(line.providerCode()).isEqualTo("DWP_NATIVE_FULFILLMENT");
            assertThat(line.providerConfigurationVersion()).isEqualTo(1L);
            assertThat(line.siteScope()).containsExactly(fixture.site());
            assertThat(line.supportedResourceTypes()).containsExactly("ROOM");
            assertThat(line.optionSchema()).isEqualTo(mapper.createArrayNode());
            assertThat(line.minimumQuantity()).isEqualTo(1);
            assertThat(line.maximumQuantity()).isEqualTo(10);
            assertThat(line.orderCutoffMinutes()).isZero();
            assertThat(line.cancellationCutoffMinutes()).isEqualTo(60);
            assertThat(line.unitPrice()).isEqualByComparingTo("12500");
            assertThat(line.currency()).isEqualTo("KRW");
        });

        jdbc.update("""
                UPDATE wp_service_catalog_items SET unit_price = unit_price + 1
                 WHERE tenant_id = ? AND catalog_item_id = ?
                """, fixture.tenant(), catalogItem);
        SubmitRequest submit = new SubmitRequest(preview.previewId(), 0, true,
                "Submit immutable policy snapshot");
        assertThatThrownBy(() -> tx(() -> service.submit(
                fixture.tenant(), ACTOR, booking, "stale-submit-" + fixture.tenant(),
                submit, "corr-stale-submit")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        jdbc.update("""
                UPDATE wp_service_catalog_items SET unit_price = unit_price - 1
                 WHERE tenant_id = ? AND catalog_item_id = ?
                """, fixture.tenant(), catalogItem);
        UUID mismatchedScope = UUID.randomUUID();
        jdbc.update("""
                UPDATE wp_service_catalog_items SET site_scope = jsonb_build_array(?::uuid::text)
                 WHERE tenant_id = ? AND catalog_item_id = ?
                """, mismatchedScope, fixture.tenant(), catalogItem);
        assertThatThrownBy(() -> tx(() -> service.submit(
                fixture.tenant(), ACTOR, booking, "scope-submit-" + fixture.tenant(),
                submit, "corr-scope-submit")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        jdbc.update("""
                UPDATE wp_service_catalog_items SET site_scope = jsonb_build_array(?::uuid::text)
                 WHERE tenant_id = ? AND catalog_item_id = ?
                """, fixture.site(), fixture.tenant(), catalogItem);
        ServiceOrder order = tx(() -> service.submit(
                fixture.tenant(), ACTOR, booking, "valid-submit-" + fixture.tenant(),
                submit, "corr-valid-submit")).order();

        UUID otherSite = UUID.randomUUID();
        UUID otherFloor = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_sites (site_id, tenant_id, site_code, name_ko, name_en)
                VALUES (?, ?, ?, '다른 사이트', 'Other site')
                """, otherSite, fixture.tenant(), "OTHER_" + fixture.tenant());
        jdbc.update("""
                INSERT INTO wp_floors (
                    floor_id, tenant_id, site_id, floor_number, name_ko, name_en)
                VALUES (?, ?, ?, 3, '3층', '3F')
                """, otherFloor, fixture.tenant(), otherSite);
        UUID otherCalendarResource = UUID.randomUUID();
        UUID otherResource = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cal_resources (
                    resource_id, tenant_id, resource_code, name_ko, name_en,
                    resource_type, site_name, capacity)
                VALUES (?, ?, ?, '다른 서비스 회의실', 'Other services room',
                        'ROOM', 'Other site', 12)
                """, otherCalendarResource, fixture.tenant(),
                "CAL_OTHER_" + fixture.tenant());
        jdbc.update("""
                INSERT INTO wp_resources (
                    resource_id, tenant_id, floor_id, calendar_resource_id,
                    resource_code, name_ko, name_en, resource_type, capacity)
                VALUES (?, ?, ?, ?, ?, '다른 서비스 회의실', 'Other services room', 'ROOM', 12)
                """, otherResource, fixture.tenant(), otherFloor, otherCalendarResource,
                "ROOM_OTHER_" + fixture.tenant());
        jdbc.update("""
                UPDATE wp_bookings SET resource_id = ?, version = version + 1
                 WHERE tenant_id = ? AND booking_id = ?
                """, otherResource, fixture.tenant(), booking);

        ServiceOrderPreview mismatched = tx(() -> service.preview(
                fixture.tenant(), ACTOR, booking,
                previewRequest(ReservationAuthority.WORKPLACE, 1, catalogItem, 1)));
        assertThat(mismatched.eligible()).isFalse();
        assertThat(mismatched.limitations()).contains("SCOPED_SUPPORT:SITE_SCOPE_MISMATCH");
        ServiceOrder impacted = service.ownOrder(
                fixture.tenant(), ACTOR, order.serviceOrderId());
        assertThatThrownBy(() -> tx(() -> service.reconfirm(
                fixture.tenant(), ACTOR, order.serviceOrderId(),
                "scoped-reconfirm-" + fixture.tenant(),
                new ReconfirmRequest(impacted.version(), 1, true,
                        "Reconfirm after authoritative site change"), "corr-scoped-reconfirm")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void fulfillmentFailsClosedForInvalidTransitionsProviderTruthAndUnknownAssignees() {
        Fixture fixture = fixture();
        UUID catalogItem = createCatalog(fixture, "FULFILLMENT_GUARD", 0)
                .item().item().catalogItemId();
        UUID booking = workplaceBooking(fixture, NOW.plusDays(2));
        ServiceOrderPreview preview = tx(() -> service.preview(
                fixture.tenant(), ACTOR, booking,
                previewRequest(ReservationAuthority.WORKPLACE, 0, catalogItem, 1)));
        ServiceOrder order = tx(() -> service.submit(
                fixture.tenant(), ACTOR, booking, "guard-submit-" + fixture.tenant(),
                new SubmitRequest(preview.previewId(), 0, true, "Guard fulfillment"),
                "corr-guard-submit")).order();
        FulfillmentTask task = order.tasks().getFirst();

        assertThatThrownBy(() -> tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, order.serviceOrderId(), task.fulfillmentTaskId(),
                "skip-state-" + fixture.tenant(),
                fulfillment(task.version(), WorkState.FULFILLED, ACTOR,
                        "Skip required states"), "corr-skip-state")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        jdbc.update("""
                UPDATE wp_service_catalog_items
                   SET lifecycle_state = 'INACTIVE', version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND catalog_item_id = ?
                """, NOW, fixture.tenant(), catalogItem);
        ServiceOrder accepted = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, order.serviceOrderId(), task.fulfillmentTaskId(),
                "inactive-accepted-" + fixture.tenant(),
                fulfillment(task.version(), WorkState.ACCEPTED, ACTOR,
                        "Continue an in-flight order"), "corr-inactive-accepted")).order();
        FulfillmentTask acceptedTask = accepted.tasks().getFirst();

        jdbc.update("""
                UPDATE wp_service_provider_truth SET configured = FALSE
                 WHERE tenant_id = ? AND provider_code = 'DWP_NATIVE_FULFILLMENT'
                """, fixture.tenant());
        assertThatThrownBy(() -> tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, order.serviceOrderId(), acceptedTask.fulfillmentTaskId(),
                "provider-not-ready-" + fixture.tenant(),
                fulfillment(acceptedTask.version(), WorkState.IN_PREPARATION, ACTOR,
                        "Provider is not ready"), "corr-provider-not-ready")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        jdbc.update("""
                UPDATE wp_service_provider_truth SET configured = TRUE
                 WHERE tenant_id = ? AND provider_code = 'DWP_NATIVE_FULFILLMENT'
                """, fixture.tenant());
        assertThatThrownBy(() -> tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, order.serviceOrderId(), acceptedTask.fulfillmentTaskId(),
                "unknown-assignee-" + fixture.tenant(),
                fulfillment(acceptedTask.version(), WorkState.IN_PREPARATION, ACTOR + 1,
                        "Attempt arbitrary assignment"), "corr-unknown-assignee")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        ServiceOrder preparing = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, order.serviceOrderId(), acceptedTask.fulfillmentTaskId(),
                "self-assignee-" + fixture.tenant(),
                fulfillment(acceptedTask.version(), WorkState.IN_PREPARATION, ACTOR,
                        "Use the authenticated capable operator"), "corr-self-assignee")).order();
        assertThatThrownBy(() -> tx(() -> service.cancel(
                fixture.tenant(), ACTOR, order.serviceOrderId(),
                "guard-cancel-" + fixture.tenant(),
                new CancelRequest(preparing.version(), true, "Cancel guarded order"),
                "corr-guard-cancel")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        ServiceOrder partiallyFulfilled = submitFreeNativeOrder(
                fixture, "FREE_NATIVE_PARTIAL", 2, NOW.plusDays(3));
        FulfillmentTask freeTask = partiallyFulfilled.tasks().getFirst();
        ServiceOrder freeAccepted = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, partiallyFulfilled.serviceOrderId(),
                freeTask.fulfillmentTaskId(), "free-accepted-" + fixture.tenant(),
                fulfillment(freeTask.version(), WorkState.ACCEPTED, ACTOR,
                        "Accept free native fulfillment"), "corr-free-accepted")).order();
        FulfillmentTask freeAcceptedTask = freeAccepted.tasks().getFirst();
        ServiceOrder freePreparing = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, partiallyFulfilled.serviceOrderId(),
                freeAcceptedTask.fulfillmentTaskId(), "free-preparing-" + fixture.tenant(),
                fulfillment(freeAcceptedTask.version(), WorkState.IN_PREPARATION, ACTOR,
                        "Prepare free native fulfillment"), "corr-free-preparing")).order();
        FulfillmentTask freePreparingTask = freePreparing.tasks().getFirst();
        assertThatThrownBy(() -> tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, partiallyFulfilled.serviceOrderId(),
                freePreparingTask.fulfillmentTaskId(),
                "free-null-partial-" + fixture.tenant(),
                new FulfillmentUpdateRequest(freePreparingTask.version(),
                        WorkState.PARTIALLY_FULFILLED, ACTOR, "provider-task-free",
                        null, null, null, null,
                        "Reject missing partial quantity", true),
                "corr-free-null-partial")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThatThrownBy(() -> tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, partiallyFulfilled.serviceOrderId(),
                freePreparingTask.fulfillmentTaskId(),
                "free-admin-cancel-" + fixture.tenant(),
                new FulfillmentUpdateRequest(freePreparingTask.version(),
                        WorkState.CANCELLED, ACTOR, null, null, null, null, 0,
                        "Reject task cancellation bypass", true),
                "corr-free-admin-cancel")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        ServiceOrder freePartial = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, partiallyFulfilled.serviceOrderId(),
                freePreparingTask.fulfillmentTaskId(), "free-partial-" + fixture.tenant(),
                new FulfillmentUpdateRequest(freePreparingTask.version(),
                        WorkState.PARTIALLY_FULFILLED, ACTOR, "provider-task-free",
                        null, null, "One of two fulfilled", 1,
                        "Record partial free fulfillment", true),
                "corr-free-partial")).order();
        assertThatThrownBy(() -> tx(() -> service.cancel(
                fixture.tenant(), ACTOR, freePartial.serviceOrderId(),
                "free-partial-cancel-" + fixture.tenant(),
                new CancelRequest(freePartial.version(), true,
                        "Do not overwrite fulfilled quantity"),
                "corr-free-partial-cancel")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        FulfillmentTask freePartialTask = freePartial.tasks().getFirst();
        ServiceOrder freeFulfilled = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, freePartial.serviceOrderId(),
                freePartialTask.fulfillmentTaskId(),
                "free-fulfilled-" + fixture.tenant(),
                new FulfillmentUpdateRequest(freePartialTask.version(), WorkState.FULFILLED,
                        ACTOR, "provider-task-free", null, null, "Two of two fulfilled", 2,
                        "Record complete free fulfillment", true),
                "corr-free-fulfilled")).order();
        assertThat(freeFulfilled.state()).isEqualTo(OrderState.FULFILLED);
        assertThat(freeFulfilled.lines()).singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo(WorkState.FULFILLED);
            assertThat(value.fulfilledQuantity()).isEqualTo(2);
            assertThat(value.cancelledQuantity()).isZero();
        });
        assertThat(freeFulfilled.tasks()).singleElement().satisfies(value ->
                assertThat(value.state()).isEqualTo(WorkState.FULFILLED));

        ServiceOrder freeOrder = submitFreeNativeOrder(
                fixture, "FREE_NATIVE_CANCEL", 1, NOW.plusDays(4));
        ServiceOrder cancelled = tx(() -> service.cancel(
                fixture.tenant(), ACTOR, freeOrder.serviceOrderId(),
                "free-cancel-" + fixture.tenant(),
                new CancelRequest(freeOrder.version(), true, "Cancel free native fulfillment"),
                "corr-free-cancel")).order();
        assertThat(cancelled.lines()).singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo(WorkState.CANCELLED);
            assertThat(value.cancelledQuantity()).isEqualTo(value.quantity());
            assertThat(value.fulfilledQuantity()).isZero();
            assertThat(value.refundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        });
        FulfillmentTask cancelledTask = cancelled.tasks().getFirst();
        assertThat(cancelledTask.state()).isEqualTo(WorkState.CANCELLED);
        assertThatThrownBy(() -> tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, freeOrder.serviceOrderId(),
                cancelledTask.fulfillmentTaskId(), "cancelled-update-" + fixture.tenant(),
                fulfillment(cancelledTask.version(), WorkState.ACCEPTED, ACTOR,
                        "Attempt update after cancellation"), "corr-cancelled-update")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void messageAttachmentAndReservationReconfirmationRemainOwnedIdempotentAndAudited() {
        Fixture fixture = fixture();
        UUID catalogItem = createCatalog(fixture, "COLLABORATION_SUPPORT", 60)
                .item().item().catalogItemId();
        UUID booking = workplaceBooking(fixture, NOW.plusDays(2));
        ServiceOrderPreview preview = tx(() -> service.preview(
                fixture.tenant(), ACTOR, booking,
                previewRequest(ReservationAuthority.WORKPLACE, 0, catalogItem, 1)));
        ServiceOrder order = tx(() -> service.submit(
                fixture.tenant(), ACTOR, booking, "collaboration-submit-" + fixture.tenant(),
                new SubmitRequest(preview.previewId(), 0, true, "Prepare service collaboration"),
                "corr-collaboration")).order();

        ServiceOrderCommandResult userMessage = tx(() -> service.addMessage(
                fixture.tenant(), ACTOR, order.serviceOrderId(), false,
                "user-message-" + fixture.tenant(),
                new MessageRequest(order.version(), "Please use the east entrance.", true,
                        "Share fulfillment instruction"), "corr-user-message"));
        ServiceOrderCommandResult adminMessage = tx(() -> service.addMessage(
                fixture.tenant(), ACTOR + 9, order.serviceOrderId(), true,
                "admin-message-" + fixture.tenant(),
                new MessageRequest(userMessage.order().version(), "Instruction received.", true,
                        "Acknowledge requester instruction"), "corr-admin-message"));
        assertThat(repository.messages(fixture.tenant(), order.serviceOrderId()))
                .extracting(ServiceOrderMessage::message)
                .containsExactlyInAnyOrder("Please use the east entrance.", "Instruction received.");
        assertThatThrownBy(() -> tx(() -> service.addMessage(
                fixture.tenant(), ACTOR + 1, order.serviceOrderId(), false,
                "other-message-" + fixture.tenant(),
                new MessageRequest(adminMessage.order().version(), "Unauthorized", true,
                        "Attempt cross-user write"), "corr-other")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        AtomicInteger stores = new AtomicInteger();
        AttachmentUpload attachment = new AttachmentUpload(
                "room-layout.pdf", "application/pdf", 9, "a".repeat(64));
        String attachmentKey = fixture.tenant() + "/workplace/service-orders/"
                + order.serviceOrderId() + "/opaque.pdf";
        ServiceOrderCommandResult linked = tx(() -> service.linkAttachment(
                fixture.tenant(), ACTOR, order.serviceOrderId(), false,
                "attachment-" + fixture.tenant(), adminMessage.order().version(),
                "Provide approved room layout", attachment,
                () -> {
                    stores.incrementAndGet();
                    return attachmentKey;
                }, "corr-attachment"));
        ServiceOrderCommandResult replay = tx(() -> service.linkAttachment(
                fixture.tenant(), ACTOR, order.serviceOrderId(), false,
                "attachment-" + fixture.tenant(), adminMessage.order().version(),
                "Provide approved room layout", attachment,
                () -> {
                    stores.incrementAndGet();
                    return "must-not-be-stored";
                }, "ignored"));
        assertThat(stores).hasValue(1);
        assertThat(replay.receipt().replayed()).isTrue();
        assertThat(repository.attachments(fixture.tenant(), order.serviceOrderId()))
                .singleElement().satisfies(value -> {
            assertThat(value.fileName()).isEqualTo("room-layout.pdf");
            assertThat(repository.attachment(fixture.tenant(), order.serviceOrderId(),
                    value.attachmentId())).get().extracting(AttachmentRow::storageReference)
                    .isEqualTo(attachmentKey);
            assertThat(repository.attachment(fixture.tenant() + 1, order.serviceOrderId(),
                    value.attachmentId())).isEmpty();
        });

        jdbc.update("""
                UPDATE wp_bookings
                   SET starts_at = starts_at + INTERVAL '30 minutes',
                       ends_at = ends_at + INTERVAL '30 minutes', version = version + 1
                 WHERE tenant_id = ? AND booking_id = ?
                """, fixture.tenant(), booking);
        ServiceOrder impacted = service.ownOrder(
                fixture.tenant(), ACTOR, order.serviceOrderId());
        assertThat(impacted.reservationImpact())
                .isEqualTo(ReservationImpact.RECONFIRMATION_REQUIRED);
        assertThat(impacted.reconfirmationRequired()).isTrue();
        ServiceOrderCommandResult reconfirmed = tx(() -> service.reconfirm(
                fixture.tenant(), ACTOR, order.serviceOrderId(),
                "reconfirm-" + fixture.tenant(),
                new ReconfirmRequest(impacted.version(), 1, true,
                        "Accept the updated reservation window"), "corr-reconfirm"));
        assertThat(reconfirmed.order().reservationVersion()).isEqualTo(1);
        assertThat(reconfirmed.order().reservationImpact()).isEqualTo(ReservationImpact.NONE);
        assertThat(reconfirmed.order().reconfirmationRequired()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND aggregate_type = 'WORKPLACE_SERVICE_ORDER'
                   AND aggregate_id = ?
                """, Long.class, fixture.tenant(), order.serviceOrderId())).isEqualTo(5L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_service_order_outbox
                 WHERE tenant_id = ? AND aggregate_id = ?
                """, Long.class, fixture.tenant(), order.serviceOrderId())).isEqualTo(5L);
    }

    @Test
    void keysetPagesReturnAllOneHundredOneRowsWithoutDuplicatesAndBindScope() {
        Fixture fixture = fixture();
        UUID catalogItem = createCatalog(fixture, "PAGE_SUPPORT", 60)
                .item().item().catalogItemId();
        UUID booking = workplaceBooking(fixture, NOW.plusDays(2));
        ServiceOrderPreview preview = tx(() -> service.preview(
                fixture.tenant(), ACTOR, booking,
                previewRequest(ReservationAuthority.WORKPLACE, 0, catalogItem, 1)));
        List<UUID> orderIds = new java.util.ArrayList<>();
        for (int index = 0; index < 101; index++) {
            UUID orderId = new UUID(fixture.tenant(), index + 1L);
            orderIds.add(orderId);
            repository.createOrder(new OrderRow(orderId, fixture.tenant(), ACTOR,
                    preview.previewId(), ReservationAuthority.WORKPLACE, booking, 0,
                    preview.reservationStartsAt(), preview.reservationEndsAt(),
                    fixture.site().toString(), fixture.resource().toString(), 6, "CC-1800",
                    BigDecimal.ZERO, "KRW", null, OrderState.SUBMITTED,
                    ReservationImpact.NONE, false, null, null, 1, NOW, NOW));
        }

        ServiceOrders first = queryService.ownOrders(fixture.tenant(), ACTOR, null, 100);
        ServiceOrders second = queryService.ownOrders(
                fixture.tenant(), ACTOR, first.nextCursor(), 100);
        List<UUID> pagedOrders = new java.util.ArrayList<>();
        first.items().forEach(value -> pagedOrders.add(value.serviceOrderId()));
        second.items().forEach(value -> pagedOrders.add(value.serviceOrderId()));
        assertThat(first.items()).hasSize(100);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.items()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(new HashSet<>(pagedOrders)).containsExactlyInAnyOrderElementsOf(orderIds);
        assertThat(pagedOrders).doesNotHaveDuplicates();
        assertThatThrownBy(() -> queryService.ownOrders(
                fixture.tenant(), ACTOR + 1, first.nextCursor(), 100))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        assertThatThrownBy(() -> queryService.ownOrders(
                fixture.tenant() + 1, ACTOR, first.nextCursor(), 100))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        UUID orderId = orderIds.getFirst();
        for (int index = 0; index < 101; index++) {
            repository.appendEvent(fixture.tenant(), orderId, "SUBMITTED", ACTOR,
                    mapper.createObjectNode().put("reason", "internal-" + index)
                            .put("lineAdjustmentId", UUID.randomUUID().toString()), NOW);
            repository.addMessage(fixture.tenant(), orderId, UUID.randomUUID(), ACTOR,
                    "REQUESTER", "Message " + index, NOW);
            repository.addAttachment(fixture.tenant(), orderId, UUID.randomUUID(), ACTOR,
                    "opaque/" + index,
                    new AttachmentUpload("file-" + index + ".pdf", "application/pdf",
                            128, String.format("%064x", index + 1)), NOW);
        }
        RequesterServiceOrderEventsPage eventsOne = queryService.ownEvents(
                fixture.tenant(), ACTOR, orderId, null, 100);
        RequesterServiceOrderEventsPage eventsTwo = queryService.ownEvents(
                fixture.tenant(), ACTOR, orderId, eventsOne.nextCursor(), 100);
        assertPageIds(eventsOne.items().stream()
                        .map(RequesterServiceOrderEvent::eventId).toList(),
                eventsTwo.items().stream().map(RequesterServiceOrderEvent::eventId).toList());
        assertThat(eventsOne.items()).allSatisfy(event -> {
            assertThat(event.detail().has("reason")).isFalse();
            assertThat(event.detail().has("lineAdjustmentId")).isTrue();
        });
        RequesterServiceOrderMessagesPage messagesOne = queryService.ownMessages(
                fixture.tenant(), ACTOR, orderId, null, 100);
        RequesterServiceOrderMessagesPage messagesTwo = queryService.ownMessages(
                fixture.tenant(), ACTOR, orderId, messagesOne.nextCursor(), 100);
        assertPageIds(messagesOne.items().stream()
                        .map(RequesterServiceOrderMessage::messageId).toList(),
                messagesTwo.items().stream().map(RequesterServiceOrderMessage::messageId).toList());
        assertThat(mapper.valueToTree(messagesOne).toString())
                .doesNotContain("authorUserId", "actorUserId");
        ServiceOrderAttachmentsPage attachmentsOne = queryService.ownAttachments(
                fixture.tenant(), ACTOR, orderId, null, 100);
        ServiceOrderAttachmentsPage attachmentsTwo = queryService.ownAttachments(
                fixture.tenant(), ACTOR, orderId, attachmentsOne.nextCursor(), 100);
        assertPageIds(attachmentsOne.items().stream()
                        .map(ServiceOrderAttachment::attachmentId).toList(),
                attachmentsTwo.items().stream().map(ServiceOrderAttachment::attachmentId).toList());
        assertThatThrownBy(() -> queryService.ownEvents(
                fixture.tenant(), ACTOR + 1, orderId, null, 50))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(queryService.adminEvents(fixture.tenant(), orderId, null, 100).items())
                .allSatisfy(event -> assertThat(event.actorUserId()).isEqualTo(ACTOR));
        assertThatThrownBy(() -> queryService.adminEvents(
                fixture.tenant() + 1, orderId, null, 50))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void lineCancellationConcurrentKeyIsSingleProviderCallAndCrossPairingFailsInDatabase()
            throws Exception {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "LINE_CANCEL_RACE", 2, 60);
        ServiceOrderLine line = order.lines().getFirst();
        FakeLineAdjustmentProvider provider = new FakeLineAdjustmentProvider();
        WorkplaceServiceLineAdjustmentService adjustments =
                new WorkplaceServiceLineAdjustmentService(repository, mapper, provider,
                        Clock.fixed(FIXED, ZoneOffset.UTC));
        LineCancellationImpact firstPreview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(order.version(), line.version(), 1,
                        "Cancel one seat service")));
        LineCancellationImpact secondPreview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(order.version(), line.version(), 1,
                        "Cancel another seat service")));
        provider.cancelOutcome.set(new ProviderOutcome(OutcomeState.SUCCEEDED,
                "provider-operation-race", firstPreview.refundableAmount(),
                "refund-receipt-race", "Cancellation and refund confirmed"));
        LineCancellationRequest firstRequest = new LineCancellationRequest(
                firstPreview.cancellationPreviewId(), order.version(), line.version(), true,
                firstPreview.reason());
        LineCancellationRequest secondRequest = new LineCancellationRequest(
                secondPreview.cancellationPreviewId(), order.version(), line.version(), true,
                secondPreview.reason());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> firstCall = () -> concurrentCancel(adjustments, fixture, order,
                    line, "same-race-key", firstRequest, ready, start);
            Callable<Object> secondCall = () -> concurrentCancel(adjustments, fixture, order,
                    line, "same-race-key", secondRequest, ready, start);
            Future<Object> one = executor.submit(firstCall);
            Future<Object> two = executor.submit(secondCall);
            ready.await();
            start.countDown();
            List<Object> results = List.of(one.get(), two.get());
            assertThat(results).filteredOn(LineAdjustmentCommandResult.class::isInstance)
                    .hasSize(1);
            BaseException conflict = (BaseException) results.stream()
                    .filter(BaseException.class::isInstance).findFirst().orElseThrow();
            assertThat(conflict.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        }
        assertThat(provider.cancelCalls).hasValue(1);
        assertThat(provider.operationIds).hasSize(1);
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()).lines())
                .singleElement().satisfies(value -> {
                    assertThat(value.cancelledQuantity()).isEqualTo(1);
                    assertThat(value.refundedAmount())
                            .isEqualByComparingTo(firstPreview.refundableAmount());
                });

        ServiceOrder other = submitOrder(
                fixture, "LINE_PAIR_OTHER", 1, 60, NOW.plusDays(3));
        ServiceOrderLine otherLine = other.lines().getFirst();
        TaskRow sourceTask = repository.task(
                fixture.tenant(), order.tasks().getFirst().fulfillmentTaskId()).orElseThrow();
        assertThatThrownBy(() -> repository.createTask(new TaskRow(
                UUID.randomUUID(), fixture.tenant(), other.serviceOrderId(),
                sourceTask.lineId(), sourceTask.state(), sourceTask.providerCode(),
                sourceTask.assigneeUserId(), sourceTask.assigneeDirectorySubjectId(),
                sourceTask.assigneeDisplayName(), sourceTask.assigneeDirectoryVersion(),
                sourceTask.responseDueAt(), sourceTask.dueAt(),
                sourceTask.providerReceiptAt(), sourceTask.acceptedAt(), sourceTask.completedAt(),
                sourceTask.externalReference(), sourceTask.blockerCode(),
                sourceTask.blockerDetail(), sourceTask.resultDetail(), 1, NOW, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
        LineCancellationImpact valid = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(
                        service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()).version(),
                        service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId())
                                .lines().getFirst().version(), 1, "DB invariant")));
        assertThatThrownBy(() -> repository.createLineAdjustment(new LineAdjustmentRow(
                UUID.randomUUID(), fixture.tenant(), ACTOR, other.serviceOrderId(),
                otherLine.serviceOrderLineId(), valid.cancellationPreviewId(), 1,
                RefundScope.FULL, BigDecimal.ZERO, BigDecimal.ZERO, "KRW",
                LineAdjustmentState.REFUND_NOT_CONFIGURED, "DWP_NATIVE_FULFILLMENT",
                1, "internal://workplace-service-native", null, null, "invalid pairing",
                1, NOW, NOW))).isInstanceOf(DataIntegrityViolationException.class);
        LineRow persistedLine = repository.line(
                fixture.tenant(), order.serviceOrderId(), line.serviceOrderLineId()).orElseThrow();
        OrderRow persistedOrder = repository.order(
                fixture.tenant(), order.serviceOrderId()).orElseThrow();
        OrderRow otherPersistedOrder = repository.order(
                fixture.tenant(), other.serviceOrderId()).orElseThrow();
        assertThatThrownBy(() -> repository.saveLineCancellationPreview(
                cancellationPreview(fixture, otherPersistedOrder, persistedLine,
                        persistedLine.unitPrice())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> repository.saveLineCancellationPreview(
                cancellationPreview(fixture, persistedOrder, persistedLine,
                        persistedLine.unitPrice().multiply(BigDecimal.TEN))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void generalCommandAdvisoryLockReplaysConcurrentSameKeyInsteadOfLeakingUniqueConflicts()
            throws Exception {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "MESSAGE_IDEMPOTENCY", 1, 60);
        MessageRequest request = new MessageRequest(
                order.version(), "One durable requester message", true,
                "Prove concurrent replay");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<ServiceOrderCommandResult> first = () -> concurrentMessage(
                    fixture, order, request, ready, start);
            Callable<ServiceOrderCommandResult> second = () -> concurrentMessage(
                    fixture, order, request, ready, start);
            Future<ServiceOrderCommandResult> one = executor.submit(first);
            Future<ServiceOrderCommandResult> two = executor.submit(second);
            ready.await();
            start.countDown();
            List<ServiceOrderCommandResult> results = List.of(one.get(), two.get());
            assertThat(results).extracting(value -> value.receipt().replayed())
                    .containsExactlyInAnyOrder(false, true);
            assertThat(results.getFirst().receipt().commandId())
                    .isEqualTo(results.getLast().receipt().commandId());
        }
        assertThat(repository.messages(fixture.tenant(), order.serviceOrderId()))
                .singleElement().satisfies(value ->
                        assertThat(value.message()).isEqualTo("One durable requester message"));
    }

    @Test
    void providerRetryUsesDeterministicOperationIdAndOversizedEvidenceFailsControlled() {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "LINE_PROVIDER_BOUNDARY", 1, 60);
        ServiceOrderLine line = order.lines().getFirst();
        FakeLineAdjustmentProvider provider = new FakeLineAdjustmentProvider();
        provider.cancelFailure.set(new RuntimeException("transport failed before outcome"));
        WorkplaceServiceLineAdjustmentService adjustments =
                new WorkplaceServiceLineAdjustmentService(repository, mapper, provider,
                        Clock.fixed(FIXED, ZoneOffset.UTC));
        LineCancellationImpact preview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(order.version(), line.version(), 1,
                        "Deterministic provider retry")));
        LineCancellationRequest request = new LineCancellationRequest(
                preview.cancellationPreviewId(), order.version(), line.version(), true,
                preview.reason());
        LineAdjustmentCommandResult pending = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                "retry-provider", request, "corr-first"));
        assertThat(pending.adjustment().state())
                .isEqualTo(LineAdjustmentState.RECONCILIATION_PENDING);
        assertThat(pending.receipt().state()).isEqualTo(CommandState.RESULT_UNKNOWN);
        provider.cancelFailure.set(null);
        provider.reconcileOutcome.set(new ProviderOutcome(OutcomeState.SUCCEEDED,
                "x".repeat(321), preview.refundableAmount(), "refund-receipt-boundary",
                "Oversized evidence"));
        LineAdjustmentCommandResult stillPending = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                "retry-provider", request, "corr-second"));
        assertThat(stillPending.adjustment().state())
                .isEqualTo(LineAdjustmentState.RECONCILIATION_PENDING);
        provider.reconcileOutcome.set(new ProviderOutcome(OutcomeState.SUCCEEDED,
                "provider-operation-boundary", preview.refundableAmount(),
                "refund-receipt-boundary", "Authoritative provider receipt"));
        LineAdjustmentCommandResult recovered = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                "retry-provider", request, "corr-third"));
        assertThat(recovered.adjustment().state()).isEqualTo(LineAdjustmentState.REFUNDED);
        assertThat(provider.cancelCalls).hasValue(1);
        assertThat(provider.reconcileCalls).hasValue(2);
        assertThat(provider.operationIds).hasSize(1);
        UUID expectedOperationId = UUID.nameUUIDFromBytes(
                ("workplace-line-adjustment:" + fixture.tenant() + ':'
                        + preview.cancellationPreviewId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(provider.operationIds).containsExactly(expectedOperationId);
        assertThat(repository.lineAdjustmentByPreview(
                fixture.tenant(), ACTOR, preview.cancellationPreviewId()))
                .hasValueSatisfying(value ->
                        assertThat(value.state()).isEqualTo(LineAdjustmentState.REFUNDED));
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()).lines())
                .singleElement().satisfies(value -> assertThat(value.cancelledQuantity()).isOne());
    }

    @Test
    void unconfiguredProviderNeverClaimsPaidCancellationButAllowsFreeNativeCancellation() {
        Fixture fixture = fixture();
        FakeLineAdjustmentProvider provider = new FakeLineAdjustmentProvider();
        WorkplaceServiceLineAdjustmentService adjustments =
                new WorkplaceServiceLineAdjustmentService(repository, mapper, provider,
                        Clock.fixed(FIXED, ZoneOffset.UTC));

        ServiceOrder paid = submitOrder(fixture, "PAID_PROVIDER_GUARD", 1, 60);
        ServiceOrderLine paidLine = paid.lines().getFirst();
        LineCancellationImpact paidPreview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, paid.serviceOrderId(), paidLine.serviceOrderLineId(),
                new LineCancellationImpactRequest(paid.version(), paidLine.version(), 1,
                        "Require authoritative paid cancellation")));
        LineAdjustmentCommandResult manualReview = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, paid.serviceOrderId(), paidLine.serviceOrderLineId(),
                "paid-not-configured", new LineCancellationRequest(
                        paidPreview.cancellationPreviewId(), paid.version(), paidLine.version(),
                        true, paidPreview.reason()), "corr-paid-not-configured"));
        assertThat(manualReview.adjustment().state())
                .isEqualTo(LineAdjustmentState.REFUND_NOT_CONFIGURED);
        assertThat(manualReview.receipt().state()).isEqualTo(CommandState.FAILED);
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, paid.serviceOrderId()).lines())
                .singleElement().satisfies(value -> {
                    assertThat(value.cancelledQuantity()).isZero();
                    assertThat(value.refundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
                });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_service_order_events
                 WHERE tenant_id = ? AND service_order_id = ?
                   AND event_type = 'LINE_CANCELLATION_REQUIRES_REVIEW'
                """, Long.class, fixture.tenant(), paid.serviceOrderId())).isEqualTo(1L);
        assertThatThrownBy(() -> tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, paid.serviceOrderId(), paidLine.serviceOrderLineId(),
                new LineCancellationImpactRequest(paid.version(), paidLine.version(), 1,
                        "Wait for manual review"))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        provider.reconcileOutcome.set(new ProviderOutcome(OutcomeState.SUCCEEDED,
                "provider-operation-manual", paidPreview.refundableAmount(),
                "refund-receipt-manual", "Provider configured and refund confirmed"));
        LineAdjustmentCommandResult recovered = tx(() -> adjustments.reconcile(
                fixture.tenant(), ACTOR + 9, paid.serviceOrderId(),
                manualReview.adjustment().lineAdjustmentId(), "manual-review-reconcile",
                new LineAdjustmentReconcileRequest(manualReview.adjustment().version(), true,
                        "Reconcile after provider configuration"),
                "corr-manual-review-reconcile"));
        assertThat(recovered.adjustment().state()).isEqualTo(LineAdjustmentState.REFUNDED);
        assertThat(recovered.order().lines()).singleElement().satisfies(value -> {
            assertThat(value.cancelledQuantity()).isEqualTo(1);
            assertThat(value.refundedAmount())
                    .isEqualByComparingTo(paidPreview.refundableAmount());
        });

        ServiceOrder free = submitFreeNativeOrder(
                fixture, "FREE_PROVIDER_GUARD", 1, NOW.plusDays(3));
        ServiceOrderLine freeLine = free.lines().getFirst();
        LineCancellationImpact freePreview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, free.serviceOrderId(), freeLine.serviceOrderLineId(),
                new LineCancellationImpactRequest(free.version(), freeLine.version(), 1,
                        "Cancel free native service")));
        LineAdjustmentCommandResult localCancellation = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, free.serviceOrderId(), freeLine.serviceOrderLineId(),
                "free-not-configured", new LineCancellationRequest(
                        freePreview.cancellationPreviewId(), free.version(), freeLine.version(),
                        true, freePreview.reason()), "corr-free-not-configured"));
        assertThat(localCancellation.adjustment().state())
                .isEqualTo(LineAdjustmentState.CANCELLATION_SUCCEEDED);
        assertThat(localCancellation.receipt().state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(localCancellation.order().lines()).singleElement().satisfies(value -> {
            assertThat(value.cancelledQuantity()).isEqualTo(1);
            assertThat(value.refundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        });
    }

    @Test
    void resultUnknownPersistsStatusOnlyRecoveryAndReconcilesWithAuthoritativeReceipt() {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "LINE_RECONCILE", 2, 60);
        ServiceOrderLine line = order.lines().getFirst();
        FakeLineAdjustmentProvider provider = new FakeLineAdjustmentProvider();
        provider.cancelFailure.set(new OutcomeUncertainException(
                "provider-operation-18", "Provider accepted but timed out", null));
        WorkplaceServiceLineAdjustmentService adjustments =
                new WorkplaceServiceLineAdjustmentService(repository, mapper, provider,
                        Clock.fixed(FIXED, ZoneOffset.UTC));
        LineCancellationImpact preview = tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(order.version(), line.version(), 1,
                        "Cancel with provider")));
        LineCancellationRequest cancelRequest = new LineCancellationRequest(
                preview.cancellationPreviewId(), order.version(), line.version(), true,
                preview.reason());
        LineAdjustmentCommandResult unknown = tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                "unknown-cancel", cancelRequest, "corr-unknown"));
        assertThat(unknown.adjustment().state())
                .isEqualTo(LineAdjustmentState.RECONCILIATION_PENDING);
        assertThat(unknown.adjustment().providerOperationReference()).isNull();
        assertThat(unknown.receipt().state()).isEqualTo(CommandState.RESULT_UNKNOWN);
        assertThat(unknown.receipt().statusHref()).startsWith("/v1/workplace/");
        assertThat(adjustments.adminStatus(fixture.tenant(), order.serviceOrderId(),
                unknown.adjustment().lineAdjustmentId()).providerOperationReference())
                .isEqualTo("provider-operation-18");
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()).lines())
                .singleElement().satisfies(value -> assertThat(value.cancelledQuantity()).isZero());
        assertThatThrownBy(() -> tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(order.version(), line.version(), 1,
                        "Do not duplicate an unresolved cancellation"))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> tx(() -> adjustments.cancel(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                "unknown-cancel-new-key", cancelRequest, "corr-unknown-duplicate")))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND aggregate_id = ?
                   AND action = 'workplace.service.line.cancellation.result_unknown'
                """, Long.class, fixture.tenant(), order.serviceOrderId())).isEqualTo(1L);

        provider.cancelFailure.set(null);
        provider.reconcileOutcome.set(new ProviderOutcome(OutcomeState.SUCCEEDED,
                "provider-operation-18", preview.refundableAmount(), "refund-receipt-18",
                "Provider cancellation and refund confirmed"));
        LineAdjustmentReconcileRequest reconcileRequest =
                new LineAdjustmentReconcileRequest(unknown.adjustment().version(), true,
                        "Reconcile provider receipt");
        LineAdjustmentCommandResult reconciled = tx(() -> adjustments.reconcile(
                fixture.tenant(), ACTOR + 9, order.serviceOrderId(),
                unknown.adjustment().lineAdjustmentId(), "reconcile-18", reconcileRequest,
                "corr-reconcile"));
        assertThat(reconciled.adjustment().state()).isEqualTo(LineAdjustmentState.REFUNDED);
        assertThat(reconciled.adjustment().refundedAmount())
                .isEqualByComparingTo(preview.refundableAmount());
        assertThat(reconciled.adjustment().refundReceiptReference())
                .isEqualTo("refund-receipt-18");
        assertThat(reconciled.receipt().statusHref()).startsWith("/v1/admin/workplace/");
        assertThat(tx(() -> adjustments.reconcile(fixture.tenant(), ACTOR + 9,
                order.serviceOrderId(), unknown.adjustment().lineAdjustmentId(),
                "reconcile-18", reconcileRequest, "ignored")).receipt().replayed()).isTrue();
        assertThat(provider.reconcileCalls).hasValue(1);
        assertThat(adjustments.status(fixture.tenant(), ACTOR, order.serviceOrderId(),
                unknown.adjustment().lineAdjustmentId())).satisfies(value -> {
                    assertThat(value.providerOperationReference()).isNull();
                    assertThat(value.refundReceiptReference()).isNull();
                    assertThat(value.resultDetail()).isNull();
                    assertThat(value.refundedAmount())
                            .isEqualByComparingTo(preview.refundableAmount());
                });
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()).lines())
                .singleElement().satisfies(value -> {
                    assertThat(value.cancelledQuantity()).isEqualTo(1);
                    assertThat(value.refundedAmount())
                            .isEqualByComparingTo(preview.refundableAmount());
                });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND aggregate_id = ?
                   AND action = 'workplace.service.line.reconciled'
                """, Long.class, fixture.tenant(), order.serviceOrderId())).isEqualTo(1L);
        ServiceOrder current = service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId());
        assertThat(tx(() -> adjustments.preview(
                fixture.tenant(), ACTOR, order.serviceOrderId(), line.serviceOrderLineId(),
                new LineCancellationImpactRequest(current.version(),
                        current.lines().getFirst().version(), 1,
                        "Continue after authoritative reconciliation"))).eligible()).isTrue();
    }

    @Test
    void slaSnapshotDrivesDueAndBreachAfterCatalogChanges() {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "SLA_SUPPORT", 1, 60);
        FulfillmentTask task = order.tasks().getFirst();
        assertThat(task.responseDueAt()).isEqualTo(NOW.plusMinutes(30));
        assertThat(task.dueAt()).isEqualTo(order.reservationStartsAt().minusMinutes(30));
        assertThat(task.providerReceiptAt()).isNull();
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.slaResponseMinutes()).isEqualTo(30);
            assertThat(line.slaFulfillmentLeadMinutes()).isEqualTo(30);
        });
        jdbc.update("""
                UPDATE wp_service_catalog_items
                   SET sla_response_minutes = 5, sla_fulfillment_lead_minutes = 5,
                       lifecycle_state = 'INACTIVE', version = version + 1
                 WHERE tenant_id = ? AND catalog_item_id = ?
                """, fixture.tenant(), order.lines().getFirst().catalogItemId());
        WorkplaceServicesService lateReader = new WorkplaceServicesService(
                repository, mapper, Clock.fixed(FIXED.plusSeconds(3 * 86_400), ZoneOffset.UTC));
        ServiceOrder late = lateReader.adminOrder(fixture.tenant(), order.serviceOrderId());
        assertThat(late.lines()).singleElement().satisfies(line -> {
            assertThat(line.slaResponseMinutes()).isEqualTo(30);
            assertThat(line.slaFulfillmentLeadMinutes()).isEqualTo(30);
        });
        assertThat(late.tasks()).singleElement().satisfies(value -> {
            assertThat(value.responseBreached()).isTrue();
            assertThat(value.fulfillmentBreached()).isTrue();
            assertThat(value.responseRemainingSeconds()).isZero();
            assertThat(value.fulfillmentRemainingSeconds()).isZero();
        });
        ServiceOrder delayed = tx(() -> service.updateFulfillment(
                fixture.tenant(), ACTOR, order.serviceOrderId(), task.fulfillmentTaskId(),
                "sla-delayed", new FulfillmentUpdateRequest(task.version(), WorkState.DELAYED,
                        ACTOR, "provider-sla-18", "SLA_DELAY", "Provider delayed",
                        "Receipt recorded", 0, "Record SLA delay", true), "corr-sla")).order();
        assertThat(delayed.state()).isEqualTo(OrderState.DELAYED);
        assertThat(delayed.tasks()).singleElement().satisfies(value -> {
            assertThat(value.providerReceiptAt()).isEqualTo(NOW);
            assertThat(value.responseBreached()).isFalse();
            assertThat(value.assigneeUserId()).isEqualTo(ACTOR);
            assertThat(value.externalFulfillmentReference()).isEqualTo("provider-sla-18");
        });
        jdbc.update("""
                UPDATE wp_service_orders SET provider_operation_reference = 'internal-provider-op'
                 WHERE tenant_id = ? AND service_order_id = ?
                """, fixture.tenant(), order.serviceOrderId());
        assertThat(service.ownOrder(fixture.tenant(), ACTOR, order.serviceOrderId()))
                .satisfies(value -> {
                    assertThat(value.providerOperationReference()).isNull();
                    assertThat(value.tasks()).singleElement().satisfies(taskValue -> {
                        assertThat(taskValue.assigneeUserId()).isNull();
                        assertThat(taskValue.externalFulfillmentReference()).isNull();
                    });
                });
        assertThat(service.adminOrder(fixture.tenant(), order.serviceOrderId()))
                .satisfies(value -> {
                    assertThat(value.providerOperationReference()).isEqualTo("internal-provider-op");
                    assertThat(value.tasks()).singleElement().satisfies(taskValue -> {
                        assertThat(taskValue.assigneeUserId()).isEqualTo(ACTOR);
                        assertThat(taskValue.externalFulfillmentReference())
                                .isEqualTo("provider-sla-18");
                    });
                });
    }

    @Test
    void newAttachmentIsQuarantinedUntilVersionedCleanEvidenceAndUserEvidenceIsMasked() {
        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "ATTACHMENT_SCAN", 1, 60);
        TenantMediaStorage storage = mock(TenantMediaStorage.class);
        WorkplaceServiceAttachmentService attachmentService =
                new WorkplaceServiceAttachmentService(service, repository, storage);
        String storageReference = "tenant/opaque/attachment.pdf";
        ServiceOrderCommandResult linked = tx(() -> service.linkAttachment(
                fixture.tenant(), ACTOR, order.serviceOrderId(), false,
                "attachment-scan-link", order.version(), "Attach service evidence",
                new AttachmentUpload("evidence.pdf", "application/pdf", 128,
                        "a".repeat(64)), () -> storageReference, "corr-link"));
        ServiceOrderAttachment quarantined = repository.attachments(
                fixture.tenant(), order.serviceOrderId()).getFirst();
        assertThat(quarantined.scanState()).isEqualTo(AttachmentScanState.QUARANTINED);
        assertThatThrownBy(() -> attachmentService.download(fixture.tenant(), ACTOR,
                order.serviceOrderId(), quarantined.attachmentId(), false, "corr-blocked"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(storage, never()).load(anyLong(), anyString());

        AttachmentScanRequest clean = new AttachmentScanRequest(1,
                AttachmentScanVerdict.CLEAN, "scanner-receipt-clean-18",
                "Structural and malware scan passed", true,
                "Register authoritative scanner evidence");
        AttachmentScanCommandResult scanned = tx(() -> attachmentService.recordScan(
                fixture.tenant(), ACTOR + 9, order.serviceOrderId(),
                quarantined.attachmentId(), "scan-clean-18", clean, "corr-scan"));
        assertThat(scanned.attachment().scanState()).isEqualTo(AttachmentScanState.CLEAN);
        assertThat(scanned.attachment().scanVersion()).isEqualTo(2);
        assertThat(tx(() -> attachmentService.recordScan(fixture.tenant(), ACTOR + 9,
                order.serviceOrderId(), quarantined.attachmentId(), "scan-clean-18",
                clean, "ignored")).receipt().replayed()).isTrue();
        when(storage.load(fixture.tenant(), storageReference))
                .thenReturn(new ByteArrayResource("clean".getBytes()));
        assertThat(attachmentService.download(fixture.tenant(), ACTOR,
                order.serviceOrderId(), quarantined.attachmentId(), false,
                "corr-download").resource()).isNotNull();
        ServiceOrderAttachment userView = queryService.ownAttachments(
                fixture.tenant(), ACTOR, order.serviceOrderId(), null, 50).items().getFirst();
        ServiceOrderAttachment adminView = queryService.adminAttachments(
                fixture.tenant(), order.serviceOrderId(), null, 50).items().getFirst();
        assertThat(userView.scannerEvidenceReference()).isNull();
        assertThat(userView.scanDetail()).isNull();
        assertThat(adminView.scannerEvidenceReference()).isEqualTo("scanner-receipt-clean-18");
        assertThat(adminView.scanDetail()).isEqualTo("Structural and malware scan passed");
        RequesterServiceOrderEvent requesterScanEvent = queryService.ownEvents(
                fixture.tenant(), ACTOR, order.serviceOrderId(), null, 50).items().stream()
                .filter(value -> value.eventType().equals("ATTACHMENT_SCAN_UPDATED"))
                .findFirst().orElseThrow();
        ServiceOrderEvent adminScanEvent = queryService.adminEvents(
                fixture.tenant(), order.serviceOrderId(), null, 50).items().stream()
                .filter(value -> value.eventType().equals("ATTACHMENT_SCAN_UPDATED"))
                .findFirst().orElseThrow();
        assertThat(requesterScanEvent.detail().has("scannerEvidenceReference")).isFalse();
        assertThat(requesterScanEvent.detail().has("reason")).isFalse();
        assertThat(requesterScanEvent.detail().path("scanState").asText()).isEqualTo("CLEAN");
        assertThat(adminScanEvent.detail().path("scannerEvidenceReference").asText())
                .isEqualTo("scanner-receipt-clean-18");
        assertThat(adminScanEvent.actorUserId()).isEqualTo(ACTOR + 9);
        assertThatThrownBy(() -> queryService.ownAttachments(
                fixture.tenant(), ACTOR + 1, order.serviceOrderId(), null, 50))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(linked.order().providerOperationReference()).isNull();
    }

    @Test
    void providerSnapshotConstraintsRejectPartialRowsUnderPostgresThreeValuedLogic() {
        long tenantId = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L)
                + 6_000_000_000L;
        UUID commandId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_service_operations_commands (
                    operations_command_id, tenant_id, actor_user_id, command_scope,
                    idempotency_key, request_fingerprint, resource_type, resource_id,
                    command_state, status_href, created_at, updated_at)
                VALUES (?, ?, ?, 'SNAPSHOT_CONSTRAINT', 'snapshot-constraint', ?,
                        'SERVICE_PROVIDER', ?, 'ACCEPTED', '/snapshot-constraint', ?, ?)
                """, commandId, tenantId, ACTOR, "a".repeat(64), UUID.randomUUID(), NOW, NOW);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE wp_service_operations_commands
                   SET provider_profile_id_snapshot = ?,
                       provider_credential_binding_reference =
                           'secret-manager://workplace/services-test/v7'
                 WHERE tenant_id = ? AND operations_command_id = ?
                """, UUID.randomUUID(), tenantId, commandId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE wp_service_operations_commands
                   SET provider_code_snapshot = 'SERVICES_TEST',
                       adapter_type_snapshot = 'SERVICES_HTTP',
                       provider_configuration_version = 7,
                       provider_credential_binding_reference =
                           'secret-manager://workplace/services-test/v7',
                       provider_capabilities_snapshot = '[]'::jsonb,
                       provider_profile_version = 3
                 WHERE tenant_id = ? AND operations_command_id = ?
                """, tenantId, commandId))
                .isInstanceOf(DataIntegrityViolationException.class);

        Fixture fixture = fixture();
        ServiceOrder order = submitOrder(fixture, "GRANT_SNAPSHOT_CONSTRAINT", 1, 60);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO wp_service_ephemeral_access_grants (
                    access_grant_id,tenant_id,service_order_id,service_order_line_id,
                    provider_code,requester_user_id,provider_grant_reference,
                    credential_fingerprint,grant_state,reason,issued_at,expires_at,
                    adapter_type_snapshot,provider_configuration_version,
                    provider_credential_binding_reference,provider_operation_kind,
                    provider_operation_command_id,version,created_at,updated_at)
                VALUES (?,?,?,?, 'DWP_NATIVE_FULFILLMENT',?,?,?,'ISSUED',?,
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        NULL,NULL,'internal://test-native','ISSUE',NULL,1,
                        CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), fixture.tenant(), order.serviceOrderId(),
                order.lines().getFirst().serviceOrderLineId(), ACTOR,
                "partial-grant-" + UUID.randomUUID(), "a".repeat(64),
                "Partial provider snapshot must be rejected"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v277MigratesZeroVersionAdjustmentAndKeepsRotatedLegacyGrantFailClosed() {
        String schema = "v277_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        PGSimpleDataSource legacySource = new PGSimpleDataSource();
        legacySource.setURL(POSTGRES.getJdbcUrl());
        legacySource.setUser(POSTGRES.getUsername());
        legacySource.setPassword(POSTGRES.getPassword());
        legacySource.setCurrentSchema(schema);
        JdbcTemplate legacy = new JdbcTemplate(legacySource);
        legacy.execute("""
                CREATE TABLE wp_service_provider_profiles (
                    tenant_id BIGINT NOT NULL,
                    provider_code VARCHAR(80) NOT NULL,
                    adapter_type VARCHAR(80) NOT NULL,
                    configuration_version BIGINT NOT NULL,
                    credential_binding_reference VARCHAR(320))
                """);
        legacy.execute("""
                CREATE TABLE wp_service_order_lines (
                    tenant_id BIGINT NOT NULL,
                    service_order_id UUID NOT NULL,
                    service_order_line_id UUID NOT NULL,
                    provider_code VARCHAR(80) NOT NULL,
                    provider_configuration_version BIGINT NOT NULL)
                """);
        legacy.execute("""
                CREATE TABLE wp_service_line_adjustments (
                    line_adjustment_id UUID PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    service_order_id UUID NOT NULL,
                    service_order_line_id UUID NOT NULL,
                    adjustment_state VARCHAR(32) NOT NULL)
                """);
        legacy.execute("""
                CREATE TABLE wp_service_ephemeral_access_grants (
                    access_grant_id UUID PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    provider_code VARCHAR(80) NOT NULL,
                    grant_state VARCHAR(20) NOT NULL)
                """);
        legacy.execute("""
                CREATE TABLE wp_service_operations_commands (
                    operations_command_id UUID PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    resource_type VARCHAR(80) NOT NULL,
                    resource_id UUID NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL)
                """);

        long tenantId = 7_277_000_001L;
        UUID orderId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        UUID adjustmentId = UUID.randomUUID();
        UUID grantId = UUID.randomUUID();
        legacy.update("""
                INSERT INTO wp_service_provider_profiles (
                    tenant_id,provider_code,adapter_type,configuration_version,
                    credential_binding_reference)
                VALUES (?, 'ROTATED_PROVIDER', 'CURRENT_HTTP', 2,
                        'secret-manager://rotated/current/v2')
                """, tenantId);
        legacy.update("""
                INSERT INTO wp_service_order_lines (
                    tenant_id,service_order_id,service_order_line_id,
                    provider_code,provider_configuration_version)
                VALUES (?, ?, ?, 'ROTATED_PROVIDER', 0)
                """, tenantId, orderId, lineId);
        legacy.update("""
                INSERT INTO wp_service_line_adjustments (
                    line_adjustment_id,tenant_id,service_order_id,
                    service_order_line_id,adjustment_state)
                VALUES (?, ?, ?, ?, 'RESULT_UNKNOWN')
                """, adjustmentId, tenantId, orderId, lineId);
        legacy.update("""
                INSERT INTO wp_service_ephemeral_access_grants (
                    access_grant_id,tenant_id,provider_code,grant_state)
                VALUES (?, ?, 'ROTATED_PROVIDER', 'ISSUED')
                """, grantId, tenantId);

        var migration = Flyway.configure().dataSource(legacySource)
                .schemas(schema).baselineOnMigrate(true).baselineVersion("276")
                .locations("filesystem:src/main/resources/db/migration")
                .load().migrate();

        assertThat(migration.migrationsExecuted).isEqualTo(1);
        assertThat(legacy.queryForMap("""
                SELECT provider_code_snapshot,provider_configuration_version,
                       provider_credential_binding_reference
                  FROM wp_service_line_adjustments
                 WHERE line_adjustment_id=?
                """, adjustmentId))
                .containsEntry("provider_code_snapshot", "ROTATED_PROVIDER")
                .containsEntry("provider_configuration_version", 0L)
                .containsEntry("provider_credential_binding_reference", null);
        assertThat(legacy.queryForMap("""
                SELECT grant_state,provider_operation_kind,adapter_type_snapshot,
                       provider_configuration_version,
                       provider_credential_binding_reference,provider_operation_command_id
                  FROM wp_service_ephemeral_access_grants
                 WHERE access_grant_id=?
                """, grantId))
                .containsEntry("grant_state", "ISSUED")
                .containsEntry("provider_operation_kind", "LEGACY_UNKNOWN")
                .containsEntry("adapter_type_snapshot", null)
                .containsEntry("provider_configuration_version", null)
                .containsEntry("provider_credential_binding_reference", null)
                .containsEntry("provider_operation_command_id", null);

        var adapter = new WorkplaceServiceHttpProviderAdapter(java.util.Optional.empty(),
                "CURRENT_HTTP");
        var outcome = adapter.lookup(new ProviderRequest(adjustmentId, tenantId, orderId,
                lineId, "ROTATED_PROVIDER", 0, null, 1, BigDecimal.TEN, "KRW",
                "Legacy migration recovery"), adjustmentId.toString());
        assertThat(outcome.state()).isEqualTo(OutcomeState.NOT_CONFIGURED);
    }

    @Test
    void accessGrantRecoveryBackoffIsFairAndLegacyUnknownNeverUsesCurrentProfile() {
        Fixture fixture = fixture();
        ServiceOrder firstOrder = submitOrder(fixture, "GRANT_RECOVERY_FIRST", 1, 60);
        ServiceOrder secondOrder = submitOrder(
                fixture, "GRANT_RECOVERY_SECOND", 1, 60, NOW.plusDays(3));
        UUID firstGrant = insertUnknownGrant(fixture.tenant(), firstOrder, ACTOR,
                "grant-fair-first", false);
        UUID secondGrant = insertUnknownGrant(fixture.tenant(), secondOrder, ACTOR,
                "grant-fair-second", false);
        UUID legacyGrant = insertUnknownGrant(fixture.tenant(), firstOrder, ACTOR + 99,
                "grant-legacy-unknown", true);

        var firstCandidate = operationsRepository.pendingAccessGrants(1).getFirst();
        assertThat(firstCandidate.grantId()).isIn(firstGrant, secondGrant);
        OffsetDateTime claimedAt = OffsetDateTime.now(ZoneOffset.UTC);
        assertThat(operationsRepository.claimAccessGrantRecovery(
                fixture.tenant(), firstCandidate.grantId(), claimedAt)).isTrue();

        var secondCandidate = operationsRepository.pendingAccessGrants(1).getFirst();
        assertThat(secondCandidate.grantId()).isIn(firstGrant, secondGrant)
                .isNotEqualTo(firstCandidate.grantId());
        assertThat(operationsRepository.claimAccessGrantRecovery(
                fixture.tenant(), secondCandidate.grantId(), claimedAt)).isTrue();

        assertThat(operationsRepository.pendingAccessGrants(10)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_service_ephemeral_access_grants
                 WHERE access_grant_id IN (?, ?)
                   AND provider_recovery_attempt_count = 1
                   AND provider_next_attempt_at > ?
                """, Integer.class, firstGrant, secondGrant, claimedAt)).isEqualTo(2);
        assertThat(jdbc.queryForMap("""
                SELECT provider_operation_kind,adapter_type_snapshot,
                       provider_configuration_version,
                       provider_credential_binding_reference,provider_operation_command_id
                  FROM wp_service_ephemeral_access_grants
                 WHERE tenant_id=? AND access_grant_id=?
                """, fixture.tenant(), legacyGrant))
                .containsEntry("provider_operation_kind", "LEGACY_UNKNOWN")
                .containsEntry("adapter_type_snapshot", null)
                .containsEntry("provider_configuration_version", null)
                .containsEntry("provider_credential_binding_reference", null)
                .containsEntry("provider_operation_command_id", null);

        UUID issuedLegacyGrant = insertLegacyIssuedGrant(
                fixture.tenant(), firstOrder, ACTOR + 100, "legacy-issued-manual-revoke");
        assertThat(operationsRepository.beginGrantRevocation(fixture.tenant(),
                issuedLegacyGrant, 1, UUID.randomUUID(), NOW)).isFalse();
        WorkplaceServiceEphemeralCredentialProvider provider =
                mock(WorkplaceServiceEphemeralCredentialProvider.class);
        WorkplaceServiceOperationsService guardedService = new WorkplaceServiceOperationsService(
                operationsRepository, mapper, List.of(), List.of(provider), transaction,
                Clock.fixed(FIXED, ZoneOffset.UTC));
        assertThatThrownBy(() -> guardedService.revokeCredential(fixture.tenant(), ACTOR + 100,
                firstOrder.serviceOrderId(), firstOrder.lines().getFirst().serviceOrderLineId(),
                issuedLegacyGrant, "legacy-issued-revoke", new AccessCredentialRevokeRequest(
                        firstOrder.version(), true, "Manual provider revocation required"),
                "corr-legacy-issued-revoke"))
                .isInstanceOfSatisfying(BaseException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(error.getMessage()).contains("manual provider revocation");
                });
        verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_service_operations_commands
                 WHERE tenant_id=? AND resource_id=?
                """, Integer.class, fixture.tenant(), issuedLegacyGrant)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT grant_state,provider_operation_kind,provider_operation_command_id
                  FROM wp_service_ephemeral_access_grants
                 WHERE tenant_id=? AND access_grant_id=?
                """, fixture.tenant(), issuedLegacyGrant))
                .containsEntry("grant_state", "ISSUED")
                .containsEntry("provider_operation_kind", "LEGACY_UNKNOWN")
                .containsEntry("provider_operation_command_id", null);
    }

    private static UUID insertLegacyIssuedGrant(
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

    private static UUID insertUnknownGrant(
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

    private static void assertPageIds(List<UUID> first, List<UUID> second) {
        assertThat(first).hasSize(100);
        assertThat(second).hasSize(1);
        List<UUID> all = new java.util.ArrayList<>(first);
        all.addAll(second);
        assertThat(all).hasSize(101).doesNotHaveDuplicates();
    }

    private static Object concurrentCancel(
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

    private static ServiceOrderCommandResult concurrentMessage(
            Fixture fixture, ServiceOrder order, MessageRequest request,
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return tx(() -> service.addMessage(fixture.tenant(), ACTOR,
                order.serviceOrderId(), false, "message-concurrent-key", request,
                "corr-message-concurrent"));
    }

    private static ServiceOrder submitOrder(
            Fixture fixture, String serviceCode, int quantity,
            int cancellationCutoffMinutes) {
        return submitOrder(fixture, serviceCode, quantity,
                cancellationCutoffMinutes, NOW.plusDays(2));
    }

    private static ServiceOrder submitOrder(
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

    private static ServiceOrder submitFreeNativeOrder(Fixture fixture, String serviceCode) {
        return submitFreeNativeOrder(fixture, serviceCode, 1);
    }

    private static ServiceOrder submitFreeNativeOrder(
            Fixture fixture, String serviceCode, int quantity) {
        return submitFreeNativeOrder(
                fixture, serviceCode, quantity, NOW.plusDays(2));
    }

    private static ServiceOrder submitFreeNativeOrder(
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

    private static LineCancellationPreviewRow cancellationPreview(
            Fixture fixture, OrderRow order, LineRow line, BigDecimal refundableAmount) {
        return new LineCancellationPreviewRow(UUID.randomUUID(), fixture.tenant(), ACTOR,
                order.orderId(), line.lineId(), order.version(), line.version(), 1,
                line.fulfilledQuantity(), line.cancelledQuantity(), line.catalogVersion(),
                line.providerConfigurationVersion(), line.unitPrice(), line.currency(),
                line.cancellationCutoffMinutes(), line.cancellationPolicyKo(),
                line.cancellationPolicyEn(), RefundScope.FULL, refundableAmount, true,
                "Database invariant", NOW.plusMinutes(10), NOW);
    }

    private static final class FakeLineAdjustmentProvider
            implements WorkplaceServiceLineAdjustmentProvider {
        private final AtomicReference<ProviderOutcome> cancelOutcome = new AtomicReference<>(
                new ProviderOutcome(OutcomeState.NOT_CONFIGURED, null,
                        BigDecimal.ZERO, null, "Not configured"));
        private final AtomicReference<ProviderOutcome> reconcileOutcome = new AtomicReference<>(
                new ProviderOutcome(OutcomeState.RESULT_UNKNOWN, null,
                        BigDecimal.ZERO, null, "Still unknown"));
        private final AtomicReference<RuntimeException> cancelFailure = new AtomicReference<>();
        private final AtomicInteger cancelCalls = new AtomicInteger();
        private final AtomicInteger reconcileCalls = new AtomicInteger();
        private final Set<UUID> operationIds = ConcurrentHashMap.newKeySet();

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

    private static Fixture fixture() {
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

    private static CatalogCommandResult createCatalog(
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

    private static CatalogCommandResult createGovernedCatalog(
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

    private static PreviewRequest previewRequest(
            ReservationAuthority authority, long version, UUID catalogItemId, int quantity) {
        return new PreviewRequest(authority, version, 6, "CC-1800", "No stored PIN",
                List.of(new ServiceLineRequest(catalogItemId, quantity,
                        mapper.createObjectNode())));
    }

    private static FulfillmentUpdateRequest fulfillment(
            long version, WorkState state, Long assigneeUserId, String reason) {
        return new FulfillmentUpdateRequest(version, state, assigneeUserId,
                "provider-task-18", null, null, null, 0, reason, true);
    }

    private static UUID workplaceBooking(Fixture fixture, OffsetDateTime startsAt) {
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

    private static UUID calendarReservation(Fixture fixture, OffsetDateTime startsAt) {
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

    private static void addCalendarResourceBooking(
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

    private static <T> T tx(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    private record Fixture(
            long tenant,
            UUID site,
            UUID floor,
            UUID resource,
            UUID calendarResource,
            UUID calendar) { }
}
