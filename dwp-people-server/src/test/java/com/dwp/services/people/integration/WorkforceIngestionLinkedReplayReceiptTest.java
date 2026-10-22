package com.dwp.services.people.integration;

import com.dwp.core.security.Hrm012OwnerReceiptAuthorityVerifier;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkforceIngestionLinkedReplayReceiptTest {
    private static final String REPLAY_KEY = "hrm012-replay-key-0001";
    private static final UUID REPLAY_RECEIPT = UUID.fromString(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID REPLAY_RUN_ID = UUID.fromString(
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID REPLAY_RUN_PUBLIC = UUID.fromString(
            "cccccccc-cccc-4ccc-8ccc-cccccccccccc");

    @AfterEach
    void clear() {
        Hrm012TestFixtures.clearContexts();
    }

    @Test
    void authorizedTerminalReplayCreatesNewLinkedReceiptAndRunWithoutMutatingOriginal() {
        Fixture fixture = fixture();
        Hrm012TestFixtures.installContexts(fixture.marker());

        Hrm012WorkforceIngestionContract.RunReceipt result =
                fixture.service().replayTerminal(
                        Hrm012TestFixtures.RECEIPT, Hrm012TestFixtures.command(),
                        Hrm012TestFixtures.headers(REPLAY_KEY));

        assertThat(result.receiptId()).isEqualTo(REPLAY_RECEIPT);
        assertThat(result.status()).isEqualTo("RECEIVED");
        assertThat(result.links()).containsExactly(Map.entry(
                "self", "/v1/hris/command-receipts/" + REPLAY_RECEIPT));
        Hrm012WorkforceIngestionRepository.CreateRun create =
                fixture.createRun().getValue();
        assertThat(create.receiptId()).isEqualTo(REPLAY_RECEIPT);
        assertThat(create.linkedReplayReceiptId()).isEqualTo(Hrm012TestFixtures.RECEIPT);
        assertThat(create.linkedReplayRunId()).isEqualTo(fixture.originalRun().runId());
        verify(fixture.receipts()).findById(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RECEIPT);
        verify(fixture.runs()).findByReceipt(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RECEIPT);
        verify(fixture.receipts(), never()).bindMarker(
                eq(Hrm012TestFixtures.TENANT), eq(Hrm012TestFixtures.RECEIPT),
                any(), any(), any(), any());
    }

    @Test
    void replayIsNotAPublicModeAndCannotChangeImmutableCommandLineage() {
        Fixture fixture = fixture();
        Hrm012TestFixtures.installContexts(fixture.marker());
        Hrm012WorkforceIngestionContract.Command changed =
                new Hrm012WorkforceIngestionContract.Command(
                        Hrm012TestFixtures.command().objectRef(),
                        Hrm012TestFixtures.command().objectDigest(),
                        Hrm012TestFixtures.command().schemaKey(),
                        Hrm012TestFixtures.command().schemaVersion(),
                        Hrm012WorkforceIngestionContract.Mode.APPLY,
                        LocalDate.parse("2026-10-23"));

        assertThatThrownBy(() -> fixture.service().replayTerminal(
                Hrm012TestFixtures.RECEIPT, changed,
                Hrm012TestFixtures.headers(REPLAY_KEY)))
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> assertThat(((Hrm012Problem) error).code())
                        .isEqualTo("HRM012_REPLAY_LINEAGE_CONFLICT"));
        verify(fixture.receipts(), never()).reserveOrRead(any());
        verifyNoInteractions(fixture.metadata(), fixture.artifacts());
    }

    @Test
    void sameReceiptRunRetryRejectsEveryImmutableSnapshotTamper() {
        Hrm012WorkforceIngestionRepository repository =
                new Hrm012WorkforceIngestionRepository(
                        mock(org.springframework.jdbc.core.namedparam
                                .NamedParameterJdbcTemplate.class),
                        new ObjectMapper().findAndRegisterModules());
        Hrm012WorkforceIngestionRepository.Run existing =
                Hrm012TestFixtures.run("RECEIVED", null, null);
        UUID other = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        var baseline = createRun(metadataSnapshot(
                Hrm012TestFixtures.CONNECTOR, 1, Hrm012TestFixtures.MAPPING,
                Hrm012TestFixtures.DIGEST_A, List.of(Hrm012TestFixtures.LEGAL_ENTITY),
                Hrm012TestFixtures.DIGEST_B, Hrm012TestFixtures.DIGEST_B, 0, 3));
        repository.requireSame(existing, baseline);

        List<Hrm012WorkforceIngestionRepository.CreateRun> tampered = List.of(
                createRun(metadataSnapshot(other, 1, Hrm012TestFixtures.MAPPING,
                        Hrm012TestFixtures.DIGEST_A,
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), Hrm012TestFixtures.DIGEST_B,
                        Hrm012TestFixtures.DIGEST_B, 0, 3)),
                createRun(metadataSnapshot(Hrm012TestFixtures.CONNECTOR, 2,
                        Hrm012TestFixtures.MAPPING, Hrm012TestFixtures.DIGEST_A,
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), Hrm012TestFixtures.DIGEST_B,
                        Hrm012TestFixtures.DIGEST_B, 0, 3)),
                createRun(metadataSnapshot(Hrm012TestFixtures.CONNECTOR, 1, other,
                        Hrm012TestFixtures.DIGEST_A,
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), Hrm012TestFixtures.DIGEST_B,
                        Hrm012TestFixtures.DIGEST_B, 0, 3)),
                createRun(metadataSnapshot(Hrm012TestFixtures.CONNECTOR, 1,
                        Hrm012TestFixtures.MAPPING, "c".repeat(64),
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), Hrm012TestFixtures.DIGEST_B,
                        Hrm012TestFixtures.DIGEST_B, 0, 3)),
                createRun(metadataSnapshot(Hrm012TestFixtures.CONNECTOR, 1,
                        Hrm012TestFixtures.MAPPING, Hrm012TestFixtures.DIGEST_A,
                        List.of(other), Hrm012TestFixtures.DIGEST_B,
                        Hrm012TestFixtures.DIGEST_B, 0, 3)),
                createRun(metadataSnapshot(Hrm012TestFixtures.CONNECTOR, 1,
                        Hrm012TestFixtures.MAPPING, Hrm012TestFixtures.DIGEST_A,
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), "c".repeat(64),
                        Hrm012TestFixtures.DIGEST_B, 0, 3)),
                createRun(metadataSnapshot(Hrm012TestFixtures.CONNECTOR, 1,
                        Hrm012TestFixtures.MAPPING, Hrm012TestFixtures.DIGEST_A,
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), Hrm012TestFixtures.DIGEST_B,
                        "c".repeat(64), 1, 3)),
                createRun(metadataSnapshot(Hrm012TestFixtures.CONNECTOR, 1,
                        Hrm012TestFixtures.MAPPING, Hrm012TestFixtures.DIGEST_A,
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), Hrm012TestFixtures.DIGEST_B,
                        Hrm012TestFixtures.DIGEST_B, 0, 4)));

        for (Hrm012WorkforceIngestionRepository.CreateRun candidate : tampered) {
            assertThatThrownBy(() -> repository.requireSame(existing, candidate))
                    .isInstanceOf(Hrm012Problem.class)
                    .satisfies(error -> assertThat(((Hrm012Problem) error).code())
                            .isEqualTo("HRM012_RUN_SNAPSHOT_CONFLICT"));
        }
    }

    private Hrm012WorkforceIngestionRepository.CreateRun createRun(
            Hrm012WorkforceIngestionContract.OwnerMetadata metadata) {
        return new Hrm012WorkforceIngestionRepository.CreateRun(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RECEIPT,
                null, null, Hrm012TestFixtures.command(), metadata,
                metadata.populationScopeDigest());
    }

    private Hrm012WorkforceIngestionContract.OwnerMetadata metadataSnapshot(
            UUID connectorPublicId,
            long connectorVersion,
            UUID mappingPublicId,
            String mappingDigest,
            List<UUID> legalEntities,
            String populationDigest,
            String cursorDigest,
            long cursorVersion,
            long objectVersion) {
        return new Hrm012WorkforceIngestionContract.OwnerMetadata(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                Hrm012TestFixtures.command().objectRef(),
                UUID.fromString("88888888-8888-4888-8888-888888888888"),
                objectVersion, Hrm012TestFixtures.CONNECTOR, connectorPublicId,
                connectorVersion, Hrm012TestFixtures.MAPPING, mappingPublicId, 1,
                mappingDigest, legalEntities, 13, 14, populationDigest,
                List.of(connectorPublicId), legalEntities, cursorDigest, cursorVersion,
                Map.of("INTEGRATION_METADATA",
                                Hrm012WorkforceIngestionContract.FieldDecision.VIEW,
                        "REDACTED_PAYLOAD",
                                Hrm012WorkforceIngestionContract.FieldDecision.VIEW));
    }

    private Fixture fixture() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Hrm012OwnerReceiptAuthorityVerifier.Evidence marker =
                Hrm012TestFixtures.marker(REPLAY_KEY);
        Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort snapshots =
                mock(Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort.class);
        when(snapshots.resolve(any())).thenAnswer(invocation -> {
            Hrm012WorkforceIngestionPorts.SnapshotRequest request = invocation.getArgument(0);
            return Hrm012TestFixtures.snapshot(request.canonicalRequestHash(), marker);
        });
        Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort postReceipt =
                mock(Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort.class);
        when(postReceipt.verify(any())).thenAnswer(invocation ->
                Hrm012TestFixtures.postReceiptVerdict(
                        invocation.<Hrm012WorkforceIngestionPorts
                                .PostReceiptAuthorityRequest>getArgument(0)));
        Hrm012WorkforceIngestionAuthority authority =
                new Hrm012WorkforceIngestionAuthority(snapshots, postReceipt,
                        Clock.fixed(Hrm012TestFixtures.NOW, ZoneOffset.UTC));
        Hrm012CommandReceiptRepository receipts = mock(Hrm012CommandReceiptRepository.class);
        Hrm012WorkforceIngestionContract.Receipt originalReceipt =
                Hrm012TestFixtures.receipt("SUCCEEDED", "d".repeat(64), false);
        when(receipts.findById(Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RECEIPT))
                .thenReturn(Optional.of(originalReceipt));
        when(receipts.findExisting(Hrm012TestFixtures.TENANT,
                Hrm012TestFixtures.PERSON, REPLAY_KEY)).thenReturn(Optional.empty());
        when(receipts.reserveOrRead(any())).thenAnswer(invocation -> {
            Hrm012CommandReceiptRepository.Reservation value = invocation.getArgument(0);
            return new Hrm012WorkforceIngestionContract.Receipt(
                    REPLAY_RECEIPT, value.tenantId(), value.personPublicId(),
                    Hrm012CommandReceiptRepository.storedKey(
                            value.personPublicId(), value.idempotencyKey()),
                    value.requestHash(), "RECEIVED", null, Map.of(), value.correlationId(),
                    value.personPublicId(), value.authority().populationScopeDigest(),
                    value.authority().fieldPolicyRevision(),
                    Hrm012WorkforceIngestionContract.PURPOSE,
                    value.authority().authorizationRevision(),
                    value.authority().originatingContextDigest(), value.expectedVersion(),
                    value.createdAt(), value.authority(), true);
        });
        Hrm012WorkforceIngestionRepository runs =
                mock(Hrm012WorkforceIngestionRepository.class);
        Hrm012WorkforceIngestionRepository.Run originalRun =
                Hrm012TestFixtures.run("SUCCEEDED", null, null);
        when(runs.findByReceipt(Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RECEIPT))
                .thenReturn(Optional.of(originalRun));
        ArgumentCaptor<Hrm012WorkforceIngestionRepository.CreateRun> createRun =
                ArgumentCaptor.forClass(Hrm012WorkforceIngestionRepository.CreateRun.class);
        when(runs.createRun(createRun.capture())).thenReturn(replayRun(originalRun));
        Hrm012WorkforceIngestionPorts.OwnerMetadataPort metadata =
                mock(Hrm012WorkforceIngestionPorts.OwnerMetadataPort.class);
        when(metadata.resolve(any())).thenReturn(Hrm012TestFixtures.metadata());
        Hrm012WorkforceIngestionPorts.ArtifactPort artifacts =
                mock(Hrm012WorkforceIngestionPorts.ArtifactPort.class);
        when(artifacts.fetch(any())).thenReturn(
                new Hrm012WorkforceIngestionContract.Artifact(
                        Hrm012TestFixtures.command().objectRef(), 3,
                        Hrm012TestFixtures.ENVELOPE));
        Hrm012WorkforceIngestionService service = new Hrm012WorkforceIngestionService(
                authority, receipts, runs, metadata, artifacts, mapper,
                Clock.fixed(Hrm012TestFixtures.NOW, ZoneOffset.UTC));
        return new Fixture(service, marker, receipts, runs, metadata, artifacts,
                originalRun, createRun);
    }

    private Hrm012WorkforceIngestionRepository.Run replayRun(
            Hrm012WorkforceIngestionRepository.Run original) {
        return new Hrm012WorkforceIngestionRepository.Run(
                REPLAY_RUN_ID, REPLAY_RUN_PUBLIC, original.tenantId(), REPLAY_RECEIPT,
                Hrm012TestFixtures.RECEIPT, original.runId(), original.connectorId(),
                original.connectorPublicId(), original.connectorVersion(),
                original.mappingId(), original.mappingPublicId(), original.mappingVersion(),
                original.mappingSha256(), original.legalEntityPublicIds(),
                original.populationScopeDigest(), original.objectRef(), original.objectDigest(),
                original.schemaKey(), original.schemaVersion(), original.mode(),
                original.effectiveDate(), "RECEIVED", original.cursorBeforeDigest(),
                original.cursorExpectedVersion(), 0, original.actorPublicId(),
                original.correlationId(), original.expectedObjectVersion(),
                Hrm012TestFixtures.NOW);
    }

    private record Fixture(
            Hrm012WorkforceIngestionService service,
            Hrm012OwnerReceiptAuthorityVerifier.Evidence marker,
            Hrm012CommandReceiptRepository receipts,
            Hrm012WorkforceIngestionRepository runs,
            Hrm012WorkforceIngestionPorts.OwnerMetadataPort metadata,
            Hrm012WorkforceIngestionPorts.ArtifactPort artifacts,
            Hrm012WorkforceIngestionRepository.Run originalRun,
            ArgumentCaptor<Hrm012WorkforceIngestionRepository.CreateRun> createRun) {
    }
}
