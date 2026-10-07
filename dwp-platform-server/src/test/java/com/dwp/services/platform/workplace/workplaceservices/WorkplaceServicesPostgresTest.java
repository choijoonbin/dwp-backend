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
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.CommandState;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesRepository.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceLineAdjustmentProvider.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceServicesPostgresTest extends WorkplaceServicesPostgresTestSupport {
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
}
