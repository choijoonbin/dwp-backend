package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferAuthorityPorts.*;
import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferDtos.*;
import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferRepository.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ControlledTransferServiceTest {

    private static final String OBJECT_DIGEST = "a".repeat(64);
    private static final String SNAPSHOT_DIGEST = "b".repeat(64);
    private static final String METADATA_AUTHORITY_DIGEST = "c".repeat(64);
    private static final String SCAN_AUTHORITY_DIGEST = "d".repeat(64);
    private static final String EGRESS_AUTHORITY_DIGEST = "f".repeat(64);
    private static final String CURSOR_SECRET = "cursor-secret-for-sys009-is-at-least-32-bytes";
    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-16T00:00:00Z");
    private static final UUID SUBJECT =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID UPLOAD =
            UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String SCOPE =
            "hcm-scope-3b21f50dc0bc6974f09f6bcb2b987f677ce5b7a5";

    private final CapturingRepository repository = new CapturingRepository();
    private final AtomicInteger metadataCalls = new AtomicInteger();
    private final AtomicInteger scanCalls = new AtomicInteger();
    private final AtomicInteger egressCalls = new AtomicInteger();
    private ControlledTransferService service;

    @BeforeEach
    void setUp() {
        TrustedObjectMetadataAuthority metadata = request -> {
            metadataCalls.incrementAndGet();
            return metadata(request);
        };
        SignedMalwareScanAuthority scan = request -> {
            scanCalls.incrementAndGet();
            return scan(request);
        };
        EgressPolicyAuthority egress = request -> {
            egressCalls.incrementAndGet();
            return egress(request);
        };
        service = service(repository, metadata, scan, egress);
    }

    @Test
    void createsRequestedTransferOnlyFromFreshEchoBoundAuthorities() {
        Receipt receipt = service.create(
                access(true, "0123456789abcdef"), command(TransferKind.INGEST));

        assertThat(receipt.status()).isEqualTo(ReceiptStatus.ACCEPTED);
        PreparedTransfer saved = repository.saved.getFirst();
        assertThat(saved.objectDigest()).isEqualTo(OBJECT_DIGEST);
        assertThat(saved.metadataAuthorityDigest())
                .isEqualTo(METADATA_AUTHORITY_DIGEST);
        assertThat(saved.scanAuthorityDigest()).isEqualTo(SCAN_AUTHORITY_DIGEST);
        assertThat(saved.requestDigest()).matches("^[a-f0-9]{64}$");
        assertThat(saved.egressAuthorityDigest()).isNull();
        assertThat(metadataCalls).hasValue(1);
        assertThat(scanCalls).hasValue(1);
        assertThat(egressCalls).hasValue(0);
    }

    @Test
    void outboundRequiresAnExactFreshEgressPolicyEcho() {
        service.create(access(true, "0123456789abcdef"),
                command(TransferKind.EXPORT));

        assertThat(repository.saved.getFirst().egressAuthorityDigest())
                .isEqualTo(EGRESS_AUTHORITY_DIGEST);
        assertThat(egressCalls).hasValue(1);

        EgressPolicyAuthority denied = request -> new VerifiedEgressPolicy(
                false, request.tenantId(), request.subjectPrincipalPublicId(),
                request.recipientPolicyRef(), request.egressPolicyRevision(),
                request.transferKind(), request.classification(), request.purposeCode(),
                request.objectRef(), request.objectVersion(), request.objectDigest(),
                request.byteSize(), request.mediaType(), EGRESS_AUTHORITY_DIGEST,
                NOW.plusMinutes(10));
        ControlledTransferService deniedService = service(
                new CapturingRepository(), this::metadata, this::scan, denied);
        assertThatThrownBy(() -> deniedService.create(
                access(true, "fedcba9876543210"), command(TransferKind.EXPORT)))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void replayReturnsBeforeExternalAuthoritiesAndVolatileAuthorityMayChange() {
        FileTransferCommand command = command(TransferKind.INGEST);
        Receipt original = service.create(access(true, "0123456789abcdef"), command);
        AccessSeal newerAuthority = copyAccess(
                access(true, "0123456789abcdef"), "decision-2");

        Receipt replay = service.create(newerAuthority, command);

        assertThat(replay).isEqualTo(original);
        assertThat(metadataCalls).hasValue(1);
        assertThat(scanCalls).hasValue(1);
        assertThat(repository.saved).hasSize(1);
    }

    @Test
    void purposePopulationProjectionAndFreshnessFailClosed() {
        AccessSeal invalidPopulation = new AccessSeal(
                1, SUBJECT, "e".repeat(64), "TENANT",
                ControlledTransferService.CREATE_FIELD_GROUPS, 7, 9,
                ControlledTransferService.CREATE_ROUTE, "TENANT", "tenant:1",
                "decision-1", ControlledTransferService.PURPOSE, "8".repeat(64),
                NOW.plusMinutes(10), UUID.randomUUID(), "0123456789abcdef");
        assertThatThrownBy(() -> service.create(
                invalidPopulation, command(TransferKind.INGEST)))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        FileTransferCommand value = command(TransferKind.INGEST);
        FileTransferCommand wrongPurpose = new FileTransferCommand(
                value.ownerModule(), value.transferKind(), null, null,
                value.objectRef(), value.objectVersion(), value.objectDigest(),
                value.byteSize(), value.mediaType(), value.malwareScanSnapshot(),
                value.toctouBinding(), value.classification(), "HRIS_TRANSFER",
                value.expiresAt());
        assertThatThrownBy(() -> service.create(
                access(true, "fedcba9876543210"), wrongPurpose))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void cursorIsSignedAndBoundToCallerQueryAndFreshAuthority() throws Exception {
        repository.catalogPage = new TransferPage(List.of(new CatalogItem(
                UUID.randomUUID(), "SYS:INGEST", "INGEST", "REQUESTED", 1,
                NOW.minusSeconds(1))), new Cursor(
                NOW.minusSeconds(1), UUID.randomUUID()));
        AccessSeal read = access(false, null);
        List<String> fields = List.of(
                "publicId", "key", "type", "state", "version", "updatedAt");
        CatalogResponse first = service.catalog(
                read, NOW, fields, Freshness.FRESH_ONLY, "sys", null, 50);

        assertThat(first.nextCursor()).isNotBlank().contains(".");
        assertThat(first.freshUntil()).isEqualTo(read.authorityFreshUntil());
        assertThat(first.staleAfter()).isEqualTo(read.authorityFreshUntil());
        CatalogResponse allowStale = service.catalog(
                read, NOW, fields, Freshness.ALLOW_STALE, "sys", null, 50);
        assertThat(allowStale.freshUntil()).isEqualTo(read.authorityFreshUntil());
        assertThat(allowStale.staleAfter()).isEqualTo(read.authorityFreshUntil());
        var cursorPayload = json().readTree(Base64.getUrlDecoder().decode(
                first.nextCursor().split("\\.", -1)[0]));
        assertThat(cursorPayload.path("expiresAt").asText())
                .isEqualTo(NOW.plusMinutes(5).toInstant().toString());
        assertThat(cursorPayload.path("authorityFreshUntil").asText())
                .isEqualTo(NOW.plusMinutes(10).toInstant().toString());
        service.catalog(read, NOW, fields, Freshness.FRESH_ONLY,
                "SYS", first.nextCursor(), 50);
        AccessSeal anotherSubject = access(UUID.randomUUID(), false, null,
                read.fieldPolicyRevision(), read.authorizationRevision(),
                read.decisionRevision(), read.authorityFreshUntil());
        assertThatThrownBy(() -> service.catalog(
                anotherSubject, NOW, fields, Freshness.FRESH_ONLY,
                "SYS", first.nextCursor(), 50))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        AccessSeal changedRevision = access(SUBJECT, false, null,
                read.fieldPolicyRevision() + 1, read.authorizationRevision(),
                read.decisionRevision(), read.authorityFreshUntil());
        assertThatThrownBy(() -> service.catalog(
                changedRevision, NOW, fields, Freshness.FRESH_ONLY,
                "SYS", first.nextCursor(), 50))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        String tampered = first.nextCursor().substring(0,
                first.nextCursor().length() - 1)
                + (first.nextCursor().endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> service.catalog(
                read, NOW, fields, Freshness.FRESH_ONLY, "SYS", tampered, 50))
                .isInstanceOf(BaseException.class);

        AccessSeal shortAuthority = access(SUBJECT, false, null, 7, 9,
                "decision-1", NOW.plusMinutes(2));
        CatalogResponse capped = service.catalog(
                shortAuthority, NOW, fields, Freshness.FRESH_ONLY,
                "SYS", null, 50);
        assertThat(capped.freshUntil()).isEqualTo(shortAuthority.authorityFreshUntil());
        assertThat(capped.staleAfter()).isEqualTo(shortAuthority.authorityFreshUntil());
        var cappedPayload = json().readTree(Base64.getUrlDecoder().decode(
                capped.nextCursor().split("\\.", -1)[0]));
        assertThat(cappedPayload.path("expiresAt").asText())
                .isEqualTo(NOW.plusMinutes(2).toInstant().toString());
    }

    @Test
    void httpAuthoritiesAreConfiguredFailClosedAndRejectOpenResponses() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://authority.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpControlledTransferAuthorities authorities =
                new HttpControlledTransferAuthorities(builder.build(), true, "secret-token");
        server.expect(once(), requestTo(
                        "https://authority.test" + HttpControlledTransferAuthorities.METADATA_PATH))
                .andExpect(header("X-DWP-Service-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"tenantId":1,
                         "subjectPrincipalPublicId":"11111111-1111-4111-8111-111111111111",
                         "objectRef":"obj_1234567890abcdef",
                         "uploadSessionId":"44444444-4444-4444-8444-444444444444",
                         "objectVersion":"v1","objectDigest":"%s","byteSize":10,
                         "mediaType":"application/json","storageETag":"etag-1",
                         "metadataAuthority":"OBJECT_STORE",
                         "metadataSnapshotDigest":"%s","authorityDigest":"%s",
                         "freshUntil":"2026-10-16T00:10:00Z"}
                        """.formatted(OBJECT_DIGEST, SNAPSHOT_DIGEST,
                        METADATA_AUTHORITY_DIGEST), MediaType.APPLICATION_JSON));
        assertThat(authorities.resolve(new ObjectMetadataRequest(
                1, SUBJECT, "obj_1234567890abcdef", "v1", UPLOAD, "etag-1")))
                .extracting(TrustedObjectMetadata::authorityDigest)
                .isEqualTo(METADATA_AUTHORITY_DIGEST);
        server.verify();

        HttpControlledTransferAuthorities unconfigured =
                new HttpControlledTransferAuthorities(null, false, "");
        assertThatThrownBy(() -> unconfigured.verifySignedCleanSnapshot(null))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));

        RestClient.Builder openBuilder = RestClient.builder()
                .baseUrl("https://authority.test");
        MockRestServiceServer openServer =
                MockRestServiceServer.bindTo(openBuilder).build();
        HttpControlledTransferAuthorities open =
                new HttpControlledTransferAuthorities(openBuilder.build(), true, "token");
        openServer.expect(once(), requestTo(
                        "https://authority.test" + HttpControlledTransferAuthorities.SCAN_PATH))
                .andRespond(withSuccess("""
                        {"tenantId":1,"scannerKey":"scanner","scannerVersion":"1",
                         "scannedAt":"2026-10-15T23:59:00Z","objectDigest":"%s",
                         "byteSize":10,"authorityDigest":"%s",
                         "freshUntil":"2026-10-16T00:10:00Z","unexpected":true}
                        """.formatted(OBJECT_DIGEST, SCAN_AUTHORITY_DIGEST),
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> open.verifySignedCleanSnapshot(
                new SignedScanRequest(1, "scanner", "1", NOW.minusMinutes(1),
                        OBJECT_DIGEST, 10)))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        openServer.verify();

        RestClient.Builder egressBuilder = RestClient.builder()
                .baseUrl("https://authority.test");
        MockRestServiceServer egressServer =
                MockRestServiceServer.bindTo(egressBuilder).build();
        HttpControlledTransferAuthorities egressAuthorities =
                new HttpControlledTransferAuthorities(
                        egressBuilder.build(), true, "secret-token");
        EgressPolicyRequest egressRequest = new EgressPolicyRequest(
                1, SUBJECT, UUID.fromString("33333333-3333-4333-8333-333333333333"),
                1, TransferKind.EXPORT, Classification.CONFIDENTIAL,
                ControlledTransferService.PURPOSE, "obj_1234567890abcdef", "v1",
                OBJECT_DIGEST, 10, "application/json");
        egressServer.expect(once(), requestTo(
                        "https://authority.test" + HttpControlledTransferAuthorities.EGRESS_PATH))
                .andExpect(header("X-DWP-Service-Token", "secret-token"))
                .andRespond(withSuccess("""
                        {"allowed":true,"tenantId":1,
                         "subjectPrincipalPublicId":"11111111-1111-4111-8111-111111111111",
                         "recipientPolicyRef":"33333333-3333-4333-8333-333333333333",
                         "egressPolicyRevision":1,"transferKind":"EXPORT",
                         "classification":"CONFIDENTIAL",
                         "purposeCode":"HRIS_INTEGRATION_ADMIN",
                         "objectRef":"obj_1234567890abcdef","objectVersion":"v1",
                         "objectDigest":"%s","byteSize":10,"mediaType":"application/json",
                         "authorityDigest":"%s","freshUntil":"2026-10-16T00:10:00Z"}
                        """.formatted(OBJECT_DIGEST, EGRESS_AUTHORITY_DIGEST),
                        MediaType.APPLICATION_JSON));
        assertThat(egressAuthorities.authorize(egressRequest).allowed()).isTrue();
        egressServer.verify();
    }

    @Test
    void defaultAuthoritiesFailClosed() {
        assertThatThrownBy(() -> new FailClosedObjectMetadataAuthority().resolve(null))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new FailClosedMalwareScanAuthority()
                .verifySignedCleanSnapshot(null)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new FailClosedEgressPolicyAuthority().authorize(null))
                .isInstanceOf(BaseException.class);
    }

    private ControlledTransferService service(
            CapturingRepository target,
            TrustedObjectMetadataAuthority metadata,
            SignedMalwareScanAuthority scan,
            EgressPolicyAuthority egress) {
        return new ControlledTransferService(
                target, metadata, scan, egress,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC), CURSOR_SECRET,
                Duration.ofMinutes(5));
    }

    private ObjectMapper json() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private TrustedObjectMetadata metadata(ObjectMetadataRequest request) {
        return new TrustedObjectMetadata(
                request.tenantId(), request.subjectPrincipalPublicId(), request.objectRef(),
                request.uploadSessionId(), "v1", OBJECT_DIGEST, 10,
                "application/json", "etag-1", "OBJECT_STORE", SNAPSHOT_DIGEST,
                METADATA_AUTHORITY_DIGEST, NOW.plusMinutes(10));
    }

    private VerifiedCleanScan scan(SignedScanRequest request) {
        return new VerifiedCleanScan(
                request.tenantId(), request.scannerKey(), request.scannerVersion(),
                request.scannedAt(), request.objectDigest(), request.byteSize(),
                SCAN_AUTHORITY_DIGEST, NOW.plusMinutes(10));
    }

    private VerifiedEgressPolicy egress(EgressPolicyRequest request) {
        return new VerifiedEgressPolicy(
                true, request.tenantId(), request.subjectPrincipalPublicId(),
                request.recipientPolicyRef(), request.egressPolicyRevision(),
                request.transferKind(), request.classification(), request.purposeCode(),
                request.objectRef(), request.objectVersion(), request.objectDigest(),
                request.byteSize(), request.mediaType(), EGRESS_AUTHORITY_DIGEST,
                NOW.plusMinutes(10));
    }

    private AccessSeal access(boolean mutation, String idempotencyKey) {
        return access(SUBJECT, mutation, idempotencyKey, 7, 9,
                "decision-1", NOW.plusMinutes(10));
    }

    private AccessSeal access(
            UUID subject,
            boolean mutation,
            String idempotencyKey,
            long fieldRevision,
            long authorizationRevision,
            String decisionRevision,
            OffsetDateTime freshUntil) {
        Set<String> groups = mutation
                ? ControlledTransferService.CREATE_FIELD_GROUPS
                : ControlledTransferService.READ_FIELD_GROUPS;
        String route = mutation ? ControlledTransferService.CREATE_ROUTE
                : ControlledTransferService.READ_ROUTE;
        String population = ControlledTransferService.populationScopeDigest(
                1, subject, "CONNECTOR_SET", SCOPE, decisionRevision);
        String authority = ControlledTransferService.authorityDigest(
                1, subject, route, "TENANT", SCOPE, "CONNECTOR_SET",
                population, groups, fieldRevision, authorizationRevision,
                decisionRevision, ControlledTransferService.PURPOSE, freshUntil);
        return new AccessSeal(
                1, subject, population, "CONNECTOR_SET", groups,
                fieldRevision, authorizationRevision, route,
                "TENANT", SCOPE, decisionRevision,
                ControlledTransferService.PURPOSE, authority, freshUntil,
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                idempotencyKey);
    }

    private AccessSeal copyAccess(
            AccessSeal source, String decisionRevision) {
        return access(source.subjectPrincipalPublicId(), true,
                source.idempotencyKey(), source.fieldPolicyRevision() + 1,
                source.authorizationRevision() + 1, decisionRevision,
                source.authorityFreshUntil());
    }

    private FileTransferCommand command(TransferKind kind) {
        UUID recipient = kind == TransferKind.INGEST ? null
                : UUID.fromString("33333333-3333-4333-8333-333333333333");
        Long revision = kind == TransferKind.INGEST ? null : 1L;
        MalwareScanSnapshot scan = new MalwareScanSnapshot(
                "scanner", "1", "CLEAN", NOW.minusMinutes(1), OBJECT_DIGEST, 10);
        ToctouBinding binding = new ToctouBinding(
                UPLOAD, "etag-1", "v1", OBJECT_DIGEST, 10, SNAPSHOT_DIGEST);
        return new FileTransferCommand(
                OwnerModule.SYS, kind, recipient, revision,
                "obj_1234567890abcdef", "v1", OBJECT_DIGEST, 10,
                "application/json", scan, binding, Classification.CONFIDENTIAL,
                ControlledTransferService.PURPOSE, NOW.plusDays(1));
    }

    private static final class CapturingRepository implements ControlledTransferRepository {
        private final List<PreparedTransfer> saved = new ArrayList<>();
        private Receipt receipt;
        private String digest;
        private UUID subject;
        private OwnerModule owner;
        private String key;
        private TransferPage catalogPage = new TransferPage(List.of(), null);

        @Override
        public Optional<Receipt> findReplay(ReplayProbe probe) {
            if (receipt == null || owner != probe.ownerModule()
                    || !key.equals(probe.access().idempotencyKey())) {
                return Optional.empty();
            }
            if (!subject.equals(probe.access().subjectPrincipalPublicId())
                    || !digest.equals(probe.requestDigest())) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                        "CONTROLLED_TRANSFER_IDEMPOTENCY_CONFLICT");
            }
            return Optional.of(receipt);
        }

        @Override
        public Receipt create(PreparedTransfer transfer) {
            saved.add(transfer);
            receipt = new Receipt(
                    UUID.randomUUID(), "CONTROLLED_TRANSFER_CREATE",
                    "CONTROLLED_TRANSFER_CREATE",
                    transfer.access().subjectPrincipalPublicId(),
                    transfer.access().populationScopeDigest(), 7,
                    transfer.access().purposeCode(), 9, ReceiptStatus.ACCEPTED,
                    transfer.access().correlationId(), transfer.requestDigest(),
                    transfer.requestedAt(), 1L,
                    "file-transfer:" + transfer.transferPublicId());
            digest = transfer.requestDigest();
            subject = transfer.access().subjectPrincipalPublicId();
            owner = transfer.ownerModule();
            key = transfer.access().idempotencyKey();
            return receipt;
        }

        @Override
        public TransferPage catalog(
                AccessSeal access,
                OffsetDateTime asOf,
                String filter,
                Cursor cursor,
                int limit) {
            return catalogPage;
        }
    }
}
