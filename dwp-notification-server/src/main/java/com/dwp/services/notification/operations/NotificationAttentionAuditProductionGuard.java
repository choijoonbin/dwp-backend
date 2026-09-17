package com.dwp.services.notification.operations;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class NotificationAttentionAuditProductionGuard implements ApplicationRunner {

    static final String RELAY_ROLE = "dwp_notification_attention_audit_relay";

    private final Environment environment;

    public NotificationAttentionAuditProductionGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> failures = violations(environment);
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Notification attention audit production readiness failed: "
                            + String.join("; ", failures));
        }
    }

    static List<String> violations(Environment environment) {
        if (!production(environment)) return List.of();
        List<String> failures = new ArrayList<>();
        if (!environment.getProperty(
                "dwp.notification.attention-audit.enabled", Boolean.class, false)) {
            failures.add("dwp.notification.attention-audit.enabled must be true");
        }
        if (!RELAY_ROLE.equals(environment.getProperty(
                "dwp.notification.attention-audit.relay-database-role", "").trim())) {
            failures.add("attention audit relay database role must be dedicated");
        }
        requireHttps(environment.getProperty("dwp.audit.collector-url", ""), failures);
        String token = environment.getProperty("dwp.audit.ingest-token", "");
        if (!token.equals(token.trim()) || token.length() < 24) {
            failures.add("dwp.audit.ingest-token must be a canonical production secret");
        }
        durationAtLeast(
                environment.getProperty(
                        "dwp.notification.attention-audit.published-retention", "P30D"),
                Duration.ofDays(1), failures);
        int attempts = environment.getProperty(
                "dwp.notification.attention-audit.maximum-attempts", Integer.class, 0);
        if (attempts < 3 || attempts > 100) {
            failures.add("attention audit maximum attempts must be between 3 and 100");
        }
        return List.copyOf(failures);
    }

    private static boolean production(Environment environment) {
        String value = environment.getProperty(
                "dwp.environment", environment.getProperty("DWP_ENVIRONMENT", "local"));
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "prod".equals(normalized) || "production".equals(normalized);
    }

    private static void requireHttps(String value, List<String> failures) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || host == null || host.isBlank()
                    || "localhost".equalsIgnoreCase(host)
                    || host.endsWith(".local") || host.endsWith(".test")) {
                failures.add("dwp.audit.collector-url must be an explicit production HTTPS endpoint");
            }
        } catch (IllegalArgumentException exception) {
            failures.add("dwp.audit.collector-url must be a valid production HTTPS endpoint");
        }
    }

    private static void durationAtLeast(
            String value,
            Duration minimum,
            List<String> failures) {
        try {
            if (Duration.parse(value).compareTo(minimum) < 0) {
                failures.add("attention audit retention must be at least one day");
            }
        } catch (RuntimeException exception) {
            failures.add("attention audit retention must be an ISO-8601 duration");
        }
    }
}
