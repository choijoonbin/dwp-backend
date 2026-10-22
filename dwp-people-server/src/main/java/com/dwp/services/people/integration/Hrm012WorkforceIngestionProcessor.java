package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012CanonicalJson;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceInputEnvelopeV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit worker entry point. No scheduler is bound by this slice. The method is
 * deliberately one Spring transaction so domain effects, evidence, cursor, audit,
 * outbox, run and receipt either all commit or all roll back.
 */
@Service
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public class Hrm012WorkforceIngestionProcessor {
    private static final Duration LEASE = Duration.ofMinutes(5);
    private final Hrm012WorkforceIngestionRepository repository;
    private final Hrm012WorkforceIngestionPorts.ArtifactPort artifacts;
    private final Hrm012WorkforceIngestionPorts.MappingPort mappings;
    private final Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort effects;
    private final Hrm012WorkforceIngestionPorts.QuarantineProtectorPort quarantine;
    private final Hrm012CanonicalJson canonical;
    private final ObjectMapper mapper;
    private final Clock clock;

    Hrm012WorkforceIngestionProcessor(
            Hrm012WorkforceIngestionRepository repository,
            Hrm012WorkforceIngestionPorts.ArtifactPort artifacts,
            Hrm012WorkforceIngestionPorts.MappingPort mappings,
            Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort effects,
            Hrm012WorkforceIngestionPorts.QuarantineProtectorPort quarantine,
            ObjectMapper mapper) {
        this(repository, artifacts, mappings, effects, quarantine,
                mapper, Clock.systemUTC());
    }

    Hrm012WorkforceIngestionProcessor(
            Hrm012WorkforceIngestionRepository repository,
            Hrm012WorkforceIngestionPorts.ArtifactPort artifacts,
            Hrm012WorkforceIngestionPorts.MappingPort mappings,
            Hrm012WorkforceIngestionPorts.TransactionalDomainEffectPort effects,
            Hrm012WorkforceIngestionPorts.QuarantineProtectorPort quarantine,
            ObjectMapper mapper,
            Clock clock) {
        this.repository = repository;
        this.artifacts = artifacts;
        this.mappings = mappings;
        this.effects = effects;
        this.quarantine = quarantine;
        this.mapper = mapper;
        this.canonical = new Hrm012CanonicalJson(mapper);
        this.clock = clock;
    }

    @Transactional
    public Processed process(long tenantId, UUID runPublicId, String workerId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw Hrm012Problem.unavailable(
                    "The atomic workforce reconciliation transaction is unavailable.");
        }
        Hrm012WorkforceIngestionRepository.Run run = repository.claim(
                tenantId, runPublicId, workerId, clock.instant(), LEASE)
                .orElseThrow(() -> Hrm012Problem.conflict(
                        "HRM012_RUN_LEASE_CONFLICT",
                        "The run is not eligible for this worker."));
        Hrm012WorkforceIngestionContract.Artifact artifact = artifacts.fetch(
                new Hrm012WorkforceIngestionPorts.ArtifactRequest(
                        tenantId, run.actorPublicId(), run.objectRef(),
                        run.expectedObjectVersion()));
        if (!run.objectRef().equals(artifact.objectRef())
                || run.expectedObjectVersion() != artifact.objectVersion()
                || !run.objectDigest().equals(Hrm012CanonicalJson.sha256(artifact.bytes()))) {
            throw Hrm012Problem.conflict("HRM012_OBJECT_DIGEST_CONFLICT",
                    "The immutable workforce object changed during recovery.");
        }
        Hrm012WorkforceInputEnvelopeV1 envelope =
                Hrm012WorkforceInputEnvelopeV1.parse(
                        artifact.bytes(), run.schemaKey(), run.schemaVersion(), mapper);
        List<Hrm012WorkforceIngestionContract.ItemEvidence> items =
                classify(run, envelope);
        Hrm012WorkforceIngestionContract.Counts counts =
                Hrm012WorkforceIngestionContract.Counts.prove(run.mode(), items);
        long validated = items.stream().filter(item ->
                item.outcome() == Hrm012WorkforceIngestionContract.ItemOutcome.VALIDATED)
                .count();
        counts.requireConservation(run.mode(), validated);
        String cursor = "hrm012-object:" + run.objectDigest();
        String cursorDigest = Hrm012CanonicalJson.sha256(cursor);
        String resultDigest = resultDigest(run, counts, items,
                run.mode() == Hrm012WorkforceIngestionContract.Mode.APPLY
                        ? cursorDigest : null);
        if (run.mode() == Hrm012WorkforceIngestionContract.Mode.VALIDATE_ONLY) {
            repository.reconcileValidation(
                    run, items, counts, resultDigest, clock.instant());
        } else {
            repository.reconcileApply(
                    run, items, counts, resultDigest, cursor,
                    cursorDigest, clock.instant());
        }
        return new Processed(run.runPublicId(), counts, resultDigest);
    }

    private List<Hrm012WorkforceIngestionContract.ItemEvidence> classify(
            Hrm012WorkforceIngestionRepository.Run run,
            Hrm012WorkforceInputEnvelopeV1 envelope) {
        List<Hrm012WorkforceIngestionContract.ItemEvidence> result = new ArrayList<>();
        Set<String> current = new HashSet<>();
        for (Hrm012WorkforceInputEnvelopeV1.Record record : envelope.records()) {
            String recordKey = Hrm012CanonicalJson.sha256(record.sourceRecordKey());
            String sourceVersion = Hrm012CanonicalJson.sha256(record.sourceVersion());
            String input = canonical.digest(record.payload());
            String duplicateKey = recordKey + ":" + sourceVersion + ":" + input;
            if (!current.add(duplicateKey) || repository.duplicate(
                    run.tenantId(), run.connectorId(), run.runId(),
                    recordKey, sourceVersion, input)) {
                result.add(evidence(record, recordKey, sourceVersion, input,
                        Hrm012WorkforceIngestionContract.ItemOutcome.DUPLICATE,
                        null, null, null, null));
                continue;
            }
            Hrm012WorkforceIngestionPorts.MappingResult mapped = mappings.validateAndMap(
                    new Hrm012WorkforceIngestionPorts.MappingRequest(
                            run.tenantId(), run.runPublicId(), run.connectorId(),
                            run.mappingId(), run.mappingVersion(), run.mappingSha256(),
                            run.schemaKey(), run.schemaVersion(), run.effectiveDate(), record));
            if (mapped == null || !mapped.accepted()) {
                String code = safeCode(mapped == null ? null : mapped.decisionCode(),
                        "SCHEMA_REJECTED");
                String summary = safeSummary(mapped == null ? null : mapped.redactedSummary(),
                        "Record rejected before restricted mapping.");
                result.add(evidence(record, recordKey, sourceVersion, input,
                        Hrm012WorkforceIngestionContract.ItemOutcome.REJECTED,
                        code, summary, null, null));
                continue;
            }
            JsonNode restricted = mapped.restrictedMappedValue();
            if (restricted == null) {
                throw Hrm012Problem.unavailable(
                        "Accepted mapping output is unavailable for safe processing.");
            }
            if (!restricted.isObject()) {
                if (run.mode() == Hrm012WorkforceIngestionContract.Mode.VALIDATE_ONLY) {
                    throw Hrm012Problem.unavailable(
                            "Accepted mapping output cannot be validated safely.");
                }
                result.add(quarantined(run, record, recordKey, sourceVersion, input,
                        restricted, "MAPPING_OUTPUT_QUARANTINED",
                        "Accepted mapping output had an unsupported shape."));
                continue;
            }
            if (run.mode() == Hrm012WorkforceIngestionContract.Mode.VALIDATE_ONLY) {
                result.add(evidence(record, recordKey, sourceVersion, input,
                        Hrm012WorkforceIngestionContract.ItemOutcome.VALIDATED,
                        null, null, null, null));
                continue;
            }
            String idempotency = Hrm012CanonicalJson.sha256(
                    run.runPublicId() + ":" + record.sequence() + ":" + input);
            Hrm012WorkforceIngestionPorts.EffectResult effect = effects.apply(
                    new Hrm012WorkforceIngestionPorts.EffectRequest(
                            run.tenantId(), run.runPublicId(), record.sequence(),
                            idempotency, restricted));
            if (effect != null && effect.applied()
                    && Hrm012WorkforceIngestionContract.digest(effect.effectDigest())) {
                result.add(evidence(record, recordKey, sourceVersion, input,
                        Hrm012WorkforceIngestionContract.ItemOutcome.APPLIED,
                        null, null, effect.effectDigest(), null));
                continue;
            }
            String summary = safeSummary(effect == null ? null : effect.redactedReason(),
                    "Accepted record could not be applied.");
            result.add(quarantined(run, record, recordKey, sourceVersion, input,
                    restricted, "APPLY_QUARANTINED", summary));
        }
        return List.copyOf(result);
    }

    private Hrm012WorkforceIngestionContract.ItemEvidence quarantined(
            Hrm012WorkforceIngestionRepository.Run run,
            Hrm012WorkforceInputEnvelopeV1.Record record,
            String recordKey,
            String sourceVersion,
            String input,
            JsonNode restricted,
            String findingCode,
            String summary) {
        Hrm012WorkforceIngestionPorts.ProtectedQuarantine protectedValue =
                quarantine.protect(new Hrm012WorkforceIngestionPorts.QuarantineRequest(
                        run.tenantId(), run.runPublicId(), record.sequence(),
                        restricted, summary));
        if (protectedValue == null || protectedValue.ciphertext() == null
                || protectedValue.ciphertext().length == 0
                || !Hrm012WorkforceIngestionContract.canonical(
                protectedValue.keyReference(), 255)
                || !Hrm012WorkforceIngestionContract.digest(
                protectedValue.envelopeDigest())) {
            throw Hrm012Problem.unavailable(
                    "Accepted record quarantine protection failed.");
        }
        return evidence(record, recordKey, sourceVersion, input,
                Hrm012WorkforceIngestionContract.ItemOutcome
                        .QUARANTINED_AFTER_ACCEPTANCE,
                findingCode, summary, null, protectedValue);
    }

    private Hrm012WorkforceIngestionContract.ItemEvidence evidence(
            Hrm012WorkforceInputEnvelopeV1.Record record,
            String recordKey,
            String sourceVersion,
            String input,
            Hrm012WorkforceIngestionContract.ItemOutcome outcome,
            String finding,
            String summary,
            String effectDigest,
            Hrm012WorkforceIngestionPorts.ProtectedQuarantine protectedValue) {
        Map<String, Object> digestMaterial = new LinkedHashMap<>();
        digestMaterial.put("sequence", record.sequence());
        digestMaterial.put("sourceRecordKeyDigest", recordKey);
        digestMaterial.put("sourceVersionDigest", sourceVersion);
        digestMaterial.put("inputDigest", input);
        digestMaterial.put("outcome", outcome.name());
        digestMaterial.put("findingCode", finding);
        digestMaterial.put("redactedSummary", summary);
        digestMaterial.put("appliedEffectDigest", effectDigest);
        digestMaterial.put("quarantineEnvelopeDigest",
                protectedValue == null ? null : protectedValue.envelopeDigest());
        return new Hrm012WorkforceIngestionContract.ItemEvidence(
                record.sequence(), recordKey, sourceVersion, input, outcome,
                canonical.digest(digestMaterial), finding, summary, effectDigest,
                protectedValue == null ? null : protectedValue.ciphertext(),
                protectedValue == null ? null : protectedValue.keyReference(),
                protectedValue == null ? null : protectedValue.envelopeDigest());
    }

    private String resultDigest(
            Hrm012WorkforceIngestionRepository.Run run,
            Hrm012WorkforceIngestionContract.Counts counts,
            List<Hrm012WorkforceIngestionContract.ItemEvidence> items,
            String cursorDigest) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("runPublicId", run.runPublicId());
        material.put("connectorPublicId", run.connectorPublicId());
        material.put("mappingVersion", run.mappingVersion());
        material.put("objectRef", run.objectRef());
        material.put("objectDigest", run.objectDigest());
        material.put("schemaKey", run.schemaKey());
        material.put("schemaVersion", run.schemaVersion());
        material.put("mode", run.mode().name());
        material.put("effectiveDate", run.effectiveDate());
        material.put("counts", counts);
        material.put("itemOutcomeDigests", items.stream()
                .map(Hrm012WorkforceIngestionContract.ItemEvidence::outcomeDigest)
                .sorted().toList());
        material.put("committedCursorDigest", cursorDigest);
        return canonical.digest(material);
    }

    private String safeCode(String value, String fallback) {
        return value != null && value.matches("^[A-Z][A-Z0-9_]{1,63}$")
                ? value : fallback;
    }

    private String safeSummary(String value, String fallback) {
        return Hrm012WorkforceIngestionContract.canonical(value, 512)
                ? value : fallback;
    }

    public record Processed(
            UUID runPublicId,
            Hrm012WorkforceIngestionContract.Counts counts,
            String resultDigest) {
    }
}
