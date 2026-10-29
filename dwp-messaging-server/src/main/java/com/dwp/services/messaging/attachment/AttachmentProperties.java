package com.dwp.services.messaging.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties("dwp.messaging.attachments")
public record AttachmentProperties(
        @DefaultValue("local") String storage,
        @DefaultValue(".dwp-data/messaging-attachments") Path localRoot,
        @DefaultValue("local") String scanner,
        @DefaultValue("PT10M") Duration uploadTtl,
        @DefaultValue("PT1M") Duration downloadTtl,
        @DefaultValue("100") int maximumTransferMb,
        @DefaultValue("2") int maximumConcurrentTransfers,
        @DefaultValue("localhost") String clamavHost,
        @DefaultValue("3310") int clamavPort,
        @DefaultValue("PT10S") Duration clamavTimeout) {

    private static final Set<String> STORAGE_PROVIDERS = Set.of("local", "s3");
    private static final Set<String> SCANNER_PROVIDERS = Set.of("local", "clamav");

    public AttachmentProperties {
        storage = provider(storage, STORAGE_PROVIDERS, "storage");
        if (localRoot == null) {
            throw new IllegalArgumentException("Messaging attachment local root is required.");
        }
        scanner = provider(scanner, SCANNER_PROVIDERS, "scanner");
        requireDuration(uploadTtl, Duration.ofMinutes(1), Duration.ofHours(24), "upload TTL");
        requireDuration(downloadTtl, Duration.ofSeconds(1), Duration.ofMinutes(15), "download TTL");
        requireRange(maximumTransferMb, 1, 1_024, "maximum transfer MB");
        requireRange(maximumConcurrentTransfers, 1, 100, "maximum concurrent transfers");
        if (clamavHost == null || clamavHost.isBlank()) {
            throw new IllegalArgumentException("Messaging ClamAV host is required.");
        }
        clamavHost = clamavHost.strip();
        requireRange(clamavPort, 1, 65_535, "ClamAV port");
        requireDuration(clamavTimeout, Duration.ofMillis(100), Duration.ofMinutes(1),
                "ClamAV timeout");
    }

    private static String provider(String value, Set<String> allowed, String name) {
        if (value == null || !value.equals(value.strip()) || !allowed.contains(value)) {
            throw new IllegalArgumentException(
                    "Unsupported messaging attachment " + name + ": " + value);
        }
        return value;
    }

    private static void requireDuration(
            Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    "Messaging attachment " + name + " must be between "
                            + minimum + " and " + maximum + '.');
        }
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "Messaging attachment " + name + " must be "
                            + minimum + " to " + maximum + '.');
        }
    }
}
