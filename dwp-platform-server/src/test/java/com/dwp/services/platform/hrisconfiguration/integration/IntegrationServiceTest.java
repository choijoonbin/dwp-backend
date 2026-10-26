package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Profile;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandReservation;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.ExecutionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.MappingRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.BoundaryResolutionPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.DefinitionEvidencePort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ResolvedDefinitionBoundary;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.ResolvedPayloadManifest;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.SourceManifestItem;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.NonExportableProviderHandle;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolutionRequest;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolvedReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
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
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IntegrationServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(
            2026, 10, 26, 6, 0, 0, 0, ZoneOffset.UTC);
    private static final long TENANT = 71L;
    private static final long ACTOR = 901L;
    private static final UUID SUBJECT = UUID.fromString(
            "81000000-0000-4000-8000-000000000001");
    private static final UUID CONNECTOR = UUID.fromString(
            "81000000-0000-4000-8000-000000000002");
    private static final UUID DEFINITION = UUID.fromString(
            "81000000-0000-4000-8000-000000000003");
    private static final UUID DEFINITION_VERSION = UUID.fromString(
            "81000000-0000-4000-8000-000000000004");
    private static final UUID MAPPING = UUID.fromString(
            "81000000-0000-4000-8000-000000000005");
    private static final UUID EXECUTION = UUID.fromString(
            "81000000-0000-4000-8000-000000000006");
    private static final UUID EXECUTION_INTERNAL = UUID.fromString(
            "81000000-0000-4000-8000-000000000007");
    private static final UUID RECEIPT = UUID.fromString(
            "81000000-0000-4000-8000-000000000008");
    private static final UUID APPROVAL = UUID.fromString(
            "81000000-0000-4000-8000-000000000009");
    private static final UUID LEGAL_ENTITY = UUID.fromString(
            "81000000-0000-4000-8000-000000000010");
    private static final UUID CORRELATION = UUID.fromString(
            "81000000-0000-4000-8000-000000000011");
    private static final String DIGEST = "a".repeat(64);
    private static final String OTHER_DIGEST = "b".repeat(64);
    private static final String SCOPE_DIGEST = "c".repeat(64);
    private static final String MAKER_SCOPE_DIGEST = "d".repeat(64);

    private static final Profile AUTHOR = new Profile(
            "CONFIG.HRIS_INTEGRATION:UPDATE", "HRIS_INTEGRATION_AUTHOR",
            "HRIS_DUTY_INTEGRATION_AUTHOR");
    private static final Profile EXECUTOR = new Profile(
            "ACTION.HRIS_INTEGRATION_CONTROL_PLANE:EXECUTE",
            "HRIS_INTEGRATION_CONTROL_PLANE_EXECUTOR",
            "HRIS_DUTY_INTEGRATION_CONTROL_PLANE_EXECUTOR");

    private IntegrationRepository repository;
    private BoundaryResolutionPort boundaries;
    private DefinitionEvidencePort evidence;
    private Sys008OpaqueReferenceOwnerPort references;
    private PlatformTransactionManager transactionManager;
    private IntegrationService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(IntegrationRepository.class);
        boundaries = mock(BoundaryResolutionPort.class);
        evidence = mock(DefinitionEvidencePort.class);
        references = mock(Sys008OpaqueReferenceOwnerPort.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenAnswer(ignored -> new SimpleTransactionStatus());
        when(repository.databaseNow()).thenReturn(NOW);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        service = new IntegrationService(
                repository, provider(boundaries), provider(evidence),
                provider(references), new IntegrationCursorCodec(
                        "0123456789abcdef0123456789abcdef", json),
                transactionManager, json);
    }

    @Test
    void validationReplayReturnsReservedReceiptBeforeMutableReadOrEffect() {
        Authority authority = authority(AUTHOR,
                "route.hcm.configuration.connector-definition-validate.action",
                999L, "TENANT", null, "validation-replay-key");
        DefinitionValidationCommand command = new DefinitionValidationCommand(
                DIGEST, 7, 8);
        CommandReceipt running = receipt(
                IntegrationService.VALIDATE_DEFINITION,
                ReceiptStatus.RUNNING, 2L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(running, false));

        assertThat(service.validateDefinition(authority, CONNECTOR, command))
                .isSameAs(running);

        verify(repository).beginCommand(any(), eq(NOW));
        verify(repository, never()).lockDefinition(anyLong(), any());
        verifyNoInteractions(boundaries, evidence, references);
    }

    @Test
    void validationClaimRaceReturnsWinningRunningReceiptWithoutDuplicateEffect() {
        Authority authority = authority(AUTHOR,
                "route.hcm.configuration.connector-definition-validate.action",
                1L, "TENANT", null, "validation-claim-race-key");
        DefinitionRecord draft = definition(
                DefinitionState.DRAFT, 1,
                URI.create("https://api.example.com/hris"), CONNECTOR, 0);
        CommandReceipt accepted = receipt(
                IntegrationService.VALIDATE_DEFINITION,
                ReceiptStatus.ACCEPTED, null);
        CommandReceipt running = receipt(
                IntegrationService.VALIDATE_DEFINITION,
                ReceiptStatus.RUNNING, 1L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, false));
        when(repository.lockDefinition(TENANT, CONNECTOR)).thenReturn(draft);
        when(repository.progressCommand(any(), eq(RECEIPT), eq(CONNECTOR),
                eq(1L), anyString(), eq(NOW)))
                .thenThrow(new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "SYS008_RECEIPT_RUNNING_CAS_CONFLICT"));
        when(repository.findReplay(any())).thenReturn(Optional.of(running));

        assertThat(service.validateDefinition(authority, CONNECTOR,
                new DefinitionValidationCommand(DIGEST, 7, 8)))
                .isSameAs(running);

        verify(repository).findReplay(any());
        verifyNoInteractions(boundaries, evidence, references);
    }

    @Test
    void validationCommitsClaimBeforeExternalEvidenceAndFinalizesSeparately()
            throws Exception {
        Authority authority = authority(AUTHOR,
                "route.hcm.configuration.connector-definition-validate.action",
                1L, "TENANT", null, "validation-effect-key");
        DefinitionRecord draft = definition(
                DefinitionState.DRAFT, 1, URI.create("https://api.example.com/hris"),
                CONNECTOR, 0);
        DefinitionRecord validated = definition(
                DefinitionState.VALIDATED, 2,
                URI.create("https://api.example.com/hris"), CONNECTOR, 0);
        CommandReceipt accepted = receipt(
                IntegrationService.VALIDATE_DEFINITION,
                ReceiptStatus.ACCEPTED, null);
        CommandReceipt running = receipt(
                IntegrationService.VALIDATE_DEFINITION,
                ReceiptStatus.RUNNING, 1L);
        CommandReceipt succeeded = receipt(
                IntegrationService.VALIDATE_DEFINITION,
                ReceiptStatus.SUCCEEDED, 2L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.progressCommand(any(), eq(accepted.receiptId()),
                eq(CONNECTOR), eq(1L), anyString(), eq(NOW)))
                .thenReturn(running);
        when(repository.lockDefinition(TENANT, CONNECTOR))
                .thenReturn(draft, draft);
        when(boundaries.resolveDefinition(
                TENANT, CONNECTOR, 1, 13,
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE))
                .thenReturn(definitionBoundary());
        stubReferences();
        when(evidence.validate(any())).thenReturn(
                new IntegrationRuntimePorts.ValidationEvidence(
                        true, OTHER_DIGEST, NOW, List.of()));
        when(repository.transitionDefinition(
                eq(draft), eq(DefinitionState.VALIDATED), eq(ACTOR),
                eq(accepted.receiptId()), eq(OTHER_DIGEST), isNull(), eq(NOW)))
                .thenReturn(validated);
        when(repository.finishCommand(any(), eq(accepted.receiptId()),
                eq(ReceiptStatus.SUCCEEDED), eq(CONNECTOR), eq(2L),
                anyString(), eq(NOW))).thenReturn(succeeded);

        assertThat(service.validateDefinition(authority, CONNECTOR,
                new DefinitionValidationCommand(DIGEST, 7, 8)))
                .isSameAs(succeeded);

        InOrder order = inOrder(transactionManager, evidence);
        order.verify(transactionManager).getTransaction(any());
        order.verify(transactionManager).commit(any());
        order.verify(evidence).validate(any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(transactionManager).commit(any());
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void staleRunningValidationResumesAndTerminalizesConcurrentMutationAsUnknown()
            throws Exception {
        Authority authority = authority(AUTHOR,
                "route.hcm.configuration.connector-definition-validate.action",
                1L, "TENANT", null, "stale-validation-effect-key");
        DefinitionRecord draft = definition(
                DefinitionState.DRAFT, 1,
                URI.create("https://api.example.com/hris"), CONNECTOR, 0);
        DefinitionRecord changed = definition(
                DefinitionState.DRAFT, 2,
                URI.create("https://api.example.com/hris"), CONNECTOR, 0);
        CommandReceipt staleRunning = receiptAt(
                IntegrationService.VALIDATE_DEFINITION,
                ReceiptStatus.RUNNING, 1L, NOW.minusMinutes(6));
        CommandReceipt unknown = receipt(
                IntegrationService.VALIDATE_DEFINITION,
                ReceiptStatus.RESULT_UNKNOWN, 1L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(staleRunning, false));
        when(repository.lockDefinition(TENANT, CONNECTOR))
                .thenReturn(draft, changed);
        when(boundaries.resolveDefinition(
                TENANT, CONNECTOR, 1, 13,
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE))
                .thenReturn(definitionBoundary());
        stubReferences();
        when(evidence.validate(any())).thenReturn(
                new IntegrationRuntimePorts.ValidationEvidence(
                        true, OTHER_DIGEST, NOW, List.of()));
        when(repository.finishCommand(any(), eq(RECEIPT),
                eq(ReceiptStatus.RESULT_UNKNOWN), eq(CONNECTOR), eq(1L),
                anyString(), eq(NOW))).thenReturn(unknown);

        assertThat(service.validateDefinition(authority, CONNECTOR,
                new DefinitionValidationCommand(DIGEST, 7, 8)))
                .isSameAs(unknown);

        verify(repository, never()).progressCommand(
                any(), any(), any(), anyLong(), anyString(), any());
        verify(repository, never()).transitionDefinition(
                any(), any(), anyLong(), any(), anyString(), anyString(), any());
        verify(repository).finishCommand(any(), eq(RECEIPT),
                eq(ReceiptStatus.RESULT_UNKNOWN), eq(CONNECTOR), eq(1L),
                anyString(), eq(NOW));
        verifyNoInteractions(evidence);
    }

    @Test
    void nonCasConnectorTestAuthorityFromFilterExecutesWithoutExpectedRevision()
            throws Exception {
        DefinitionRecord validated = definition(
                DefinitionState.VALIDATED, 1,
                URI.create("https://api.example.com/hris"), CONNECTOR, 0);
        DefinitionRecord tested = definition(
                DefinitionState.TESTED, 2,
                URI.create("https://api.example.com/hris"), CONNECTOR, 0);
        CommandReceipt accepted = receipt(
                IntegrationService.TEST_CONNECTOR,
                ReceiptStatus.ACCEPTED, null);
        CommandReceipt running = receipt(
                IntegrationService.TEST_CONNECTOR,
                ReceiptStatus.RUNNING, 1L);
        CommandReceipt succeeded = receipt(
                IntegrationService.TEST_CONNECTOR,
                ReceiptStatus.SUCCEEDED, 2L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.progressCommand(any(), eq(RECEIPT), eq(CONNECTOR),
                eq(1L), anyString(), eq(NOW))).thenReturn(running);
        when(repository.lockDefinition(TENANT, CONNECTOR))
                .thenReturn(validated, validated);
        when(boundaries.resolveDefinition(
                TENANT, CONNECTOR, 1, 13,
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE))
                .thenReturn(definitionBoundary());
        stubReferences();
        when(evidence.test(any())).thenReturn(
                new IntegrationRuntimePorts.ConnectionTestEvidence(
                        true, "ref_connectiontest0002", OTHER_DIGEST, NOW));
        when(repository.recordConnectionTest(
                eq(validated), eq(ACTOR), eq(RECEIPT),
                eq("ref_connectiontest0002"), eq(OTHER_DIGEST), eq(NOW)))
                .thenReturn(tested);
        when(repository.finishCommand(any(), eq(RECEIPT),
                eq(ReceiptStatus.SUCCEEDED), eq(CONNECTOR), eq(2L),
                anyString(), eq(NOW))).thenReturn(succeeded);

        MockHttpServletRequest request = connectorTestRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<CommandReceipt> result = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) -> {
            Authority filtered = (Authority) servletRequest.getAttribute(
                    IntegrationOwnerAuthorityFilter.AUTHORITY_ATTRIBUTE);
            assertThat(filtered.expectedObjectRevision()).isNull();
            result.set(service.testConnector(filtered, CONNECTOR,
                    new ConnectorTestCommand(
                            "CONNECTIVITY", "sample_abcdefghijklmnop",
                            OTHER_DIGEST)));
        };

        new IntegrationOwnerAuthorityFilter(
                "sys008-trusted-gateway-token", new ObjectMapper()
                        .findAndRegisterModules())
                .doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(result.get()).isSameAs(succeeded);
        verify(repository).recordConnectionTest(
                eq(validated), eq(ACTOR), eq(RECEIPT),
                eq("ref_connectiontest0002"), eq(OTHER_DIGEST), eq(NOW));
    }

    @Test
    void definitionValidationRejectsEveryFrozenBoundaryDrift() throws Exception {
        var allowlist = new Sys008EgressBoundary.Allowlist(
                Set.of("https"), Set.of("api.example.com", "other.example.com"),
                Set.of(443));
        var destinations = List.of(
                InetAddress.getByName("93.184.216.34"));
        List<ResolvedDefinitionBoundary> drifted = List.of(
                new ResolvedDefinitionBoundary(
                        "other-egress", URI.create("https://api.example.com/hris"),
                        "secret_abcdefghijklmnop", allowlist, destinations,
                        new NonExportableProviderHandle("secret-handle"), 7, 8),
                new ResolvedDefinitionBoundary(
                        "corp-egress", URI.create("https://other.example.com/hris"),
                        "secret_abcdefghijklmnop", allowlist, destinations,
                        new NonExportableProviderHandle("secret-handle"), 7, 8),
                new ResolvedDefinitionBoundary(
                        "corp-egress", URI.create("https://api.example.com/hris"),
                        "secret_zabcdefghijklmnop", allowlist, destinations,
                        new NonExportableProviderHandle("secret-handle"), 7, 8),
                new ResolvedDefinitionBoundary(
                        "corp-egress", URI.create("https://api.example.com/hris"),
                        "secret_abcdefghijklmnop", allowlist, destinations,
                        new NonExportableProviderHandle("secret-handle"), 9, 8),
                new ResolvedDefinitionBoundary(
                        "corp-egress", URI.create("https://api.example.com/hris"),
                        "secret_abcdefghijklmnop", allowlist, destinations,
                        new NonExportableProviderHandle("secret-handle"), 7, 9),
                new ResolvedDefinitionBoundary(
                        "corp-egress", URI.create("https://api.example.com/hris"),
                        "secret_abcdefghijklmnop", allowlist, destinations,
                        new NonExportableProviderHandle("rotated-secret-handle"),
                        7, 8));
        DefinitionRecord draft = definition(
                DefinitionState.DRAFT, 1,
                URI.create("https://api.example.com/hris"), CONNECTOR, 0);
        CommandReceipt accepted = receipt(
                IntegrationService.VALIDATE_DEFINITION,
                ReceiptStatus.ACCEPTED, null);
        CommandReceipt running = receipt(
                IntegrationService.VALIDATE_DEFINITION,
                ReceiptStatus.RUNNING, 1L);
        CommandReceipt rejected = receipt(
                IntegrationService.VALIDATE_DEFINITION,
                ReceiptStatus.REJECTED, null);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.progressCommand(any(), eq(RECEIPT), eq(CONNECTOR),
                eq(1L), anyString(), eq(NOW))).thenReturn(running);
        when(repository.lockDefinition(TENANT, CONNECTOR)).thenReturn(draft);
        when(boundaries.resolveDefinition(
                TENANT, CONNECTOR, 1, 13,
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE))
                .thenReturn(drifted.get(0), drifted.subList(1, drifted.size())
                        .toArray(ResolvedDefinitionBoundary[]::new));
        stubReferences();
        when(repository.finishCommand(any(), eq(RECEIPT),
                eq(ReceiptStatus.REJECTED), eq(CONNECTOR), eq(1L),
                anyString(), eq(NOW))).thenReturn(rejected);
        Authority authority = authority(AUTHOR,
                "route.hcm.configuration.connector-definition-validate.action",
                1L, "TENANT", null, "boundary-drift-key");

        for (int index = 0; index < drifted.size(); index++) {
            assertThatThrownBy(() -> service.validateDefinition(
                    authority, CONNECTOR,
                    new DefinitionValidationCommand(DIGEST, 7, 8)))
                    .isInstanceOf(BaseException.class);
        }

        verify(evidence, never()).validate(any());
    }

    @Test
    void mappingValidationRejectsEveryFrozenBoundaryDrift() {
        List<IntegrationRuntimePorts.ResolvedMappingBoundary> drifted = List.of(
                new IntegrationRuntimePorts.ResolvedMappingBoundary(
                        "INBOUND", "ref_abcdefghijklmnop", DIGEST, DIGEST,
                        OTHER_DIGEST,
                        new NonExportableProviderHandle("mapping-handle")),
                new IntegrationRuntimePorts.ResolvedMappingBoundary(
                        "OUTBOUND", "ref_zabcdefghijklmnop", DIGEST, DIGEST,
                        OTHER_DIGEST,
                        new NonExportableProviderHandle("mapping-handle")),
                new IntegrationRuntimePorts.ResolvedMappingBoundary(
                        "OUTBOUND", "ref_abcdefghijklmnop", OTHER_DIGEST,
                        DIGEST, OTHER_DIGEST,
                        new NonExportableProviderHandle("mapping-handle")),
                new IntegrationRuntimePorts.ResolvedMappingBoundary(
                        "OUTBOUND", "ref_abcdefghijklmnop", DIGEST,
                        OTHER_DIGEST, OTHER_DIGEST,
                        new NonExportableProviderHandle("mapping-handle")),
                new IntegrationRuntimePorts.ResolvedMappingBoundary(
                        "OUTBOUND", "ref_abcdefghijklmnop", DIGEST, DIGEST,
                        DIGEST,
                        new NonExportableProviderHandle("mapping-handle")),
                new IntegrationRuntimePorts.ResolvedMappingBoundary(
                        "OUTBOUND", "ref_abcdefghijklmnop", DIGEST, DIGEST,
                        OTHER_DIGEST,
                        new NonExportableProviderHandle("rotated-mapping-handle")));
        MappingRecord current = mapping(
                MappingState.DRAFT, 3, NOW.minusDays(1), NOW.plusDays(1));
        CommandReceipt accepted = receipt(
                IntegrationService.VALIDATE_MAPPING,
                ReceiptStatus.ACCEPTED, null);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.lockMapping(TENANT, CONNECTOR, 3)).thenReturn(current);
        when(boundaries.resolveMapping(
                TENANT, CONNECTOR, 3, 13,
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE))
                .thenReturn(drifted.get(0), drifted.subList(1, drifted.size())
                        .toArray(IntegrationRuntimePorts.ResolvedMappingBoundary[]::new));
        stubReferences();
        Authority authority = authority(AUTHOR,
                "route.hcm.configuration.connector-mapping-version-validate.action",
                3L, "TENANT", null, "mapping-boundary-drift-key");

        for (int index = 0; index < drifted.size(); index++) {
            assertThatThrownBy(() -> service.validateMapping(
                    authority, CONNECTOR, 3,
                    new MappingValidationCommand(DIGEST, DIGEST, OTHER_DIGEST)))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("SYS008_MAPPING_BOUNDARY_CHANGED");
        }

        verify(repository, never()).transitionMapping(
                any(), any(), anyLong(), any(), anyString(), anyString(), any());
    }

    @Test
    void connectorRejectsUserInfoQueryAndFragmentBeforeReferenceOrPersistence()
            throws Exception {
        Authority authority = authority(AUTHOR,
                "route.hcm.configuration.connector-definition-create.action",
                null, "TENANT", null, "unsafe-endpoint-key");
        CommandReceipt accepted = receipt(
                IntegrationService.CREATE_CONNECTOR,
                ReceiptStatus.ACCEPTED, null);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(boundaries.resolveDefinition(eq(TENANT), any(), eq(1L), eq(13L),
                eq(IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE)))
                .thenReturn(definitionBoundary(
                        URI.create("https://api.example.com:443/hris")));
        ConnectorCommand unsafe = new ConnectorCommand(
                "acme.hris", ConnectorType.HTTPS,
                URI.create("https://user:password@api.example.com/hris"
                        + "?token=secret#fragment"),
                "secret_abcdefghijklmnop", "corp-egress");

        assertThatThrownBy(() -> service.createConnector(authority, unsafe))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SYS008_EGRESS_ENDPOINT_INVALID");

        verifyNoInteractions(references);
        verify(repository, never()).insertDefinition(
                anyLong(), any(), anyLong(), anyString(), any(), anyString(),
                anyLong(), anyLong(), any());
    }

    @Test
    void connectorCanonicalizesEndpointOnlyAfterPinnedAllowlistResolution()
            throws Exception {
        Authority authority = authority(AUTHOR,
                "route.hcm.configuration.connector-definition-create.action",
                null, "TENANT", null, "canonical-endpoint-key");
        CommandReceipt accepted = receipt(
                IntegrationService.CREATE_CONNECTOR,
                ReceiptStatus.ACCEPTED, null);
        CommandReceipt succeeded = receipt(
                IntegrationService.CREATE_CONNECTOR,
                ReceiptStatus.SUCCEEDED, 1L);
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(boundaries.resolveDefinition(eq(TENANT), any(), eq(1L), eq(13L),
                eq(IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE)))
                .thenReturn(definitionBoundary(
                        URI.create("https://api.example.com:443/hris")));
        stubReferences();
        when(repository.insertDefinition(
                eq(TENANT), any(), eq(ACTOR), eq(SCOPE_DIGEST),
                any(ConnectorCommand.class), anyString(), eq(7L), eq(8L),
                eq(NOW))).thenAnswer(invocation -> {
                    UUID connectorId = invocation.getArgument(1);
                    ConnectorCommand command = invocation.getArgument(4);
                    return definition(DefinitionState.DRAFT, 1,
                            command.endpoint(), connectorId, 0);
                });
        when(repository.finishCommand(any(), eq(accepted.receiptId()),
                eq(ReceiptStatus.SUCCEEDED), any(), eq(1L), anyString(),
                eq(NOW))).thenReturn(succeeded);
        ConnectorCommand command = new ConnectorCommand(
                "acme.hris", ConnectorType.HTTPS,
                URI.create("HTTPS://API.Example.COM:443/a/../hris"),
                "secret_abcdefghijklmnop", "corp-egress");

        assertThat(service.createConnector(authority, command))
                .isSameAs(succeeded);

        ArgumentCaptor<ConnectorCommand> persisted =
                ArgumentCaptor.forClass(ConnectorCommand.class);
        verify(repository).insertDefinition(
                eq(TENANT), any(), eq(ACTOR), eq(SCOPE_DIGEST),
                persisted.capture(), anyString(), eq(7L), eq(8L), eq(NOW));
        assertThat(persisted.getValue().endpoint())
                .isEqualTo(URI.create("https://api.example.com:443/hris"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void legalEntityExecutionFreezesCanonicalMultiItemManifest()
            throws Exception {
        Authority authority = authority(EXECUTOR,
                "route.hcm.configuration.connector-execution-create.action",
                null, "LEGAL_ENTITY", LEGAL_ENTITY, "execution-owner-key");
        CommandReceipt accepted = receipt(
                IntegrationService.CREATE_EXECUTION,
                ReceiptStatus.ACCEPTED, null);
        CommandReceipt running = receipt(
                IntegrationService.CREATE_EXECUTION,
                ReceiptStatus.RUNNING, 1L);
        DefinitionRecord definition = definition(
                DefinitionState.ACTIVE, 6,
                URI.create("https://api.example.com/hris"), CONNECTOR, 3);
        MappingRecord mapping = mapping(
                MappingState.ACTIVE, 3, NOW.minusDays(1), NOW.plusDays(1));
        List<SourceManifestItem> manifest = List.of(
                new SourceManifestItem("1".repeat(64), "a".repeat(64)),
                new SourceManifestItem("2".repeat(64), "b".repeat(64)));
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.lockDefinition(TENANT, CONNECTOR)).thenReturn(definition);
        when(repository.lockMapping(TENANT, CONNECTOR, 3)).thenReturn(mapping);
        stubReferences();
        when(boundaries.resolveDefinition(
                TENANT, CONNECTOR, 1, 13,
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE))
                .thenReturn(definitionBoundary());
        when(boundaries.resolveMapping(
                TENANT, CONNECTOR, 3, 13,
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE))
                .thenReturn(mappingBoundary());
        when(boundaries.resolvePayload(
                eq(TENANT), eq(CONNECTOR), any(), eq("payload/batch-0001"),
                eq(OTHER_DIGEST), eq(13L),
                eq(IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE)))
                .thenReturn(new ResolvedPayloadManifest(
                        new NonExportableProviderHandle("payload-handle"), manifest));
        ExecutionRecord execution = execution(manifest.size());
        when(repository.insertExecution(
                eq(definition), eq(mapping), any(), eq(ACTOR),
                eq(accepted.receiptId()), any(ExecutionCommand.class),
                anyString(), anyString(), eq(13L), eq(SCOPE_DIGEST),
                eq("LEGAL_ENTITY"), eq(LEGAL_ENTITY), anyList(), eq(NOW)))
                .thenReturn(execution);
        when(repository.progressCommand(any(), eq(accepted.receiptId()),
                eq(EXECUTION), eq(1L), anyString(), eq(NOW)))
                .thenReturn(running);
        ExecutionCommand command = new ExecutionCommand(
                Operation.EXPORT, "payload/batch-0001", OTHER_DIGEST, NOW);

        assertThat(service.createExecution(authority, CONNECTOR, command))
                .isSameAs(running);

        ArgumentCaptor<List> frozenItems = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> providerKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> requestDigest = ArgumentCaptor.forClass(String.class);
        verify(repository).insertExecution(
                eq(definition), eq(mapping), any(), eq(ACTOR),
                eq(accepted.receiptId()), eq(command), providerKey.capture(),
                requestDigest.capture(), eq(13L), eq(SCOPE_DIGEST),
                eq("LEGAL_ENTITY"), eq(LEGAL_ENTITY), frozenItems.capture(),
                eq(NOW));
        assertThat(frozenItems.getValue()).isEqualTo(manifest);
        assertThat(providerKey.getValue())
                .startsWith("sys008-").hasSize(71);
        assertThat(requestDigest.getValue()).matches("^[a-f0-9]{64}$");
        verify(repository).appendExecutionEventAndAudit(
                any(), eq(execution), eq("EXECUTION_ACCEPTED"), eq(NOW));
    }

    @Test
    void executionReadCarriesCurrentScopeAndLegalEntityPredicate() {
        Authority authority = authority(EXECUTOR,
                "route.hcm.configuration.connector-execution-detail.data",
                null, "LEGAL_ENTITY", LEGAL_ENTITY, null);
        ExecutionResponse expected = new ExecutionResponse(
                EXECUTION, 3, ExecutionState.ACCEPTED,
                2, 0, 0, 2, DIGEST, null, 1);
        when(repository.executionResponseAuthorized(
                TENANT, EXECUTION, SCOPE_DIGEST, "LEGAL_ENTITY", LEGAL_ENTITY))
                .thenReturn(expected);

        assertThat(service.execution(authority, EXECUTION)).isSameAs(expected);

        verify(repository).executionResponseAuthorized(
                TENANT, EXECUTION, SCOPE_DIGEST, "LEGAL_ENTITY", LEGAL_ENTITY);
        verify(repository, never()).executionResponse(anyLong(), any());
    }

    @Test
    void mappingActivationRejectsTimestampOutsideFrozenEffectiveWindow() {
        Authority authority = authority(EXECUTOR,
                "route.hcm.configuration.connector-mapping-version-activate.action",
                4L, "TENANT", null, "mapping-activation-key");
        CommandReceipt accepted = receipt(
                IntegrationService.ACTIVATE_MAPPING,
                ReceiptStatus.ACCEPTED, null);
        MappingRecord approved = mapping(
                MappingState.APPROVED, 4, NOW.plusDays(1), NOW.plusDays(2));
        when(repository.beginCommand(any(), eq(NOW)))
                .thenReturn(new CommandReservation(accepted, true));
        when(repository.lockMapping(TENANT, CONNECTOR, 3)).thenReturn(approved);

        assertThatThrownBy(() -> service.activateMapping(
                authority, CONNECTOR, 3,
                new ActivationCommand(APPROVAL, NOW)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SYS008_MAPPING_EFFECTIVE_WINDOW_INVALID");

        verify(repository, never()).transitionMapping(
                any(), any(), anyLong(), any(), anyString(), anyString(), any());
    }

    @Test
    void catalogIssuesAuthorityBoundCursorAndAppliesFilterAndFreshness() {
        Authority authority = authority(AUTHOR,
                "route.hcm.configuration.connector-catalog.data",
                null, "TENANT", null, "catalog-read-key");
        UUID nextId = UUID.fromString(
                "81000000-0000-4000-8000-000000000099");
        List<String> fields = List.of(
                "updatedAt", "type", "state", "publicId", "version", "key");
        ConnectorSummary item = new ConnectorSummary(
                CONNECTOR, "acme.hris", "HTTPS", "ACTIVE", 1, NOW);
        when(repository.catalog(TENANT, NOW, 20, null, "bank"))
                .thenReturn(new IntegrationRepository.CatalogPage(
                        List.of(item), nextId.toString()));

        CatalogResponse fresh = service.catalog(
                authority, NOW, fields, "FRESH_ONLY", 20, null, "bank");

        assertThat(fresh.items()).containsExactly(item);
        assertThat(fresh.nextCursor()).isNotEqualTo(nextId.toString())
                .contains(".");
        assertThat(fresh.staleAfter()).isEqualTo(fresh.freshUntil());
        verify(repository).catalog(TENANT, NOW, 20, null, "bank");

        when(repository.catalog(TENANT, NOW, 20, nextId.toString(), "bank"))
                .thenReturn(new IntegrationRepository.CatalogPage(List.of(), null));
        CatalogResponse continued = service.catalog(
                authority, NOW, fields, "FRESH_ONLY", 20,
                fresh.nextCursor(), "bank");
        assertThat(continued.items()).isEmpty();
        verify(repository).catalog(
                TENANT, NOW, 20, nextId.toString(), "bank");

        reset(repository);
        when(repository.databaseNow()).thenReturn(NOW);
        when(repository.catalog(TENANT, NOW, 20, null, null))
                .thenReturn(new IntegrationRepository.CatalogPage(List.of(), null));
        CatalogResponse staleAllowed = service.catalog(
                authority, NOW, fields, "ALLOW_STALE", 20, null, null);
        assertThat(staleAllowed.staleAfter())
                .isEqualTo(staleAllowed.freshUntil().plusMinutes(5));
    }

    @Test
    void catalogCursorRejectsTamperingAndEveryChangedQueryBinding() {
        Authority authority = authority(AUTHOR,
                "route.hcm.configuration.connector-catalog.data",
                null, "TENANT", null, "catalog-read-key");
        UUID nextId = UUID.fromString(
                "81000000-0000-4000-8000-000000000099");
        List<String> fields = List.of(
                "key", "publicId", "state", "type", "updatedAt", "version");
        when(repository.catalog(TENANT, NOW, 10, null, "bank"))
                .thenReturn(new IntegrationRepository.CatalogPage(
                        List.of(), nextId.toString()));
        String cursor = service.catalog(authority, NOW, fields,
                "FRESH_ONLY", 10, null, "bank").nextCursor();

        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> service.catalog(authority, NOW, fields,
                "FRESH_ONLY", 10, tampered, "bank"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SYS008_CATALOG_CURSOR_INVALID");
        assertThatThrownBy(() -> service.catalog(authority, NOW, fields,
                "ALLOW_STALE", 10, cursor, "bank"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SYS008_CATALOG_CURSOR_BINDING_CHANGED");
        assertThatThrownBy(() -> service.catalog(authority, NOW.plusSeconds(1),
                fields, "FRESH_ONLY", 10, cursor, "bank"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SYS008_CATALOG_CURSOR_BINDING_CHANGED");
        assertThatThrownBy(() -> service.catalog(authority, NOW, fields,
                "FRESH_ONLY", 10, cursor, "payroll"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SYS008_CATALOG_CURSOR_BINDING_CHANGED");

        Authority changedRevision = new Authority(
                authority.tenantId(), authority.actorId(),
                authority.subjectPrincipalPublicId(), authority.profiles(),
                authority.routeContractKey(), authority.decisionRevision(),
                authority.revalidateAt(), authority.populationScopeDigest(),
                authority.scopeRevision() + 1, authority.fieldPolicyRevision(),
                authority.authorizationRevision(), authority.contextKey(),
                authority.contextScopeKey(), authority.correlationId(),
                authority.purposeCode(), authority.idempotencyKey(),
                authority.expectedObjectRevision(),
                authority.stepUpEvidenceDigest(), authority.populationKind(),
                authority.legalEntityPublicId(), authority.maskedFields(),
                authority.omittedFields());
        assertThatThrownBy(() -> service.catalog(changedRevision, NOW, fields,
                "FRESH_ONLY", 10, cursor, "bank"))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("SYS008_CATALOG_CURSOR_BINDING_CHANGED");
    }

    private ResolvedDefinitionBoundary definitionBoundary() throws Exception {
        return definitionBoundary(URI.create("https://api.example.com/hris"));
    }

    private ResolvedDefinitionBoundary definitionBoundary(URI endpoint)
            throws Exception {
        return new ResolvedDefinitionBoundary(
                "corp-egress", endpoint,
                "secret_abcdefghijklmnop",
                new Sys008EgressBoundary.Allowlist(
                        Set.of("https"), Set.of("api.example.com"), Set.of(443)),
                List.of(InetAddress.getByName("93.184.216.34")),
                new NonExportableProviderHandle("secret-handle"), 7, 8);
    }

    private IntegrationRuntimePorts.ResolvedMappingBoundary mappingBoundary() {
        return new IntegrationRuntimePorts.ResolvedMappingBoundary(
                "OUTBOUND", "ref_abcdefghijklmnop", DIGEST, DIGEST,
                OTHER_DIGEST,
                new NonExportableProviderHandle("mapping-handle"));
    }

    private void stubReferences() {
        when(references.resolveAuthorized(any())).thenAnswer(invocation -> {
            ResolutionRequest request = invocation.getArgument(0);
            String handle = switch (request.type()) {
                case SECRET_REF -> "secret-handle";
                case MAPPING_REF -> "mapping-handle";
                case PAYLOAD_REF -> "payload-handle";
                default -> "evidence-handle";
            };
            return Optional.of(new ResolvedReference(
                    request.opaqueReference(), request.tenantId(),
                    request.connectorId(), request.subjectRef(), request.purpose(),
                    request.type(), request.namespace(), request.canonicalDigest(),
                    request.authorizationRevision(), request.constraint(),
                    new NonExportableProviderHandle(handle)));
        });
    }

    private static MockHttpServletRequest connectorTestRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/admin/hris/connectors/" + CONNECTOR + "/test");
        request.addHeader(
                "X-DWP-Service-Token", "sys008-trusted-gateway-token");
        request.addHeader("X-DWP-Route-Contract-Key",
                "route.hcm.configuration.connector-test.action");
        request.addHeader("X-DWP-Tenant-ID", Long.toString(TENANT));
        request.addHeader("X-DWP-User-ID", Long.toString(ACTOR));
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader(
                "X-DWP-Subject-Principal-Public-ID", SUBJECT.toString());
        request.addHeader("X-DWP-Current-Decision-Revision", "psr-" + DIGEST);
        request.addHeader("X-DWP-Expected-Decision-Revision", "psr-" + DIGEST);
        request.addHeader("X-DWP-Current-Revalidate-At",
                OffsetDateTime.now().plusHours(2).toString());
        request.addHeader("X-DWP-Population-Kind", "TENANT");
        request.addHeader("X-DWP-Context-Key", "psc-" + DIGEST);
        request.addHeader(
                "X-DWP-Context-Scope-Key", "tenant:71:hris-integration");
        request.addHeader("X-DWP-Scope-Revision", "11");
        request.addHeader("X-DWP-Field-Policy-Revision", "12");
        request.addHeader("X-DWP-Authorization-Revision", "13");
        request.addHeader("X-Correlation-ID", CORRELATION.toString());
        request.addHeader("X-Purpose-Code",
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE);
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        request.addHeader("X-DWP-Permissions", AUTHOR.permission());
        request.addHeader("X-DWP-Roles", AUTHOR.role());
        request.addHeader("X-DWP-Resource-Roles", AUTHOR.resourceRole());
        request.addHeader("Idempotency-Key", "sys008-test-idempotency-0001");
        return request;
    }

    private static Authority authority(
            Profile profile,
            String route,
            Long expectedRevision,
            String populationKind,
            UUID legalEntity,
            String idempotencyKey) {
        return new Authority(
                TENANT, ACTOR, SUBJECT, Set.of(profile), route,
                "psr-" + DIGEST, NOW.plusMinutes(5), SCOPE_DIGEST,
                11, 12, 13, "psc-" + DIGEST,
                "tenant:71:hris-integration", CORRELATION,
                IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE,
                idempotencyKey, expectedRevision,
                EXECUTOR.equals(profile) ? DIGEST : null,
                populationKind, legalEntity, Set.of(), Set.of());
    }

    private static CommandReceipt receipt(
            String action, ReceiptStatus status, Long revision) {
        return receiptAt(action, status, revision, NOW);
    }

    private static CommandReceipt receiptAt(
            String action,
            ReceiptStatus status,
            Long revision,
            OffsetDateTime createdAt) {
        return new CommandReceipt(
                RECEIPT, "COMMAND", action, SUBJECT, SCOPE_DIGEST,
                12, IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE, 13,
                status, CORRELATION, DIGEST, createdAt, revision,
                revision == null ? null : "urn:sys008:result");
    }

    private static DefinitionRecord definition(
            DefinitionState state,
            long revision,
            URI endpoint,
            UUID connectorId,
            long activeMappingVersion) {
        return new DefinitionRecord(
                DEFINITION, DEFINITION_VERSION, connectorId, TENANT,
                "acme.hris", ConnectorType.HTTPS, endpoint,
                "secret_abcdefghijklmnop", "corp-egress", 1,
                activeMappingVersion, state, DIGEST, 7, 8,
                revision, 1, revision, 701L, MAKER_SCOPE_DIGEST,
                state == DefinitionState.ACTIVE ? 702L : null,
                state == DefinitionState.ACTIVE ? APPROVAL : null,
                state == DefinitionState.DRAFT ? null : "ref_connectiontest0001",
                state == DefinitionState.DRAFT ? null : OTHER_DIGEST);
    }

    private static MappingRecord mapping(
            MappingState state,
            long revision,
            OffsetDateTime effectiveFrom,
            OffsetDateTime effectiveTo) {
        return new MappingRecord(
                MAPPING, TENANT, DEFINITION, CONNECTOR, 3, state,
                1, 2, DIGEST, OTHER_DIGEST,
                "ref_abcdefghijklmnop", DIGEST, CompatibilityMode.EXACT,
                "OUTBOUND", effectiveFrom, effectiveTo,
                revision, 1, 701L, MAKER_SCOPE_DIGEST,
                702L, APPROVAL, "ref_dryrunresult0001", OTHER_DIGEST);
    }

    private static ExecutionRecord execution(long sourceCount) {
        return new ExecutionRecord(
                EXECUTION, EXECUTION_INTERNAL, TENANT, DEFINITION, CONNECTOR,
                MAPPING, 1, 3, "ref_abcdefghijklmnop", DIGEST, "OUTBOUND",
                DIGEST, OTHER_DIGEST,
                URI.create("https://api.example.com/hris"), "corp-egress",
                "secret_abcdefghijklmnop", 7, 8, ExecutionState.ACCEPTED,
                Operation.EXPORT, "payload/batch-0001", OTHER_DIGEST,
                "provider-key-00000001", DIGEST, sourceCount,
                0, 0, 0, sourceCount, DIGEST, null, 0,
                1, sourceCount, false, RECEIPT, 13,
                SCOPE_DIGEST, "LEGAL_ENTITY", LEGAL_ENTITY, NOW, ACTOR, CORRELATION,
                "execution-owner-key", null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
