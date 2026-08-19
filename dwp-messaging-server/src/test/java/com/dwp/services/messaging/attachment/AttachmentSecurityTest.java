package com.dwp.services.messaging.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentSecurityTest {

    @Test
    void acceptsAFileWithinTheTenantPolicy() {
        AttachmentSecurity.ValidatedMetadata metadata = AttachmentSecurity.validate(
                "분기 보고서.pdf", "application/pdf", 1024, 25);

        assertThat(metadata.normalizedFilename()).isEqualTo("분기 보고서.pdf");
        assertThat(metadata.extension()).isEqualTo("pdf");
    }

    @Test
    void rejectsSizeMimePathAndArchiveRisks() {
        assertInvalid(() -> AttachmentSecurity.validate(
                "large.pdf", "application/pdf", 2L * 1024 * 1024, 1));
        assertInvalid(() -> AttachmentSecurity.validate(
                "image.png", "application/pdf", 100, 1));
        assertInvalid(() -> AttachmentSecurity.validate(
                "../secret.pdf", "application/pdf", 100, 1));
        assertInvalid(() -> AttachmentSecurity.validate(
                "bundle.zip", "application/zip", 100, 1));
        assertInvalid(() -> AttachmentSecurity.validate(
                "installer.exe", "application/octet-stream", 100, 1));
    }

    @Test
    void localScannerFailsClosedForExecutableAndMalwareContent() {
        LocalAttachmentScanner scanner = new LocalAttachmentScanner();

        AttachmentScanner.ScanResult executable = scanner.scan(
                new AttachmentScanner.ScanRequest("note.txt", "txt", "text/plain"),
                new byte[] {'M', 'Z', 0, 0});
        AttachmentScanner.ScanResult eicar = scanner.scan(
                new AttachmentScanner.ScanRequest("note.txt", "txt", "text/plain"),
                "EICAR-STANDARD-ANTIVIRUS-TEST-FILE".getBytes(StandardCharsets.US_ASCII));

        assertThat(executable.clean()).isFalse();
        assertThat(executable.reason()).isEqualTo("EXECUTABLE_CONTENT");
        assertThat(eicar.clean()).isFalse();
        assertThat(eicar.reason()).isEqualTo("MALWARE_SIGNATURE");
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }
}
