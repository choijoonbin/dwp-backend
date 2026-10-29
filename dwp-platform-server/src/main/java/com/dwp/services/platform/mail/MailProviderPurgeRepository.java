package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable provider-purge command and receipt store. */
@Repository
class MailProviderPurgeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    MailProviderPurgeRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void stage(AdminMailCompletionRepository.PurgeLeaseRow job) {
        OffsetDateTime checkedAt = OffsetDateTime.now(ZoneOffset.UTC);
        List<ReferenceRow> rows = jdbc.query("""
                SELECT connection.connection_id, connection.provider_type,
                       connection.credential_ref, connection.mail_domain,
                       account.account_id, account.provider_account_ref,
                       thread.provider_thread_ref, message.provider_message_ref
                  FROM mail_purge_candidate_snapshot_rows candidate
                  JOIN mail_threads thread
                    ON thread.tenant_id = candidate.tenant_id
                   AND thread.thread_id = candidate.thread_id
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                  LEFT JOIN mail_messages message
                    ON message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
                 WHERE candidate.tenant_id = ?
                   AND candidate.candidate_snapshot_id = ?
                   AND candidate.held = FALSE
                 ORDER BY account.account_id, thread.thread_id, message.message_id
                """, (result, ignored) -> new ReferenceRow(
                result.getObject("connection_id", UUID.class),
                result.getString("provider_type"), result.getString("credential_ref"),
                result.getString("mail_domain"), result.getObject("account_id", UUID.class),
                result.getString("provider_account_ref"),
                result.getString("provider_thread_ref"),
                result.getString("provider_message_ref")),
                job.tenantId(), job.snapshotId());
        Map<UUID, Staged> grouped = new LinkedHashMap<>();
        for (ReferenceRow row : rows) {
            if ("DWP_SANDBOX".equals(row.providerType())) continue;
            Staged staged = grouped.computeIfAbsent(row.accountId(), ignored -> new Staged(row));
            if (row.threadReference() != null) staged.threadReferences().add(row.threadReference());
            if (row.messageReference() != null) staged.messageReferences().add(row.messageReference());
        }
        for (Staged staged : grouped.values()) {
            UUID idempotency = UUID.nameUUIDFromBytes(("mail-provider-purge:" + job.id()
                    + ':' + staged.row().accountId()).getBytes(StandardCharsets.UTF_8));
            String holdEvidence = sha256(job.id() + ":" + job.snapshotId() + ":"
                    + checkedAt + ":" + job.candidateThreadIds());
            jdbc.update("""
                    INSERT INTO mail_provider_purge_commands (
                        tenant_id, job_id, connection_id, account_id, provider_type,
                        provider_account_reference, provider_message_references,
                        provider_thread_references, idempotency_key,
                        legal_hold_checked_at, legal_hold_evidence_hash)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                    ON CONFLICT (job_id, account_id) DO NOTHING
                    """, job.tenantId(), job.id(), staged.row().connectionId(),
                    staged.row().accountId(), staged.row().providerType(),
                    staged.row().providerAccountReference(),
                    json(List.copyOf(staged.messageReferences())),
                    json(List.copyOf(staged.threadReferences())),
                    idempotency, checkedAt, holdEvidence);
        }
    }

    List<Command> commands(long tenantId, UUID jobId) {
        return jdbc.query("""
                SELECT command.command_id, command.tenant_id, command.job_id,
                       command.connection_id, command.account_id, command.provider_type,
                       command.provider_account_reference,
                       command.provider_message_references::text,
                       command.provider_thread_references::text,
                       command.command_state, command.idempotency_key,
                       command.purged_items, connection.credential_ref,
                       connection.mail_domain
                  FROM mail_provider_purge_commands command
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = command.tenant_id
                   AND connection.connection_id = command.connection_id
                 WHERE command.tenant_id = ? AND command.job_id = ?
                 ORDER BY command.command_id
                """, (result, ignored) -> new Command(
                result.getObject("command_id", UUID.class), result.getLong("tenant_id"),
                result.getObject("job_id", UUID.class),
                result.getObject("connection_id", UUID.class),
                result.getObject("account_id", UUID.class),
                MailTypes.ProviderType.valueOf(result.getString("provider_type")),
                result.getString("provider_account_reference"),
                strings(result.getString("provider_message_references")),
                strings(result.getString("provider_thread_references")),
                result.getString("command_state"),
                result.getObject("idempotency_key", UUID.class),
                result.getInt("purged_items"), uri(result.getString("credential_ref")),
                result.getString("mail_domain")), tenantId, jobId);
    }

    int record(Command command, MailConnectorPort.PurgeReceipt receipt) {
        String receiptHash = sha256(receipt.outcome() + ":" + receipt.purgedItems() + ":"
                + value(receipt.providerReceiptReference()) + ':' + receipt.observedAt());
        boolean erase = receipt.outcome() == MailConnectorPort.PurgeOutcome.PURGED;
        return jdbc.update("""
                UPDATE mail_provider_purge_commands
                   SET command_state = ?, purged_items = ?, provider_receipt_hash = ?,
                       last_error_code = ?, attempt_count = attempt_count + 1,
                       provider_message_references = CASE WHEN ? THEN '[]'::jsonb
                                                          ELSE provider_message_references END,
                       provider_thread_references = CASE WHEN ? THEN '[]'::jsonb
                                                         ELSE provider_thread_references END,
                       references_purged_at = CASE WHEN ? THEN CURRENT_TIMESTAMP
                                                   ELSE references_purged_at END,
                       completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND command_id = ? AND command_state = 'PENDING'
                """, receipt.outcome().name(), receipt.purgedItems(), receiptHash,
                receipt.errorCode(), erase, erase, erase, command.tenantId(), command.id());
    }

    private List<String> strings(String json) {
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("MAIL_PROVIDER_PURGE_REFERENCES_INVALID", invalid);
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("MAIL_PROVIDER_PURGE_REFERENCES_INVALID", invalid);
        }
    }

    private URI uri(String value) {
        return value == null || value.isBlank() ? null : URI.create(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String value(String value) { return value == null ? "" : value; }

    record Command(UUID id, long tenantId, UUID jobId, UUID connectionId, UUID accountId,
                   MailTypes.ProviderType providerType, String providerAccountReference,
                   List<String> messageReferences, List<String> threadReferences,
                   String state, UUID idempotencyKey, int purgedItems,
                   URI credentialReference, String mailDomain) { }
    private record ReferenceRow(UUID connectionId, String providerType, String credentialReference,
                                String mailDomain, UUID accountId,
                                String providerAccountReference, String threadReference,
                                String messageReference) { }
    private record Staged(ReferenceRow row, Set<String> messageReferences,
                          Set<String> threadReferences) {
        private Staged(ReferenceRow row) {
            this(row, new LinkedHashSet<>(), new LinkedHashSet<>());
        }
    }
}
