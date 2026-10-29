package com.dwp.services.platform.mail;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.dwp.services.platform.mail.MailTypes.ProviderType.GOOGLE_GMAIL;
import static com.dwp.services.platform.mail.MailTypes.ProviderType.MICROSOFT_GRAPH;

/**
 * Deployment-level readiness for external Mail providers.
 *
 * <p>Provider consent and credential validity remain connection-scoped and are proven by the
 * durable activation diagnostic. This component only seals prerequisites that can be evaluated
 * without reading or leasing a customer credential.</p>
 */
@Component
class MailExternalProviderReadiness {

    private static final String GRAPH = "MICROSOFT_GRAPH";
    private static final String GMAIL = "GOOGLE_GMAIL";
    private static final String GMAIL_TOPIC =
            "projects/[A-Za-z0-9._-]{1,200}/topics/[A-Za-z0-9._-]{1,200}";
    private static final String GMAIL_SUBSCRIPTION =
            "projects/[A-Za-z0-9._-]{1,200}/subscriptions/[A-Za-z0-9._-]{1,200}";

    private final MailConnectorRegistry connectors;
    private final List<MailProviderTokenOwner> tokenOwners;
    private final List<MailWebhookVerificationPort> webhookVerifiers;
    private final List<GmailPushAuthenticationOwner> gmailPushOwners;
    private final Environment environment;
    private final boolean graphEnabled;
    private final boolean gmailEnabled;
    private final boolean tokenBrokerEnabled;
    private final boolean graphInsecureLocalhost;
    private final boolean gmailInsecureLocalhost;
    private final boolean tokenBrokerInsecureLocalhost;
    private final boolean subscriptionsEnabled;
    private final String callbackBaseUrl;
    private final String gmailTopicName;
    private final String gmailSubscriptionName;
    private final String gmailPushAudience;

    MailExternalProviderReadiness(
            MailConnectorRegistry connectors,
            List<MailProviderTokenOwner> tokenOwners,
            List<MailWebhookVerificationPort> webhookVerifiers,
            List<GmailPushAuthenticationOwner> gmailPushOwners,
            Environment environment,
            @Value("${dwp.platform.mail.providers.microsoft-graph.enabled:false}")
            boolean graphEnabled,
            @Value("${dwp.platform.mail.providers.google-gmail.enabled:false}")
            boolean gmailEnabled,
            @Value("${dwp.platform.mail.token-broker.enabled:false}")
            boolean tokenBrokerEnabled,
            @Value("${dwp.platform.mail.providers.microsoft-graph.allow-insecure-localhost:false}")
            boolean graphInsecureLocalhost,
            @Value("${dwp.platform.mail.providers.google-gmail.allow-insecure-localhost:false}")
            boolean gmailInsecureLocalhost,
            @Value("${dwp.platform.mail.token-broker.allow-insecure-localhost:false}")
            boolean tokenBrokerInsecureLocalhost,
            @Value("${dwp.platform.mail.subscriptions.enabled:false}")
            boolean subscriptionsEnabled,
            @Value("${dwp.platform.mail.subscriptions.callback-base-url:}")
            String callbackBaseUrl,
            @Value("${dwp.platform.mail.providers.google-gmail.topic-name:}")
            String gmailTopicName,
            @Value("${dwp.platform.mail.providers.google-gmail.subscription-name:}")
            String gmailSubscriptionName,
            @Value("${dwp.platform.mail.providers.google-gmail.push-audience:}")
            String gmailPushAudience) {
        this.connectors = connectors;
        this.tokenOwners = tokenOwners == null ? List.of() : List.copyOf(tokenOwners);
        this.webhookVerifiers = webhookVerifiers == null
                ? List.of() : List.copyOf(webhookVerifiers);
        this.gmailPushOwners = gmailPushOwners == null
                ? List.of() : List.copyOf(gmailPushOwners);
        this.environment = environment;
        this.graphEnabled = graphEnabled;
        this.gmailEnabled = gmailEnabled;
        this.tokenBrokerEnabled = tokenBrokerEnabled;
        this.graphInsecureLocalhost = graphInsecureLocalhost;
        this.gmailInsecureLocalhost = gmailInsecureLocalhost;
        this.tokenBrokerInsecureLocalhost = tokenBrokerInsecureLocalhost;
        this.subscriptionsEnabled = subscriptionsEnabled;
        this.callbackBaseUrl = trimmed(callbackBaseUrl);
        this.gmailTopicName = trimmed(gmailTopicName);
        this.gmailSubscriptionName = trimmed(gmailSubscriptionName);
        this.gmailPushAudience = trimmed(gmailPushAudience);
    }

    @PostConstruct
    void validateProduction() {
        Snapshot snapshot = snapshot();
        if (production() && snapshot.state() == State.BLOCKED_EXTERNAL) {
            throw new IllegalStateException(
                    "Production Mail external provider readiness is blocked: "
                            + String.join(",", snapshot.errorCodes()));
        }
    }

