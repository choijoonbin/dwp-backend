package com.dwp.services.platform.mail;

import com.dwp.platform.contract.ExecutionContext;
import com.dwp.platform.contract.MailConnectorPort;
import com.dwp.platform.contract.MailProviderException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Executes one account-bound provider subscription renewal outside database transactions. */
@Component
@ConditionalOnProperty(
        name = "dwp.platform.mail.subscriptions.enabled",
        havingValue = "true")
class MailSubscriptionCoordinator {

    private final AdminMailCompletionRepository repository;
    private final MailConnectorRegistry connectors;
    private final MailSubscriptionLedger ledger;
    private final URI callbackBase;
    private final Duration requestedLifetime;
    private final Duration renewalLead;
    private final Clock clock;

    MailSubscriptionCoordinator(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectors,
            MailSubscriptionLedger ledger,
            @Value("${dwp.platform.mail.subscriptions.callback-base-url:}")
            String callbackBaseUrl,
            @Value("${dwp.platform.mail.subscriptions.requested-lifetime:P5D}")
            Duration requestedLifetime,
            @Value("${dwp.platform.mail.subscriptions.renewal-lead:PT30M}")
            Duration renewalLead) {
        this(repository, connectors, ledger, trustedBase(callbackBaseUrl),
                requestedLifetime, renewalLead, Clock.systemUTC());
    }

