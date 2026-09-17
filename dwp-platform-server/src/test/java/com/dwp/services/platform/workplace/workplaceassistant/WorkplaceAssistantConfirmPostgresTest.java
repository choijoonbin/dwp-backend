package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.calendar.RoomService;
import com.dwp.services.platform.workplace.WorkplaceDtos;
import com.dwp.services.platform.workplace.WorkplaceService;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceService;
import com.dwp.services.platform.workplace.WorkplaceTypes.BookingStatus;
import com.dwp.services.platform.workplace.WorkplaceTypes.ResourceType;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingBatchExecutor;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.BookingBatch;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationRepository;
import com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationService;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.CommandRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.dwp.services.platform.workplace.workplaceassistant.StructuredWorkplaceAssistantSuggestionProvider.PROVIDER_REFERENCE;
import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceAssistantConfirmPostgresTest {
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    private static final long ACTOR = 23_101L;
    private static final AtomicLong TENANTS = new AtomicLong(9_997_000L);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static PlatformTransactionManager transactionManager;
    private static WorkplaceAssistantRepository repository;
    private static WorkplaceAssistantAuditRepository audit;
    private static WorkplaceAssistantSupport support;
    private static WorkplaceAssistantRequestService requestService;
    private static WorkplaceAssistantBookingService bookingService;
    private static WorkplaceAssistantGovernanceService governanceService;
    private static WorkplaceAssistantCommandCoordinator coordinator;
    private static WorkplaceBookingOrchestrationService bookingAuthority;
    private static ObjectMapper mapper;
    private static WorkplaceService workplace;
    private static final AtomicInteger ownerEffects = new AtomicInteger();
    private static final Set<UUID> knownFailureResources =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void migrateAndBuildActualAuthority() {
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
        mapper = new ObjectMapper().findAndRegisterModules();

        WorkplaceBookingOrchestrationRepository authorityRepository =
                new WorkplaceBookingOrchestrationRepository(jdbc, mapper);
        WorkplaceSpatialGovernanceService spatial = mock(WorkplaceSpatialGovernanceService.class);
        when(spatial.evaluateFloorAccess(
                anyLong(), anyLong(), any(), any(UUID.class), any(UUID.class),
                any(WorkplaceSpatialGovernanceDtos.AccessPermission.class)))
                .thenAnswer(invocation -> new WorkplaceSpatialGovernanceDtos.SiteAccessDecision(
                        invocation.getArgument(3), invocation.getArgument(1),
                        invocation.getArgument(5), true, "TEST_ALLOW", List.of(),
                        NOW, invocation.getArgument(4)));
        workplace = mock(WorkplaceService.class);
        when(workplace.createBooking(anyLong(), anyLong(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    WorkplaceDtos.BookingRequest request = invocation.getArgument(7);
                    if (knownFailureResources.contains(request.resourceId())) {
                        throw new BaseException(
                                ErrorCode.RESOURCE_CONFLICT, "Known owner conflict");
                    }
                    ownerEffects.incrementAndGet();
                    return new WorkplaceDtos.Booking(
                            UUID.randomUUID(), request.resourceId(), "Confirmed desk",
                            ResourceType.DESK, "Seoul", "10F", request.purpose(),
                            request.startsAt(), request.endsAt(), BookingStatus.RESERVED,
                            request.visibleToColleagues(), null, null, false, true, true,
                            null, null, 1);
                });
        WorkplaceBookingBatchExecutor executor = new WorkplaceBookingBatchExecutor(
                authorityRepository, workplace, mock(RoomService.class), transactionManager);
        WorkplaceBookingOrchestrationService authorityTarget =
                new WorkplaceBookingOrchestrationService(
                        authorityRepository, spatial, executor, mapper);
        bookingAuthority = transactionalProxy(authorityTarget,
                WorkplaceBookingOrchestrationService.class, transactionManager);

        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(source);
        repository = new WorkplaceAssistantRepository(named, mapper);
        WorkplaceAssistantAuditRepository auditTarget =
                new WorkplaceAssistantAuditRepository(named, mapper);
        audit = transactionalProxy(auditTarget,
                WorkplaceAssistantAuditRepository.class, transactionManager);
        support = new WorkplaceAssistantSupport(
                repository, List.of(new StructuredWorkplaceAssistantSuggestionProvider()), mapper,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        coordinator = new WorkplaceAssistantCommandCoordinator(
                audit, transactionManager, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        WorkplaceAssistantRedactor redactor = new WorkplaceAssistantRedactor();
        requestService = new WorkplaceAssistantRequestService(
                repository, audit, redactor, mapper, support);
        bookingService = new WorkplaceAssistantBookingService(
                repository, audit, coordinator, bookingAuthority, redactor, mapper, support);
        governanceService = new WorkplaceAssistantGovernanceService(
                repository, audit, redactor, mapper, support);
    }

    @Test
    void normalConfirmationCompletesWithoutFalseResultUnknown() {
        Prepared prepared = prepared(1);
        int before = ownerEffects.get();

        AssistantCommandResult result = bookingService.confirm(
                prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                "confirm-success", "corr-confirm-success", confirm(prepared.version()));

        assertThat(result.request().state()).isEqualTo(RequestState.SUCCEEDED);
        assertThat(result.request().requeryRequired()).isFalse();
        assertThat(result.receipt().state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(ownerEffects.get() - before).isEqualTo(1);
        assertThat(batchCount(prepared.tenantId(), prepared.requestId())).isEqualTo(1);
    }

    @Test
    void staleProcessingPollCannotRegressTerminalRequestState() {
        Prepared prepared = prepared(1);
        AssistantCommandResult confirmed = bookingService.confirm(
                prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                "confirm-terminal-fence", "corr-terminal-fence",
                confirm(prepared.version()));
        BookingBatch terminalBatch = bookingAuthority.batch(
                prepared.tenantId(), ACTOR, confirmed.request().bookingBatchId());
        BookingBatch staleProcessing = new BookingBatch(
                terminalBatch.batchId(), terminalBatch.intentId(), terminalBatch.actorUserId(),
                com.dwp.services.platform.workplace.bookingorchestration
                        .WorkplaceBookingOrchestrationDtos.BatchState.PROCESSING,
                terminalBatch.failurePolicy(), terminalBatch.reason(), terminalBatch.items(),
                false, false, Math.max(1, terminalBatch.version() - 1),
                terminalBatch.createdAt(), terminalBatch.startedAt(), null,
                terminalBatch.updatedAt());
        WorkplaceBookingOrchestrationService staleAuthority =
                mock(WorkplaceBookingOrchestrationService.class);
        when(staleAuthority.batch(
                prepared.tenantId(), ACTOR, terminalBatch.batchId()))
                .thenReturn(staleProcessing);
        WorkplaceAssistantBookingService polling = new WorkplaceAssistantBookingService(
                repository, audit, coordinator, staleAuthority,
                new WorkplaceAssistantRedactor(), mapper, support);

        AssistantExecution execution = polling.execution(
                prepared.tenantId(), ACTOR, prepared.requestId());

        assertThat(execution.state()).isEqualTo(RequestState.SUCCEEDED);
        assertThat(requestState(prepared.tenantId(), prepared.requestId()))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void knownOwnerFailureProducesPartialInsteadOfFalseUnknown() {
        Prepared prepared = prepared(2);
        knownFailureResources.add(prepared.resources().get(1));
        try {
            AssistantCommandResult result = bookingService.confirm(
                    prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                    "confirm-partial", "corr-confirm-partial", confirm(prepared.version()));

            assertThat(result.request().state()).isEqualTo(RequestState.PARTIAL);
            assertThat(result.request().requeryRequired()).isTrue();
            assertThat(result.request().lastResultCode()).isEqualTo("PARTIAL");
            assertThat(result.receipt().state()).isEqualTo(CommandState.SUCCEEDED);
        } finally {
            knownFailureResources.remove(prepared.resources().get(1));
        }
    }

    @Test
    void concurrentSameKeyCreatesOneBatchAndOneOwnerEffect() throws Exception {
        Prepared prepared = prepared(1);
        ConfirmAssistantRequest request = confirm(prepared.version());
        CountDownLatch start = new CountDownLatch(1);
        int before = ownerEffects.get();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AssistantCommandResult> first = executor.submit(() -> {
                start.await();
                return bookingService.confirm(prepared.tenantId(), ACTOR, "group:employee", "en",
                        prepared.requestId(), "confirm-concurrent", "corr-concurrent", request);
            });
            Future<AssistantCommandResult> second = executor.submit(() -> {
                start.await();
                return bookingService.confirm(prepared.tenantId(), ACTOR, "group:employee", "en",
                        prepared.requestId(), "confirm-concurrent", "corr-concurrent", request);
            });
            start.countDown();
            first.get();
            second.get();
        }

        AssistantExecution finalState = bookingService.execution(
                prepared.tenantId(), ACTOR, prepared.requestId());
        assertThat(finalState.state()).isEqualTo(RequestState.SUCCEEDED);
        assertThat(ownerEffects.get() - before).isEqualTo(1);
        assertThat(batchCount(prepared.tenantId(), prepared.requestId())).isEqualTo(1);
        assertThat(commandCount(prepared.tenantId(), "confirm-concurrent")).isEqualTo(1);
    }

    @Test
    void concurrentDifferentKeysKeepOneActiveConfirmationAndOneAuthorityEffect()
            throws Exception {
        Prepared prepared = prepared(1);
        CountDownLatch firstHoldEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstHold = new CountDownLatch(1);
        AtomicInteger holdCalls = new AtomicInteger();
        WorkplaceBookingOrchestrationService delayedAuthority =
                mock(WorkplaceBookingOrchestrationService.class, delegatesTo(bookingAuthority));
        doAnswer(invocation -> {
            holdCalls.incrementAndGet();
            firstHoldEntered.countDown();
            if (!releaseFirstHold.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the first hold call.");
            }
            return bookingAuthority.createHolds(
                    invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3),
                    invocation.getArgument(4), invocation.getArgument(5),
                    invocation.getArgument(6));
        }).when(delayedAuthority).createHolds(
                anyLong(), anyLong(), any(), any(), any(), any(), any());
        WorkplaceAssistantBookingService delayed = new WorkplaceAssistantBookingService(
                repository, audit, coordinator, delayedAuthority,
                new WorkplaceAssistantRedactor(), mapper, support);
        ConfirmAssistantRequest request = confirm(prepared.version());
        int effectsBefore = ownerEffects.get();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AssistantCommandResult> winner = executor.submit(() -> delayed.confirm(
                    prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                    "confirm-first-key", "corr-first-key", request));
            assertThat(firstHoldEntered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<AssistantCommandResult> loser = executor.submit(() -> delayed.confirm(
                    prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                    "confirm-second-key", "corr-second-key", request));
            AssistantCommandResult loserResult;
            try {
                loserResult = loser.get(10, TimeUnit.SECONDS);
            } finally {
                releaseFirstHold.countDown();
            }
            AssistantCommandResult winnerResult = winner.get(10, TimeUnit.SECONDS);

            assertThat(loserResult.receipt().state()).isEqualTo(CommandState.FAILED);
            assertThat(loserResult.receipt().replayed()).isTrue();
            assertThat(loserResult.receipt().resultCode())
                    .isEqualTo("CONFIRMATION_ALREADY_IN_PROGRESS");
            AssistantCommandResult loserReplay = delayed.confirm(
                    prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                    "confirm-second-key", "corr-second-key", request);
            assertThat(loserReplay.receipt()).isEqualTo(new CommandReceipt(
                    loserResult.receipt().commandId(), loserResult.receipt().state(),
                    loserResult.receipt().statusHref(), true,
                    loserResult.receipt().correlationId(), loserResult.receipt().resultCode(),
                    loserResult.receipt().acceptedAt(), loserResult.receipt().completedAt()));
            assertThat(winnerResult.request().state()).isEqualTo(RequestState.SUCCEEDED);
        }
        assertThat(holdCalls).hasValue(1);
        assertThat(ownerEffects.get() - effectsBefore).isEqualTo(1);
        assertThat(batchCount(prepared.tenantId(), prepared.requestId())).isEqualTo(1);
        assertThat(commandState(prepared.tenantId(), "confirm-first-key"))
                .isEqualTo("SUCCEEDED");
        assertThat(commandState(prepared.tenantId(), "confirm-second-key"))
                .isEqualTo("FAILED");
    }

    @Test
    void differentPayloadDuringActiveConfirmationFailsWithoutSecondEffect()
            throws Exception {
        Prepared prepared = prepared(1);
        CountDownLatch firstHoldEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstHold = new CountDownLatch(1);
        WorkplaceBookingOrchestrationService delayedAuthority =
                mock(WorkplaceBookingOrchestrationService.class, delegatesTo(bookingAuthority));
        doAnswer(invocation -> {
            firstHoldEntered.countDown();
            releaseFirstHold.await(10, TimeUnit.SECONDS);
            return bookingAuthority.createHolds(
                    invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3),
                    invocation.getArgument(4), invocation.getArgument(5),
                    invocation.getArgument(6));
        }).when(delayedAuthority).createHolds(
                anyLong(), anyLong(), any(), any(), any(), any(), any());
        WorkplaceAssistantBookingService delayed = new WorkplaceAssistantBookingService(
                repository, audit, coordinator, delayedAuthority,
                new WorkplaceAssistantRedactor(), mapper, support);
        int effectsBefore = ownerEffects.get();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<AssistantCommandResult> winner = executor.submit(() -> delayed.confirm(
                    prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                    "confirm-active-original", "corr-active-original",
                    confirm(prepared.version())));
            assertThat(firstHoldEntered.await(10, TimeUnit.SECONDS)).isTrue();
            AssistantCommandResult conflict;
            try {
                conflict = delayed.confirm(
                        prepared.tenantId(), ACTOR, "group:employee", "en",
                        prepared.requestId(), "confirm-active-different",
                        "corr-active-different", new ConfirmAssistantRequest(
                                prepared.version(), SelectionMode.ALL, List.of(),
                                ConfirmationFailurePolicy.COMPENSATE_ALL, true,
                                "A different confirmation payload"));
            } finally {
                releaseFirstHold.countDown();
            }
            assertThat(conflict.receipt().state()).isEqualTo(CommandState.FAILED);
            assertThat(conflict.receipt().resultCode())
                    .isEqualTo("CONFIRMATION_ALREADY_IN_PROGRESS");
            assertThat(winner.get(10, TimeUnit.SECONDS).request().state())
                    .isEqualTo(RequestState.SUCCEEDED);
        }
        assertThat(ownerEffects.get() - effectsBefore).isEqualTo(1);
        assertThat(batchCount(prepared.tenantId(), prepared.requestId())).isEqualTo(1);
    }

    @Test
    void attachedAcceptedBatchResumesAfterProcessCrashBeforeExecution() {
        Prepared prepared = prepared(1);
        AtomicInteger executionAttempts = new AtomicInteger();
        WorkplaceBookingOrchestrationService crashOnceAuthority =
                mock(WorkplaceBookingOrchestrationService.class, delegatesTo(bookingAuthority));
        doAnswer(invocation -> {
            if (executionAttempts.incrementAndGet() == 1) {
                throw new SimulatedProcessCrash();
            }
            bookingAuthority.executeBatch(
                    invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3));
            return null;
        }).when(crashOnceAuthority).executeBatch(anyLong(), any(), any(), any());
        WorkplaceAssistantBookingService crashing = new WorkplaceAssistantBookingService(
                repository, audit, coordinator, crashOnceAuthority,
                new WorkplaceAssistantRedactor(), mapper, support);
        ConfirmAssistantRequest request = confirm(prepared.version());

        assertThatThrownBy(() -> crashing.confirm(
                prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                "confirm-attached-crash", "corr-attached-crash", request))
                .isInstanceOf(SimulatedProcessCrash.class);
        assertThat(commandState(prepared.tenantId(), "confirm-attached-crash"))
                .isEqualTo("ACCEPTED");
        assertThat(batchState(prepared.tenantId(), prepared.requestId())).isEqualTo("ACCEPTED");
        assertThat(requestState(prepared.tenantId(), prepared.requestId()))
                .isEqualTo("PROCESSING");

        WorkplaceAssistantCommandCoordinator restartedCoordinator =
                new WorkplaceAssistantCommandCoordinator(audit, transactionManager,
                        Clock.fixed(NOW.plusSeconds(31).toInstant(), ZoneOffset.UTC));
        WorkplaceAssistantBookingService restarted = new WorkplaceAssistantBookingService(
                repository, audit, restartedCoordinator, crashOnceAuthority,
                new WorkplaceAssistantRedactor(), mapper, support);
        AssistantCommandResult recovered = restarted.confirm(
                prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                "confirm-attached-crash", "corr-attached-crash", request);

        assertThat(executionAttempts).hasValue(2);
        assertThat(recovered.request().state()).isEqualTo(RequestState.SUCCEEDED);
        assertThat(recovered.receipt().state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(batchCount(prepared.tenantId(), prepared.requestId())).isEqualTo(1);
    }

    @Test
    void expiredCommandRecoversWithNewKeyWhenBrowserLostOriginalKey() {
        Prepared prepared = prepared(1);
        WorkplaceBookingOrchestrationService crashingAuthority =
                mock(WorkplaceBookingOrchestrationService.class);
        when(crashingAuthority.createHolds(anyLong(), anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("simulated process crash"));
        WorkplaceAssistantBookingService crashing = new WorkplaceAssistantBookingService(
                repository, audit, coordinator, crashingAuthority,
                new WorkplaceAssistantRedactor(), mapper, support);
        ConfirmAssistantRequest request = confirm(prepared.version());

        assertThatThrownBy(() -> crashing.confirm(
                prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                "confirm-restart", "corr-restart", request))
                .isInstanceOf(IllegalStateException.class);
        assertThat(commandState(prepared.tenantId(), "confirm-restart"))
                .isEqualTo("ACCEPTED");
        WorkplaceAssistantCommandCoordinator restartedCoordinator =
                new WorkplaceAssistantCommandCoordinator(audit, transactionManager,
                        Clock.fixed(NOW.plusSeconds(31).toInstant(), ZoneOffset.UTC));
        WorkplaceAssistantBookingService restarted = new WorkplaceAssistantBookingService(
                repository, audit, restartedCoordinator, bookingAuthority,
                new WorkplaceAssistantRedactor(), mapper, support);
        AssistantCommandResult recovered = restarted.confirm(
                prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                "confirm-restart-new-key", "corr-restart-new", request);

        assertThat(recovered.request().state()).isEqualTo(RequestState.SUCCEEDED);
        assertThat(recovered.receipt().state()).isEqualTo(CommandState.SUCCEEDED);
        assertThat(commandState(prepared.tenantId(), "confirm-restart"))
                .isEqualTo("RESULT_UNKNOWN");
        assertThat(commandState(prepared.tenantId(), "confirm-restart-new-key"))
                .isEqualTo("SUCCEEDED");
        assertThat(batchCount(prepared.tenantId(), prepared.requestId())).isEqualTo(1);
    }

    @Test
    void leaseTakeoverFencesStaleFinisherFromOverwritingTerminalTruth() {
        Prepared prepared = prepared(1);
        String key = "confirm-fence";
        String fingerprint = support.fingerprint(java.util.Map.of(
                "requestId", prepared.requestId(), "key", key));
        CommandRow accepted = support.acceptedCommand(
                prepared.tenantId(), ACTOR, prepared.requestId(), "CONFIRM_REQUEST", key,
                fingerprint, WorkplaceAssistantSupport.executionHref(prepared.requestId()),
                "Fence stale worker", "corr-fence", NOW);
        var first = coordinator.claim(accepted);
        assertThat(first.executionClaimed()).isTrue();
        WorkplaceAssistantCommandCoordinator laterCoordinator =
                new WorkplaceAssistantCommandCoordinator(audit, transactionManager,
                        Clock.fixed(NOW.plusSeconds(31).toInstant(), ZoneOffset.UTC));
        var takeover = laterCoordinator.claim(accepted);
        assertThat(takeover.executionClaimed()).isTrue();

        assertThat(audit.finishClaimedCommand(
                prepared.tenantId(), accepted.commandId(), takeover.executionClaimToken(),
                CommandState.SUCCEEDED, "SUCCEEDED", NOW.plusSeconds(32))).isTrue();
        assertThat(audit.finishClaimedCommand(
                prepared.tenantId(), accepted.commandId(), first.executionClaimToken(),
                CommandState.FAILED, "STALE_FAILURE", NOW.plusSeconds(33))).isFalse();
        assertThat(commandState(prepared.tenantId(), key)).isEqualTo("SUCCEEDED");
    }

    @Test
    void claimedKnownFailureClosesAsFailedAndSameKeyReplaysExactReceipt() {
        Prepared prepared = prepared(1);
        ConfirmAssistantRequest stale = confirm(prepared.version() + 9);

        assertThatThrownBy(() -> bookingService.confirm(
                prepared.tenantId(), ACTOR, null, "en", prepared.requestId(),
                "confirm-known-failure", "corr-failure", stale))
                .isInstanceOf(BaseException.class);
        assertThat(commandState(prepared.tenantId(), "confirm-known-failure"))
                .isEqualTo("FAILED");
        CommandTerminal terminal = commandTerminal(
                prepared.tenantId(), "confirm-known-failure");

        AssistantCommandResult replay = bookingService.confirm(
                prepared.tenantId(), ACTOR, null, "en", prepared.requestId(),
                "confirm-known-failure", "corr-failure", stale);
        assertThat(replay.receipt().state()).isEqualTo(CommandState.FAILED);
        assertThat(replay.receipt().replayed()).isTrue();
        assertThat(replay.receipt().resultCode()).isEqualTo(terminal.resultCode());
        assertThat(replay.receipt().completedAt()).isEqualTo(terminal.completedAt());
        assertThat(replay.receipt().resultCode()).isEqualTo(
                ErrorCode.OBJECT_VERSION_CONFLICT.getCode());
    }

    @Test
    void wrongActorCannotCreateConfirmationCommandOrLockOutRequestOwner() {
        Prepared prepared = prepared(1);

        assertThatThrownBy(() -> bookingService.confirm(
                prepared.tenantId(), ACTOR + 1, "group:employee", "en", prepared.requestId(),
                "confirm-attacker", "corr-attacker", confirm(prepared.version())))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(commandCount(prepared.tenantId(), "confirm-attacker")).isZero();
        assertThat(activeConfirmationCount(prepared.tenantId(), prepared.requestId())).isZero();

        AssistantCommandResult owner = bookingService.confirm(
                prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                "confirm-owner-after-attack", "corr-owner", confirm(prepared.version()));
        assertThat(owner.request().state()).isEqualTo(RequestState.SUCCEEDED);
        assertThat(owner.receipt().state()).isEqualTo(CommandState.SUCCEEDED);
    }

    @Test
    void requestReadsAreTenantAndActorScopedAndKillSwitchFailsClosed() {
        Prepared prepared = prepared(1);
        assertThat(requestService.request(
                prepared.tenantId(), ACTOR, prepared.requestId()).requestId())
                .isEqualTo(prepared.requestId());
        assertThatThrownBy(() -> requestService.request(
                prepared.tenantId(), ACTOR + 1, prepared.requestId()))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> requestService.request(
                prepared.tenantId() + 1, ACTOR, prepared.requestId()))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        jdbc.update("""
                UPDATE wp_assistant_governance SET kill_switch = TRUE, version = version + 1
                 WHERE tenant_id = ?
                """, prepared.tenantId());
        assertThatThrownBy(() -> bookingService.confirm(
                prepared.tenantId(), ACTOR, null, "en", prepared.requestId(),
                "confirm-killed", "corr-killed", confirm(prepared.version())))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(commandState(prepared.tenantId(), "confirm-killed")).isEqualTo("FAILED");
        assertThat(batchCount(prepared.tenantId(), prepared.requestId())).isZero();
    }

    @Test
    void feedbackReuseRequiresTenantOptInKillSwitchOffAndAllConsents() {
        Prepared enabled = prepared(1, true);
        FeedbackReceipt eligible = transaction.execute(status -> governanceService.feedback(
                enabled.tenantId(), ACTOR, enabled.requestId(), "feedback-enabled",
                "corr-feedback-enabled", feedback(enabled.version())));
        assertThat(eligible.eligibleForModelImprovementUse()).isTrue();

        Prepared optedOut = prepared(1, true);
        jdbc.update("""
                UPDATE wp_assistant_governance
                   SET tenant_opt_in = FALSE, version = version + 1
                 WHERE tenant_id = ?
                """, optedOut.tenantId());
        FeedbackReceipt optOutReceipt = transaction.execute(status -> governanceService.feedback(
                optedOut.tenantId(), ACTOR, optedOut.requestId(), "feedback-optout",
                "corr-feedback-optout", feedback(optedOut.version())));
        assertThat(optOutReceipt.eligibleForModelImprovementUse()).isFalse();

        Prepared killed = prepared(1, true);
        jdbc.update("""
                UPDATE wp_assistant_governance
                   SET kill_switch = TRUE, version = version + 1
                 WHERE tenant_id = ?
                """, killed.tenantId());
        FeedbackReceipt killedReceipt = transaction.execute(status -> governanceService.feedback(
                killed.tenantId(), ACTOR, killed.requestId(), "feedback-killed",
                "corr-feedback-killed", feedback(killed.version())));
        assertThat(killedReceipt.eligibleForModelImprovementUse()).isFalse();
    }

    @Test
    void expiredRequestRejectsFeedbackBeforeRetentionSchedulerRuns() {
        Prepared prepared = prepared(1, true);
        jdbc.update("""
                UPDATE wp_assistant_requests
                   SET retention_expires_at = ?
                 WHERE tenant_id = ? AND request_id = ?
                """, NOW.plusSeconds(1), prepared.tenantId(), prepared.requestId());
        WorkplaceAssistantSupport afterExpiry = new WorkplaceAssistantSupport(
                repository, List.of(new StructuredWorkplaceAssistantSuggestionProvider()), mapper,
                Clock.fixed(NOW.plusSeconds(2).toInstant(), ZoneOffset.UTC));
        WorkplaceAssistantGovernanceService serviceAfterExpiry =
                new WorkplaceAssistantGovernanceService(
                        repository, audit, new WorkplaceAssistantRedactor(), mapper, afterExpiry);

        assertThatThrownBy(() -> transaction.execute(status -> serviceAfterExpiry.feedback(
                prepared.tenantId(), ACTOR, prepared.requestId(), "feedback-after-expiry",
                "corr-after-expiry", feedback(prepared.version()))))
                .isInstanceOf(BaseException.class);
        assertThat(feedbackCount(prepared.tenantId(), prepared.requestId())).isZero();
        assertThat(commandCountByType(
                prepared.tenantId(), prepared.requestId(), "SUBMIT_FEEDBACK")).isZero();
    }

    @Test
    void retentionSerializesWithFeedbackAndScrubsAConcurrentPreExpiryInsert()
            throws Exception {
        Prepared prepared = prepared(1, true);
        jdbc.update("""
                UPDATE wp_assistant_requests
                   SET retention_expires_at = ?
                 WHERE tenant_id = ? AND request_id = ?
                """, NOW.plusSeconds(1), prepared.tenantId(), prepared.requestId());
        WorkplaceAssistantRetentionService retention = new WorkplaceAssistantRetentionService(
                audit, Clock.fixed(NOW.plusSeconds(2).toInstant(), ZoneOffset.UTC));
        CountDownLatch requestLocked = new CountDownLatch(1);
        CountDownLatch retentionAttempting = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<FeedbackReceipt> feedback = executor.submit(() -> transaction.execute(status -> {
                repository.requestForUpdate(
                        prepared.tenantId(), ACTOR, prepared.requestId()).orElseThrow();
                requestLocked.countDown();
                awaitLatch(retentionAttempting);
                return governanceService.feedback(
                        prepared.tenantId(), ACTOR, prepared.requestId(),
                        "feedback-retention-race", "corr-retention-race",
                        feedback(prepared.version()));
            }));
            assertThat(requestLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> cleanup = executor.submit(() -> {
                retentionAttempting.countDown();
                return transaction.execute(status -> retention.redactExpiredContent());
            });
            assertThat(feedback.get(10, TimeUnit.SECONDS).redactedComment()).isNotNull();
            assertThat(cleanup.get(10, TimeUnit.SECONDS)).isPositive();
        }

        assertThat(retainedContentDeleted(
                prepared.tenantId(), prepared.requestId())).isTrue();
        assertThat(retainedFeedbackScrubbed(
                prepared.tenantId(), prepared.requestId())).isTrue();
        assertThat(unscrubbedCommandReasonCount(
                prepared.tenantId(), prepared.requestId())).isZero();
    }

    @Test
    void retentionDeletesContentButPreservesTombstoneAuthorityAndTenantAuditIsolation() {
        Prepared prepared = prepared(1, true);
        AssistantCommandResult confirmed = bookingService.confirm(
                prepared.tenantId(), ACTOR, "group:employee", "en", prepared.requestId(),
                "confirm-retention", "corr-retention", confirm(prepared.version()));
        assertThat(confirmed.request().state()).isEqualTo(RequestState.SUCCEEDED);
        FeedbackReceipt feedback = transaction.execute(status -> governanceService.feedback(
                prepared.tenantId(), ACTOR, prepared.requestId(), "feedback-retention",
                "corr-feedback-retention", new FeedbackRequest(
                        confirmed.request().version(), FeedbackRating.HELPFUL,
                        "Contact retained@example.com", true, true,
                        "Keep this sensitive feedback reason")));
        assertThat(feedback.redactedComment()).isNotNull();
        assertThat(feedback.eligibleForModelImprovementUse()).isTrue();
        AuthorityRefs authorityBefore = authorityRefs(
                prepared.tenantId(), prepared.requestId());
        long commandsBefore = assistantCommandCount(prepared.tenantId(), prepared.requestId());
        long auditBefore = assistantAuditCount(prepared.tenantId(), prepared.requestId());
        assertThat(authorityBefore.intentId()).isNotNull();
        assertThat(authorityBefore.batchId()).isNotNull();
        assertThat(auditBefore).isPositive();
        jdbc.update("""
                UPDATE wp_assistant_requests
                   SET retention_expires_at = ?
                 WHERE tenant_id = ? AND request_id = ?
                """, NOW.plusSeconds(1), prepared.tenantId(), prepared.requestId());

        WorkplaceAssistantRetentionService retention = new WorkplaceAssistantRetentionService(
                audit, Clock.fixed(NOW.plusSeconds(2).toInstant(), ZoneOffset.UTC));
        int redacted = transaction.execute(status -> retention.redactExpiredContent());

        assertThat(redacted).isPositive();
        AssistantRequest tombstone = requestService.request(
                prepared.tenantId(), ACTOR, prepared.requestId());
        assertThat(tombstone.state()).isEqualTo(RequestState.SUCCEEDED);
        assertThat(tombstone.redactedRequestText()).isNull();
        assertThat(tombstone.redactionState()).isEqualTo(RedactionState.RETAINED_CONTENT_DELETED);
        assertThat(tombstone.proposals()).isEmpty();
        assertThat(tombstone.validation()).isNull();
        assertThat(tombstone.limitations()).isEmpty();
        assertThat(retainedContentDeleted(prepared.tenantId(), prepared.requestId())).isTrue();
        assertThat(authorityRefs(prepared.tenantId(), prepared.requestId()))
                .isEqualTo(authorityBefore);
        assertThat(assistantCommandCount(prepared.tenantId(), prepared.requestId()))
                .isEqualTo(commandsBefore);
        assertThat(assistantAuditCount(prepared.tenantId(), prepared.requestId()))
                .isEqualTo(auditBefore);
        assertThat(authorityRowCount(prepared.tenantId(), authorityBefore)).isEqualTo(2);
        assertThat(retainedFeedbackScrubbed(
                prepared.tenantId(), prepared.requestId())).isTrue();
        assertThat(unscrubbedCommandReasonCount(
                prepared.tenantId(), prepared.requestId())).isZero();
        assertThatThrownBy(() -> transaction.execute(status -> governanceService.feedback(
                prepared.tenantId(), ACTOR, prepared.requestId(), "feedback-after-retention",
                "corr-feedback-after-retention", new FeedbackRequest(
                        tombstone.version(), FeedbackRating.HELPFUL, "late comment",
                        false, true, "late feedback"))))
                .isInstanceOf(BaseException.class);

        Fixture otherTenant = fixture(1);
        assertThatThrownBy(() -> requestService.request(
                otherTenant.tenantId(), ACTOR, prepared.requestId()))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(audit.auditEvents(
                otherTenant.tenantId(), prepared.requestId(), 100)).isEmpty();
        assertThat(audit.auditEvents(
                prepared.tenantId(), prepared.requestId(), 100)).hasSize((int) auditBefore);
    }

    private static Prepared prepared(int itemCount) {
        return prepared(itemCount, false);
    }

    private static Prepared prepared(int itemCount, boolean feedbackUseConsent) {
        Fixture fixture = fixture(itemCount);
        List<RequestedBookingItem> items = java.util.stream.IntStream.range(0, itemCount)
                .mapToObj(index -> new RequestedBookingItem(
                        "item-" + index, ACTOR, fixture.personId(), "Trusted user", null,
                        ResourceType.DESK, fixture.resources().get(index), fixture.siteId(),
                        fixture.floorId(), NOW.plusDays(1), NOW.plusDays(1).plusHours(1),
                        "Focus " + index, true, false, List.of()))
                .toList();
        AssistantCommandResult created = transaction.execute(status -> requestService.create(
                fixture.tenantId(), ACTOR, fixture.personId(), "Trusted user",
                "group:employee", "en", "create-" + fixture.tenantId(),
                "corr-create", new CreateAssistantRequest(
                        "Reserve workspace", items, true, feedbackUseConsent,
                        "Prepare options")));
        AssistantCommandResult validated = transaction.execute(status -> bookingService.validate(
                fixture.tenantId(), ACTOR, fixture.personId(), "Trusted user",
                "group:employee", "en", created.request().requestId(),
                "validate-" + fixture.tenantId(), "corr-validate",
                new ValidateAssistantRequest(created.request().version(), SelectionMode.ALL,
                        List.of(), 120, true, "Validate with booking authority")));
        return new Prepared(fixture.tenantId(), validated.request().requestId(),
                validated.request().version(), fixture.resources());
    }

    private static Fixture fixture(int resources) {
        long tenantId = TENANTS.incrementAndGet();
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID personId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_service_tenants (
                    provider_tenant_id, tenant_id, tenant_key, display_name, lifecycle_state,
                    data_region, isolation_model, created_by, updated_by)
                VALUES (?, ?, ?, 'Assistant confirm', 'ACTIVE', 'kr', 'POOL', ?, ?)
                """, UUID.randomUUID(), tenantId, "assistant-confirm-" + tenantId, ACTOR, ACTOR);
        jdbc.update("INSERT INTO wp_tenant_policies (tenant_id) VALUES (?)", tenantId);
        jdbc.update("""
                INSERT INTO wp_sites (
                    site_id, tenant_id, site_code, name_ko, name_en, time_zone)
                VALUES (?, ?, ?, '서울', 'Seoul', 'Asia/Seoul')
                """, siteId, tenantId, "SITE_" + siteId);
        jdbc.update("""
                INSERT INTO wp_floors (
                    floor_id, tenant_id, site_id, floor_number, name_ko, name_en,
                    lifecycle_state)
                VALUES (?, ?, ?, 10, '10층', '10F', 'ACTIVE')
                """, floorId, tenantId, siteId);
        List<UUID> resourceIds = java.util.stream.IntStream.range(0, resources)
                .mapToObj(index -> {
                    UUID resourceId = UUID.randomUUID();
                    jdbc.update("""
                            INSERT INTO wp_resources (
                                resource_id, tenant_id, floor_id, resource_code,
                                name_ko, name_en, resource_type, neighborhood,
                                position_x, position_y, features, lifecycle_state, booking_mode)
                            VALUES (?, ?, ?, ?, ?, ?, 'DESK', 'ALPHA', ?, 5,
                                    '[]'::jsonb, 'AVAILABLE', 'RESERVABLE')
                            """, resourceId, tenantId, floorId, "DESK_" + resourceId,
                            "좌석 " + index, "Desk " + index, 5 + index * 10);
                    return resourceId;
                }).toList();
        jdbc.update("""
                INSERT INTO wp_assistant_governance (
                    tenant_id, tenant_opt_in, kill_switch, model_provider_reference,
                    model_version, prompt_version, tool_version, retention_days,
                    feedback_use_enabled, redaction_state, version, updated_by)
                VALUES (?, TRUE, FALSE, ?, 'model-1', 'prompt-1', 'tool-1',
                        30, TRUE, 'READY', 1, ?)
                """, tenantId, PROVIDER_REFERENCE, ACTOR);
        return new Fixture(tenantId, siteId, floorId, personId, resourceIds);
    }

    private static ConfirmAssistantRequest confirm(long version) {
        return new ConfirmAssistantRequest(version, SelectionMode.ALL, List.of(),
                ConfirmationFailurePolicy.KEEP_SUCCEEDED, true, "Confirm selected options");
    }

    private static FeedbackRequest feedback(long version) {
        return new FeedbackRequest(
                version, FeedbackRating.HELPFUL, "Useful result", true, true,
                "Allow governed improvement");
    }

    private static long batchCount(long tenantId, UUID requestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_batches batch_row
                  JOIN wp_assistant_requests request_row
                    ON request_row.tenant_id = batch_row.tenant_id
                   AND request_row.booking_intent_id = batch_row.intent_id
                 WHERE request_row.tenant_id = ? AND request_row.request_id = ?
                """, Long.class, tenantId, requestId);
    }

    private static long commandCount(long tenantId, String key) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_assistant_commands
                 WHERE tenant_id = ? AND command_type = 'CONFIRM_REQUEST'
                   AND idempotency_key = ?
                """, Long.class, tenantId, key);
    }

    private static String commandState(long tenantId, String key) {
        return jdbc.queryForObject("""
                SELECT command_state FROM wp_assistant_commands
                 WHERE tenant_id = ? AND command_type = 'CONFIRM_REQUEST'
                   AND idempotency_key = ?
                """, String.class, tenantId, key);
    }

    private static CommandTerminal commandTerminal(long tenantId, String key) {
        return jdbc.queryForObject("""
                SELECT result_code, completed_at FROM wp_assistant_commands
                 WHERE tenant_id = ? AND command_type = 'CONFIRM_REQUEST'
                   AND idempotency_key = ?
                """, (resultSet, ignored) -> new CommandTerminal(
                        resultSet.getString("result_code"),
                        resultSet.getObject("completed_at", OffsetDateTime.class)),
                tenantId, key);
    }

    private static long activeConfirmationCount(long tenantId, UUID requestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_assistant_commands
                 WHERE tenant_id = ? AND request_id = ?
                   AND command_type = 'CONFIRM_REQUEST' AND command_state = 'ACCEPTED'
                """, Long.class, tenantId, requestId);
    }

    private static long commandCountByType(
            long tenantId, UUID requestId, String commandType) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_assistant_commands
                 WHERE tenant_id = ? AND request_id = ? AND command_type = ?
                """, Long.class, tenantId, requestId, commandType);
    }

    private static long feedbackCount(long tenantId, UUID requestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_assistant_feedback
                 WHERE tenant_id = ? AND request_id = ?
                """, Long.class, tenantId, requestId);
    }

    private static String batchState(long tenantId, UUID requestId) {
        return jdbc.queryForObject("""
                SELECT batch.batch_state FROM wp_booking_batches batch
                  JOIN wp_assistant_requests request
                    ON request.tenant_id = batch.tenant_id
                   AND request.booking_batch_id = batch.batch_id
                 WHERE request.tenant_id = ? AND request.request_id = ?
                """, String.class, tenantId, requestId);
    }

    private static String requestState(long tenantId, UUID requestId) {
        return jdbc.queryForObject("""
                SELECT request_state FROM wp_assistant_requests
                 WHERE tenant_id = ? AND request_id = ?
                """, String.class, tenantId, requestId);
    }

    private static AuthorityRefs authorityRefs(long tenantId, UUID requestId) {
        return jdbc.queryForObject("""
                SELECT booking_intent_id, booking_batch_id
                  FROM wp_assistant_requests
                 WHERE tenant_id = ? AND request_id = ?
                """, (resultSet, ignored) -> new AuthorityRefs(
                        resultSet.getObject("booking_intent_id", UUID.class),
                        resultSet.getObject("booking_batch_id", UUID.class)),
                tenantId, requestId);
    }

    private static long assistantCommandCount(long tenantId, UUID requestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_assistant_commands
                 WHERE tenant_id = ? AND request_id = ?
                """, Long.class, tenantId, requestId);
    }

    private static long assistantAuditCount(long tenantId, UUID requestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_assistant_audit_events
                 WHERE tenant_id = ? AND request_id = ?
                """, Long.class, tenantId, requestId);
    }

    private static boolean retainedContentDeleted(long tenantId, UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT redacted_request_text IS NULL
                   AND redaction_state = 'RETAINED_CONTENT_DELETED'
                   AND structured_proposal = '[]'::jsonb
                   AND validation_snapshot IS NULL
                   AND limitations = '[]'::jsonb
                   AND retention_deleted_at IS NOT NULL
                  FROM wp_assistant_requests
                 WHERE tenant_id = ? AND request_id = ?
                """, Boolean.class, tenantId, requestId));
    }

    private static boolean retainedFeedbackScrubbed(long tenantId, UUID requestId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT bool_and(redacted_comment IS NULL
                                AND NOT eligible_for_model_improvement)
                  FROM wp_assistant_feedback
                 WHERE tenant_id = ? AND request_id = ?
                """, Boolean.class, tenantId, requestId));
    }

    private static long unscrubbedCommandReasonCount(long tenantId, UUID requestId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_assistant_commands
                 WHERE tenant_id = ? AND request_id = ?
                   AND reason <> 'RETAINED_CONTENT_DELETED'
                """, Long.class, tenantId, requestId);
    }

    private static long authorityRowCount(long tenantId, AuthorityRefs refs) {
        Long intents = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_intents
                 WHERE tenant_id = ? AND intent_id = ?
                """, Long.class, tenantId, refs.intentId());
        Long batches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_booking_batches
                 WHERE tenant_id = ? AND batch_id = ?
                """, Long.class, tenantId, refs.batchId());
        return intents + batches;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test coordination.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating the test.", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactionalProxy(
            T target, Class<T> type, PlatformTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy(type.getClassLoader());
    }

    private record Fixture(
            long tenantId, UUID siteId, UUID floorId, UUID personId,
            List<UUID> resources) { }

    private record Prepared(
            long tenantId, UUID requestId, long version, List<UUID> resources) { }

    private record AuthorityRefs(UUID intentId, UUID batchId) { }

    private record CommandTerminal(String resultCode, OffsetDateTime completedAt) { }

    private static final class SimulatedProcessCrash extends Error {
        private static final long serialVersionUID = 1L;
    }
}
