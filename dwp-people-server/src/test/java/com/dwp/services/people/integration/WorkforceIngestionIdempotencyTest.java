package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkforceIngestionIdempotencyTest {
    @AfterEach
    void clear() {
        Hrm012TestFixtures.clearContexts();
    }

    @Test
    void newCommandReservesReceiptBeforePostReceiptAuthorityAndEveryObjectAccess() {
        Fixture fixture = fixture();
        Hrm012TestFixtures.installContexts();

        Hrm012WorkforceIngestionContract.RunReceipt result = fixture.service().initiate(
                Hrm012TestFixtures.command(), Hrm012TestFixtures.headers());

        assertThat(result.receiptId()).isEqualTo(Hrm012TestFixtures.RECEIPT);
        assertThat(result.status()).isEqualTo("RECEIVED");
        assertThat(result.links()).containsExactly(Map.entry(
                "self", "/v1/hris/command-receipts/" + Hrm012TestFixtures.RECEIPT));
        InOrder order = inOrder(fixture.receipts(), fixture.postReceipt(),
                fixture.metadata(), fixture.artifacts(), fixture.runs());
        order.verify(fixture.receipts()).reserveOrRead(any());
        order.verify(fixture.receipts()).bindMarker(eq(Hrm012TestFixtures.TENANT),
                eq(Hrm012TestFixtures.RECEIPT), any(), any(), any(), any());
        order.verify(fixture.postReceipt()).verify(any());
        order.verify(fixture.receipts()).markAuthority(
                eq(Hrm012TestFixtures.TENANT), eq(Hrm012TestFixtures.RECEIPT),
                eq(Hrm012TestFixtures.marker().jti()), eq("VERIFIED_ALLOWED"));
        order.verify(fixture.metadata()).resolve(any());
        order.verify(fixture.artifacts()).fetch(any());
        order.verify(fixture.runs()).createRun(any());
    }

    @Test
    void sameTupleAndHashReturnsOriginalTerminalReceiptWithStableCreatedAt() {
        Fixture fixture = fixture();
        Hrm012TestFixtures.installContexts();
        fixture.service().initiate(Hrm012TestFixtures.command(), Hrm012TestFixtures.headers());
        Hrm012CommandReceiptRepository.Reservation reservation =
                fixture.reservation().getValue();
        var terminal = receipt(reservation, "SUCCEEDED", Map.of(
                "runPublicId", Hrm012TestFixtures.RUN.toString()), false);
        when(fixture.receipts().findExisting(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                Hrm012TestFixtures.IDEMPOTENCY)).thenReturn(Optional.of(terminal));
        clearInvocations(fixture.snapshots(), fixture.postReceipt(), fixture.metadata(),
                fixture.artifacts(), fixture.runs(), fixture.receipts());

        Hrm012WorkforceIngestionContract.RunReceipt retried = fixture.service().initiate(
                Hrm012TestFixtures.command(), Hrm012TestFixtures.headers());

        assertThat(retried.receiptId()).isEqualTo(terminal.receiptPublicId());
        assertThat(retried.status()).isEqualTo("SUCCEEDED");
        assertThat(retried.receivedAt()).isEqualTo(reservation.createdAt());
        assertThat(retried.requestHash()).isEqualTo(terminal.requestHash());
        verify(fixture.receipts()).findExisting(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                Hrm012TestFixtures.IDEMPOTENCY);
        verify(fixture.receipts(), never()).reserveOrRead(any());
        verifyNoInteractions(fixture.snapshots(), fixture.postReceipt(), fixture.metadata(),
                fixture.artifacts(), fixture.runs());
    }

    @Test
    void terminalTransitionBeforeMarkerLockReturnsOriginalWithoutAuthorizationOrObjectAccess() {
        Fixture fixture = fixture();
        Hrm012TestFixtures.installContexts();
        fixture.service().initiate(Hrm012TestFixtures.command(), Hrm012TestFixtures.headers());
        Hrm012CommandReceiptRepository.Reservation reservation =
                fixture.reservation().getValue();
        var nonterminal = receipt(reservation, "RECEIVED", Map.of(), false);
        var terminal = receipt(reservation, "SUCCEEDED", Map.of(
                "runPublicId", Hrm012TestFixtures.RUN.toString()), false);
        when(fixture.receipts().findExisting(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                Hrm012TestFixtures.IDEMPOTENCY)).thenReturn(Optional.of(nonterminal));
        doThrow(new Hrm012CommandReceiptRepository.TerminalReceiptObserved(terminal))
                .when(fixture.receipts()).bindMarker(
                        eq(Hrm012TestFixtures.TENANT),
                        eq(Hrm012TestFixtures.RECEIPT), any(), any(), any(), any());
        clearInvocations(fixture.snapshots(), fixture.postReceipt(), fixture.metadata(),
                fixture.artifacts(), fixture.runs(), fixture.receipts());

        Hrm012WorkforceIngestionContract.RunReceipt retried = fixture.service().initiate(
                Hrm012TestFixtures.command(), Hrm012TestFixtures.headers());

        assertThat(retried.receiptId()).isEqualTo(terminal.receiptPublicId());
        assertThat(retried.status()).isEqualTo("SUCCEEDED");
        assertThat(retried.receivedAt()).isEqualTo(reservation.createdAt());
        assertThat(retried.requestHash()).isEqualTo(terminal.requestHash());
        verify(fixture.receipts()).bindMarker(
                eq(Hrm012TestFixtures.TENANT), eq(Hrm012TestFixtures.RECEIPT),
                eq(Hrm012TestFixtures.marker().jti()),
                eq(Hrm012TestFixtures.marker().markerSha256()), any(), any());
        verify(fixture.receipts(), never()).markAuthority(
                eq(Hrm012TestFixtures.TENANT), any(), any(), any());
        verifyNoInteractions(fixture.snapshots(), fixture.postReceipt(), fixture.metadata(),
                fixture.artifacts(), fixture.runs());
    }

    @Test
    void sameTupleDifferentHashConflictsBeforeSnapshotOrObjectAccess() {
        Fixture fixture = fixture();
        Hrm012TestFixtures.installContexts();
        when(fixture.receipts().findExisting(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                Hrm012TestFixtures.IDEMPOTENCY)).thenReturn(Optional.of(
                Hrm012TestFixtures.receipt(
                        "RECEIVED", "f".repeat(64), false)));

        assertThatThrownBy(() -> fixture.service().initiate(
                Hrm012TestFixtures.command(), Hrm012TestFixtures.headers()))
                .isInstanceOf(Hrm012Problem.class)
                .satisfies(error -> {
                    Hrm012Problem problem = (Hrm012Problem) error;
                    assertThat(problem.status()).isEqualTo(409);
                    assertThat(problem.code()).isEqualTo(
                            "HRM012_IDEMPOTENCY_HASH_CONFLICT");
                });
        verifyNoInteractions(fixture.snapshots(), fixture.postReceipt(), fixture.metadata(),
                fixture.artifacts(), fixture.runs());
    }

    @Test
    void nonterminalRetryUsesWinnerFrozenSnapshotWithoutResolvingCurrentSnapshotAgain() {
        Fixture fixture = fixture();
        Hrm012TestFixtures.installContexts();
        fixture.service().initiate(Hrm012TestFixtures.command(), Hrm012TestFixtures.headers());
        Hrm012CommandReceiptRepository.Reservation reservation =
                fixture.reservation().getValue();
        var original = receipt(reservation, "RECEIVED", Map.of(), false);
        when(fixture.receipts().findExisting(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                Hrm012TestFixtures.IDEMPOTENCY)).thenReturn(Optional.of(original));
        clearInvocations(fixture.snapshots(), fixture.postReceipt(), fixture.metadata(),
                fixture.artifacts(), fixture.runs(), fixture.receipts());

        fixture.service().initiate(
                Hrm012TestFixtures.command(), Hrm012TestFixtures.headers());

        verifyNoInteractions(fixture.snapshots());
        verify(fixture.postReceipt()).verify(any());
        verify(fixture.metadata()).resolve(any());
        verify(fixture.runs()).createRun(any());
        verify(fixture.receipts(), never()).reserveOrRead(any());
    }

    @Test
    void postAuthFailureRetriesOnlyWithSameMarkerAndSealsBeforeMetadata() {
        Fixture fixture = fixture();
        Hrm012TestFixtures.installContexts();
        when(fixture.metadata().resolve(any()))
                .thenThrow(new IllegalStateException("metadata unavailable"))
                .thenReturn(Hrm012TestFixtures.metadata());

        assertThatThrownBy(() -> fixture.service().initiate(
                Hrm012TestFixtures.command(), Hrm012TestFixtures.headers()))
                .isInstanceOf(IllegalStateException.class);
        Hrm012CommandReceiptRepository.Reservation reservation =
                fixture.reservation().getValue();
        Hrm012WorkforceIngestionContract.Receipt original =
                receipt(reservation, "RECEIVED", Map.of(), false);
        when(fixture.receipts().findExisting(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                Hrm012TestFixtures.IDEMPOTENCY)).thenReturn(Optional.of(original));
        verify(fixture.runs(), never()).createRun(any());
        InOrder firstOrder = inOrder(
                fixture.postReceipt(), fixture.receipts(), fixture.metadata());
        firstOrder.verify(fixture.postReceipt()).verify(any());
        firstOrder.verify(fixture.receipts()).markAuthority(
                Hrm012TestFixtures.TENANT, Hrm012TestFixtures.RECEIPT,
                Hrm012TestFixtures.marker().jti(), "VERIFIED_ALLOWED");
        firstOrder.verify(fixture.metadata()).resolve(any());
        clearInvocations(fixture.snapshots(), fixture.postReceipt(), fixture.metadata(),
                fixture.artifacts(), fixture.runs(), fixture.receipts());

        Hrm012WorkforceIngestionContract.RunReceipt retried = fixture.service().initiate(
                Hrm012TestFixtures.command(), Hrm012TestFixtures.headers());

        assertThat(retried.receiptId()).isEqualTo(original.receiptPublicId());
        verifyNoInteractions(fixture.snapshots());
        InOrder order = inOrder(fixture.receipts(), fixture.postReceipt(),
                fixture.metadata(), fixture.artifacts(), fixture.runs());
        order.verify(fixture.receipts()).bindMarker(
                eq(Hrm012TestFixtures.TENANT), eq(Hrm012TestFixtures.RECEIPT),
                eq(Hrm012TestFixtures.marker().jti()),
                eq(Hrm012TestFixtures.marker().markerSha256()), any(), any());
        order.verify(fixture.postReceipt()).verify(any());
        order.verify(fixture.receipts()).markAuthority(
                eq(Hrm012TestFixtures.TENANT), eq(Hrm012TestFixtures.RECEIPT),
                eq(Hrm012TestFixtures.marker().jti()), eq("VERIFIED_ALLOWED"));
        order.verify(fixture.metadata()).resolve(any());
        order.verify(fixture.artifacts()).fetch(any());
        order.verify(fixture.runs()).createRun(any());
        assertThat(original.frozenAuthority()).isEqualTo(reservation.authority());
    }

    @Test
    void resolvedMetadataMustExactlyMatchFrozenScopeAndRequireViewBeforeArtifact() {
        UUID other = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        Map<String, Hrm012WorkforceIngestionContract.FieldDecision> masked = Map.of(
                "INTEGRATION_METADATA",
                Hrm012WorkforceIngestionContract.FieldDecision.VIEW,
                "REDACTED_PAYLOAD",
                Hrm012WorkforceIngestionContract.FieldDecision.MASK);
        Map<String, Hrm012WorkforceIngestionContract.FieldDecision> viewed = Map.of(
                "INTEGRATION_METADATA",
                Hrm012WorkforceIngestionContract.FieldDecision.VIEW,
                "REDACTED_PAYLOAD",
                Hrm012WorkforceIngestionContract.FieldDecision.VIEW);
        List<Hrm012WorkforceIngestionContract.OwnerMetadata> invalid = List.of(
                Hrm012TestFixtures.metadata(
                        Hrm012TestFixtures.TENANT + 1, Hrm012TestFixtures.PERSON,
                        Hrm012TestFixtures.command().objectRef(), 13, 14,
                        Hrm012TestFixtures.DIGEST_B, List.of(Hrm012TestFixtures.CONNECTOR),
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), viewed),
                Hrm012TestFixtures.metadata(
                        Hrm012TestFixtures.TENANT, other,
                        Hrm012TestFixtures.command().objectRef(), 13, 14,
                        Hrm012TestFixtures.DIGEST_B, List.of(Hrm012TestFixtures.CONNECTOR),
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), viewed),
                Hrm012TestFixtures.metadata(
                        Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                        "obj_ponmlkjihgfedcba", 13, 14,
                        Hrm012TestFixtures.DIGEST_B, List.of(Hrm012TestFixtures.CONNECTOR),
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), viewed),
                Hrm012TestFixtures.metadata(
                        Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                        Hrm012TestFixtures.command().objectRef(), 4, 13, 14,
                        Hrm012TestFixtures.DIGEST_B, List.of(Hrm012TestFixtures.CONNECTOR),
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), viewed),
                Hrm012TestFixtures.metadata(
                        Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                        Hrm012TestFixtures.command().objectRef(), 99, 14,
                        Hrm012TestFixtures.DIGEST_B, List.of(Hrm012TestFixtures.CONNECTOR),
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), viewed),
                Hrm012TestFixtures.metadata(
                        Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                        Hrm012TestFixtures.command().objectRef(), 13, 99,
                        Hrm012TestFixtures.DIGEST_B, List.of(Hrm012TestFixtures.CONNECTOR),
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), viewed),
                Hrm012TestFixtures.metadata(
                        Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                        Hrm012TestFixtures.command().objectRef(), 13, 14,
                        "c".repeat(64), List.of(Hrm012TestFixtures.CONNECTOR),
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), viewed),
                Hrm012TestFixtures.metadata(
                        Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                        Hrm012TestFixtures.command().objectRef(), 13, 14,
                        Hrm012TestFixtures.DIGEST_B, List.of(other),
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), viewed),
                Hrm012TestFixtures.metadata(
                        Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                        Hrm012TestFixtures.command().objectRef(), 13, 14,
                        Hrm012TestFixtures.DIGEST_B, List.of(Hrm012TestFixtures.CONNECTOR),
                        List.of(other), viewed),
                Hrm012TestFixtures.metadata(
                        Hrm012TestFixtures.TENANT, Hrm012TestFixtures.PERSON,
                        Hrm012TestFixtures.command().objectRef(), 13, 14,
                        Hrm012TestFixtures.DIGEST_B, List.of(Hrm012TestFixtures.CONNECTOR),
                        List.of(Hrm012TestFixtures.LEGAL_ENTITY), masked));

        for (Hrm012WorkforceIngestionContract.OwnerMetadata candidate : invalid) {
            Fixture fixture = fixture();
            Hrm012TestFixtures.installContexts();
            when(fixture.metadata().resolve(any())).thenReturn(candidate);

            assertThatThrownBy(() -> fixture.service().initiate(
                    Hrm012TestFixtures.command(), Hrm012TestFixtures.headers()))
                    .isInstanceOf(Hrm012Problem.class)
                    .satisfies(error -> assertThat(((Hrm012Problem) error).status())
                            .isEqualTo(403));
            verifyNoInteractions(fixture.artifacts(), fixture.runs());
        }
    }

    @Test
    void verifiedMarkerIsNeverReplaceableAndEvidenceShapeIsBoundedAndRedacted() {
        assertThat(Hrm012CommandReceiptRepository.replaceableMarker(
                "VERIFIED_ALLOWED", Hrm012TestFixtures.NOW.plusSeconds(30),
                0, Hrm012TestFixtures.NOW)).isFalse();
        assertThat(Hrm012CommandReceiptRepository.replaceableMarker(
                "VERIFIED_ALLOWED", Hrm012TestFixtures.NOW.minusSeconds(1),
                0, Hrm012TestFixtures.NOW)).isFalse();
        assertThat(Hrm012CommandReceiptRepository.replaceableMarker(
                "UNVERIFIED", Hrm012TestFixtures.NOW.plusSeconds(30),
                0, Hrm012TestFixtures.NOW)).isTrue();
        Map<String, Object> current = Hrm012CommandReceiptRepository.currentEvidence(
                Hrm012TestFixtures.marker().jti(),
                Hrm012TestFixtures.marker().expiresAt(),
                Hrm012TestFixtures.marker().markerSha256(), Hrm012TestFixtures.NOW);
        Map<String, Object> previous = Hrm012CommandReceiptRepository.previousEvidence(
                current, Hrm012TestFixtures.NOW.plusSeconds(1));
        assertThat(current.keySet()).containsExactlyInAnyOrder(
                "jti", "exp", "markerSha256", "state", "boundAt",
                "authDecisionRevision");
        current.put("state", "VERIFIED_ALLOWED");
        current.put("authDecisionRevision", "auth-v15");
        Map<String, Object> immutable = Map.copyOf(current);
        assertThat(Hrm012CommandReceiptRepository.exactSameMarkerRetry(
                current, Hrm012TestFixtures.marker().jti(),
                Hrm012TestFixtures.marker().markerSha256(),
                Hrm012TestFixtures.marker().expiresAt(), Hrm012TestFixtures.NOW))
                .isTrue();
        assertThat(current).isEqualTo(immutable)
                .containsEntry("state", "VERIFIED_ALLOWED")
                .containsEntry("authDecisionRevision", "auth-v15");
        assertThat(Hrm012CommandReceiptRepository.exactSameMarkerRetry(
                current, Hrm012TestFixtures.marker().jti(), "f".repeat(64),
                Hrm012TestFixtures.marker().expiresAt(), Hrm012TestFixtures.NOW))
                .isFalse();
        assertThat(Hrm012CommandReceiptRepository.exactSameMarkerRetry(
                current, Hrm012TestFixtures.marker().jti(),
                Hrm012TestFixtures.marker().markerSha256(),
                Hrm012TestFixtures.marker().expiresAt(),
                Hrm012TestFixtures.marker().expiresAt()))
                .isFalse();
        assertThat(previous.keySet()).containsExactlyInAnyOrder(
                "jtiSha256", "exp", "state", "replacedAt");
        assertThat(previous.toString())
                .doesNotContain(Hrm012TestFixtures.marker().jti(),
                        Hrm012TestFixtures.marker().markerSha256());
    }

    @Test
    void storedDatabaseKeyIsSubjectBoundOpaqueAndNeverContainsTheRawKey() {
        UUID otherPerson = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

        String first = Hrm012CommandReceiptRepository.storedKey(
                Hrm012TestFixtures.PERSON, Hrm012TestFixtures.IDEMPOTENCY);
        String same = Hrm012CommandReceiptRepository.storedKey(
                Hrm012TestFixtures.PERSON, Hrm012TestFixtures.IDEMPOTENCY);
        String otherSubject = Hrm012CommandReceiptRepository.storedKey(
                otherPerson, Hrm012TestFixtures.IDEMPOTENCY);

        assertThat(first).isEqualTo(same).matches("HRM012:[a-f0-9]{64}");
        assertThat(otherSubject).matches("HRM012:[a-f0-9]{64}").isNotEqualTo(first);
        assertThat(first).doesNotContain(Hrm012TestFixtures.IDEMPOTENCY);
    }

    private Fixture fixture() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort snapshots =
                mock(Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort.class);
        when(snapshots.resolve(any())).thenAnswer(invocation -> {
            Hrm012WorkforceIngestionPorts.SnapshotRequest request = invocation.getArgument(0);
            return Hrm012TestFixtures.snapshot(request.canonicalRequestHash());
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
        when(receipts.findExisting(org.mockito.ArgumentMatchers.anyLong(),
                any(UUID.class), any()))
                .thenReturn(Optional.empty());
        ArgumentCaptor<Hrm012CommandReceiptRepository.Reservation> reservation =
                ArgumentCaptor.forClass(Hrm012CommandReceiptRepository.Reservation.class);
        when(receipts.reserveOrRead(reservation.capture())).thenAnswer(invocation ->
                receipt(invocation.getArgument(0), "RECEIVED", Map.of(), true));
        Hrm012WorkforceIngestionRepository runs =
                mock(Hrm012WorkforceIngestionRepository.class);
        when(runs.createRun(any())).thenReturn(
                Hrm012TestFixtures.run("RECEIVED", null, null));
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
        return new Fixture(service, snapshots, postReceipt, receipts, runs,
                metadata, artifacts, reservation);
    }

    private Hrm012WorkforceIngestionContract.Receipt receipt(
            Hrm012CommandReceiptRepository.Reservation value,
            String state,
            Map<String, Object> result,
            boolean newlyReserved) {
        return new Hrm012WorkforceIngestionContract.Receipt(
                Hrm012TestFixtures.RECEIPT, value.tenantId(), value.personPublicId(),
                Hrm012CommandReceiptRepository.storedKey(
                        value.personPublicId(), value.idempotencyKey()),
                value.requestHash(), state,
                "SUCCEEDED".equals(state) ? "WORKFORCE_INGESTION_SUCCEEDED" : null,
                result, value.correlationId(), value.personPublicId(),
                value.authority().populationScopeDigest(),
                value.authority().fieldPolicyRevision(),
                Hrm012WorkforceIngestionContract.PURPOSE,
                value.authority().authorizationRevision(),
                value.authority().originatingContextDigest(), value.expectedVersion(),
                value.createdAt(), value.authority(), newlyReserved);
    }

    private record Fixture(
            Hrm012WorkforceIngestionService service,
            Hrm012WorkforceIngestionPorts.TrustedAuthoritySnapshotPort snapshots,
            Hrm012WorkforceIngestionPorts.PostReceiptAuthorityPort postReceipt,
            Hrm012CommandReceiptRepository receipts,
            Hrm012WorkforceIngestionRepository runs,
            Hrm012WorkforceIngestionPorts.OwnerMetadataPort metadata,
            Hrm012WorkforceIngestionPorts.ArtifactPort artifacts,
            ArgumentCaptor<Hrm012CommandReceiptRepository.Reservation> reservation) {
    }
}