    MailSubscriptionCoordinator(
            AdminMailCompletionRepository repository,
            MailConnectorRegistry connectors,
            MailSubscriptionLedger ledger,
            URI callbackBase,
            Duration requestedLifetime,
            Duration renewalLead,
            Clock clock) {
        this.repository = repository;
        this.connectors = connectors;
        this.ledger = ledger;
        this.callbackBase = trustedBase(callbackBase == null ? "" : callbackBase.toString());
        this.requestedLifetime = positive(requestedLifetime, "requestedLifetime");
        this.renewalLead = positive(renewalLead, "renewalLead");
        if (this.renewalLead.compareTo(this.requestedLifetime) >= 0) {
            throw new IllegalArgumentException(
                    "renewalLead must be shorter than requestedLifetime");
        }
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    Outcome renew(
            MailSubscriptionLedger.Renewal renewal,
            String workerId,
            int maximumAttempts) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return fail(renewal, workerId, maximumAttempts,
                    "SUBSCRIPTION_NETWORK_TRANSACTION_ACTIVE", "PERMANENT", null, true);
        }
        AdminMailCompletionRepository.ConnectionRow connection = repository
                .connection(renewal.tenantId(), renewal.connectionId()).orElse(null);
        if (connection == null || !"ACTIVE".equals(connection.state())) {
            return fail(renewal, workerId, maximumAttempts,
                    "SUBSCRIPTION_CONNECTION_UNAVAILABLE", "PERMANENT", null, true);
        }
        AdminMailCompletionRepository.AccountRow account = repository
                .connectionAccounts(renewal.tenantId(), renewal.connectionId()).stream()
                .filter(candidate -> candidate.id().equals(renewal.accountId()))
                .findFirst().orElse(null);
        if (account == null) {
            return fail(renewal, workerId, maximumAttempts,
                    "SUBSCRIPTION_ACCOUNT_UNAVAILABLE", "PERMANENT", null, true);
        }
        MailTypes.ProviderType provider;
        try {
            provider = MailTypes.ProviderType.valueOf(connection.providerType());
        } catch (RuntimeException invalid) {
            return fail(renewal, workerId, maximumAttempts,
                    "SUBSCRIPTION_PROVIDER_UNSUPPORTED", "PERMANENT", null, true);
        }
        MailConnectorPort connector = connectors.connector(provider).orElse(null);
        if (connector == null
                || !connector.manifest().capabilities().contains(
                        MailConnectorPort.Capability.PUSH)) {
            return fail(renewal, workerId, maximumAttempts,
                    "SUBSCRIPTION_CONNECTOR_UNAVAILABLE", "PERMANENT", null, true);
        }
        MailConnectorPort.ConnectionContext context;
        try {
            context = new MailConnectorPort.ConnectionContext(
                    new ExecutionContext(
                            Long.toString(renewal.tenantId()),
                            Long.toString(renewal.actorId()),
                            Set.of("ADMIN.MAIL:MANAGE"),
                            "mail-subscription-renewal"),
                    connection.id(), connection.secretReference(), connection.mailDomain());
        } catch (RuntimeException invalid) {
            return fail(renewal, workerId, maximumAttempts,
                    "SUBSCRIPTION_CREDENTIAL_REFERENCE_INVALID", "PERMANENT", null, true);
        }
        Instant now = clock.instant();
        URI callback = callbackBase.resolve(
                "/internal/v1/mail/webhooks/"
                        + provider.name().toLowerCase(Locale.ROOT));
        MailConnectorPort.SubscriptionReceipt receipt;
        try {
            receipt = connector.renewSubscription(
                    new MailConnectorPort.SubscriptionRequest(
                            context, account.providerAccountRef(), callback,
                            now.plus(requestedLifetime)));
        } catch (MailProviderException failure) {
            boolean terminal = failure.category() == MailProviderException.Category.AUTHENTICATION
                    || failure.category() == MailProviderException.Category.PERMANENT
                    || renewal.attemptCount() >= maximumAttempts;
            OffsetDateTime retryAt = failure.retryAfter() == null ? null
                    : OffsetDateTime.ofInstant(failure.retryAfter(), ZoneOffset.UTC);
            return fail(renewal, workerId, maximumAttempts,
                    failure.errorCode(), failure.category().name(), retryAt, terminal);
        } catch (RuntimeException failure) {
            return fail(renewal, workerId, maximumAttempts,
                    "SUBSCRIPTION_PROVIDER_UNAVAILABLE", "TRANSIENT", null,
                    renewal.attemptCount() >= maximumAttempts);
        }
        if (receipt == null || !receipt.expiresAt().isAfter(now)) {
            return fail(renewal, workerId, maximumAttempts,
                    "SUBSCRIPTION_RECEIPT_INVALID", "PERMANENT", null, true);
        }
        if (!safeProviderReference(receipt.providerSubscriptionReference())) {
            return fail(renewal, workerId, maximumAttempts,
                    "SUBSCRIPTION_REFERENCE_INVALID", "PERMANENT", null, true);
        }
        if (provider == MailTypes.ProviderType.MICROSOFT_GRAPH
                && receipt.clientStateSha256() == null) {
            return fail(renewal, workerId, maximumAttempts,
                    "SUBSCRIPTION_CLIENT_STATE_RECEIPT_MISSING",
                    "PERMANENT", null, true);
        }
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                receipt.expiresAt(), ZoneOffset.UTC);
        Instant due = receipt.expiresAt().minus(renewalLead);
        if (due.isBefore(now)) due = now;
        try {
            boolean persisted = ledger.completeSuccess(
                    renewal, workerId,
                    sha256(receipt.providerSubscriptionReference()),
                    receipt.clientStateSha256(), expiresAt,
                    OffsetDateTime.ofInstant(due, ZoneOffset.UTC),
                    Map.of("provider", provider.name(), "status", "VERIFIED"));
            return persisted ? Outcome.RENEWED : Outcome.LEASE_LOST;
        } catch (RuntimeException persistenceFailure) {
            return fail(renewal, workerId, maximumAttempts,
                    "SUBSCRIPTION_RESULT_UNKNOWN", "RESULT_UNKNOWN", null, true);
        }
    }

    private Outcome fail(
            MailSubscriptionLedger.Renewal renewal,
            String workerId,
            int maximumAttempts,
            String code,
            String classification,
            OffsetDateTime retryAt,
            boolean requestedTerminal) {
        boolean terminal = requestedTerminal
                || renewal.attemptCount() >= maximumAttempts;
        MailSubscriptionLedger.FailureDisposition disposition = ledger.completeFailure(
                renewal, workerId, code, classification, retryAt, terminal);
        if (!disposition.persisted()) return Outcome.LEASE_LOST;
        return disposition.terminal() ? Outcome.FAILED : Outcome.RETRY_WAIT;
    }

    private static URI trustedBase(String raw) {
        URI base;
        try {
            base = URI.create(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Mail subscription callback base URL is invalid", invalid);
        }
        if (!"https".equalsIgnoreCase(base.getScheme())
                || base.getHost() == null || base.getHost().isBlank()
                || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null
                || !(base.getRawPath() == null || base.getRawPath().isEmpty()
                     || "/".equals(base.getRawPath()))) {
            throw new IllegalArgumentException(
                    "Mail subscription callback base URL must be an HTTPS origin");
        }
        String normalized = base.toString();
        return URI.create(normalized.endsWith("/") ? normalized : normalized + '/');
    }

    private static Duration positive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static boolean safeProviderReference(String value) {
        return value != null && !value.isBlank() && value.length() <= 2_000
                && value.chars().noneMatch(character -> character < 0x20);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    enum Outcome {
        RENEWED,
        RETRY_WAIT,
        FAILED,
        LEASE_LOST
    }
}
