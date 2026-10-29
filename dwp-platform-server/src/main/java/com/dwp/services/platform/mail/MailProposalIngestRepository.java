package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailTypes.ProposalType;

@Repository
class MailProposalIngestRepository {

    private final JdbcTemplate jdbc;
    private final MailJsonCodec json;

    MailProposalIngestRepository(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<Source> source(
            long tenantId,
            long actorId,
            UUID threadId,
            UUID messageId) {
        return jdbc.query("""
                SELECT thread.account_id, message.message_id,
                       encode(digest(concat_ws(E'\\x1f',
                           message.tenant_id::text,
                           message.thread_id::text,
                           message.message_id::text,
                           COALESCE(message.provider_message_ref, ''),
                           message.sent_at::text,
                           COALESCE(message.sender_email, ''),
                           COALESCE(thread.subject, ''),
                           COALESCE(message.body_content, '')), 'sha256'), 'hex')
                           AS source_evidence_hash
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_messages message
                    ON message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
                   AND message.message_id = ?
                   AND message.message_direction = 'INBOUND'
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """ + MailAccessSql.THREAD_ACCESS,
                (result, ignored) -> new Source(
                        result.getObject("account_id", UUID.class),
                        result.getObject("message_id", UUID.class),
                        result.getString("source_evidence_hash")),
                messageId, tenantId, threadId, actorId, actorId).stream().findFirst();
    }

    Optional<Existing> existing(
            long tenantId,
            String producerIdentity,
            UUID producerEventId) {
        return jdbc.query("""
                SELECT proposal_id, producer_fingerprint, proposal_status, version
                  FROM mail_action_proposals
                 WHERE tenant_id = ? AND producer_identity = ? AND producer_event_id = ?
                """, (result, ignored) -> new Existing(
                result.getObject("proposal_id", UUID.class),
                result.getString("producer_fingerprint"),
                result.getString("proposal_status"),
                result.getLong("version")),
                tenantId, producerIdentity, producerEventId).stream().findFirst();
    }

    int insert(
            UUID proposalId,
            long tenantId,
            long actorId,
            Source source,
            String producerIdentity,
            MailProposalIngestDtos.Request request,
            MailAiActionCatalog.Policy policy,
            String fingerprint) {
        return jdbc.update("""
                INSERT INTO mail_action_proposals (
                    proposal_id, tenant_id, account_id, thread_id, source_message_id,
                    proposal_type, action_contract_version, proposal_status,
                    title, summary, evidence, proposed_payload, confidence, risk_level,
                    required_resource_key, required_permission_code, target_route, expires_at,
                    producer_identity, producer_event_id, producer_model, model_revision,
                    prompt_revision, policy_revision, source_evidence_hashes,
                    producer_fingerprint, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?, ?, ?::jsonb, ?::jsonb, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (tenant_id, producer_identity, producer_event_id)
                    WHERE producer_identity IS NOT NULL DO NOTHING
                """, proposalId, tenantId, source.accountId(), request.threadId(),
                request.sourceMessageId(), request.proposalType().name(),
                request.actionContractVersion(), request.title().trim(),
                request.summary().trim(), json.write(request.evidence()),
                json.write(request.proposedPayload()), request.confidence(),
                request.riskLevel(), policy.resourceKey(), policy.permissionCode(),
                request.targetRoute().trim(), request.expiresAt(), producerIdentity,
                request.producerEventId(), request.producerModel().trim(),
                request.modelRevision().trim(), request.promptRevision().trim(),
                request.policyRevision().trim(), json.write(request.sourceEvidenceHashes()),
                fingerprint, actorId, actorId);
    }

    record Source(UUID accountId, UUID messageId, String evidenceHash) { }

    record Existing(UUID proposalId, String fingerprint, String state, long version) { }
}
