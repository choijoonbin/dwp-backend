package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
class MailProposalIngestService {

    static final String PRODUCER_IDENTITY = "dwp-agent-runtime";
    private static final int MAXIMUM_SERIALIZED_EVIDENCE_BYTES = 64 * 1024;

    private final MailProposalIngestRepository repository;
    private final MailCommandRepository evidence;
    private final ObjectMapper canonicalJson;

    MailProposalIngestService(
            MailProposalIngestRepository repository,
            MailCommandRepository evidence,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.evidence = evidence;
        this.canonicalJson = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional
    MailProposalIngestDtos.Receipt ingest(
            String producerIdentity,
            long tenantId,
            long actorId,
            String correlationId,
            MailProposalIngestDtos.Request request) {
        if (!PRODUCER_IDENTITY.equals(producerIdentity)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The Agent runtime service identity is required.");
        }
        if (tenantId < 1 || actorId < 1) {
            throw new BaseException(ErrorCode.UNAUTHORIZED, "A tenant actor is required.");
        }
        validateBoundedEvidence(request);
        MailAiActionCatalog.Policy policy = MailAiActionCatalog.validateProducer(
                request.proposalType(), request.actionContractVersion(), request.riskLevel(),
                request.targetRoute(), request.proposedPayload());
        MailProposalIngestRepository.Source source = repository.source(
                tenantId, actorId, request.threadId(), request.sourceMessageId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!request.sourceEvidenceHashes().contains(source.evidenceHash())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The proposal source evidence does not match the current Mail message.");
        }
        String fingerprint = fingerprint(request);
        var existing = repository.existing(
                tenantId, producerIdentity, request.producerEventId());
        if (existing.isPresent()) {
            requireReplay(existing.get(), fingerprint);
            return receipt(existing.get(), true);
        }

        UUID proposalId = UUID.randomUUID();
        int inserted = repository.insert(
                proposalId, tenantId, actorId, source, producerIdentity,
                request, policy, fingerprint);
        if (inserted != 1) {
            MailProposalIngestRepository.Existing winner = repository.existing(
                    tenantId, producerIdentity, request.producerEventId())
                    .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_CONFLICT));
            requireReplay(winner, fingerprint);
            return receipt(winner, true);
        }
        String resolvedCorrelation = correlationId == null || correlationId.isBlank()
                ? "mail-proposal-ingest:" + request.producerEventId()
                : correlationId.trim();
        evidence.audit(
                tenantId, actorId, "mail.proposal.produced", "MAIL_ACTION_PROPOSAL",
                proposalId.toString(), resolvedCorrelation, Map.of(), Map.of(
                        "proposalType", request.proposalType().name(),
                        "producerIdentity", producerIdentity,
                        "producerEventId", request.producerEventId(),
                        "modelRevision", request.modelRevision(),
                        "promptRevision", request.promptRevision(),
                        "policyRevision", request.policyRevision(),
                        "sourceMessageId", request.sourceMessageId()));
        evidence.domainEvent(
                tenantId, "MAIL_ACTION_PROPOSAL", proposalId,
                "mail.proposal.produced", Map.of(
                        "proposalId", proposalId,
                        "threadId", request.threadId(),
                        "sourceMessageId", request.sourceMessageId(),
                        "proposalType", request.proposalType().name(),
                        "producerEventId", request.producerEventId()),
                resolvedCorrelation);
        return new MailProposalIngestDtos.Receipt(
                proposalId, "PROPOSED", false, 0L);
    }

    private void validateBoundedEvidence(MailProposalIngestDtos.Request request) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (!request.expiresAt().isAfter(now)
                || request.expiresAt().isAfter(now.plusDays(7))) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A proposal must expire within seven days.");
        }
        if (new HashSet<>(request.sourceEvidenceHashes()).size()
                != request.sourceEvidenceHashes().size()
                || !boundedJson(request.evidence(), 0)
                || !boundedJson(request.proposedPayload(), 0)
                || serializedBytes(request.evidence()) > MAXIMUM_SERIALIZED_EVIDENCE_BYTES
                || serializedBytes(request.proposedPayload()) > MAXIMUM_SERIALIZED_EVIDENCE_BYTES) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Proposal evidence and payload must be bounded canonical JSON.");
        }
    }

    private boolean boundedJson(Object value, int depth) {
        if (depth > 6) return false;
        if (value == null || value instanceof Boolean) return true;
        if (value instanceof String text) return text.length() <= 10_000;
        if (value instanceof Number number) {
            if (number instanceof Double item) return Double.isFinite(item);
            if (number instanceof Float item) return Float.isFinite(item);
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            return map.size() <= 100 && map.entrySet().stream().allMatch(entry ->
                    entry.getKey() instanceof String key
                            && !key.isBlank() && key.length() <= 160
                            && boundedJson(entry.getValue(), depth + 1));
        }
        if (value instanceof Collection<?> collection) {
            return collection.size() <= 100
                    && collection.stream().allMatch(item -> boundedJson(item, depth + 1));
        }
        return false;
    }

    private int serializedBytes(Object value) {
        try {
            return canonicalJson.writeValueAsBytes(value).length;
        } catch (JsonProcessingException invalid) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Proposal evidence is not valid JSON.");
        }
    }

    private String fingerprint(MailProposalIngestDtos.Request request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("producerEventId", request.producerEventId());
        canonical.put("threadId", request.threadId());
        canonical.put("sourceMessageId", request.sourceMessageId());
        canonical.put("proposalType", request.proposalType().name());
        canonical.put("actionContractVersion", request.actionContractVersion());
        canonical.put("title", request.title().trim());
        canonical.put("summary", request.summary().trim());
        canonical.put("evidence", request.evidence());
        canonical.put("proposedPayload", request.proposedPayload());
        canonical.put("confidence", request.confidence().stripTrailingZeros().toPlainString());
        canonical.put("riskLevel", request.riskLevel());
        canonical.put("targetRoute", request.targetRoute().trim());
        canonical.put("expiresAt", request.expiresAt().toInstant().toString());
        canonical.put("producerModel", request.producerModel().trim());
        canonical.put("modelRevision", request.modelRevision().trim());
        canonical.put("promptRevision", request.promptRevision().trim());
        canonical.put("policyRevision", request.policyRevision().trim());
        canonical.put("sourceEvidenceHashes", request.sourceEvidenceHashes());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.writeValueAsBytes(canonical)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        } catch (JsonProcessingException invalid) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Proposal evidence is not valid JSON.");
        }
    }

    private void requireReplay(
            MailProposalIngestRepository.Existing existing,
            String expectedFingerprint) {
        if (!MessageDigest.isEqual(
                existing.fingerprint().getBytes(StandardCharsets.US_ASCII),
                expectedFingerprint.getBytes(StandardCharsets.US_ASCII))) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The producer event was already used for different proposal evidence.");
        }
    }

    private MailProposalIngestDtos.Receipt receipt(
            MailProposalIngestRepository.Existing existing,
            boolean replayed) {
        return new MailProposalIngestDtos.Receipt(
                existing.proposalId(), existing.state(), replayed, existing.version());
    }
}
