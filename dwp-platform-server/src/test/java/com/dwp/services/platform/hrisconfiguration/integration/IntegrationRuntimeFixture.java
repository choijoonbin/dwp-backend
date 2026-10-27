package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.AttemptRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationExecutionRepository.CompensationClaim;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Profile;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandReservation;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.BoundaryResolutionPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEffectPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderEvidence;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ProviderItemEvidence;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolutionRequest;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolvedReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.net.InetAddress;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Shared setup and evidence builders for IntegrationRuntimeServiceTest. */
abstract class IntegrationRuntimeFixture {
    protected static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 10, 26, 4, 0, 0, 0, ZoneOffset.UTC);
    protected static final long TENANT = 71L;
    protected static final UUID EXECUTION = UUID.fromString(
            "80000000-0000-4000-8000-000000000001");
    protected static final UUID INTERNAL = UUID.fromString(
            "80000000-0000-4000-8000-000000000002");
    protected static final UUID CONNECTOR = UUID.fromString(
            "80000000-0000-4000-8000-000000000003");
    protected static final UUID DEFINITION = UUID.fromString(
            "80000000-0000-4000-8000-000000000004");
    protected static final UUID MAPPING = UUID.fromString(
            "80000000-0000-4000-8000-000000000005");
    protected static final UUID ITEM = UUID.fromString(
            "80000000-0000-4000-8000-000000000006");
    protected static final UUID ITEM_INTERNAL = UUID.fromString(
            "80000000-0000-4000-8000-000000000007");
    protected static final UUID ATTEMPT = UUID.fromString(
            "80000000-0000-4000-8000-000000000008");
    protected static final UUID LEGAL_ENTITY = UUID.fromString(
            "80000000-0000-4000-8000-000000000009");
    protected static final String DIGEST = "a".repeat(64);
    protected static final String OTHER_DIGEST = "b".repeat(64);
    protected static final String REQUEST_DIGEST = "c".repeat(64);
    protected static final String ITEM_DIGEST = "d".repeat(64);
    protected static final String PROVIDER_KEY = "provider-parent-key-0001";
    protected static final String ITEM_KEY = "provider-item-key-000001";

    protected IntegrationRepository repository;
    protected IntegrationExecutionRepository executions;
    protected ProviderEffectPort provider;
    protected IntegrationRuntimePorts.OperationsExceptionPort operations;
    protected PlatformTransactionManager transactionManager;
    protected IntegrationRuntimeService service;

    @BeforeEach
    void setUp() throws Exception {
        initialize(boundary());
    }

    protected void initialize(BoundaryResolutionPort boundary) {
        repository = mock(IntegrationRepository.class);
        executions = mock(IntegrationExecutionRepository.class);
        provider = mock(ProviderEffectPort.class);
        operations = mock(IntegrationRuntimePorts.OperationsExceptionPort.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenAnswer(ignored -> new SimpleTransactionStatus());
        when(repository.databaseNow()).thenReturn(NOW);
        when(executions.lockExecutionAuthorized(
                anyLong(), any(), anyString(), anyString(), nullable(UUID.class)))
                .thenAnswer(invocation -> repository.lockExecution(
                        invocation.getArgument(0), invocation.getArgument(1)));
        when(repository.executionResponseAuthorized(
                anyLong(), any(), anyString(), anyString(), nullable(UUID.class)))
                .thenAnswer(invocation -> repository.executionResponse(
                        invocation.getArgument(0), invocation.getArgument(1)));

        ObjectProvider<ProviderEffectPort> providers = provider(provider);
        ObjectProvider<BoundaryResolutionPort> boundaries = provider(boundary);
        ObjectProvider<Sys008OpaqueReferenceOwnerPort> references =
                provider(referenceOwner());
        service = new IntegrationRuntimeService(
                repository, executions, providers, boundaries, references,
                operations, transactionManager,
                new ObjectMapper().findAndRegisterModules(), 3);
    }

    protected BoundaryResolutionPort boundary() throws Exception {
        return boundary(definitionBoundary(), mappingBoundary());
    }

    protected BoundaryResolutionPort boundary(
            IntegrationRuntimePorts.ResolvedDefinitionBoundary definition,
            IntegrationRuntimePorts.ResolvedMappingBoundary mapping) {
        NonExportableProviderHandle payload =
                new NonExportableProviderHandle("payload-handle");
        return new BoundaryResolutionPort() {
            @Override
            public IntegrationRuntimePorts.ResolvedDefinitionBoundary resolveDefinition(
                    long tenantId, UUID connectorId, long definitionVersion,
                    long authorizationRevision, String purposeCode) {
                return definition;
            }

            @Override
            public IntegrationRuntimePorts.ResolvedMappingBoundary resolveMapping(
                    long tenantId, UUID connectorId, long mappingVersion,
                    long authorizationRevision, String purposeCode) {
                return mapping;
            }

            @Override
            public IntegrationRuntimePorts.ResolvedPayloadManifest resolvePayload(
                    long tenantId, UUID connectorId, UUID executionId,
                    String payloadRef, String payloadDigest,
                    long authorizationRevision, String purposeCode) {
                return new IntegrationRuntimePorts.ResolvedPayloadManifest(
                        payload, List.of(new IntegrationRuntimePorts.SourceManifestItem(
                        DIGEST, OTHER_DIGEST)));
            }
        };
    }

    protected IntegrationRuntimePorts.ResolvedDefinitionBoundary definitionBoundary()
            throws Exception {
        return definitionBoundary(
                URI.create("https://api.example.com/hris"),
                "secret_abcdefghijklmnop", "egress-policy", 7, 8);
    }

    protected IntegrationRuntimePorts.ResolvedDefinitionBoundary definitionBoundary(
            URI endpoint,
            String secretRef,
            String egressPolicyKey,
            long endpointPolicyRevision,
            long secretRevision) throws Exception {
        return definitionBoundary(endpoint, secretRef, egressPolicyKey,
                endpointPolicyRevision, secretRevision, "secret-handle");
    }

    protected IntegrationRuntimePorts.ResolvedDefinitionBoundary definitionBoundary(
            URI endpoint,
            String secretRef,
            String egressPolicyKey,
            long endpointPolicyRevision,
            long secretRevision,
            String secretHandleId) throws Exception {
        NonExportableProviderHandle secret =
                new NonExportableProviderHandle(secretHandleId);
        InetAddress address = InetAddress.getByName("8.8.8.8");
        return new IntegrationRuntimePorts.ResolvedDefinitionBoundary(
                egressPolicyKey, endpoint, secretRef,
                new Sys008EgressBoundary.Allowlist(
                        Set.of("https"), Set.of("api.example.com"), Set.of(443)),
                List.of(address), secret,
                endpointPolicyRevision, secretRevision);
    }

    protected IntegrationRuntimePorts.ResolvedMappingBoundary mappingBoundary() {
        return mappingBoundary("OUTBOUND", "ref_abcdefghijklmnop",
                DIGEST, "mapping-handle");
    }

    protected IntegrationRuntimePorts.ResolvedMappingBoundary mappingBoundary(
            String direction,
            String mappingRef,
            String mappingDigest,
            String handleId) {
        return mappingBoundary(direction, mappingRef, mappingDigest,
                DIGEST, OTHER_DIGEST, handleId);
    }

    protected IntegrationRuntimePorts.ResolvedMappingBoundary mappingBoundary(
            String direction,
            String mappingRef,
            String mappingDigest,
            String sourceSchemaDigest,
            String targetSchemaDigest,
            String handleId) {
        return new IntegrationRuntimePorts.ResolvedMappingBoundary(
                direction, mappingRef, mappingDigest,
                sourceSchemaDigest, targetSchemaDigest,
                new NonExportableProviderHandle(handleId));
    }

    protected Sys008OpaqueReferenceOwnerPort referenceOwner() {
        return new Sys008OpaqueReferenceOwnerPort() {
            @Override
            public Optional<ResolvedReference> findOpaque(String opaqueReference) {
                return Optional.empty();
            }

            @Override
            public Optional<ResolvedReference> resolveAuthorized(
                    ResolutionRequest request) {
                String handle = switch (request.type()) {
                    case PAYLOAD_REF -> "payload-handle";
                    case MAPPING_REF -> "mapping-handle";
                    case SECRET_REF -> "secret-handle";
                    case PROVIDER_RECEIPT_REF -> "provider-receipt-handle";
                    case COMPENSATION_POLICY_REF -> "compensation-policy-handle";
                    default -> "generic-handle";
                };
                return Optional.of(new ResolvedReference(
                        request.opaqueReference(), request.tenantId(),
                        request.connectorId(), request.subjectRef(),
                        request.purpose(), request.type(), request.namespace(),
                        request.canonicalDigest(), request.authorizationRevision(),
                        request.constraint(), new NonExportableProviderHandle(handle)));
            }
        };
    }

    protected IntegrationRuntimePorts.CompensationRequest invokeSuccessfulCompensation(
            String callerIdempotencyKey) throws Exception {
        initialize(boundary());
        Authority authority = authority(
                3L, "TENANT", null, callerIdempotencyKey);
        CancelCommand command = new CancelCommand(
                "OWNER_REQUEST", "policy_abcdefghijklmnop");
        ExecutionRecord current = execution(
                ExecutionState.ACKNOWLEDGED, 3, true, "TENANT", null);
        ExecutionRecord claimed = execution(
                ExecutionState.ACKNOWLEDGED, 4, true, "TENANT", null);
        ExecutionRecord cancelled = execution(
                ExecutionState.CANCELLED, 5, true, "TENANT", null);
        CommandReceipt accepted = receipt(ReceiptStatus.ACCEPTED, null);
        CommandReceipt running = receipt(ReceiptStatus.RUNNING, 4L);
        CommandReceipt succeeded = receipt(ReceiptStatus.SUCCEEDED, 5L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.lockExecution(TENANT, EXECUTION))
                .thenReturn(current, claimed);
        when(executions.currentAttempts(current)).thenReturn(List.of(attempt()));
        when(executions.claimCompensation(
                eq(current), anyString(), eq(3L), eq(NOW)))
                .thenAnswer(invocation -> new CompensationClaim(
                        claimed, invocation.getArgument(1), true));
        when(repository.progressCommand(any(), eq(accepted.receiptId()),
                eq(EXECUTION), eq(4L), anyString(), eq(NOW))).thenReturn(running);
        when(provider.compensate(any())).thenAnswer(invocation -> {
            IntegrationRuntimePorts.CompensationRequest request =
                    invocation.getArgument(0);
            return new ProviderEvidence(
                    ProviderEvidence.Outcome.CANCELLED,
                    request.compensationIdempotencyKey(),
                    request.commandRequestDigest(),
                    "ref_compensation0001", DIGEST,
                    1, 0, 0, 0, null, NOW, List.of());
        });
        when(executions.cancel(eq(claimed), eq(command.compensationPolicyRef()),
                anyString(), eq("ref_compensation0001"), eq(DIGEST),
                eq(authority.actorId()), eq(NOW))).thenReturn(cancelled);
        when(repository.finishCommand(any(), eq(accepted.receiptId()),
                eq(ReceiptStatus.SUCCEEDED), eq(EXECUTION), eq(5L),
                anyString(), eq(NOW))).thenReturn(succeeded);

        assertThat(service.cancel(authority, EXECUTION, command))
                .isSameAs(succeeded);
        ArgumentCaptor<IntegrationRuntimePorts.CompensationRequest> request =
                ArgumentCaptor.forClass(
                        IntegrationRuntimePorts.CompensationRequest.class);
        verify(provider).compensate(request.capture());
        ArgumentCaptor<CommandSeal> receiptSeal =
                ArgumentCaptor.forClass(CommandSeal.class);
        verify(repository).beginCommand(receiptSeal.capture(), eq(NOW));
        assertThat(receiptSeal.getValue().idempotencyKey())
                .isEqualTo(callerIdempotencyKey);
        return request.getValue();
    }

    protected static Authority authority(
            long revision, String populationKind, UUID legalEntity) {
        return authority(revision, populationKind, legalEntity,
                "owner-command-key-0001");
    }

    protected static Authority authority(
            long revision,
            String populationKind,
            UUID legalEntity,
            String callerIdempotencyKey) {
        Profile executor = new Profile(
                "ACTION.HRIS_INTEGRATION_CONTROL_PLANE:EXECUTE",
                "HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR",
                "HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR");
        return new Authority(
                TENANT, 910L,
                UUID.fromString("80000000-0000-4000-8000-000000000010"),
                Set.of(executor),
                "route.hcm.configuration.connector-execution-cancel.action",
                "psr-" + DIGEST, NOW.plusMinutes(5), OTHER_DIGEST,
                9, 10, 11, "psc-" + DIGEST,
                "tenant:71:hris-integration",
                UUID.fromString("80000000-0000-4000-8000-000000000011"),
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE,
                callerIdempotencyKey, revision, DIGEST,
                populationKind, legalEntity, Set.of(), Set.of());
    }

    protected static ExecutionRecord execution(
            ExecutionState state,
            long revision,
            boolean dispatched,
            String populationKind,
            UUID legalEntity) {
        return execution(state, revision, dispatched, populationKind,
                legalEntity, 0);
    }

    protected static ExecutionRecord execution(
            ExecutionState state,
            long revision,
            boolean dispatched,
            String populationKind,
            UUID legalEntity,
            long retryGeneration) {
        return new ExecutionRecord(
                EXECUTION, INTERNAL, TENANT, DEFINITION, CONNECTOR, MAPPING,
                2, 3, "ref_abcdefghijklmnop", DIGEST, "OUTBOUND",
                DIGEST, OTHER_DIGEST,
                URI.create("https://api.example.com/hris"), "egress-policy",
                "secret_abcdefghijklmnop", 7, 8, state, Operation.EXPORT,
                "payload_abcdefghijklmnop", OTHER_DIGEST,
                PROVIDER_KEY, REQUEST_DIGEST, 1, 0, 0, 0, 1, DIGEST,
                null, retryGeneration, revision, 1, dispatched,
                UUID.fromString("80000000-0000-4000-8000-000000000012"),
                11, DIGEST, populationKind, legalEntity, NOW.minusMinutes(2), 910L,
                UUID.fromString("80000000-0000-4000-8000-000000000011"),
                "owner-command-key-0001", null, null, null, null);
    }

    protected static AttemptRecord attempt() {
        return new AttemptRecord(
                ATTEMPT, ITEM_INTERNAL, ITEM, 1, 1,
                ITEM_KEY, ITEM_DIGEST);
    }

    protected static ProviderEvidence evidence(
            ProviderEvidence.Outcome outcome,
            String providerKey,
            String requestDigest,
            boolean includeItem) {
        List<ProviderItemEvidence> items = includeItem
                ? List.of(new ProviderItemEvidence(
                ITEM, outcome, ITEM_KEY, ITEM_DIGEST,
                "ref_providerreceipt0001", DIGEST,
                outcome == ProviderEvidence.Outcome.RECONCILED ? DIGEST : null,
                false, null, false)) : List.of();
        return new ProviderEvidence(
                outcome, providerKey, requestDigest,
                "ref_providerreceipt0001", DIGEST,
                1, outcome == ProviderEvidence.Outcome.ACKNOWLEDGED ? 1 : 0,
                outcome == ProviderEvidence.Outcome.FAILED ? 1 : 0,
                0, outcome == ProviderEvidence.Outcome.RECONCILED ? DIGEST : null,
                NOW, items);
    }

    protected static ProviderEvidence failureEvidence(boolean retryable) {
        return new ProviderEvidence(
                ProviderEvidence.Outcome.FAILED,
                PROVIDER_KEY, REQUEST_DIGEST,
                "ref_providerreceipt0001", DIGEST,
                1, 0, 1, 0, null, NOW,
                List.of(new ProviderItemEvidence(
                        ITEM, ProviderEvidence.Outcome.FAILED,
                        ITEM_KEY, ITEM_DIGEST,
                        "ref_providerreceipt0001", DIGEST,
                        null, false, "PROVIDER_REJECTED", retryable)));
    }

    protected static CommandReceipt receipt(ReceiptStatus status, Long revision) {
        return receiptAt(status, revision, NOW);
    }

    protected static CommandReceipt receiptAt(
            ReceiptStatus status,
            Long revision,
            OffsetDateTime createdAt) {
        return new CommandReceipt(
                UUID.fromString("80000000-0000-4000-8000-000000000013"),
                "COMMAND", IntegrationService.CANCEL_EXECUTION,
                UUID.fromString("80000000-0000-4000-8000-000000000010"),
                OTHER_DIGEST, 10,
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE, 11,
                status,
                UUID.fromString("80000000-0000-4000-8000-000000000011"),
                REQUEST_DIGEST, createdAt, revision,
                revision == null ? null : "/v1/admin/hris/connector-executions/"
                        + EXECUTION);
    }

    protected static ExecutionResponse response(
            ExecutionState state, long revision) {
        return new ExecutionResponse(
                EXECUTION, 3, state, 1,
                state == ExecutionState.ACKNOWLEDGED ? 1 : 0,
                0, 1, DIGEST, null, revision);
    }

    @SuppressWarnings("unchecked")
    protected static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    protected record BoundaryCase(
            String name,
            IntegrationRuntimePorts.ResolvedDefinitionBoundary definition,
            IntegrationRuntimePorts.ResolvedMappingBoundary mapping) {}
}
