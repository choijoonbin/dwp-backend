package com.dwp.services.messaging.attachment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties("dwp.messaging.attachments")
public record AttachmentProperties(
        String storage,
        Path localRoot,
        String scanner,
        Duration uploadTtl,
        Duration downloadTtl,
        int maximumTransferMb,
        int maximumConcurrentTransfers,
        String clamavHost,
        int clamavPort,
        Duration clamavTimeout) {

    public AttachmentProperties {
        storage = value(storage, "local");
        localRoot = localRoot == null ? Path.of(".dwp-data/messaging-attachments") : localRoot;
        scanner = value(scanner, "local");
        uploadTtl = duration(uploadTtl, Duration.ofMinutes(10));
        downloadTtl = duration(downloadTtl, Duration.ofMinutes(1));
        maximumTransferMb = positive(maximumTransferMb, 100);
        maximumConcurrentTransfers = positive(maximumConcurrentTransfers, 2);
        clamavHost = value(clamavHost, "localhost");
        clamavPort = clamavPort <= 0 ? 3310 : clamavPort;
        clamavTimeout = duration(clamavTimeout, Duration.ofSeconds(10));
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Duration duration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }
}
