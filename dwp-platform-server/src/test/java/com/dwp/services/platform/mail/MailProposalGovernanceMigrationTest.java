package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MailProposalGovernanceMigrationTest {

    @Test
    void bindsProducerProvenanceAndBulkReceiptsToTenantOwnedMailFacts()
            throws IOException {
        String migration = resource(
                "db/migration/V372__govern_mail_proposal_provenance_and_bulk_intents.sql");

        assertThat(migration)
                .contains(
                        "source_message_id",
                        "producer_identity",
                        "producer_event_id",
                        "producer_model",
                        "model_revision",
                        "prompt_revision",
                        "policy_revision",
                        "source_evidence_hashes",
                        "producer_fingerprint")
                .contains("fk_mail_proposal_source_message")
                .contains("FOREIGN KEY (tenant_id, thread_id, source_message_id)")
                .contains("uk_mail_proposal_producer_event")
                .contains("CREATE TABLE mail_bulk_mutation_receipts")
                .contains("UNIQUE (tenant_id, actor_user_id, item_idempotency_key)")
                .contains("intent_id UUID NOT NULL")
                .contains("item_idempotency_key UUID NOT NULL")
                .contains("action_kind IN (")
                .contains("'MARK_READ', 'STAR', 'ARCHIVE'")
                .contains("FOREIGN KEY (tenant_id, thread_id)")
                .doesNotContain("provider_access_token", "refresh_token", "message_body");
    }

    private String resource(String name) throws IOException {
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(name)) {
            if (stream == null) throw new IOException("Missing resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
