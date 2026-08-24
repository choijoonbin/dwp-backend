package com.dwp.services.approval.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public final class ApprovalRecoveryAuditorProductionReadiness
        implements ApplicationRunner {

    private static final String EXPECTED_SERVICE_IDENTITY = "dwp-approval-server";

    private final String environment;
    private final boolean governedRecoveryRequired;
    private final boolean enabled;
    private final String authUrl;
    private final String token;
    private final int maximumAttempts;
    private final long probeCooldownSeconds;
    private final long maximumProbeCooldownSeconds;

    public ApprovalRecoveryAuditorProductionReadiness(
            @Value("${dwp.environment:${DWP_ENVIRONMENT:local}}") String environment,
            @Value("${dwp.approval.product-authorization-v2-enabled:false}")
                    boolean governedRecoveryRequired,
            @Value("${dwp.approval.recovery-auditor-assignment.enabled:false}")
                    boolean enabled,
            @Value("${dwp.approval.recovery-auditor-assignment.auth-url:}") String authUrl,
            @Value("${dwp.approval.recovery-auditor-assignment.token:}") String token,
            @Value("${dwp.approval.recovery-auditor-assignment.maximum-attempts:10}")
                    int maximumAttempts,
            @Value("${dwp.approval.recovery-auditor-assignment.probe-cooldown-seconds:86400}")
                    long probeCooldownSeconds,
            @Value("${dwp.approval.recovery-auditor-assignment."
                    + "maximum-probe-cooldown-seconds:604800}")
                    long maximumProbeCooldownSeconds) {
        this.environment = environment == null ? "local" : environment;
        this.governedRecoveryRequired = governedRecoveryRequired;
        this.enabled = enabled;
        this.authUrl = authUrl == null ? "" : authUrl;
        this.token = token == null ? "" : token;
        this.maximumAttempts = maximumAttempts;
        this.probeCooldownSeconds = probeCooldownSeconds;
        this.maximumProbeCooldownSeconds = maximumProbeCooldownSeconds;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!production() || !governedRecoveryRequired) return;
        List<String> failures = new ArrayList<>();
        if (!enabled) {
            failures.add("recovery auditor assignment must be enabled for governed recovery");
        }
        requireProductionAuthUrl(failures);
        requireDedicatedToken(failures);
        if (maximumAttempts < 1 || maximumAttempts > 100) {
            failures.add("recovery auditor maximum attempts must be between 1 and 100");
        }
        if (probeCooldownSeconds < 3600 || probeCooldownSeconds > 604800) {
            failures.add("recovery auditor probe cooldown must be between 3600 and 604800 seconds");
        }
        if (maximumProbeCooldownSeconds < probeCooldownSeconds
                || maximumProbeCooldownSeconds > 2592000) {
            failures.add("recovery auditor maximum probe cooldown must be between the initial "
                    + "cooldown and 2592000 seconds");
        }
        if (!EXPECTED_SERVICE_IDENTITY.equals(
                AuthApprovalRecoveryAuditorResolver.SERVICE_IDENTITY)) {
            failures.add("recovery auditor service identity must be fixed to "
                    + EXPECTED_SERVICE_IDENTITY);
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Approval recovery auditor production readiness failed: "
                            + String.join(", ", failures));
        }
    }

    private boolean production() {
        String normalized = environment.strip().toLowerCase(Locale.ROOT);
        return normalized.equals("prod") || normalized.equals("production");
    }

    private void requireProductionAuthUrl(List<String> failures) {
        try {
            URI uri = URI.create(authUrl.strip());
            String host = uri.getHost() == null
                    ? ""
                    : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            boolean fixtureHost = host.isBlank()
                    || host.equals("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("::1")
                    || host.endsWith(".localhost")
                    || host.endsWith(".test")
                    || host.endsWith(".invalid")
                    || host.contains("fixture")
                    || host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}");
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || fixtureHost
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !(path == null || path.isBlank() || path.equals("/"))) {
                failures.add("recovery auditor Auth URL must be a non-fixture HTTPS origin");
            }
        } catch (IllegalArgumentException exception) {
            failures.add("recovery auditor Auth URL must be a non-fixture HTTPS origin");
        }
    }

    private void requireDedicatedToken(List<String> failures) {
        String value = token.strip();
        String normalized = value.toLowerCase(Locale.ROOT);
        boolean placeholder = normalized.contains("placeholder")
                || normalized.contains("change-me")
                || normalized.contains("changeme")
                || normalized.contains("replace-me")
                || normalized.contains("dummy")
                || normalized.contains("fixture")
                || normalized.contains("example")
                || normalized.startsWith("test-")
                || normalized.startsWith("dev-")
                || normalized.startsWith("local-")
                || normalized.equals("recovery-secret");
        boolean invalidCharacter = value.chars().anyMatch(Character::isWhitespace)
                || value.chars().anyMatch(Character::isISOControl);
        if (value.length() < 32 || value.length() > 512
                || value.chars().distinct().count() < 8
                || invalidCharacter || placeholder || !value.equals(token)) {
            failures.add(
                    "recovery auditor token must be a strong non-placeholder dedicated secret");
        }
    }
}
