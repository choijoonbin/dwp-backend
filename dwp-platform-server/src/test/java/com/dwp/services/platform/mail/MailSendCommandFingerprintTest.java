package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.DeliveryMode.SEND;
import static org.assertj.core.api.Assertions.assertThat;

class MailSendCommandFingerprintTest {

    private final MailSendCommandFingerprint fingerprints = new MailSendCommandFingerprint();

    @Test
    void composeFingerprintBindsModeRecipientSubjectAndBody() {
        UUID key = UUID.randomUUID();
        var baseline = compose(
                "recipient@sk.com", "Recipient", "Subject", "Body", SEND, key);
        String fingerprint = fingerprints.compose(7L, baseline);

        assertThat(fingerprints.compose(7L, baseline)).isEqualTo(fingerprint);
        assertThat(fingerprints.compose(8L, baseline)).isNotEqualTo(fingerprint);
        assertThat(fingerprints.compose(7L, compose(
                "other@sk.com", "Recipient", "Subject", "Body", SEND, key)))
                .isNotEqualTo(fingerprint);
        assertThat(fingerprints.compose(7L, compose(
                "recipient@sk.com", "Other", "Subject", "Body", SEND, key)))
                .isNotEqualTo(fingerprint);
        assertThat(fingerprints.compose(7L, compose(
                "recipient@sk.com", "Recipient", "Changed", "Body", SEND, key)))
                .isNotEqualTo(fingerprint);
        assertThat(fingerprints.compose(7L, compose(
                "recipient@sk.com", "Recipient", "Subject", "Changed", SEND, key)))
                .isNotEqualTo(fingerprint);
        assertThat(fingerprints.compose(7L, compose(
                "recipient@sk.com", "Recipient", "Subject", "Body",
                MailTypes.DeliveryMode.DRAFT, key)))
                .isNotEqualTo(fingerprint);
    }

    @Test
    void replyFingerprintBindsThreadAndBody() {
        UUID threadId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String baseline = fingerprints.reply(
                7L, threadId, new MailDtos.ReplyRequest("same body", key));

        assertThat(fingerprints.reply(
                7L, threadId, new MailDtos.ReplyRequest("same body", key)))
                .isEqualTo(baseline);
        assertThat(fingerprints.reply(
                8L, threadId, new MailDtos.ReplyRequest("same body", key)))
                .isNotEqualTo(baseline);
        assertThat(fingerprints.reply(
                7L, UUID.randomUUID(), new MailDtos.ReplyRequest("same body", key)))
                .isNotEqualTo(baseline);
        assertThat(fingerprints.reply(
                7L, threadId, new MailDtos.ReplyRequest("changed body", key)))
                .isNotEqualTo(baseline);
    }

    @Test
    void draftSendFingerprintBindsThreadVersionRecipientSubjectAndBody() {
        UUID threadId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        var baselineRequest = request(
                "recipient@sk.com", "Recipient", "Subject", "Body", key, 4L);
        String baseline = fingerprints.draftSend(7L, threadId, baselineRequest);

        assertThat(fingerprints.draftSend(7L, threadId, baselineRequest)).isEqualTo(baseline);
        assertThat(fingerprints.draftSend(8L, threadId, baselineRequest))
                .isNotEqualTo(baseline);
        assertThat(fingerprints.draftSend(7L, UUID.randomUUID(), baselineRequest))
                .isNotEqualTo(baseline);
        assertThat(fingerprints.draftSend(7L, threadId, request(
                "other@sk.com", "Recipient", "Subject", "Body", key, 4L)))
                .isNotEqualTo(baseline);
        assertThat(fingerprints.draftSend(7L, threadId, request(
                "recipient@sk.com", "Other", "Subject", "Body", key, 4L)))
                .isNotEqualTo(baseline);
        assertThat(fingerprints.draftSend(7L, threadId, request(
                "recipient@sk.com", "Recipient", "Changed", "Body", key, 4L)))
                .isNotEqualTo(baseline);
        assertThat(fingerprints.draftSend(7L, threadId, request(
                "recipient@sk.com", "Recipient", "Subject", "Changed", key, 4L)))
                .isNotEqualTo(baseline);
        assertThat(fingerprints.draftSend(7L, threadId, request(
                "recipient@sk.com", "Recipient", "Subject", "Body", key, 5L)))
                .isNotEqualTo(baseline);
    }

    private MailDtos.DraftUpdateRequest request(
            String email,
            String name,
            String subject,
            String body,
            UUID key,
            long version) {
        return new MailDtos.DraftUpdateRequest(
                email, name, subject, body, SEND, key, version);
    }

    private MailDtos.ComposeRequest compose(
            String email,
            String name,
            String subject,
            String body,
            MailTypes.DeliveryMode mode,
            UUID key) {
        return new MailDtos.ComposeRequest(email, name, subject, body, mode, key);
    }
}
