package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailExternalProviderReadinessTest {

    @ParameterizedTest
    @CsvSource({"production, local", "local, prod"})
    void eitherProductionEnvironmentMarkerEnforcesTheStartupGuard(
            String dwpEnvironment, String legacyEnvironment) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dwp.environment", dwpEnvironment)
                .withProperty("DWP_ENVIRONMENT", legacyEnvironment);
        MailExternalProviderReadiness readiness = new MailExternalProviderReadiness(
                new MailConnectorRegistry(List.of(graphConnector())),
                List.of(), List.of(), List.of(), environment,
                true, false, false,
                false, false, false, false,
                "", "", "", "");

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_PROVIDER_TOKEN_BROKER_UNAVAILABLE");
    }

    @Test
    void productionFailsClosedWhenEnabledProviderHasNoTokenBrokerOwner() {
        MailExternalProviderReadiness readiness = readiness(
                "production", true, false, false,
                false, false, false, false,
                "", "", "", "",
                List.of(), List.of(), List.of(), List.of(graphConnector()));

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_PROVIDER_TOKEN_BROKER_UNAVAILABLE");
    }

    @Test
    void configuredProviderIsReportedBlockedExternalOutsideProduction() {
        MailExternalProviderReadiness readiness = readiness(
                "test", true, false, false,
                false, false, false, false,
                "", "", "", "",
                List.of(), List.of(), List.of(), List.of(graphConnector()));

        MailExternalProviderReadiness.Snapshot snapshot = readiness.snapshot();
        assertThat(snapshot.state())
                .isEqualTo(MailExternalProviderReadiness.State.BLOCKED_EXTERNAL);
        assertThat(snapshot.errorCodes())
                .containsExactly("MAIL_PROVIDER_TOKEN_BROKER_UNAVAILABLE");
        assertThat(new MailExternalProviderHealthIndicator(readiness).health().getStatus())
                .isEqualTo(Status.DOWN);
    }

    @Test
    void productionGmailSubscriptionsRequirePushAuthenticatorAndBindings() {
        MailExternalProviderReadiness readiness = readiness(
                "prod", false, true, true,
                false, false, false, true,
                "https://mail.example", "", "", "",
                List.of(mock(MailProviderTokenOwner.class)),
                List.of(verifier("GOOGLE_GMAIL")), List.of(),
                List.of(gmailConnector()));

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_GMAIL_PUSH_BINDING_INVALID")
                .hasMessageContaining("MAIL_GMAIL_PUSH_AUTHENTICATOR_UNAVAILABLE");
    }

    @Test
    void productionRejectsInsecureLocalhostEscapeHatches() {
        MailExternalProviderReadiness readiness = readiness(
                "production", true, false, true,
                true, false, false, false,
                "", "", "", "",
                List.of(mock(MailProviderTokenOwner.class)), List.of(), List.of(),
                List.of(graphConnector()));

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_PROVIDER_INSECURE_LOCALHOST_FORBIDDEN");
    }

    @Test
    void productionProviderRequiresSubscriptionRuntime() {
        MailExternalProviderReadiness readiness = readiness(
                "production", true, false, true,
                false, false, false, false,
                "", "", "", "",
                List.of(mock(MailProviderTokenOwner.class)), List.of(), List.of(),
                List.of(graphConnector()));

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_PROVIDER_SUBSCRIPTIONS_REQUIRED");
    }

    @Test
    void productionGmailSubscriptionsRequireExactlyOnePushAuthenticator() {
        GmailPushAuthenticationOwner first = mock(GmailPushAuthenticationOwner.class);
        GmailPushAuthenticationOwner second = mock(GmailPushAuthenticationOwner.class);
        MailExternalProviderReadiness readiness = readiness(
                "production", false, true, true,
                false, false, false, true,
                "https://mail.example",
                "projects/project/topics/mail",
                "projects/project/subscriptions/mail",
                "https://mail.example/internal/v1/mail/webhooks/google_gmail",
                List.of(mock(MailProviderTokenOwner.class)),
                List.of(verifier("GOOGLE_GMAIL")), List.of(first, second),
                List.of(gmailConnector()));

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_GMAIL_PUSH_AUTHENTICATOR_AMBIGUOUS");
    }

    @Test
    void productionSubscriptionsRejectAmbiguousProviderVerifierOwnership() {
        MailExternalProviderReadiness readiness = readiness(
                "production", true, false, true,
                false, false, false, true,
                "https://mail.example", "", "", "",
                List.of(mock(MailProviderTokenOwner.class)),
                List.of(verifier("MICROSOFT_GRAPH"), verifier("MICROSOFT_GRAPH")),
                List.of(), List.of(graphConnector()));

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_GRAPH_WEBHOOK_VERIFIER_AMBIGUOUS");
    }

    @Test
    void completeGraphSubscriptionDeploymentIsConfigurationReady() {
        MailExternalProviderReadiness readiness = readiness(
                "production", true, false, true,
                false, false, false, true,
                "https://mail.example", "", "", "",
                List.of(mock(MailProviderTokenOwner.class)),
                List.of(verifier("MICROSOFT_GRAPH")), List.of(),
                List.of(graphConnector()));

        assertThatCode(readiness::validateProduction).doesNotThrowAnyException();
        assertThat(readiness.snapshot().state())
                .isEqualTo(MailExternalProviderReadiness.State.READY);
        assertThat(new MailExternalProviderHealthIndicator(readiness).health().getStatus())
                .isEqualTo(Status.UP);
    }

    @Test
    void completeGmailSubscriptionDeploymentAcceptsPathBoundPushAudience() {
        MailExternalProviderReadiness readiness = readiness(
                "production", false, true, true,
                false, false, false, true,
                "https://mail.example",
                "projects/project/topics/mail",
                "projects/project/subscriptions/mail",
                "https://mail.example/internal/v1/mail/webhooks/google_gmail",
                List.of(mock(MailProviderTokenOwner.class)),
                List.of(verifier("GOOGLE_GMAIL")),
                List.of(mock(GmailPushAuthenticationOwner.class)),
                List.of(gmailConnector()));

        assertThatCode(readiness::validateProduction).doesNotThrowAnyException();
        assertThat(readiness.snapshot().state())
                .isEqualTo(MailExternalProviderReadiness.State.READY);
    }

    @Test
    void disabledExternalProvidersDoNotClaimReadiness() {
        MailExternalProviderReadiness readiness = readiness(
                "production", false, false, false,
                false, false, false, false,
                "", "", "", "",
                List.of(), List.of(), List.of(), List.of());

        assertThatCode(readiness::validateProduction).doesNotThrowAnyException();
        assertThat(readiness.snapshot().state())
                .isEqualTo(MailExternalProviderReadiness.State.DISABLED);
        assertThat(new MailExternalProviderHealthIndicator(readiness).health().getStatus())
                .isEqualTo(Status.UNKNOWN);
    }

    private MailExternalProviderReadiness readiness(
            String environment,
            boolean graphEnabled,
            boolean gmailEnabled,
            boolean tokenBrokerEnabled,
            boolean graphInsecure,
            boolean gmailInsecure,
            boolean tokenBrokerInsecure,
            boolean subscriptionsEnabled,
            String callback,
            String topic,
            String subscription,
            String audience,
            List<MailProviderTokenOwner> tokenOwners,
            List<MailWebhookVerificationPort> verifiers,
            List<GmailPushAuthenticationOwner> pushOwners,
            List<MailConnectorPort> connectors) {
        return new MailExternalProviderReadiness(
                new MailConnectorRegistry(connectors), tokenOwners, verifiers, pushOwners,
                new MockEnvironment().withProperty("DWP_ENVIRONMENT", environment),
                graphEnabled, gmailEnabled, tokenBrokerEnabled,
                graphInsecure, gmailInsecure, tokenBrokerInsecure, subscriptionsEnabled,
                callback, topic, subscription, audience);
    }

    private MailWebhookVerificationPort verifier(String provider) {
        MailWebhookVerificationPort verifier = mock(MailWebhookVerificationPort.class);
        when(verifier.supports(provider)).thenReturn(true);
        return verifier;
    }

    private MailConnectorPort graphConnector() {
        return connector(MailConnectorPort.ProviderFamily.MICROSOFT_GRAPH);
    }

    private MailConnectorPort gmailConnector() {
        return connector(MailConnectorPort.ProviderFamily.GOOGLE_GMAIL);
    }

    private MailConnectorPort connector(MailConnectorPort.ProviderFamily provider) {
        MailConnectorPort connector = mock(MailConnectorPort.class);
        when(connector.manifest()).thenReturn(new MailConnectorPort.Manifest(
                provider, "test", provider.name(), Set.of()));
        return connector;
    }
}
