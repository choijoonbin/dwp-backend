package com.dwp.services.platform.mail;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Component
class MailAttachmentSecurityReadiness {

    private final List<MailAttachmentScanner> scanners;
    private final boolean attachmentFeatureEnabled;
    private final String environment;

    MailAttachmentSecurityReadiness(
            List<MailAttachmentScanner> scanners,
            @Value("${dwp.platform.mail.attachments.enabled:false}")
            boolean attachmentFeatureEnabled,
            @Value("${DWP_ENVIRONMENT:local}") String environment) {
        this.scanners = scanners == null ? List.of() : List.copyOf(scanners);
        this.attachmentFeatureEnabled = attachmentFeatureEnabled;
        this.environment = environment == null ? "local" : environment;
    }

    @PostConstruct
    void validateProduction() {
        String normalized = environment.trim().toLowerCase(Locale.ROOT);
        if (("prod".equals(normalized) || "production".equals(normalized))
                && attachmentFeatureEnabled && scanners.isEmpty()) {
            throw new IllegalStateException(
                    "Production Mail attachments require a trusted scanner owner.");
        }
    }

    Snapshot snapshot() {
        if (!attachmentFeatureEnabled) {
            return new Snapshot(false, false, "DISABLED", Instant.now(),
                    "MAIL_ATTACHMENT_FEATURE_DISABLED");
        }
        if (scanners.isEmpty()) {
            return new Snapshot(true, false, "UNCONFIGURED", Instant.now(),
                    "TRUSTED_ATTACHMENT_SCANNER_UNAVAILABLE");
        }
        MailAttachmentScanner.Readiness latest = null;
        for (MailAttachmentScanner scanner : scanners) {
            MailAttachmentScanner.Readiness readiness;
            try {
                readiness = scanner.readiness();
            } catch (RuntimeException failure) {
                return new Snapshot(true, false, "SCANNER_OWNER", Instant.now(),
                        "SCANNER_READINESS_UNAVAILABLE");
            }
            if (readiness == null || !readiness.ready()) {
                return new Snapshot(true, false,
                        readiness == null ? "SCANNER_OWNER" : readiness.owner(),
                        readiness == null ? Instant.now() : readiness.checkedAt(),
                        readiness == null || readiness.errorCode() == null
                                ? "SCANNER_READINESS_UNAVAILABLE"
                                : readiness.errorCode());
            }
            latest = readiness;
        }
        return new Snapshot(true, true, latest.owner(), latest.checkedAt(), null);
    }

    record Snapshot(
            boolean enabled,
            boolean ready,
            String owner,
            Instant checkedAt,
            String errorCode) { }
}
