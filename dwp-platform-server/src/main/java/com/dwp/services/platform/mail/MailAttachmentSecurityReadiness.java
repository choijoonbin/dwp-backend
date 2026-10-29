package com.dwp.services.platform.mail;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
class MailAttachmentSecurityReadiness {

    private static final Set<String> NON_PUBLIC_DNS_SUFFIXES = Set.of(
            "local", "localhost", "test", "internal", "corp", "mail", "lan", "home",
            "arpa", "invalid", "example", "localdomain", "onion", "alt", "svc");

    private final List<MailAttachmentScanner> scanners;
    private final boolean attachmentFeatureEnabled;
    private final Environment environment;

    MailAttachmentSecurityReadiness(
            List<MailAttachmentScanner> scanners,
            @Value("${dwp.platform.mail.attachments.enabled:false}")
            boolean attachmentFeatureEnabled,
            Environment environment) {
        this.scanners = scanners == null ? List.of() : List.copyOf(scanners);
        this.attachmentFeatureEnabled = attachmentFeatureEnabled;
        this.environment = environment;
    }

    @PostConstruct
    void validateProduction() {
        if (!production()) return;
        if (attachmentFeatureEnabled && scanners.isEmpty()) {
            throw new IllegalStateException(
                    "Production Mail attachments require a trusted scanner owner.");
        }
        if (scanners.isEmpty()) return;
        if (!productionScannerEndpoint(environment.getProperty(
                "dwp.platform.mail.attachments.trusted-scanner.base-url", ""))) {
            throw new IllegalStateException(
                    "Production Mail attachments require a non-local HTTPS scanner endpoint.");
        }
        if (!productionScannerSecret(environment.getProperty(
                "dwp.platform.mail.attachments.trusted-scanner.service-token", ""))) {
            throw new IllegalStateException(
                    "Production Mail attachments require a strong non-placeholder scanner token.");
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

    private boolean production() {
        return productionValue(environment.getProperty("dwp.environment"))
                || productionValue(environment.getProperty("DWP_ENVIRONMENT"));
    }

    private static boolean productionValue(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return "prod".equals(normalized) || "production".equals(normalized);
    }

    private static boolean productionScannerEndpoint(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!value.equals(raw)) return false;
        try {
            URI uri = URI.create(value);
            String path = uri.getRawPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && productionDnsHost(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && (path == null || path.isEmpty() || "/".equals(path));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean productionDnsHost(String raw) {
        if (raw == null) return false;
        String host = raw.toLowerCase(Locale.ROOT);
        if (NON_PUBLIC_DNS_SUFFIXES.stream().anyMatch(suffix ->
                host.equals(suffix) || host.endsWith("." + suffix))) {
            return false;
        }
        return !documentationDomain(host) && publicDnsName(host);
    }

    private static boolean documentationDomain(String host) {
        return host.equals("example.com")
                || host.endsWith(".example.com")
                || host.equals("example.net")
                || host.endsWith(".example.net")
                || host.equals("example.org")
                || host.endsWith(".example.org");
    }

    private static boolean publicDnsName(String host) {
        if (host.contains(":")) return false;
        String[] labels = host.split("\\.", -1);
        if (labels.length < 2) return false;
        for (String label : labels) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                return false;
            }
        }
        String suffix = labels[labels.length - 1];
        return suffix.matches("[a-z]{2,63}")
                || suffix.matches("xn--[a-z0-9](?:[a-z0-9-]{0,57}[a-z0-9])");
    }

    private static boolean productionScannerSecret(String raw) {
        if (raw == null) return false;
        String normalized = raw.toLowerCase(Locale.ROOT);
        boolean placeholder = normalized.contains("placeholder")
                || normalized.contains("change-me")
                || normalized.contains("changeme")
                || normalized.contains("changeit")
                || normalized.contains("replace-me")
                || normalized.contains("dummy")
                || normalized.contains("fixture")
                || normalized.contains("example")
                || normalized.startsWith("test-")
                || normalized.startsWith("dev-")
                || normalized.startsWith("local-")
                || normalized.contains("${");
        boolean invalidCharacter = raw.chars().anyMatch(character ->
                character < 0x21 || character > 0x7e);
        return raw.length() >= 32 && raw.length() <= 512
                && raw.chars().distinct().count() >= 8
                && !invalidCharacter && !placeholder;
    }

    record Snapshot(
            boolean enabled,
            boolean ready,
            String owner,
            Instant checkedAt,
            String errorCode) { }
}
