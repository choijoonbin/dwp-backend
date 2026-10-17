package com.dwp.services.people.hris.employeeservice.certificate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CertificateArtifactPortConformanceTest {

    private static final Instant AS_OF = Instant.parse("2026-10-18T03:00:00Z");
    private static final CertificateDtos.ArtifactRequest REQUEST =
            new CertificateDtos.ArtifactRequest(
                    UUID.fromString("11111111-1111-4111-8111-111111111111"),
                    UUID.fromString("22222222-2222-4222-8222-222222222222"),
                    17L, "a".repeat(64), "employment-letter", "en-US", AS_OF,
                    UUID.fromString("33333333-3333-4333-8333-333333333333"), 4,
                    UUID.fromString("44444444-4444-4444-8444-444444444444"), 2);

    @Test
    void productionBindingIsTheSingleFailClosedAdapterAndNeverReturnsSuccess() {
        CertificateArtifactPort adapter = new FailClosedCertificateArtifactAdapter();

        assertThat(FailClosedCertificateArtifactAdapter.class
                .getAnnotation(Component.class)).isNotNull();
        assertThatThrownBy(() -> adapter.issue(REQUEST))
                .isInstanceOf(CertificateException.class)
                .satisfies(failure -> {
                    CertificateException unavailable = (CertificateException) failure;
                    assertThat(unavailable.status())
                            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(unavailable.retryable()).isTrue();
                });
        assertThat(CertificateArtifactPort.class.getDeclaredMethods())
                .singleElement()
                .extracting(java.lang.reflect.Method::getName)
                .isEqualTo("issue");
    }

    @Test
    void testOnlyConformanceDoubleReturnsDeterministicImmutableMetadataWithoutBytes() {
        CertificateArtifactPort port = new ConformanceDouble(false);

        CertificateDtos.ArtifactResult first = port.issue(REQUEST);
        CertificateDtos.ArtifactResult second = port.issue(REQUEST);
        CertificateDtos.ArtifactRequest changedAsOf = new CertificateDtos.ArtifactRequest(
                REQUEST.requestPublicId(), REQUEST.subjectPersonPublicId(),
                REQUEST.subjectExpectedVersion(), REQUEST.subjectSnapshotDigest(),
                REQUEST.certificateType(), REQUEST.locale(), AS_OF.plusSeconds(1),
                REQUEST.templatePublicId(), REQUEST.templateVersion(),
                REQUEST.sealPublicId(), REQUEST.sealVersion());

        assertThat(first).isEqualTo(second);
        assertThat(port.issue(changedAsOf).contentSha256())
                .isNotEqualTo(first.contentSha256());
        assertThat(first.objectVersion()).isEqualTo(1);
        assertThat(first.contentSha256()).matches("[0-9a-f]{64}");
        assertThat(first.verificationTokenHash()).matches("[0-9a-f]{64}");
        assertThat(first.issuedAt()).isEqualTo(AS_OF);
        assertThat(first.expiresAt()).isAfter(first.issuedAt());
        assertThat(CertificateDtos.ArtifactRequest.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "requestPublicId", "subjectPersonPublicId",
                        "subjectExpectedVersion", "subjectSnapshotDigest",
                        "certificateType", "locale", "asOf",
                        "templatePublicId", "templateVersion",
                        "sealPublicId", "sealVersion");
        assertThat(CertificateDtos.ArtifactResult.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "objectPublicId", "objectVersion", "contentSha256",
                        "verificationTokenHash", "issuedAt", "expiresAt")
                .noneMatch(name -> name.toLowerCase().contains("byte")
                        || name.toLowerCase().contains("contenturl")
                        || name.toLowerCase().contains("download"));
    }

    @Test
    void testOnlyConformanceDoubleCanModelRetryableProviderUnavailability() {
        CertificateArtifactPort port = new ConformanceDouble(true);

        assertThatThrownBy(() -> port.issue(REQUEST))
                .isInstanceOf(CertificateException.class)
                .satisfies(failure ->
                        assertThat(((CertificateException) failure).retryable()).isTrue());
    }

    /** Test-only accepted-port behavior; deliberately absent from main sources. */
    static final class ConformanceDouble implements CertificateArtifactPort {
        private final boolean unavailable;

        ConformanceDouble(boolean unavailable) {
            this.unavailable = unavailable;
        }

        @Override
        public CertificateDtos.ArtifactResult issue(
                CertificateDtos.ArtifactRequest request) {
            if (unavailable) {
                throw CertificateException.unavailable(
                        "Synthetic artifact provider is unavailable.");
            }
            String canonical = request.requestPublicId() + "\n"
                    + request.subjectPersonPublicId() + "\n"
                    + request.subjectExpectedVersion() + "\n"
                    + request.subjectSnapshotDigest() + "\n"
                    + request.certificateType() + "\n" + request.locale() + "\n"
                    + request.asOf() + "\n"
                    + request.templatePublicId() + "\n" + request.templateVersion() + "\n"
                    + request.sealPublicId() + "\n" + request.sealVersion();
            byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
            String content = digest(bytes);
            String verification = digest(("verify\n" + canonical)
                    .getBytes(StandardCharsets.UTF_8));
            return new CertificateDtos.ArtifactResult(
                    UUID.nameUUIDFromBytes(bytes), 1, content, verification,
                    request.asOf(), request.asOf().plusSeconds(31_536_000));
        }

        private String digest(byte[] value) {
            try {
                return HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256")
                                .digest(value));
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
