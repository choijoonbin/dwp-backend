package com.dwp.services.platform.workplace.workplaceservices;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsDtos.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServiceOperationsRepository.*;
import static com.dwp.services.platform.workplace.workplaceservices.WorkplaceServicesDtos.CommandState;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkplaceServiceOperationsServiceTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-17T00:00:00Z");
    private static final long TENANT = 42L;
    private static final long ACTOR = 18001L;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final WorkplaceServiceOperationsRepository repository =
            mock(WorkplaceServiceOperationsRepository.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final WorkplaceServiceOperationsService service =
            new WorkplaceServiceOperationsService(repository, mapper, List.of(), List.of(),
                    transaction,
                    Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC));

    WorkplaceServiceOperationsServiceTest() {
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    @Test
    void terminalLegacyVerificationCommandReplaysWithoutSnapshotOrProviderCall() throws Exception {
        UUID providerId = UUID.randomUUID();
        var request = new ProviderVerifyRequest(3, true, "Replay completed verification");
        String fingerprint = fingerprint(providerId, request);
        OperationsCommandRow command = new OperationsCommandRow(UUID.randomUUID(), TENANT,
                ACTOR, "SERVICE_PROVIDER_VERIFY:" + providerId, "verify-replay", fingerprint,
                "SERVICE_PROVIDER", providerId, CommandState.SUCCEEDED,
                "/v1/admin/workplace/service-providers/" + providerId, "corr", NOW, NOW);
        when(repository.command(TENANT, ACTOR, command.scope(), "verify-replay"))
                .thenReturn(Optional.of(command));
        when(repository.provider(TENANT, providerId)).thenReturn(Optional.of(provider(providerId)));

        ProviderCommandResult result = service.verifyProvider(TENANT, ACTOR, providerId,
                "verify-replay", request, "ignored");

        assertThat(result.receipt().replayed()).isTrue();
        assertThat(result.receipt().state()).isEqualTo(CommandState.SUCCEEDED);
        verify(repository, never()).commandProviderSnapshot(anyLong(), any());
    }

    @Test
    void nonterminalLegacyVerificationWithoutSnapshotFailsClosedAndDoesNotRedispatch()
            throws Exception {
        UUID providerId = UUID.randomUUID();
        var request = new ProviderVerifyRequest(3, true, "Recover uncertain verification");
        OperationsCommandRow command = new OperationsCommandRow(UUID.randomUUID(), TENANT,
                ACTOR, "SERVICE_PROVIDER_VERIFY:" + providerId, "verify-legacy",
                fingerprint(providerId, request), "SERVICE_PROVIDER", providerId,
                CommandState.RESULT_UNKNOWN,
                "/v1/admin/workplace/service-providers/" + providerId, "corr", NOW, NOW);
        when(repository.command(TENANT, ACTOR, command.scope(), "verify-legacy"))
                .thenReturn(Optional.of(command));
        when(repository.commandProviderSnapshot(TENANT, command.commandId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyProvider(TENANT, ACTOR, providerId,
                "verify-legacy", request, "ignored"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(repository, never()).provider(TENANT, providerId);
    }

    @Test
    void quantityOneRequesterInspectionCanPassFromExplicitReadyState() {
        TaskContextRow context = context("IN_PREPARATION");
        var responses = mapper.createObjectNode().put("roomReady", true);
        InspectionAttempt attempt = attempt(context, InspectionDecision.PASSED, responses, false);
        when(repository.lineContext(TENANT, context.orderId(), context.lineId(), ACTOR))
                .thenReturn(Optional.of(context));
        when(repository.attachmentsClean(TENANT, context.orderId(), List.of())).thenReturn(true);
        when(repository.command(eq(TENANT), eq(ACTOR), anyString(), eq("inspect-1")))
                .thenReturn(Optional.empty());
        when(repository.latestInspection(TENANT, context.orderId(), context.lineId()))
                .thenReturn(Optional.of(attempt));

        InspectionCommandResult result = service.inspect(TENANT, ACTOR, context.orderId(),
                context.lineId(), false, "inspect-1",
                new InspectionAttemptRequest(InspectionDecision.PASSED, responses, List.of(),
                        context.orderVersion(), context.taskVersion(), true,
                        "Requester confirmed the final room setup"), "corr-inspection");

        assertThat(result.inspection().fulfilledQuantityReady()).isTrue();
        assertThat(result.inspection().accepted()).isTrue();
        assertThat(result.inspection().remediationRequired()).isFalse();
        verify(repository).createInspection(eq(TENANT), any(), eq(context), eq(ACTOR),
                eq(InspectionActorRole.REQUESTER), any(), eq(NOW));
    }

    @Test
    void failedInspectionRequiresRemediationAndNonReadyStateFailsClosed() {
        TaskContextRow ready = context("PARTIALLY_FULFILLED");
        var failedResponses = mapper.createObjectNode().put("roomReady", false);
        InspectionAttempt failed = attempt(
                ready, InspectionDecision.FAILED, failedResponses, true);
        when(repository.lineContext(TENANT, ready.orderId(), ready.lineId(), ACTOR))
                .thenReturn(Optional.of(ready));
        when(repository.attachmentsClean(TENANT, ready.orderId(), List.of())).thenReturn(true);
        when(repository.command(eq(TENANT), eq(ACTOR), anyString(), eq("inspect-failed")))
                .thenReturn(Optional.empty());
        when(repository.latestInspection(TENANT, ready.orderId(), ready.lineId()))
                .thenReturn(Optional.of(failed));

        InspectionCommandResult result = service.inspect(TENANT, ACTOR, ready.orderId(),
                ready.lineId(), false, "inspect-failed",
                new InspectionAttemptRequest(InspectionDecision.FAILED, failedResponses, List.of(),
                        ready.orderVersion(), ready.taskVersion(), true,
                        "Room setup needs remediation"), "corr-remediation");

        assertThat(result.inspection().accepted()).isFalse();
        assertThat(result.inspection().remediationRequired()).isTrue();

        TaskContextRow acceptedOnly = context("ACCEPTED");
        when(repository.lineContext(TENANT, acceptedOnly.orderId(), acceptedOnly.lineId(), ACTOR))
                .thenReturn(Optional.of(acceptedOnly));
        assertThatThrownBy(() -> service.inspect(TENANT, ACTOR, acceptedOnly.orderId(),
                acceptedOnly.lineId(), false, "inspect-too-soon",
                new InspectionAttemptRequest(InspectionDecision.PASSED,
                        mapper.createObjectNode().put("roomReady", true), List.of(),
                        acceptedOnly.orderVersion(), acceptedOnly.taskVersion(), true,
                        "Too early"), "corr-too-early"))
                .isInstanceOfSatisfying(BaseException.class,
                        error -> assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void capacityProjectionReportsStaleAndExhaustedBucketsWithoutInventingAvailability() {
        UUID itemId = UUID.randomUUID();
        OffsetDateTime from = NOW.plusHours(1);
        OffsetDateTime to = from.plusHours(1);
        when(repository.catalogPolicy(TENANT, itemId)).thenReturn(new CatalogOperationsPolicy(
                itemId, CapacityMode.BUCKETED, 300, InspectionMode.NONE,
                mapper.createArrayNode()));
        when(repository.capacityBuckets(TENANT, itemId, "site-18", from, to, NOW))
                .thenReturn(List.of(new CapacityBucketRow(UUID.randomUUID(), itemId, "site-18",
                        from, to, 1, 1, 0, "source-v1", NOW.minusMinutes(10),
                        NOW.minusMinutes(10), 1)));

        CapacityRange result = service.capacity(TENANT, itemId, "site-18", from, to);

        assertThat(result.complete()).isFalse();
        assertThat(result.limitations())
                .containsExactly("CAPACITY_STALE", "CAPACITY_EXHAUSTED");
        assertThat(result.buckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.availableQuantity()).isZero();
            assertThat(bucket.fresh()).isFalse();
        });
    }

    private TaskContextRow context(String lineState) {
        var schema = mapper.createArrayNode();
        schema.addObject().put("key", "roomReady").put("type", "BOOLEAN")
                .put("required", true);
        return new TaskContextRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "DWP_NATIVE_FULFILLMENT", 3, ACTOR, "site-18", 5,
                InspectionMode.REQUESTER, schema, 1, 0, lineState,
                mapper.createArrayNode(), UUID.randomUUID(), "DWP_NATIVE",
                "internal://test", List.of("WORKPLACE_SERVICE_FULFILLMENT"),
                mapper.createObjectNode(), ProviderLifecycleState.ACTIVE, 1, true, 1L,
                "HEALTHY", "evidence-18", NOW.minusMinutes(1), NOW.minusMinutes(1));
    }

    private InspectionAttempt attempt(TaskContextRow context, InspectionDecision decision,
                                      com.fasterxml.jackson.databind.JsonNode responses,
                                      boolean remediationRequired) {
        return new InspectionAttempt(UUID.randomUUID(), context.orderId(), context.lineId(),
                context.taskId(), context.inspectionMode(), InspectionActorRole.REQUESTER,
                decision, context.inspectionSchema(), responses, List.of(), "Inspection result",
                remediationRequired, NOW);
    }

    private ProviderRow provider(UUID providerId) {
        return new ProviderRow(providerId, "SERVICES_TEST", "서비스", "Service",
                "SERVICES_HTTP", ProviderLifecycleState.ACTIVE, List.of(),
                List.of("EPHEMERAL_CREDENTIAL"), mapper.createObjectNode(),
                "secret-manager://workplace/services-test/v7", 7, true, 7L,
                "HEALTHY", "evidence-71", NOW.minusMinutes(1), NOW.minusMinutes(1),
                null, 3, NOW);
    }

    private String fingerprint(Object... values) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(values).getBytes(StandardCharsets.UTF_8)));
    }
}
