package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferAuthorityPorts.*;
import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferDtos.*;
import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferRepository.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlledTransferIdempotencyTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-16T00:00:00Z");
    private static final String OBJECT_DIGEST = "a".repeat(64);
    private static final String KEY = "controlled-transfer-0001";
    private static final String SCOPE =
            "hcm-scope-3b21f50dc0bc6974f09f6bcb2b987f677ce5b7a5";

    @Test
    void canonicalOwnerReplayPrecedesAuthoritiesAndConflictsStayOpaque() {
        assertThat(JdbcControlledTransferRepository.internalReceiptKey(
                OwnerModule.SYS, KEY))
                .isEqualTo("ct1:SYS:b0025418248e4bf6fdddafcdf1302cd3b2503c7e494fd0ef39049f0a3a7467dc")
                .hasSize(72);
        SealedRepository repository = new SealedRepository();
        AtomicInteger authorityCalls = new AtomicInteger();
        TrustedObjectMetadataAuthority metadata = request -> {
            authorityCalls.incrementAndGet();
            return new TrustedObjectMetadata(
                    request.tenantId(), request.subjectPrincipalPublicId(),
                    request.objectRef(), request.uploadSessionId(), "v1", OBJECT_DIGEST,
                    10, "application/json", "etag-1", "OBJECT_STORE",
                    "b".repeat(64), "c".repeat(64), NOW.plusMinutes(10));
        };
        SignedMalwareScanAuthority scan = request -> new VerifiedCleanScan(
                request.tenantId(), request.scannerKey(), request.scannerVersion(),
                request.scannedAt(), request.objectDigest(), request.byteSize(),
                "d".repeat(64), NOW.plusMinutes(10));
        EgressPolicyAuthority egress = request -> {
            throw new AssertionError("INGEST does not resolve egress");
        };
        ControlledTransferService service = new ControlledTransferService(
                repository, metadata, scan, egress,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
                "cursor-secret-for-sys009-is-at-least-32-bytes",
                Duration.ofMinutes(5));
        FileTransferCommand command = command(OwnerModule.SYS, OBJECT_DIGEST);
        Receipt first = service.create(access(SUBJECT_ONE), command);

        assertThat(service.create(access(SUBJECT_ONE), command)).isEqualTo(first);
        assertThat(authorityCalls).hasValue(1);

        assertThatThrownBy(() -> service.create(
                access(SUBJECT_ONE), command(OwnerModule.SYS, "e".repeat(64))))
                .isInstanceOfSatisfying(BaseException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(error.getMessage())
                            .contains("CONTROLLED_TRANSFER_IDEMPOTENCY_CONFLICT");
                });
        assertThatThrownBy(() -> service.create(access(SUBJECT_TWO), command))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getMessage())
                                .contains("CONTROLLED_TRANSFER_IDEMPOTENCY_CONFLICT"));
        assertThat(authorityCalls).hasValue(1);

        service.create(access(SUBJECT_ONE), command(OwnerModule.PER, OBJECT_DIGEST));
        assertThat(repository.records).hasSize(2);
        assertThat(authorityCalls).hasValue(2);
    }

    private static final UUID SUBJECT_ONE =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SUBJECT_TWO =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private AccessSeal access(UUID subject) {
        String population = ControlledTransferService.populationScopeDigest(
                42, subject, "CONNECTOR_SET", SCOPE, "decision-1");
        String authority = ControlledTransferService.authorityDigest(
                42, subject, ControlledTransferService.CREATE_ROUTE, "TENANT",
                SCOPE, "CONNECTOR_SET", population,
                ControlledTransferService.CREATE_FIELD_GROUPS, 7, 9,
                "decision-1", ControlledTransferService.PURPOSE,
                NOW.plusMinutes(10));
        return new AccessSeal(
                42, subject, population, "CONNECTOR_SET",
                ControlledTransferService.CREATE_FIELD_GROUPS, 7, 9,
                ControlledTransferService.CREATE_ROUTE, "TENANT", SCOPE,
                "decision-1", ControlledTransferService.PURPOSE, authority,
                NOW.plusMinutes(10), UUID.randomUUID(), KEY);
    }

    private FileTransferCommand command(OwnerModule owner, String digest) {
        return new FileTransferCommand(
                owner, TransferKind.INGEST, null, null,
                "obj_1234567890abcdef", "v1", digest, 10,
                "application/json", new MalwareScanSnapshot(
                "scanner", "1", "CLEAN", NOW.minusMinutes(1), digest, 10),
                new ToctouBinding(
                        UUID.fromString("44444444-4444-4444-8444-444444444444"),
                        "etag-1", "v1", digest, 10, "b".repeat(64)),
                Classification.CONFIDENTIAL, ControlledTransferService.PURPOSE,
                NOW.plusDays(1));
    }

    private static final class SealedRepository implements ControlledTransferRepository {
        private final Map<String, Stored> records = new HashMap<>();

        @Override
        public Optional<Receipt> findReplay(ReplayProbe probe) {
            String key = key(probe.access().tenantId(), probe.ownerModule(),
                    probe.access().idempotencyKey());
            Stored stored = records.get(key);
            if (stored == null) return Optional.empty();
            if (!stored.subject().equals(probe.access().subjectPrincipalPublicId())
                    || !stored.requestDigest().equals(probe.requestDigest())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "CONTROLLED_TRANSFER_IDEMPOTENCY_CONFLICT");
            }
            return Optional.of(stored.receipt());
        }

        @Override
        public Receipt create(PreparedTransfer transfer) {
            Receipt receipt = new Receipt(
                    UUID.randomUUID(), JdbcControlledTransferRepository.ACTION,
                    JdbcControlledTransferRepository.ACTION,
                    transfer.access().subjectPrincipalPublicId(),
                    transfer.access().populationScopeDigest(), 7,
                    ControlledTransferService.PURPOSE, 9, ReceiptStatus.ACCEPTED,
                    transfer.access().correlationId(), transfer.requestDigest(),
                    transfer.requestedAt(), 1L,
                    "file-transfer:" + transfer.transferPublicId());
            records.put(key(transfer.access().tenantId(), transfer.ownerModule(),
                    transfer.access().idempotencyKey()), new Stored(
                    transfer.access().subjectPrincipalPublicId(),
                    transfer.requestDigest(), receipt));
            return receipt;
        }

        @Override
        public TransferPage catalog(
                AccessSeal access,
                OffsetDateTime asOf, String filter, Cursor cursor, int limit) {
            return new TransferPage(List.of(), null);
        }

        private static String key(long tenantId, OwnerModule owner, String idempotencyKey) {
            return tenantId + ":" + owner + ":" + idempotencyKey;
        }

        private record Stored(UUID subject, String requestDigest, Receipt receipt) {
        }
    }
}
