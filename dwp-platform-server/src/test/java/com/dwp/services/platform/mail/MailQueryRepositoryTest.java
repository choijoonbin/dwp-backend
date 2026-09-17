package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MailQueryRepositoryTest {

    private final MailQueryRepository repository = new MailQueryRepository(
            null, new MailJsonCodec(new ObjectMapper()));

    @Test
    void inboundRecipientsNeverExposeBccAcrossKeyAndValueCasing() {
        List<Map<String, Object>> recipients = repository.visibleRecipients(
                "inbound",
                """
                [
                  {"type":"TO","email":"to@example.com"},
                  {"TyPe":"bCc","email":"hidden@example.com"},
                  {"TYPE":" BCC ","email":"also-hidden@example.com"},
                  {"email":"legacy@example.com"}
                ]
                """);

        assertThat(recipients)
                .extracting(recipient -> recipient.get("email"))
                .containsExactly("to@example.com", "legacy@example.com");
    }

    @Test
    void outboundRecipientsPreserveBccForTheSenderView() {
        List<Map<String, Object>> recipients = repository.visibleRecipients(
                "OUTBOUND",
                """
                [{"type":"BCC","email":"hidden@example.com"}]
                """);

        assertThat(recipients).singleElement()
                .satisfies(recipient -> assertThat(recipient)
                        .containsEntry("email", "hidden@example.com"));
    }

    @Test
    void outboundRecipientsHideBccFromAnotherSharedMailboxReader() {
        List<Map<String, Object>> recipients = repository.visibleRecipients(
                "OUTBOUND",
                """
                [
                  {"type":"TO","email":"visible@example.com"},
                  {"type":"BCC","email":"hidden@example.com"}
                ]
                """,
                false);

        assertThat(recipients)
                .extracting(recipient -> recipient.get("email"))
                .containsExactly("visible@example.com");
    }
}