    Snapshot snapshot() {
        Set<String> providers = enabledProviders();
        if (providers.isEmpty() && !subscriptionsEnabled) {
            return new Snapshot(State.DISABLED, Instant.now(), Set.of(), false, List.of());
        }

        List<String> errors = new ArrayList<>();
        if (providers.isEmpty()) {
            errors.add("MAIL_SUBSCRIPTIONS_REQUIRE_ENABLED_PROVIDER");
        } else {
            if (production() && !subscriptionsEnabled) {
                errors.add("MAIL_PROVIDER_SUBSCRIPTIONS_REQUIRED");
            }
            if (!tokenBrokerEnabled || tokenOwners.isEmpty()) {
                errors.add("MAIL_PROVIDER_TOKEN_BROKER_UNAVAILABLE");
            }
            if (production() && (tokenBrokerInsecureLocalhost
                    || graphEnabled && graphInsecureLocalhost
                    || gmailEnabled && gmailInsecureLocalhost)) {
                errors.add("MAIL_PROVIDER_INSECURE_LOCALHOST_FORBIDDEN");
            }
            if (graphEnabled && !connectors.isAvailable(MICROSOFT_GRAPH)) {
                errors.add("MAIL_GRAPH_ADAPTER_UNAVAILABLE");
            }
            if (gmailEnabled && !connectors.isAvailable(GOOGLE_GMAIL)) {
                errors.add("MAIL_GMAIL_ADAPTER_UNAVAILABLE");
            }
        }

        if (subscriptionsEnabled) {
            if (!publicHttpsOrigin(callbackBaseUrl)) {
                errors.add("MAIL_SUBSCRIPTION_CALLBACK_ORIGIN_INVALID");
            }
            if (graphEnabled) {
                addVerifierError(errors, "GRAPH", verifierCount(GRAPH));
            }
            if (gmailEnabled) {
                addVerifierError(errors, "GMAIL", verifierCount(GMAIL));
                if (!gmailTopicName.matches(GMAIL_TOPIC)
                        || !gmailSubscriptionName.matches(GMAIL_SUBSCRIPTION)
                        || !publicHttpsUri(gmailPushAudience)) {
                    errors.add("MAIL_GMAIL_PUSH_BINDING_INVALID");
                }
                if (gmailPushOwners.size() != 1) {
                    errors.add(gmailPushOwners.isEmpty()
                            ? "MAIL_GMAIL_PUSH_AUTHENTICATOR_UNAVAILABLE"
                            : "MAIL_GMAIL_PUSH_AUTHENTICATOR_AMBIGUOUS");
                }
            }
        }

        List<String> distinctErrors = List.copyOf(new LinkedHashSet<>(errors));
        return new Snapshot(distinctErrors.isEmpty() ? State.READY : State.BLOCKED_EXTERNAL,
                Instant.now(), providers, subscriptionsEnabled, distinctErrors);
    }

    private Set<String> enabledProviders() {
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        if (graphEnabled) providers.add(GRAPH);
        if (gmailEnabled) providers.add(GMAIL);
        return Set.copyOf(providers);
    }

    private long verifierCount(String provider) {
        return webhookVerifiers.stream().filter(verifier -> {
            try {
                return verifier.supports(provider);
            } catch (RuntimeException failure) {
                return false;
            }
        }).count();
    }

    private static void addVerifierError(
            List<String> errors, String provider, long count) {
        if (count == 0) {
            errors.add("MAIL_" + provider + "_WEBHOOK_VERIFIER_UNAVAILABLE");
        } else if (count > 1) {
            errors.add("MAIL_" + provider + "_WEBHOOK_VERIFIER_AMBIGUOUS");
        }
    }

    private boolean production() {
        return productionValue(environment.getProperty("dwp.environment"))
                || productionValue(environment.getProperty("DWP_ENVIRONMENT"));
    }

    private static boolean productionValue(String value) {
        String normalized = normalized(value);
        return "prod".equals(normalized) || "production".equals(normalized);
    }

    private static boolean publicHttpsOrigin(String value) {
        try {
            URI uri = URI.create(value);
            return publicHttps(uri)
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty()
                    || "/".equals(uri.getRawPath()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean publicHttpsUri(String value) {
        try {
            return publicHttps(URI.create(value));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static boolean publicHttps(URI uri) {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isBlank()
                || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return !"localhost".equals(normalizedHost)
                && !"0.0.0.0".equals(normalizedHost)
                && !"::1".equals(normalizedHost)
                && !normalizedHost.startsWith("127.")
                && !normalizedHost.endsWith(".local");
    }

    private static String normalized(String value) {
        return trimmed(value).toLowerCase(Locale.ROOT);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    enum State { DISABLED, READY, BLOCKED_EXTERNAL }

    record Snapshot(
            State state,
            Instant checkedAt,
            Set<String> enabledProviders,
            boolean subscriptionsEnabled,
            List<String> errorCodes) { }
}
