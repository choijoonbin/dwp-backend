package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferDtos.*;
import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferRepository.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlledTransferApiContractTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-16T00:00:00Z");
    private static final List<String> FIELDS = List.of(
            "publicId", "key", "type", "state", "version", "updatedAt");
    private static final String SCOPE =
            "hcm-scope-3b21f50dc0bc6974f09f6bcb2b987f677ce5b7a5";
    private ControlledTransferController controller;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        ControlledTransferRepository repository = new ControlledTransferRepository() {
            @Override
            public Optional<Receipt> findReplay(ReplayProbe probe) {
                return Optional.empty();
            }

            @Override
            public Receipt create(PreparedTransfer transfer) {
                throw new AssertionError("not used");
            }

            @Override
            public TransferPage catalog(
                    AccessSeal access,
                    OffsetDateTime asOf, String filter, Cursor cursor, int limit) {
                return new TransferPage(List.of(), null);
            }
        };
        ControlledTransferAuthorityPorts.TrustedObjectMetadataAuthority unavailable =
                request -> { throw new AssertionError("not used"); };
        ControlledTransferAuthorityPorts.SignedMalwareScanAuthority scan =
                request -> { throw new AssertionError("not used"); };
        ControlledTransferAuthorityPorts.EgressPolicyAuthority egress =
                request -> { throw new AssertionError("not used"); };
        controller = new ControlledTransferController(new ControlledTransferService(
                repository, unavailable, scan, egress, json,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
                "cursor-secret-for-sys009-is-at-least-32-bytes",
                Duration.ofMinutes(5)), json);
    }

    @Test
    void exactReadContractAcceptsOnlyTrustedClosedEvidence() {
        MockHttpServletRequest request = readRequest();

        var response = controller.catalog(
                request, NOW, FIELDS, Freshness.FRESH_ONLY, null, null, 50);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().freshUntil()).isEqualTo(NOW.plusMinutes(10));
        assertThat(response.getBody().staleAfter()).isEqualTo(NOW.plusMinutes(10));
    }

    @Test
    void queryIsClosedAndRejectsUnknownOrDuplicateScalars() {
        MockHttpServletRequest unknown = readRequest();
        unknown.addParameter("tenantId", "99");
        assertThatThrownBy(() -> controller.catalog(
                unknown, NOW, FIELDS, Freshness.FRESH_ONLY, null, null, 50))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        MockHttpServletRequest duplicate = readRequest();
        duplicate.addParameter("freshness", "ALLOW_STALE");
        assertThatThrownBy(() -> controller.catalog(
                duplicate, NOW, FIELDS, Freshness.FRESH_ONLY, null, null, 50))
                .isInstanceOf(BaseException.class);

        MockHttpServletRequest post = new MockHttpServletRequest(
                "POST", "/v1/admin/hris/file-transfers");
        post.addParameter("debug", "true");
        assertThatThrownBy(() -> controller.create(post))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void callerSuppliedPopulationDigestIsIgnoredAndPopulationKindIsClosed() {
        MockHttpServletRequest request = readRequest();
        request.addHeader("X-DWP-Population-Scope-Digest", "0".repeat(64));
        var response = controller.catalog(
                request, NOW, FIELDS, Freshness.FRESH_ONLY, null, null, 50);
        assertThat(response.getStatusCode().value()).isEqualTo(200);

        MockHttpServletRequest invalid = readRequest();
        invalid.removeHeader("X-DWP-Population-Kind");
        invalid.addHeader("X-DWP-Population-Kind", "TENANT");
        assertThatThrownBy(() -> controller.catalog(
                invalid, NOW, FIELDS, Freshness.FRESH_ONLY, null, null, 50))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void projectionEvidenceIsClosedAndCannotOmitRequiredMetadata() {
        MockHttpServletRequest redacted = readRequest();
        redacted.addHeader("X-DWP-Omitted-Field-Groups", "REDACTED_PAYLOAD");
        assertThat(controller.catalog(
                redacted, NOW, FIELDS, Freshness.FRESH_ONLY, null, null, 50)
                .getStatusCode().value()).isEqualTo(200);

        MockHttpServletRequest missingMetadata = readRequest();
        missingMetadata.addHeader(
                "X-DWP-Omitted-Field-Groups", "TRANSFER_METADATA");
        assertThatThrownBy(() -> controller.catalog(
                missingMetadata, NOW, FIELDS, Freshness.FRESH_ONLY,
                null, null, 50))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        MockHttpServletRequest openGroup = readRequest();
        openGroup.addHeader("X-DWP-Masked-Field-Groups", "RAW_OBJECT_BYTES");
        assertThatThrownBy(() -> controller.catalog(
                openGroup, NOW, FIELDS, Freshness.FRESH_ONLY,
                null, null, 50))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void commandAndAuthorityResponsesRejectUnknownPropertiesWithLenientMapper() {
        assertThatThrownBy(() -> json.readValue(validCommandJson()
                        .replace("\"expiresAt\"", "\"clientTenantId\":99,\"expiresAt\""),
                FileTransferCommand.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown FileTransferCommand property");

        assertThatThrownBy(() -> json.readValue("""
                        {"tenantId":1,"subjectPrincipalPublicId":"11111111-1111-4111-8111-111111111111",
                         "objectRef":"obj_1234567890abcdef","uploadSessionId":"44444444-4444-4444-8444-444444444444",
                         "objectVersion":"v1","objectDigest":"%s","byteSize":10,
                         "mediaType":"application/json","storageETag":"etag",
                         "metadataAuthority":"OBJECT_STORE","metadataSnapshotDigest":"%s",
                         "authorityDigest":"%s","freshUntil":"2026-10-16T00:10:00Z",
                         "signedUrl":"forbidden"}
                        """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64)),
                ControlledTransferAuthorityPorts.TrustedObjectMetadata.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown trusted object metadata property");
    }

    @Test
    void commandBodyRejectsDuplicateSecurityFieldsAndTrailingRoots() {
        String duplicatePurpose = validCommandJson().replace(
                "\"purposeCode\":\"HRIS_INTEGRATION_ADMIN\"",
                "\"purposeCode\":\"WRONG\",\"purposeCode\":\"HRIS_INTEGRATION_ADMIN\"");
        assertThatThrownBy(() -> controller.create(postRequest(
                "0123456789abcdef", duplicatePurpose)))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        assertThatThrownBy(() -> controller.create(postRequest(
                "0123456789abcdef", validCommandJson() + " {}")))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        assertThatThrownBy(() -> controller.create(
                postRequest("short", validCommandJson())))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void commandRequiresExactlyOneBoundedStepUpChallenge() {
        MockHttpServletRequest missing = postRequest(
                "0123456789abcdef", validCommandJson());
        missing.removeHeader("X-DWP-Step-Up-Challenge");
        assertThatThrownBy(() -> controller.create(missing))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        MockHttpServletRequest duplicate = postRequest(
                "0123456789abcdef", validCommandJson());
        duplicate.addHeader("X-DWP-Step-Up-Challenge", "second-proof");
        assertThatThrownBy(() -> controller.create(duplicate))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        MockHttpServletRequest oversized = postRequest(
                "0123456789abcdef", validCommandJson());
        oversized.removeHeader("X-DWP-Step-Up-Challenge");
        oversized.addHeader("X-DWP-Step-Up-Challenge", "x".repeat(16_385));
        assertThatThrownBy(() -> controller.create(oversized))
                .isInstanceOfSatisfying(BaseException.class, error ->
                        assertThat(error.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void commandBodyIsBoundedForDeclaredAndChunkedIngress() {
        MockHttpServletRequest declared = postRequest("0123456789abcdef");
        declared.setContent("x".repeat(65_537).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> controller.create(declared))
                .isInstanceOfSatisfying(BaseException.class, error -> {
                    assertThat(error.getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
                    assertThat(error).hasMessageContaining("bounded");
                });

        MockHttpServletRequest chunked = new MockHttpServletRequest(
                "POST", "/v1/admin/hris/file-transfers") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        addPostHeaders(chunked, "0123456789abcdef");
        chunked.addHeader("Transfer-Encoding", "chunked");
        chunked.setContent("x".repeat(65_537).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> controller.create(chunked))
                .isInstanceOfSatisfying(BaseException.class, error -> {
                    assertThat(error.getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
                    assertThat(error).hasMessageContaining("bounded");
                });
    }

    private MockHttpServletRequest readRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/admin/hris/file-transfers");
        request.setAttribute(HcmPlatformPepRegistry.class.getName() + ".authority",
                new HcmPlatformPepRegistry.RouteAuthority(
                        ControlledTransferService.READ_ROUTE, "DATA", "GET",
                        "/v1/admin/hris/file-transfers", "controlled-transfer-reader",
                        false, "hcm.integration.transfer.view", null, null));
        request.addHeader("X-DWP-Tenant-ID", "1");
        request.addHeader("X-DWP-Subject-Principal-Public-ID",
                "11111111-1111-4111-8111-111111111111");
        request.addHeader("X-DWP-Population-Kind", "CONNECTOR_SET");
        request.addHeader("X-DWP-Field-Policy-Revision", "7");
        request.addHeader("X-DWP-Authorization-Revision", "9");
        request.addHeader("X-DWP-Context-Key", "TENANT");
        request.addHeader("X-DWP-Context-Scope-Key", SCOPE);
        request.addHeader("X-DWP-Current-Decision-Revision", "decision-1");
        request.addHeader("X-DWP-Current-Revalidate-At",
                NOW.plusMinutes(10).toString());
        request.addHeader("X-Purpose-Code", ControlledTransferService.PURPOSE);
        request.addHeader("X-Correlation-ID",
                "22222222-2222-4222-8222-222222222222");
        request.addParameter("asOf", NOW.toString());
        request.addParameter("fields", FIELDS.toArray(String[]::new));
        request.addParameter("freshness", "FRESH_ONLY");
        request.addParameter("limit", "50");
        return request;
    }

    private MockHttpServletRequest postRequest(String idempotencyKey) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/v1/admin/hris/file-transfers");
        addPostHeaders(request, idempotencyKey);
        return request;
    }

    private MockHttpServletRequest postRequest(String idempotencyKey, String body) {
        MockHttpServletRequest request = postRequest(idempotencyKey);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private void addPostHeaders(
            MockHttpServletRequest request, String idempotencyKey) {
        request.setAttribute(HcmPlatformPepRegistry.class.getName() + ".authority",
                new HcmPlatformPepRegistry.RouteAuthority(
                        ControlledTransferService.CREATE_ROUTE, "ACTION", "POST",
                        "/v1/admin/hris/file-transfers", "controlled-transfer-creator",
                        true, "hcm.integration.transfer.create", "STEPUP-MGMT-HIGH-V1",
                        null));
        request.addHeader("X-DWP-Tenant-ID", "1");
        request.addHeader("X-DWP-Subject-Principal-Public-ID",
                "11111111-1111-4111-8111-111111111111");
        request.addHeader("X-DWP-Population-Kind", "CONNECTOR_SET");
        request.addHeader("X-DWP-Field-Policy-Revision", "7");
        request.addHeader("X-DWP-Authorization-Revision", "9");
        request.addHeader("X-DWP-Context-Key", "TENANT");
        request.addHeader("X-DWP-Context-Scope-Key", SCOPE);
        request.addHeader("X-DWP-Current-Decision-Revision", "decision-1");
        request.addHeader("X-DWP-Current-Revalidate-At",
                NOW.plusMinutes(10).toString());
        request.addHeader("X-Purpose-Code", ControlledTransferService.PURPOSE);
        request.addHeader("X-Correlation-ID",
                "22222222-2222-4222-8222-222222222222");
        request.addHeader("Idempotency-Key", idempotencyKey);
        request.addHeader("X-DWP-Step-Up-Challenge", "step-up-proof");
    }

    private String validCommandJson() {
        return """
                {
                  "ownerModule":"SYS","transferKind":"INGEST",
                  "objectRef":"obj_1234567890abcdef","objectVersion":"v1",
                  "objectDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "byteSize":10,"mediaType":"application/json",
                  "malwareScanSnapshot":{"scannerKey":"scanner","scannerVersion":"1",
                    "verdict":"CLEAN","scannedAt":"2026-10-15T23:59:00Z",
                    "objectDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "byteSize":10},
                  "toctouBinding":{"uploadSessionId":"44444444-4444-4444-8444-444444444444",
                    "storageETag":"etag","boundObjectVersion":"v1",
                    "boundDigest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "boundByteSize":10,
                    "snapshotDigest":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"},
                  "classification":"CONFIDENTIAL","purposeCode":"HRIS_INTEGRATION_ADMIN",
                  "expiresAt":"2026-10-17T00:00:00Z"
                }
                """;
    }
}
