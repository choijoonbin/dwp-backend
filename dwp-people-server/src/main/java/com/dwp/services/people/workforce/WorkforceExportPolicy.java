package com.dwp.services.people.workforce;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class WorkforceExportPolicy {

    private final boolean executionEnabled;
    private final String maskingProfile;
    private final String watermarkTemplate;
    private final int artifactTtlHours;
    private final int maximumAttempts;
    private final int maximumManualRetries;
    private final List<String> blockers;

    public WorkforceExportPolicy(
            @Value("${dwp.people.exports.execution-enabled:false}") boolean executionEnabled,
            @Value("${dwp.people.exports.masking-profile:WORKFORCE_MINIMUM}") String maskingProfile,
            @Value("${dwp.people.exports.watermark-template:DWP confidential | tenant={{tenantId}} | requester={{userId}} | recipient={{recipient}} | request={{requestId}}}")
                    String watermarkTemplate,
            @Value("${dwp.people.exports.artifact-ttl-hours:24}") int artifactTtlHours,
            @Value("${dwp.people.exports.maximum-attempts:5}") int maximumAttempts,
            @Value("${dwp.people.exports.maximum-manual-retries:1}") int maximumManualRetries,
            @Value("${dwp.people.exports.blockers:D-09,D-12}") String blockers) {
        this.executionEnabled = executionEnabled;
        this.maskingProfile = required(maskingProfile, "masking profile");
        this.watermarkTemplate = required(watermarkTemplate, "watermark template");
        this.artifactTtlHours = Math.min(168, Math.max(1, artifactTtlHours));
        this.maximumAttempts = Math.min(20, Math.max(1, maximumAttempts));
        this.maximumManualRetries = Math.min(3, Math.max(0, maximumManualRetries));
        this.blockers = Arrays.stream(blockers == null ? new String[0] : blockers.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (executionEnabled && !this.blockers.isEmpty()) {
            throw new IllegalStateException(
                    "Workforce export execution cannot be enabled while release blockers remain.");
        }
    }

    public boolean executionEnabled() {
        return executionEnabled;
    }

    public String maskingProfile() {
        return maskingProfile;
    }

    public String watermarkTemplate() {
        return watermarkTemplate;
    }

    public int artifactTtlHours() {
        return artifactTtlHours;
    }

    public int maximumAttempts() {
        return maximumAttempts;
    }

    public int maximumManualRetries() {
        return maximumManualRetries;
    }

    public List<String> blockers() {
        return blockers;
    }

    public String watermark(Long tenantId, Long userId, String recipient, java.util.UUID requestId) {
        return watermarkTemplate
                .replace("{{tenantId}}", tenantId.toString())
                .replace("{{userId}}", userId.toString())
                .replace("{{recipient}}", recipient)
                .replace("{{requestId}}", requestId.toString());
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Workforce export " + label + " is required.");
        }
        return value.trim();
    }
}
