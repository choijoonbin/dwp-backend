package com.dwp.services.people.hr.performance.typedartifact;

import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PerTypedArtifactQueryPortBatchTest {

    private static final long TENANT_ID = 41L;
    private static final UUID CYCLE_ID = UUID.randomUUID();

    @Test
    void bindsOnceAndReturnsOnlyAnExactCompleteBatch() {
        PerTypedArtifactRepository repository = mock(PerTypedArtifactRepository.class);
        PerTypedArtifactProtector protector = mock(PerTypedArtifactProtector.class);
        PerTypedArtifactCanonicalizer canonicalizer =
                new PerTypedArtifactCanonicalizer(new ObjectMapper());
        PerTypedArtifactQueryPort port =
                new PerTypedArtifactQueryPort(repository, protector, canonicalizer);
        Fixture first = fixture(canonicalizer, "100", 1);
        Fixture second = fixture(canonicalizer, "200", 2);
        List<PerTypedArtifactQueryPort.QuerySpec> requests = List.of(
                first.request(), second.request());

        when(repository.findAll(TENANT_ID, List.of(first.artifactId(), second.artifactId())))
                .thenReturn(List.of(second.stored(), first.stored()));
        when(protector.reveal(
                eq(TENANT_ID), eq(first.artifactId()),
                eq(PerArtifactType.COMPENSATION_PROPOSAL), any(), any()))
                .thenReturn(first.canonicalBytes());
        when(protector.reveal(
                eq(TENANT_ID), eq(second.artifactId()),
                eq(PerArtifactType.COMPENSATION_PROPOSAL), any(), any()))
                .thenReturn(second.canonicalBytes());

        Map<UUID, PerTypedArtifactQueryPort.TypedArtifact> result = port.queryAll(
                TENANT_ID, requests, PerTypedArtifactQueryPort.PLANNING_PURPOSE);

        assertThat(result).containsOnlyKeys(first.artifactId(), second.artifactId());
        assertThat(result.get(first.artifactId()).content()).isEqualTo(first.proposal());
        assertThat(result.get(second.artifactId()).content()).isEqualTo(second.proposal());
        verify(repository).bindTenant(TENANT_ID);
        verify(repository).findAll(
                TENANT_ID, List.of(first.artifactId(), second.artifactId()));
        verify(repository, never()).find(anyLong(), any());
    }

    @Test
    void failsOpaqueWhenTheDatabaseReturnsOnlyPartOfTheRequestedSet() {
        PerTypedArtifactRepository repository = mock(PerTypedArtifactRepository.class);
        PerTypedArtifactQueryPort port = new PerTypedArtifactQueryPort(
                repository, mock(PerTypedArtifactProtector.class),
                new PerTypedArtifactCanonicalizer(new ObjectMapper()));
        Fixture first = fixture(
                new PerTypedArtifactCanonicalizer(new ObjectMapper()), "100", 1);
        Fixture second = fixture(
                new PerTypedArtifactCanonicalizer(new ObjectMapper()), "200", 2);
        when(repository.findAll(TENANT_ID, List.of(first.artifactId(), second.artifactId())))
                .thenReturn(List.of(first.stored()));

        assertThatThrownBy(() -> port.queryAll(
                TENANT_ID, List.of(first.request(), second.request()),
                PerTypedArtifactQueryPort.PLANNING_PURPOSE))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void rejectsInvalidPurposeDuplicateOwnerTupleAndOversizedBatchBeforeDatabaseRead() {
        PerTypedArtifactRepository repository = mock(PerTypedArtifactRepository.class);
        PerTypedArtifactQueryPort port = new PerTypedArtifactQueryPort(
                repository, mock(PerTypedArtifactProtector.class),
                new PerTypedArtifactCanonicalizer(new ObjectMapper()));
        Fixture value = fixture(
                new PerTypedArtifactCanonicalizer(new ObjectMapper()), "100", 1);
        PerTypedArtifactQueryPort.QuerySpec wrongOwner =
                new PerTypedArtifactQueryPort.QuerySpec(
                        UUID.randomUUID(), PerArtifactType.COMPENSATION_PROPOSAL,
                        "COMPENSATION_CYCLE", UUID.randomUUID());

        assertThatThrownBy(() -> port.queryAll(
                TENANT_ID, List.of(value.request()), "WRONG_PURPOSE"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> port.queryAll(
                TENANT_ID, List.of(value.request(), value.request()),
                PerTypedArtifactQueryPort.PLANNING_PURPOSE))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> port.queryAll(
                TENANT_ID, List.of(wrongOwner),
                PerTypedArtifactQueryPort.PLANNING_PURPOSE))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> port.queryAll(
                TENANT_ID, Collections.nCopies(100_001, value.request()),
                PerTypedArtifactQueryPort.PLANNING_PURPOSE))
                .isInstanceOf(BaseException.class);
        verify(repository, never()).bindTenant(anyLong());
        verify(repository, never()).findAll(anyLong(), any());
    }

    private static Fixture fixture(
            PerTypedArtifactCanonicalizer canonicalizer,
            String amount,
            int suffix) {
        UUID artifactId = uuid(100 + suffix);
        UUID revisionId = uuid(200 + suffix);
        Proposal proposal = new Proposal(
                CYCLE_ID, uuid(300 + suffix), uuid(400 + suffix),
                "BASE", "KRW", amount, LocalDate.parse("2027-01-01"),
                null, null, null);
        PerTypedArtifactCanonicalizer.CanonicalArtifact canonical =
                canonicalizer.canonicalize(PerArtifactType.COMPENSATION_PROPOSAL, proposal);
        PerTypedArtifactRepository.StoredArtifact stored =
                new PerTypedArtifactRepository.StoredArtifact(
                        artifactId, PerArtifactType.COMPENSATION_PROPOSAL.name(),
                        PerArtifactType.SCHEMA_VERSION, 1L,
                        "COMPENSATION_PROPOSAL_REVISION", revisionId,
                        canonical.digest(), new byte[]{(byte) suffix}, uuid(500 + suffix),
                        LocalDate.parse("2027-01-01"), null,
                        Instant.parse("2027-01-01T00:00:00Z"), uuid(600 + suffix));
        return new Fixture(
                artifactId, proposal, canonical.bytes(), stored,
                new PerTypedArtifactQueryPort.QuerySpec(
                        artifactId, PerArtifactType.COMPENSATION_PROPOSAL,
                        "COMPENSATION_PROPOSAL_REVISION", revisionId));
    }

    private static UUID uuid(int value) {
        return UUID.fromString(String.format(
                "00000000-0000-4000-8000-%012d", value));
    }

    private record Fixture(
            UUID artifactId,
            Proposal proposal,
            byte[] canonicalBytes,
            PerTypedArtifactRepository.StoredArtifact stored,
            PerTypedArtifactQueryPort.QuerySpec request) {
        private Fixture {
            canonicalBytes = canonicalBytes.clone();
        }

        @Override
        public byte[] canonicalBytes() {
            return canonicalBytes.clone();
        }
    }
}
