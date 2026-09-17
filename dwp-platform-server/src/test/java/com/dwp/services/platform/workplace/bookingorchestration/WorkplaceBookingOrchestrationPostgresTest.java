package com.dwp.services.platform.workplace.bookingorchestration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.RoomService;
import com.dwp.services.platform.workplace.WorkplaceService;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceBookingOrchestrationPostgresTest {
    private static final Instant FIXED = Instant.parse("2026-09-16T06:00:00Z");
    private static final long ACTOR = 72001L;
    private static final AtomicLong TENANTS = new AtomicLong(9_940_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static WorkplaceBookingOrchestrationRepository repository;
    private static WorkplaceBookingOrchestrationService service;
    private static DataSourceTransactionManager transactionManager;
    private static WorkplaceSpatialGovernanceService spatial;

    @BeforeAll
    static void migrateAndBuildActualRepository() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load().migrate();
        jdbc = new JdbcTemplate(source);
        transactionManager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(transactionManager);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        repository = new WorkplaceBookingOrchestrationRepository(jdbc, mapper);
        spatial = mock(WorkplaceSpatialGovernanceService.class);
        when(spatial.evaluateFloorAccess(
                anyLong(), anyLong(), any(), any(UUID.class), any(UUID.class),
                any(WorkplaceSpatialGovernanceDtos.AccessPermission.class)))
                .thenAnswer(invocation -> new WorkplaceSpatialGovernanceDtos.SiteAccessDecision(
                        invocation.getArgument(3), invocation.getArgument(1),
                        invocation.getArgument(5), true, "TEST_ALLOW", List.of(),
                        OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC),
                        invocation.getArgument(4)));
        service = new WorkplaceBookingOrchestrationService(
                repository, spatial, mock(WorkplaceBookingBatchExecutor.class), mapper,
                Clock.fixed(FIXED, ZoneOffset.UTC));
    }

    @Test
    void maintenancePromotesActiveWaitlistIntoExactHoldAndOfferOnlyOnce() {
        Fixture fixture = fixture();
        WaitlistEntry entry = tx(() -> service.createWaitlist(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null,
                "promotion-" + fixture.tenant(), "corr-promotion",
                waitlistRequest(fixture, false)));
        WorkplaceWaitlistPromotionWorker worker = worker(mock(WorkplaceService.class));

        WorkplaceWaitlistPromotionWorker.MaintenanceResult first = worker.maintainOnce();
        WorkplaceWaitlistPromotionWorker.MaintenanceResult replay = worker.maintainOnce();

        assertThat(first.promoted()).isGreaterThanOrEqualTo(1);
        assertThat(replay.promoted()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_alternative_offers
                 WHERE tenant_id = ? AND waitlist_entry_id = ? AND offer_state = 'OFFERED'
                """, Long.class, fixture.tenant(), entry.waitlistEntryId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_alternative_offers offer
                  JOIN wp_reservation_holds hold
                    ON hold.tenant_id = offer.tenant_id AND hold.hold_id = offer.hold_id
                 WHERE offer.tenant_id = ? AND offer.waitlist_entry_id = ?
                   AND hold.resource_id = ? AND hold.starts_at = ? AND hold.ends_at = ?
                   AND hold.hold_state = 'ACTIVE'
                """, Long.class, fixture.tenant(), entry.waitlistEntryId(), fixture.resource(),
                starts(), starts().plusHours(1))).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_orchestration_outbox
                 WHERE tenant_id = ? AND event_type = 'AlternativeOfferCreated'
                """, Long.class, fixture.tenant())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND action = 'workplace.waitlist.offer.created'
                """, Long.class, fixture.tenant())).isEqualTo(1L);
    }

    @Test
    void concurrentMaintenanceCreatesSingleActiveOffer() throws Exception {
        Fixture fixture = fixture();
        WaitlistEntry entry = tx(() -> service.createWaitlist(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null,
                "parallel-promotion-" + fixture.tenant(), "corr-parallel",
                waitlistRequest(fixture, false)));
        WorkplaceWaitlistPromotionWorker one = worker(mock(WorkplaceService.class));
        WorkplaceWaitlistPromotionWorker two = worker(mock(WorkplaceService.class));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> awaitAndMaintain(start, one));
            Future<?> second = threads.submit(() -> awaitAndMaintain(start, two));
            start.countDown();
            first.get();
            second.get();
        } finally {
            threads.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_alternative_offers
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                   AND offer_state IN ('OFFERED', 'ACCEPTING', 'RESULT_UNKNOWN')
                """, Long.class, fixture.tenant(), entry.waitlistEntryId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_reservation_holds hold
                  JOIN wp_alternative_offers offer
                    ON offer.tenant_id = hold.tenant_id AND offer.hold_id = hold.hold_id
                 WHERE offer.tenant_id = ? AND offer.waitlist_entry_id = ?
                """, Long.class, fixture.tenant(), entry.waitlistEntryId())).isEqualTo(1L);
    }

    @Test
    void autoConfirmUsesOwnerAndPersistsResultUnknownForRequery() {
        Fixture fixture = fixture();
        WaitlistEntry entry = tx(() -> service.createWaitlist(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null,
                "auto-promotion-" + fixture.tenant(), "corr-auto",
                waitlistRequest(fixture, true)));
        WorkplaceService owner = mock(WorkplaceService.class);
        when(owner.createBooking(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("provider timeout after dispatch"));

        WorkplaceWaitlistPromotionWorker.MaintenanceResult result = worker(owner).maintainOnce();

        assertThat(result.autoConfirmed()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT offer_state FROM wp_alternative_offers
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                """, String.class, fixture.tenant(), entry.waitlistEntryId()))
                .isEqualTo("RESULT_UNKNOWN");
        assertThat(jdbc.queryForObject("""
                SELECT waitlist_state FROM wp_waitlist_entries
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                """, String.class, fixture.tenant(), entry.waitlistEntryId()))
                .isEqualTo("CONFIRMING");
        UUID batchId = jdbc.queryForObject("""
                SELECT accepted_batch_id FROM wp_alternative_offers
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                """, UUID.class, fixture.tenant(), entry.waitlistEntryId());
        BookingBatch batch = service.batch(fixture.tenant(), ACTOR, batchId);
        assertThat(batch.items()).singleElement().satisfies(item -> {
            assertThat(item.beneficiaryDisplayName()).isEqualTo("Planner member");
            assertThat(item.beneficiaryPersonPublicId()).isEqualTo(fixture.person());
            assertThat(item.resourceDisplayName()).isEqualTo("Focus desk");
            assertThat(item.siteId()).isEqualTo(fixture.site());
            assertThat(item.floorId()).isEqualTo(fixture.floor());
            assertThat(item.timeZone()).isEqualTo("Asia/Seoul");
            assertThat(item.startsAt()).isEqualTo(starts());
            assertThat(item.endsAt()).isEqualTo(starts().plusHours(1));
        });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_batches batch
                  JOIN wp_alternative_offers offer
                    ON offer.tenant_id = batch.tenant_id
                   AND offer.accepted_batch_id = batch.batch_id
                 WHERE offer.tenant_id = ? AND offer.waitlist_entry_id = ?
                   AND batch.batch_state = 'RESULT_UNKNOWN'
                """, Long.class, fixture.tenant(), entry.waitlistEntryId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_orchestration_outbox
                 WHERE tenant_id = ? AND event_type IN (
                    'AlternativeOfferCreated', 'AlternativeOfferAccepted',
                    'BookingBatchItemResultUnknown', 'AlternativeOfferBatchReconciled')
                """, Long.class, fixture.tenant())).isEqualTo(4L);
    }

    @Test
    void flexibleWindowIsUsedAndExposedWhenRequestedWindowIsOccupied() {
        Fixture fixture = fixture();
        rawBooking(fixture, fixture.resource(), starts(), starts().plusHours(1));
        WaitlistEntry entry = tx(() -> service.createWaitlist(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null,
                "flex-promotion-" + fixture.tenant(), "corr-flex",
                waitlistRequest(fixture, false)));

        worker(mock(WorkplaceService.class)).maintainOnce();

        WaitlistEntry promoted = service.waitlist(
                fixture.tenant(), ACTOR, entry.waitlistEntryId());
        assertThat(promoted.state()).isEqualTo(WaitlistState.OFFERED);
        assertThat(promoted.promotionEvaluationState())
                .isEqualTo(PromotionEvaluationState.MATCHED);
        assertThat(promoted.promotionDecisionCode()).isEqualTo("MATCHED_FLEXIBLE_WINDOW");
        assertThat(promoted.offer()).isNotNull();
        assertThat(promoted.offer().resourceDisplayName()).isEqualTo("Focus desk");
        assertThat(promoted.offer().siteId()).isEqualTo(fixture.site());
        assertThat(promoted.offer().floorId()).isEqualTo(fixture.floor());
        assertThat(promoted.offer().timeZone()).isEqualTo("Asia/Seoul");
        assertThat(promoted.offer().startsAt()).isEqualTo(starts().minusHours(1));
        assertThat(promoted.offer().endsAt()).isEqualTo(starts());
    }

    @Test
    void governedDistanceFailsClosedWithExplicitAuditedDecision() {
        Fixture fixture = fixture();
        rawBooking(fixture, fixture.resource(), starts().minusHours(1), starts().plusHours(2));
        UUID alternative = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_resources (
                    resource_id, tenant_id, floor_id, resource_code, name_ko, name_en,
                    resource_type, neighborhood, position_x, position_y,
                    features, lifecycle_state, booking_mode)
                VALUES (?, ?, ?, ?, '대체 좌석', 'Alternative desk', 'DESK', 'ALPHA', 15, 5,
                        '[]'::jsonb, 'AVAILABLE', 'RESERVABLE')
                """, alternative, fixture.tenant(), fixture.floor(), "ALT_" + alternative);
        WaitlistCreateRequest request = new WaitlistCreateRequest(
                plannerItem(fixture, "distance-waitlist", fixture.resource()), false,
                new WaitlistConditions(
                        500, starts().minusHours(1), starts().plusHours(2),
                        PricingMode.NOT_APPLICABLE, null, null),
                List.of(NotificationChannel.IN_APP), "Keep the alternative within 500 meters");
        WaitlistEntry entry = tx(() -> service.createWaitlist(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null,
                "distance-promotion-" + fixture.tenant(), "corr-distance-promotion", request));

        PromotionEvaluationState evaluation = worker(mock(WorkplaceService.class))
                .reevaluate(fixture.tenant(), entry.waitlistEntryId());

        WaitlistEntry blocked = service.waitlist(
                fixture.tenant(), ACTOR, entry.waitlistEntryId());
        assertThat(evaluation).isEqualTo(PromotionEvaluationState.BLOCKED);
        assertThat(blocked.state()).isEqualTo(WaitlistState.ACTIVE);
        assertThat(blocked.promotionEvaluationState())
                .isEqualTo(PromotionEvaluationState.BLOCKED);
        assertThat(blocked.promotionDecisionCode())
                .isEqualTo("PHYSICAL_DISTANCE_SCALE_NOT_CONFIGURED");
        assertThat(blocked.offer()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_orchestration_outbox
                 WHERE tenant_id = ? AND event_type = 'WaitlistPromotionBlocked'
                """, Long.class, fixture.tenant())).isEqualTo(1L);
    }

    @Test
    void groupDelegationIsBlockedWhenMembershipCannotBeAuthoritativelyRevalidated() {
        Fixture fixture = fixture();
        UUID group = UUID.randomUUID();
        UUID grant = UUID.randomUUID();
        UUID beneficiaryPerson = UUID.randomUUID();
        long beneficiary = ACTOR + 88;
        jdbc.update("""
                INSERT INTO wp_booking_delegate_grants (
                    grant_id, tenant_id, actor_group_ref, beneficiary_user_id,
                    beneficiary_person_public_id, beneficiary_display_name,
                    resource_types, valid_from, valid_until, grant_state,
                    version, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Group delegate', '["DESK"]'::jsonb,
                        ?, ?, 'ACTIVE', 1, ?, ?)
                """, grant, fixture.tenant(), group, beneficiary, beneficiaryPerson,
                OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC).minusHours(1),
                OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC).plusDays(5), ACTOR, ACTOR);
        IntentItemRequest delegated = new IntentItemRequest(
                "group-waitlist", beneficiary, beneficiaryPerson, "Untrusted", grant,
                ResourceType.DESK, fixture.resource(), fixture.site(), fixture.floor(),
                starts(), starts().plusHours(1), "Delegated booking", true, false, List.of());
        WaitlistEntry entry = tx(() -> service.createWaitlist(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", group.toString(),
                "group-promotion-" + fixture.tenant(), "corr-group-promotion",
                new WaitlistCreateRequest(
                        delegated, false,
                        new WaitlistConditions(null, null, null,
                                PricingMode.NOT_APPLICABLE, null, null),
                        List.of(NotificationChannel.IN_APP),
                        "Wait under delegated authority")));

        assertThat(repository.promotionCandidateKeys(
                OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC), 200))
                .contains(new WorkplaceBookingOrchestrationRepositorySupport.WaitlistKey(
                        fixture.tenant(), entry.waitlistEntryId()));
        assertThat(repository.delegationValid(
                fixture.tenant(), grant, ACTOR, beneficiary, ResourceType.DESK,
                null, OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC))).isFalse();
        assertThat(repository.groupDelegation(fixture.tenant(), grant)).isTrue();
        PromotionEvaluationState evaluation = worker(mock(WorkplaceService.class))
                .reevaluate(fixture.tenant(), entry.waitlistEntryId());

        WaitlistEntry blocked = service.waitlist(
                fixture.tenant(), ACTOR, entry.waitlistEntryId());
        assertThat(evaluation).isEqualTo(PromotionEvaluationState.BLOCKED);
        assertThat(blocked.promotionEvaluationState())
                .isEqualTo(PromotionEvaluationState.BLOCKED);
        assertThat(blocked.promotionDecisionCode())
                .isEqualTo("GROUP_MEMBERSHIP_REVALIDATION_UNAVAILABLE");
        assertThat(blocked.offer()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND action = 'workplace.waitlist.promotion.blocked'
                """, Long.class, fixture.tenant())).isEqualTo(1L);
    }

    @Test
    void expiredOfferReleasesExactHoldAndWritesLifecycleEvidence() {
        Fixture fixture = fixture();
        WaitlistEntry entry = tx(() -> service.createWaitlist(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null,
                "expiry-promotion-" + fixture.tenant(), "corr-expiry",
                waitlistRequest(fixture, false)));
        WorkplaceWaitlistPromotionWorker initial = worker(mock(WorkplaceService.class));
        initial.maintainOnce();
        UUID offerId = jdbc.queryForObject("""
                SELECT offer_id FROM wp_alternative_offers
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                """, UUID.class, fixture.tenant(), entry.waitlistEntryId());

        worker(mock(WorkplaceService.class), FIXED.plusSeconds(121)).maintainOnce();

        assertThat(jdbc.queryForObject("""
                SELECT offer_state FROM wp_alternative_offers WHERE offer_id = ?
                """, String.class, offerId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT hold_state FROM wp_reservation_holds hold
                  JOIN wp_alternative_offers offer ON offer.hold_id = hold.hold_id
                 WHERE offer.offer_id = ?
                """, String.class, offerId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_orchestration_outbox
                 WHERE tenant_id = ? AND event_type = 'AlternativeOfferExpired'
                """, Long.class, fixture.tenant())).isEqualTo(1L);
    }

    @Test
    void previewAndHoldAreTenantScopedIdempotentAuditedAndOutboxed() {
        Fixture fixture = fixture();
        IntentPreviewRequest request = previewRequest(fixture);
        BookingIntentPreview first = tx(() -> service.preview(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null, "en",
                "preview-" + fixture.tenant(), "corr-preview", request));
        BookingIntentPreview replay = tx(() -> service.preview(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null, "en",
                "preview-" + fixture.tenant(), "ignored", request));

        assertThat(replay.intentId()).isEqualTo(first.intentId());
        assertThat(first.items()).singleElement().satisfies(item -> {
            assertThat(item.beneficiaryUserId()).isEqualTo(ACTOR);
            assertThat(item.delegationGrantId()).isNull();
            assertThat(item.candidates()).singleElement().satisfies(candidate ->
                    assertThat(candidate.resourceId()).isEqualTo(fixture.resource()));
        });
        assertThatThrownBy(() -> tx(() -> service.preview(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null, "en",
                "preview-" + fixture.tenant(), "other",
                new IntentPreviewRequest(request.items(), 90, false, "Different command"))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        BookingCandidate candidate = first.items().getFirst().candidates().getFirst();
        HoldRequest holdRequest = new HoldRequest(
                first.version(), List.of(new HoldSelection(
                        first.items().getFirst().intentItemId(), candidate.resourceId(),
                        first.items().getFirst().version(), candidate.resourceVersion())),
                "Hold the selected desk", true);
        HoldResponse held = tx(() -> service.createHolds(
                fixture.tenant(), ACTOR, null, "hold-" + fixture.tenant(),
                "corr-hold", first.intentId(), holdRequest));
        HoldResponse heldReplay = tx(() -> service.createHolds(
                fixture.tenant(), ACTOR, null, "hold-" + fixture.tenant(),
                "ignored", first.intentId(), holdRequest));

        assertThat(held.intentState()).isEqualTo(IntentState.HELD);
        assertThat(heldReplay.holds()).extracting(ReservationHold::holdId)
                .containsExactlyElementsOf(held.holds().stream()
                        .map(ReservationHold::holdId).toList());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_orchestration_outbox
                 WHERE tenant_id = ?
                """, Long.class, fixture.tenant())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND action IN (
                    'workplace.booking.intent.previewed',
                    'workplace.booking.holds.created')
                """, Long.class, fixture.tenant())).isEqualTo(2L);
        assertThatThrownBy(() -> service.intentStatus(
                fixture.tenant() + 1000, ACTOR, first.intentId(), "en"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void delegatedBeneficiaryIsAuthoritativeAndLineageSurvivesPreview() {
        Fixture fixture = fixture();
        UUID grantId = UUID.randomUUID();
        UUID beneficiaryPerson = UUID.randomUUID();
        long beneficiaryUser = ACTOR + 77;
        jdbc.update("""
                INSERT INTO wp_booking_delegate_grants (
                    grant_id, tenant_id, actor_user_id, beneficiary_user_id,
                    beneficiary_person_public_id, beneficiary_display_name,
                    resource_types, valid_from, valid_until, grant_state,
                    version, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'Canonical delegate', '["DESK"]'::jsonb,
                        ?, ?, 'ACTIVE', 1, ?, ?)
                """, grantId, fixture.tenant(), ACTOR, beneficiaryUser, beneficiaryPerson,
                OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC).minusHours(1),
                OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC).plusDays(10), ACTOR, ACTOR);

        AuthorizedBeneficiaries authorized = service.beneficiaries(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null);
        assertThat(authorized.beneficiaries()).filteredOn(value -> !value.self())
                .singleElement().satisfies(value -> {
                    assertThat(value.delegationGrantId()).isEqualTo(grantId);
                    assertThat(value.beneficiaryUserId()).isEqualTo(beneficiaryUser);
                    assertThat(value.displayName()).isEqualTo("Canonical delegate");
                });

        IntentItemRequest delegated = new IntentItemRequest(
                "delegate-desk", beneficiaryUser, UUID.randomUUID(), "Untrusted client name",
                grantId, ResourceType.DESK, fixture.resource(), fixture.site(), fixture.floor(),
                starts(), starts().plusHours(1), "Delegate booking", false, false, List.of());
        BookingIntentPreview preview = tx(() -> service.preview(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null, "en",
                "delegate-preview-" + fixture.tenant(), "corr-delegate",
                new IntentPreviewRequest(List.of(delegated), 120, true,
                        "Plan for the authorized delegate")));
        assertThat(preview.items()).singleElement().satisfies(item -> {
            assertThat(item.beneficiaryUserId()).isEqualTo(beneficiaryUser);
            assertThat(item.beneficiaryPersonPublicId()).isEqualTo(beneficiaryPerson);
            assertThat(item.beneficiaryDisplayName()).isEqualTo("Canonical delegate");
            assertThat(item.delegationGrantId()).isEqualTo(grantId);
        });
    }

    @Test
    void batchResultUnknownIsDurableAndRecoveredByStatusInsteadOfCommandReplay() {
        Fixture fixture = fixture();
        BookingIntentPreview preview = tx(() -> service.preview(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null, "en",
                "unknown-preview-" + fixture.tenant(), "corr-preview", previewRequest(fixture)));
        BookingCandidate candidate = preview.items().getFirst().candidates().getFirst();
        HoldResponse holds = tx(() -> service.createHolds(
                fixture.tenant(), ACTOR, null, "unknown-hold-" + fixture.tenant(),
                "corr-hold", preview.intentId(), new HoldRequest(
                        preview.version(), List.of(new HoldSelection(
                                preview.items().getFirst().intentItemId(), candidate.resourceId(),
                                preview.items().getFirst().version(), candidate.resourceVersion())),
                        "Hold before batch", true)));
        BatchStartResponse batch = tx(() -> service.startBatch(
                fixture.tenant(), ACTOR, "unknown-batch-" + fixture.tenant(), "corr-batch",
                new BatchStartRequest(preview.intentId(), holds.intentVersion(),
                        List.of(new HoldReference(holds.holds().getFirst().holdId(),
                                holds.holds().getFirst().version())),
                        FailurePolicy.KEEP_SUCCEEDED, "Confirm planner batch", true)));

        WorkplaceBookingBatchExecutor actualExecutor = new WorkplaceBookingBatchExecutor(
                repository, mock(WorkplaceService.class), mock(RoomService.class),
                transactionManager, Clock.fixed(FIXED, ZoneOffset.UTC));
        actualExecutor.execute(fixture.tenant(), batch.batchId(), "en", null);
        actualExecutor.execute(fixture.tenant(), batch.batchId(), "en", null);

        BookingBatch status = service.batch(fixture.tenant(), ACTOR, batch.batchId());
        assertThat(status.state()).isEqualTo(BatchState.RESULT_UNKNOWN);
        assertThat(status.requeryRequired()).isTrue();
        assertThat(status.items()).singleElement().satisfies(item -> {
            assertThat(item.state()).isEqualTo(BatchItemState.RESULT_UNKNOWN);
            assertThat(item.requeryRequired()).isTrue();
            assertThat(item.delegationGrantId()).isNull();
        });
        BookingIntentStatus recovery = service.intentStatus(
                fixture.tenant(), ACTOR, preview.intentId(), "en");
        assertThat(recovery.latestBatchId()).isEqualTo(batch.batchId());
        assertThat(recovery.latestBatchStatusUrl())
                .endsWith("/booking-batches/" + batch.batchId());
    }

    @Test
    void teamPlacementIsActuallyValidatedAndMeterDistanceFailsClosedWithoutScale() {
        Fixture fixture = fixture();
        UUID second = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_resources (
                    resource_id, tenant_id, floor_id, resource_code, name_ko, name_en,
                    resource_type, neighborhood, position_x, position_y,
                    features, lifecycle_state, booking_mode)
                VALUES (?, ?, ?, ?, '인접 좌석', 'Adjacent desk', 'DESK', 'ALPHA',
                        15, 5, '[]'::jsonb, 'AVAILABLE', 'RESERVABLE')
                """, second, fixture.tenant(), fixture.floor(), "DESK_" + second);
        List<IntentItemRequest> items = List.of(
                plannerItem(fixture, "member-one", fixture.resource()),
                plannerItem(fixture, "member-two", second));
        TeamPlacementConstraint adjacency = new TeamPlacementConstraint(
                "team-alpha", List.of("member-one", "member-two"), true, true,
                null, null);

        BookingIntentPreview satisfied = tx(() -> service.preview(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null, "en",
                "team-preview-" + fixture.tenant(), "corr-team",
                new IntentPreviewRequest(items, 120, true, List.of(adjacency),
                        "Seat the team together")));
        assertThat(satisfied.placementConstraintEvidence()).singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo(ConstraintEvaluationState.SATISFIED);
            assertThat(value.selectedResourceIds()).containsExactly(
                    fixture.resource(), second);
        });
        assertThat(satisfied.items()).allSatisfy(item -> {
            assertThat(item.decisionCode()).isEqualTo("TEAM_PLACEMENT_SATISFIED");
            assertThat(item.candidates()).hasSize(1);
            assertThat(item.candidates().getFirst().neighborhood()).isEqualTo("ALPHA");
        });

        TeamPlacementConstraint physicalDistance = new TeamPlacementConstraint(
                "team-meter", List.of("member-one", "member-two"), false, false,
                BigDecimal.ONE, BigDecimal.TEN);
        BookingIntentPreview unsupported = tx(() -> service.preview(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null, "en",
                "distance-preview-" + fixture.tenant(), "corr-distance",
                new IntentPreviewRequest(items, 120, true, List.of(physicalDistance),
                        "Keep a governed physical distance")));
        assertThat(unsupported.placementConstraintEvidence()).singleElement().satisfies(value -> {
            assertThat(value.state()).isEqualTo(ConstraintEvaluationState.UNSUPPORTED);
            assertThat(value.code()).isEqualTo("PHYSICAL_DISTANCE_SCALE_NOT_CONFIGURED");
        });
        assertThat(unsupported.items()).allSatisfy(item -> {
            assertThat(item.decision()).isEqualTo(IntentItemDecision.POLICY_DENIED);
            assertThat(item.candidates()).isEmpty();
        });
    }

    @Test
    void waitlistPersistsNotApplicablePricingAndRejectsInventedManagedPrice() {
        Fixture fixture = fixture();
        IntentItemRequest item = plannerItem(fixture, "waitlist", fixture.resource());
        WaitlistCreateRequest request = new WaitlistCreateRequest(
                item, false, new WaitlistConditions(
                        500, starts().minusHours(1), starts().plusHours(2),
                        PricingMode.NOT_APPLICABLE, null, null),
                List.of(NotificationChannel.IN_APP), "Wait for an adjacent option");
        WaitlistEntry created = tx(() -> service.createWaitlist(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null,
                "waitlist-" + fixture.tenant(), "corr-waitlist", request));
        assertThat(created.conditions().pricingMode()).isEqualTo(PricingMode.NOT_APPLICABLE);
        assertThat(created.conditions().maximumPrice()).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT pricing_mode FROM wp_waitlist_entries
                 WHERE tenant_id = ? AND waitlist_entry_id = ?
                """, String.class, fixture.tenant(), created.waitlistEntryId()))
                .isEqualTo("NOT_APPLICABLE");

        WaitlistCancelRequest cancel = new WaitlistCancelRequest(
                created.version(), "No longer needed", true);
        WaitlistEntry cancelled = tx(() -> service.cancelWaitlist(
                fixture.tenant(), ACTOR, created.waitlistEntryId(),
                "waitlist-cancel-" + fixture.tenant(), "corr-cancel", cancel));
        WaitlistEntry cancelReplay = tx(() -> service.cancelWaitlist(
                fixture.tenant(), ACTOR, created.waitlistEntryId(),
                "waitlist-cancel-" + fixture.tenant(), "ignored", cancel));
        assertThat(cancelled.state()).isEqualTo(WaitlistState.CANCELLED);
        assertThat(cancelReplay.version()).isEqualTo(cancelled.version());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_orchestration_outbox
                 WHERE tenant_id = ? AND event_type = 'WaitlistEntryCancelled'
                """, Long.class, fixture.tenant())).isEqualTo(1L);
        assertThatThrownBy(() -> tx(() -> service.cancelWaitlist(
                fixture.tenant(), ACTOR, created.waitlistEntryId(),
                "waitlist-cancel-unconfirmed-" + fixture.tenant(), "corr-unconfirmed",
                new WaitlistCancelRequest(cancelled.version(), "Try again", false))))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        WaitlistCreateRequest fabricatedPrice = new WaitlistCreateRequest(
                item, false, new WaitlistConditions(
                        500, starts().minusHours(1), starts().plusHours(2),
                        PricingMode.MANAGED, BigDecimal.TEN, "USD"),
                List.of(NotificationChannel.IN_APP), "Apply a price ceiling");
        assertThatThrownBy(() -> tx(() -> service.createWaitlist(
                fixture.tenant(), ACTOR, fixture.person(), "Planner member", null,
                "priced-waitlist-" + fixture.tenant(), "corr-price", fabricatedPrice)))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void aggregateAuditAndOutboxRollbackAsOneTransaction() {
        Fixture fixture = fixture();
        String key = "atomic-preview-" + fixture.tenant();
        jdbc.execute("""
                ALTER TABLE wp_booking_orchestration_outbox
                ADD CONSTRAINT ck_wp_booking_orchestration_outbox_test_failure
                CHECK (correlation_id <> 'corr-force-outbox-failure')
                """);
        try {
            assertThatThrownBy(() -> tx(() -> service.preview(
                    fixture.tenant(), ACTOR, fixture.person(), "Planner member", null, "en",
                    key, "corr-force-outbox-failure", previewRequest(fixture))))
                    .isInstanceOfAny(DataIntegrityViolationException.class,
                            org.springframework.transaction.TransactionSystemException.class);
        } finally {
            jdbc.execute("""
                    ALTER TABLE wp_booking_orchestration_outbox
                    DROP CONSTRAINT ck_wp_booking_orchestration_outbox_test_failure
                    """);
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_intents
                 WHERE tenant_id = ? AND idempotency_key = ?
                """, Long.class, fixture.tenant(), key)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_audit_events
                 WHERE tenant_id = ? AND action = 'workplace.booking.intent.previewed'
                """, Long.class, fixture.tenant())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_orchestration_outbox
                 WHERE tenant_id = ?
                """, Long.class, fixture.tenant())).isZero();
    }

    @Test
    void compositeForeignKeysRejectCrossTenantAndCrossIntentAggregateLinks() {
        Fixture one = fixture();
        Fixture two = fixture();
        UUID intentOne = intent(one.tenant());
        UUID intentOneOther = intent(one.tenant());
        UUID intentTwo = intent(two.tenant());
        UUID itemOne = item(one, intentOne, "one");
        UUID itemOneOther = item(one, intentOneOther, "one-other");
        UUID itemTwo = item(two, intentTwo, "two");
        UUID holdOne = hold(one, intentOne, itemOne);
        UUID holdOneOther = hold(one, intentOneOther, itemOneOther);
        UUID holdTwo = hold(two, intentTwo, itemTwo);
        UUID batchOne = batch(one.tenant(), intentOne);
        UUID batchTwo = batch(two.tenant(), intentTwo);

        assertConstraint(() -> jdbc.update("""
                INSERT INTO wp_reservation_holds (
                    hold_id, tenant_id, intent_id, intent_item_id, resource_id,
                    actor_user_id, beneficiary_user_id, hold_state, starts_at, ends_at,
                    expires_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 1)
                """, UUID.randomUUID(), one.tenant(), intentOne, itemOneOther,
                one.resource(), ACTOR, ACTOR, starts(), starts().plusHours(1),
                starts().minusMinutes(1)));

        assertConstraint(() -> jdbc.update("""
                INSERT INTO wp_booking_batch_items (
                    batch_item_id, batch_id, tenant_id, intent_id, intent_item_id, hold_id,
                    authority, item_state, version)
                VALUES (?, ?, ?, ?, ?, ?, 'WORKPLACE', 'PENDING', 1)
                """, UUID.randomUUID(), batchTwo, one.tenant(), intentOne,
                itemOne, holdOne));

        assertConstraint(() -> jdbc.update("""
                INSERT INTO wp_booking_batch_items (
                    batch_item_id, batch_id, tenant_id, intent_id, intent_item_id, hold_id,
                    authority, item_state, version)
                VALUES (?, ?, ?, ?, ?, ?, 'WORKPLACE', 'PENDING', 1)
                """, UUID.randomUUID(), batchOne, one.tenant(), intentOneOther,
                itemOneOther, holdOneOther));

        UUID waitlist = waitlist(one, "cross-batch");
        assertConstraint(() -> jdbc.update("""
                INSERT INTO wp_alternative_offers (
                    offer_id, tenant_id, waitlist_entry_id, hold_id, resource_id,
                    offer_state, expires_at, accepted_batch_id, version)
                VALUES (?, ?, ?, ?, ?, 'ACCEPTED', ?, ?, 1)
                """, UUID.randomUUID(), one.tenant(), waitlist, holdOne,
                one.resource(), starts().minusMinutes(1), batchTwo));

        assertThat(holdTwo).isNotNull();
    }

    @Test
    void activeHoldBlocksUnownedBookingAndBatchedHoldOnlyAuthorizesExactWindow() {
        Fixture fixture = fixture();
        UUID intent = intent(fixture.tenant());
        UUID item = item(fixture, intent, "guard");
        UUID hold = hold(fixture, intent, item);

        assertConstraint(() -> rawBooking(
                fixture, fixture.resource(), starts(), starts().plusHours(1)));
        jdbc.update("""
                UPDATE wp_reservation_holds SET hold_state = 'BATCHED'
                 WHERE tenant_id = ? AND hold_id = ?
                """, fixture.tenant(), hold);
        assertConstraint(() -> transaction.executeWithoutResult(status -> {
            repository.setHoldContext(hold);
            rawBooking(fixture, fixture.resource(), starts(), starts().plusMinutes(30));
        }));
        transaction.executeWithoutResult(status -> {
            repository.setHoldContext(hold);
            rawBooking(fixture, fixture.resource(), starts(), starts().plusHours(1));
        });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_bookings
                 WHERE tenant_id = ? AND resource_id = ?
                """, Long.class, fixture.tenant(), fixture.resource())).isEqualTo(1L);
    }

    private static IntentPreviewRequest previewRequest(Fixture fixture) {
        return new IntentPreviewRequest(List.of(new IntentItemRequest(
                "weekday-desk", ACTOR, fixture.person(), "Client supplied name", null,
                ResourceType.DESK, fixture.resource(), fixture.site(), fixture.floor(),
                starts(), starts().plusHours(1), "Deep work", true, false, List.of())),
                120, true, "Plan the work week");
    }

    private static WaitlistCreateRequest waitlistRequest(Fixture fixture, boolean autoConfirm) {
        return new WaitlistCreateRequest(
                plannerItem(fixture, "waitlist-promotion", fixture.resource()),
                autoConfirm,
                new WaitlistConditions(
                        null, starts().minusHours(1), starts().plusHours(2),
                        PricingMode.NOT_APPLICABLE, null, null),
                List.of(NotificationChannel.IN_APP),
                "Promote the first verified resource that becomes available");
    }

    private static WorkplaceWaitlistPromotionWorker worker(WorkplaceService workplace) {
        return worker(workplace, FIXED);
    }

    private static WorkplaceWaitlistPromotionWorker worker(
            WorkplaceService workplace, Instant instant) {
        WorkplaceBookingBatchExecutor executor = new WorkplaceBookingBatchExecutor(
                repository, workplace, mock(RoomService.class), transactionManager,
                Clock.fixed(instant, ZoneOffset.UTC));
        return new WorkplaceWaitlistPromotionWorker(
                repository, service, executor, spatial, transactionManager,
                Clock.fixed(instant, ZoneOffset.UTC), true, 200, 120);
    }

    private static void awaitAndMaintain(
            CountDownLatch start, WorkplaceWaitlistPromotionWorker worker) {
        try {
            start.await();
            worker.maintainOnce();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static Fixture fixture() {
        long tenant = TENANTS.incrementAndGet();
        UUID site = UUID.randomUUID();
        UUID floor = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        UUID person = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name, lifecycle_state,
                    data_region, isolation_model, created_by, updated_by)
                VALUES (?, ?, ?, 'Booking orchestration test', 'ACTIVE', 'kr', 'POOL', ?, ?)
                """, UUID.randomUUID(), tenant, "booking-orchestration-" + tenant, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies (tenant_id) VALUES (?)", tenant);
        jdbc.update("""
                INSERT INTO wp_sites (
                    site_id, tenant_id, site_code, name_ko, name_en, time_zone)
                VALUES (?, ?, ?, '서울', 'Seoul', 'Asia/Seoul')
                """, site, tenant, "SITE_" + site);
        jdbc.update("""
                INSERT INTO wp_floors (
                    floor_id, tenant_id, site_id, floor_number, name_ko, name_en,
                    lifecycle_state)
                VALUES (?, ?, ?, 10, '10층', '10F', 'ACTIVE')
                """, floor, tenant, site);
        jdbc.update("""
                INSERT INTO wp_resources (
                    resource_id, tenant_id, floor_id, resource_code, name_ko, name_en,
                    resource_type, neighborhood, position_x, position_y,
                    features, lifecycle_state, booking_mode)
                VALUES (?, ?, ?, ?, '집중 좌석', 'Focus desk', 'DESK', 'ALPHA', 5, 5,
                        '[]'::jsonb,
                        'AVAILABLE', 'RESERVABLE')
                """, resource, tenant, floor, "DESK_" + resource);
        return new Fixture(tenant, site, floor, resource, person);
    }

    private static UUID intent(long tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_booking_intents (
                    intent_id, tenant_id, actor_user_id, intent_state, reason,
                    requested_hold_ttl_seconds, allow_alternatives, idempotency_key,
                    request_fingerprint, correlation_id, version)
                VALUES (?, ?, ?, 'HELD', 'Fixture', 120, TRUE, ?, ?, ?, 1)
                """, id, tenant, ACTOR, "intent-" + id, "a".repeat(64), "corr-" + id);
        return id;
    }

    private static IntentItemRequest plannerItem(
            Fixture fixture, String clientKey, UUID resourceId) {
        return new IntentItemRequest(
                clientKey, ACTOR, fixture.person(), "Planner member", null,
                ResourceType.DESK, resourceId, fixture.site(), fixture.floor(),
                starts(), starts().plusHours(1), "Team work", true, false, List.of());
    }

    private static UUID item(Fixture fixture, UUID intent, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_booking_intent_items (
                    intent_item_id, intent_id, tenant_id, client_item_key, actor_user_id,
                    beneficiary_user_id, beneficiary_display_name, resource_type,
                    preferred_resource_id, site_id, floor_id, starts_at, ends_at,
                    visible_to_colleagues, accessible_only, required_features, decision,
                    decision_code, candidate_resource_ids, version)
                VALUES (?, ?, ?, ?, ?, ?, 'Member', 'DESK', ?, ?, ?, ?, ?, TRUE, FALSE,
                        '[]'::jsonb, 'AVAILABLE', 'AVAILABLE', ?::jsonb, 1)
                """, id, intent, fixture.tenant(), key, ACTOR, ACTOR,
                fixture.resource(), fixture.site(), fixture.floor(), starts(),
                starts().plusHours(1), "[\"" + fixture.resource() + "\"]");
        return id;
    }

    private static UUID hold(Fixture fixture, UUID intent, UUID item) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_reservation_holds (
                    hold_id, tenant_id, intent_id, intent_item_id, resource_id,
                    actor_user_id, beneficiary_user_id, hold_state, starts_at, ends_at,
                    expires_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 1)
                """, id, fixture.tenant(), intent, item, fixture.resource(), ACTOR, ACTOR,
                starts(), starts().plusHours(1), starts().minusMinutes(1));
        return id;
    }

    private static UUID batch(long tenant, UUID intent) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_booking_batches (
                    batch_id, tenant_id, intent_id, actor_user_id, batch_state,
                    failure_policy, reason, explicit_confirmation, idempotency_key,
                    request_fingerprint, correlation_id, version)
                VALUES (?, ?, ?, ?, 'ACCEPTED', 'KEEP_SUCCEEDED', 'Fixture', TRUE,
                        ?, ?, ?, 1)
                """, id, tenant, intent, ACTOR, "batch-" + id, "b".repeat(64), "corr-" + id);
        return id;
    }

    private static UUID waitlist(Fixture fixture, String suffix) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO wp_waitlist_entries (
                    waitlist_entry_id, tenant_id, actor_user_id, beneficiary_user_id,
                    beneficiary_display_name, resource_type, preferred_resource_id,
                    site_id, floor_id, starts_at, ends_at, visible_to_colleagues,
                    auto_confirm, notification_channels, waitlist_state,
                    rank_visible, idempotency_key, request_fingerprint, correlation_id,
                    version)
                VALUES (?, ?, ?, ?, 'Member', 'DESK', ?, ?, ?, ?, ?, TRUE, FALSE,
                        '[\"IN_APP\"]'::jsonb, 'ACTIVE', FALSE, ?, ?, ?, 1)
                """, id, fixture.tenant(), ACTOR, ACTOR, fixture.resource(), fixture.site(),
                fixture.floor(), starts(), starts().plusHours(1), "wait-" + suffix + id,
                "c".repeat(64), "corr-" + id);
        return id;
    }

    private static UUID rawBooking(
            Fixture fixture, UUID resource, OffsetDateTime from, OffsetDateTime to) {
        return jdbc.queryForObject("""
                INSERT INTO wp_bookings (
                    tenant_id, resource_id, user_id, booked_for_display_name,
                    starts_at, ends_at, booking_status, policy_snapshot,
                    policy_snapshot_hash, require_check_in_snapshot,
                    check_in_lead_minutes_snapshot, auto_release_minutes_snapshot,
                    booking_retention_days_snapshot)
                VALUES (?, ?, ?, 'Member', ?, ?, 'RESERVED', '{}'::jsonb,
                        encode(digest('{}'::jsonb::TEXT, 'sha256'), 'hex'),
                        FALSE, 15, 0, 365)
                RETURNING booking_id
                """, UUID.class, fixture.tenant(), resource, ACTOR, from, to);
    }

    private static OffsetDateTime starts() {
        return OffsetDateTime.ofInstant(FIXED, ZoneOffset.UTC).plusDays(2);
    }

    private static <T> T tx(Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    private static void assertConstraint(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfAny(DataIntegrityViolationException.class,
                        org.springframework.transaction.TransactionSystemException.class);
    }

    private record Fixture(
            long tenant, UUID site, UUID floor, UUID resource, UUID person) { }
}
