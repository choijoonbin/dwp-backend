package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MailProviderWebhookVerifierTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void gmailPushFailsClosedWithoutExactlyOneOidcOwner() {
        RecordingJdbc jdbc = new RecordingJdbc();
        GmailPubSubMailWebhookVerifier verifier = new GmailPubSubMailWebhookVerifier(
                jdbc, mapper, List.of(), "https://mail.example/webhook",
                "projects/project/topics/mail");

        MailWebhookVerificationPort.Verification result = verifier.verify(callback(
                "GOOGLE_GMAIL", gmailEnvelope()));

        assertThat(result.verified()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GMAIL_PUSH_AUTHENTICATOR_UNAVAILABLE");
        assertThat(jdbc.queries).isZero();
    }

    @Test
    void gmailPushBindsOidcSubscriptionAccountAndHistoryToHashOnlyReceipt() {
        RecordingJdbc jdbc = new RecordingJdbc();
        GmailPushAuthenticationOwner owner = new GmailPushAuthenticationOwner() {
            @Override public boolean supports(Map<String, List<String>> headers) { return true; }
            @Override public Authentication verify(
                    Map<String, List<String>> headers, String expectedAudience) {
                return new Authentication(true, "https://accounts.google.com",
                        expectedAudience, "push@example.iam.gserviceaccount.com", null);
            }
        };
        GmailPubSubMailWebhookVerifier verifier = new GmailPubSubMailWebhookVerifier(
                jdbc, mapper, List.of(owner), "https://mail.example/webhook",
                "projects/project/topics/mail");

        MailWebhookVerificationPort.Verification result = verifier.verify(callback(
                "GOOGLE_GMAIL", gmailEnvelope()));

        assertThat(result.verified()).isTrue();
        assertThat(result.providerType()).isEqualTo("GOOGLE_GMAIL");
        assertThat(result.tenantId()).isEqualTo(7);
        assertThat(result.eventKeyHash()).matches("[0-9a-f]{64}");
        assertThat(result.clientStateHash()).matches("[0-9a-f]{64}");
        assertThat(jdbc.queries).isOne();
    }

    @Test
    void graphNotificationRejectsMixedClientStateBeforeDatabaseLookup() {
        RecordingJdbc jdbc = new RecordingJdbc();
        MicrosoftGraphMailWebhookVerifier verifier =
                new MicrosoftGraphMailWebhookVerifier(jdbc, mapper);
        String body = "{\"value\":["
                + "{\"subscriptionId\":\"sub\",\"clientState\":\"one\","
                + "\"changeType\":\"created\",\"resource\":\"users/a/messages/1\"},"
                + "{\"subscriptionId\":\"sub\",\"clientState\":\"two\","
                + "\"changeType\":\"updated\",\"resource\":\"users/a/messages/2\"}]}";

        MailWebhookVerificationPort.Verification result = verifier.verify(
                callback("MICROSOFT_GRAPH", body));

        assertThat(result.verified()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GRAPH_NOTIFICATION_MIXED_SUBSCRIPTIONS");
        assertThat(jdbc.queries).isZero();
    }

    @Test
    void graphNotificationRejectsAmbiguousCrossTenantLedgerOwnership() {
        RecordingJdbc jdbc = new RecordingJdbc(2);
        MicrosoftGraphMailWebhookVerifier verifier =
                new MicrosoftGraphMailWebhookVerifier(jdbc, mapper);
        String body = "{\"value\":[{\"subscriptionId\":\"sub\","
                + "\"clientState\":\"state\",\"changeType\":\"created\","
                + "\"resource\":\"users/a/messages/1\"}]}";

        MailWebhookVerificationPort.Verification result = verifier.verify(
                callback("MICROSOFT_GRAPH", body));

        assertThat(result.verified()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GRAPH_SUBSCRIPTION_AMBIGUOUS");
    }

    @Test
    void gmailPushRejectsAmbiguousCrossTenantLedgerOwnership() {
        RecordingJdbc jdbc = new RecordingJdbc(2);
        GmailPushAuthenticationOwner owner = new GmailPushAuthenticationOwner() {
            @Override public boolean supports(Map<String, List<String>> headers) { return true; }
            @Override public Authentication verify(
                    Map<String, List<String>> headers, String expectedAudience) {
                return new Authentication(true, "https://accounts.google.com",
                        expectedAudience, "push@example.iam.gserviceaccount.com", null);
            }
        };
        GmailPubSubMailWebhookVerifier verifier = new GmailPubSubMailWebhookVerifier(
                jdbc, mapper, List.of(owner), "https://mail.example/webhook",
                "projects/project/topics/mail");

        MailWebhookVerificationPort.Verification result = verifier.verify(callback(
                "GOOGLE_GMAIL", gmailEnvelope()));

        assertThat(result.verified()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GMAIL_PUSH_SUBSCRIPTION_AMBIGUOUS");
    }

    private MailWebhookVerificationPort.Callback callback(String provider, String body) {
        return new MailWebhookVerificationPort.Callback(provider,
                Map.of("Authorization", List.of("Bearer redacted")),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private String gmailEnvelope() {
        String data = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"emailAddress\":\"user@example.com\",\"historyId\":\"101\"}"
                        .getBytes(StandardCharsets.UTF_8));
        return "{\"subscription\":\"projects/project/subscriptions/mail\","
                + "\"message\":{\"messageId\":\"message-1\",\"data\":\""
                + data + "\"}}";
    }

    private static final class RecordingJdbc extends JdbcTemplate {
        private final int rows;
        private int queries;

        private RecordingJdbc() {
            this(1);
        }

        private RecordingJdbc(int rows) {
            this.rows = rows;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            queries++;
            try {
                java.util.ArrayList<T> mapped = new java.util.ArrayList<>();
                for (int row = 0; row < rows; row++) {
                    ResultSet result = mock(ResultSet.class);
                    when(result.getLong("tenant_id")).thenReturn(7L + row);
                    when(result.getObject("connection_id", UUID.class))
                            .thenReturn(new UUID(0, 7L + row));
                    mapped.add(mapper.mapRow(result, row));
                }
                return List.copyOf(mapped);
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
