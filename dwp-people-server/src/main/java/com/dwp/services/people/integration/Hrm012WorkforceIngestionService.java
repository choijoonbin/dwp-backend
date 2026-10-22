package com.dwp.services.people.integration;

import com.dwp.core.security.Hrm012OwnerReceiptAuthorityVerifier;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012CanonicalJson;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceInputEnvelopeV1;
import com.dwp.services.people.security.Hrm012OwnerReceiptAuthorityContext;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Receipt-first initiation; it returns 202 without an apply or cursor claim. */
@Service
@ConditionalOnProperty(prefix = "dwp.hris.data-quality-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public class Hrm012WorkforceIngestionService {
    private final Hrm012WorkforceIngestionAuthority authority;
    private final Hrm012CommandReceiptRepository receipts;
    private final Hrm012WorkforceIngestionRepository runs;
    private final Hrm012WorkforceIngestionPorts.OwnerMetadataPort metadata;
    private final Hrm012WorkforceIngestionPorts.ArtifactPort artifacts;
    private final Hrm012CanonicalJson canonical;
    private final ObjectMapper mapper;
    private final Clock clock;

    Hrm012WorkforceIngestionService(
            Hrm012WorkforceIngestionAuthority authority,
            Hrm012CommandReceiptRepository receipts,
            Hrm012WorkforceIngestionRepository runs,
            Hrm012WorkforceIngestionPorts.OwnerMetadataPort metadata,
            Hrm012WorkforceIngestionPorts.ArtifactPort artifacts,
            ObjectMapper mapper) {
        this(authority, receipts, runs, metadata, artifacts, mapper, Clock.systemUTC());
    }

    Hrm012WorkforceIngestionService(
            Hrm012WorkforceIngestionAuthority authority,
            Hrm012CommandReceiptRepository receipts,
            Hrm012WorkforceIngestionRepository runs,
            Hrm012WorkforceIngestionPorts.OwnerMetadataPort metadata,
            Hrm012WorkforceIngestionPorts.ArtifactPort artifacts,
            ObjectMapper mapper,
            Clock clock) {
        this.authority = authority;
        this.receipts = receipts;
        this.runs = runs;
        this.metadata = metadata;
        this.artifacts = artifacts;
        this.mapper = mapper;
        this.canonical = new Hrm012CanonicalJson(mapper);
        this.clock = clock;
    }

    public Hrm012WorkforceIngestionContract.RunReceipt initiate(
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.CommandHeaders headers) {
        PeopleRequestContext.Actor actor;
        Hrm012OwnerReceiptAuthorityVerifier.Evidence marker;
        try {
            actor = PeopleRequestContext.require();
            marker = Hrm012OwnerReceiptAuthorityContext.require();
        } catch (RuntimeException exception) {
            throw Hrm012Problem.denied("Trusted HRM-012 request identity is unavailable.");
        }
        Hrm012WorkforceIngestionAuthority.VerifiedIdentity identity =
                authority.verifyIdentity(actor, marker, headers);
        String payloadDigest = canonical.digest(command);
        String requestHash = requestHash(command, headers, payloadDigest);

        Optional<Hrm012WorkforceIngestionContract.Receipt> prior =
                receipts.findExisting(actor.tenantId(), actor.personPublicId(),
                        headers.idempotencyKey());
        if (prior.isPresent()) {
            Hrm012WorkforceIngestionContract.Receipt existing = prior.get();
            if (!requestHash.equals(existing.requestHash())) {
                throw Hrm012Problem.conflict("HRM012_IDEMPOTENCY_HASH_CONFLICT",
                        "The idempotency tuple was used with different command bytes.");
            }
            if (existing.terminal()) return terminal(existing);
            return continueReceipt(
                    identity, command, headers, payloadDigest, existing, null, null);
        }

        Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot snapshot =
                authority.preReceipt(identity, command, headers, requestHash);
        Instant now = clock.instant();
        Hrm012WorkforceIngestionContract.Receipt receipt = receipts.reserveOrRead(
                new Hrm012CommandReceiptRepository.Reservation(
                        actor.tenantId(), actor.personPublicId(), headers.idempotencyKey(),
                        requestHash, snapshot, headers.expectedVersion(),
                        headers.correlationId(), now));
        if (receipt.terminal()) return terminal(receipt);
        return continueReceipt(
                identity, command, headers, payloadDigest, receipt, null, null);
    }

    /**
     * Internal authorized terminal replay. It is intentionally not exposed by the
     * six-field public command controller and always writes a new linked receipt/run.
     */
    Hrm012WorkforceIngestionContract.RunReceipt replayTerminal(
            UUID originalReceiptId,
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.CommandHeaders headers) {
        PeopleRequestContext.Actor actor;
        Hrm012OwnerReceiptAuthorityVerifier.Evidence marker;
        try {
            actor = PeopleRequestContext.require();
            marker = Hrm012OwnerReceiptAuthorityContext.require();
        } catch (RuntimeException exception) {
            throw Hrm012Problem.denied("Trusted HRM-012 replay identity is unavailable.");
        }
        Hrm012WorkforceIngestionAuthority.VerifiedIdentity identity =
                authority.verifyIdentity(actor, marker, headers);
        Hrm012WorkforceIngestionContract.Receipt original = receipts.findById(
                        actor.tenantId(), originalReceiptId)
                .orElseThrow(() -> Hrm012Problem.conflict(
                        "HRM012_REPLAY_ORIGINAL_MISSING",
                        "The original workforce receipt is unavailable."));
        if (!original.terminal()
                || !actor.personPublicId().equals(original.subjectPersonPublicId())) {
            throw Hrm012Problem.denied(
                    "Only an owned terminal workforce receipt may be replayed.");
        }
        Hrm012WorkforceIngestionRepository.Run originalRun = runs.findByReceipt(
                        actor.tenantId(), originalReceiptId)
                .filter(run -> Set.of("SUCCEEDED", "REJECTED", "DEAD")
                        .contains(run.state()))
                .orElseThrow(() -> Hrm012Problem.conflict(
                        "HRM012_REPLAY_RUN_MISSING",
                        "The original terminal workforce run is unavailable."));
        requireReplayCommand(originalRun, command);

        String payloadDigest = canonical.digest(command);
        String requestHash = replayRequestHash(
                command, headers, payloadDigest, originalReceiptId, originalRun.runId());
        Optional<Hrm012WorkforceIngestionContract.Receipt> prior =
                receipts.findExisting(actor.tenantId(), actor.personPublicId(),
                        headers.idempotencyKey());
        Hrm012WorkforceIngestionContract.Receipt receipt;
        if (prior.isPresent()) {
            receipt = prior.get();
            if (!requestHash.equals(receipt.requestHash())) {
                throw Hrm012Problem.conflict("HRM012_IDEMPOTENCY_HASH_CONFLICT",
                        "The replay idempotency tuple has different command bytes.");
            }
            Optional<Hrm012WorkforceIngestionRepository.Run> priorRun =
                    runs.findByReceipt(actor.tenantId(), receipt.receiptPublicId());
            if (priorRun.isPresent()) {
                requireReplayLink(priorRun.get(), originalReceiptId, originalRun.runId());
            }
            if (receipt.terminal()) return terminal(receipt);
        } else {
            Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot snapshot =
                    authority.preReceipt(identity, command, headers, requestHash);
            receipt = receipts.reserveOrRead(
                    new Hrm012CommandReceiptRepository.Reservation(
                            actor.tenantId(), actor.personPublicId(),
                            headers.idempotencyKey(), requestHash, snapshot,
                            headers.expectedVersion(), headers.correlationId(),
                            clock.instant()));
        }
        return continueReceipt(identity, command, headers, payloadDigest, receipt,
                originalReceiptId, originalRun.runId());
    }

    private Hrm012WorkforceIngestionContract.RunReceipt continueReceipt(
            Hrm012WorkforceIngestionAuthority.VerifiedIdentity identity,
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.CommandHeaders headers,
            String payloadDigest,
            Hrm012WorkforceIngestionContract.Receipt receipt,
            UUID linkedReplayReceiptId,
            UUID linkedReplayRunId) {
        Hrm012OwnerReceiptAuthorityVerifier.Evidence marker = identity.marker();
        try {
            receipts.bindMarker(receipt.tenantId(), receipt.receiptPublicId(), marker.jti(),
                    marker.markerSha256(), marker.expiresAt(), clock.instant());
        } catch (Hrm012CommandReceiptRepository.TerminalReceiptObserved observed) {
            return terminal(observed.receipt());
        }
        try {
            authority.postReceipt(identity, command, headers, receipt, payloadDigest);
        } catch (Hrm012Problem problem) {
            try {
                receipts.markAuthority(receipt.tenantId(), receipt.receiptPublicId(),
                        marker.jti(), problem.retryable()
                                ? "TRANSIENT_UNAVAILABLE" : "DENIED");
            } catch (Hrm012CommandReceiptRepository.TerminalReceiptObserved observed) {
                return terminal(observed.receipt());
            }
            throw problem;
        }
        // Once exact post-receipt authority is ALLOWED, this marker is immutable.
        // Metadata/artifact/payload failures may retry only with this same
        // unexpired jti; a fresh marker can never replace VERIFIED_ALLOWED.
        try {
            receipts.markAuthority(receipt.tenantId(), receipt.receiptPublicId(),
                    marker.jti(), "VERIFIED_ALLOWED");
        } catch (Hrm012CommandReceiptRepository.TerminalReceiptObserved observed) {
            return terminal(observed.receipt());
        }
        Hrm012WorkforceIngestionContract.OwnerMetadata owner = metadata.resolve(
                new Hrm012WorkforceIngestionPorts.MetadataRequest(
                        receipt.tenantId(), receipt.subjectPersonPublicId(), command,
                        receipt.frozenAuthority(), headers.expectedVersion()));
        requireMetadata(receipt, command, headers, owner);
        Hrm012WorkforceIngestionContract.Artifact artifact = artifacts.fetch(
                new Hrm012WorkforceIngestionPorts.ArtifactRequest(
                        receipt.tenantId(), receipt.subjectPersonPublicId(),
                        command.objectRef(), owner.objectVersion()));
        requireArtifact(command, owner, artifact);
        Hrm012WorkforceInputEnvelopeV1.parse(
                artifact.bytes(), command.schemaKey(), command.schemaVersion(), mapper);
        Hrm012WorkforceIngestionRepository.Run run = runs.createRun(
                new Hrm012WorkforceIngestionRepository.CreateRun(
                        receipt.tenantId(), receipt.receiptPublicId(),
                        linkedReplayReceiptId, linkedReplayRunId,
                        command, owner, receipt.populationScopeDigest()));
        return response(receipt, run.state());
    }

    private void requireReplayCommand(
            Hrm012WorkforceIngestionRepository.Run original,
            Hrm012WorkforceIngestionContract.Command command) {
        if (!original.objectRef().equals(command.objectRef())
                || !original.objectDigest().equals(command.objectDigest())
                || !original.schemaKey().equals(command.schemaKey())
                || original.schemaVersion() != command.schemaVersion()
                || original.mode() != command.mode()
                || !original.effectiveDate().equals(command.effectiveDate())) {
            throw Hrm012Problem.conflict("HRM012_REPLAY_LINEAGE_CONFLICT",
                    "A terminal replay must preserve the immutable command lineage.");
        }
    }

    private void requireReplayLink(
            Hrm012WorkforceIngestionRepository.Run replay,
            UUID originalReceiptId,
            UUID originalRunId) {
        if (!originalReceiptId.equals(replay.linkedReplayReceiptId())
                || !originalRunId.equals(replay.linkedReplayRunId())) {
            throw Hrm012Problem.conflict("HRM012_REPLAY_LINEAGE_CONFLICT",
                    "The replay receipt is already linked to different evidence.");
        }
    }

    private void requireArtifact(
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.OwnerMetadata metadata,
            Hrm012WorkforceIngestionContract.Artifact artifact) {
        if (!command.objectRef().equals(artifact.objectRef())
                || metadata.objectVersion() != artifact.objectVersion()
                || !command.objectDigest().equals(
                Hrm012CanonicalJson.sha256(artifact.bytes()))) {
            throw Hrm012Problem.conflict("HRM012_OBJECT_DIGEST_CONFLICT",
                    "The immutable workforce object does not match its command.");
        }
    }

    private void requireMetadata(
            Hrm012WorkforceIngestionContract.Receipt receipt,
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.CommandHeaders headers,
            Hrm012WorkforceIngestionContract.OwnerMetadata owner) {
        Hrm012WorkforceIngestionContract.TrustedAuthoritySnapshot frozen =
                receipt.frozenAuthority();
        boolean exactIdentity = owner.tenantId() == receipt.tenantId()
                && receipt.subjectPersonPublicId().equals(owner.subjectPersonPublicId())
                && command.objectRef().equals(owner.objectRef())
                && owner.objectVersion() == headers.expectedVersion()
                && receipt.expectedVersion() != null
                && owner.objectVersion() == receipt.expectedVersion();
        boolean exactFrozenPolicy = owner.populationRevision()
                == frozen.populationRevision()
                && owner.fieldPolicyRevision() == frozen.fieldPolicyRevision()
                && owner.populationScopeDigest().equals(frozen.populationScopeDigest());
        boolean fullPopulationCoverage = owner.coveredConnectorPublicIds().equals(
                java.util.List.of(owner.connectorPublicId()))
                && owner.coveredLegalEntityPublicIds().equals(
                owner.legalEntityPublicIds());
        boolean viewAllowed = Hrm012WorkforceIngestionContract.FIELD_GROUPS.stream()
                .allMatch(group -> owner.fieldDecisions().get(group)
                        == Hrm012WorkforceIngestionContract.FieldDecision.VIEW);
        if (!exactIdentity || !exactFrozenPolicy
                || !fullPopulationCoverage || !viewAllowed) {
            throw Hrm012Problem.denied(
                    "Trusted workforce metadata is stale, partial or not fully viewable.");
        }
    }

    private Hrm012WorkforceIngestionContract.RunReceipt terminal(
            Hrm012WorkforceIngestionContract.Receipt receipt) {
        return response(receipt, receipt.lifecycleState());
    }

    private Hrm012WorkforceIngestionContract.RunReceipt response(
            Hrm012WorkforceIngestionContract.Receipt receipt, String status) {
        return new Hrm012WorkforceIngestionContract.RunReceipt(
                receipt.receiptPublicId(), Hrm012WorkforceIngestionContract.COMMAND_TYPE,
                status, receipt.requestHash(), receipt.correlationId(), receipt.createdAt(),
                Hrm012WorkforceIngestionContract.ORIGINATING_ACTION,
                receipt.subjectPersonPublicId(), receipt.populationScopeDigest(),
                receipt.fieldPolicyRevision(), receipt.purposeCode(),
                receipt.authorizationRevision(), Map.of("self",
                "/v1/hris/command-receipts/" + receipt.receiptPublicId()));
    }

    private String requestHash(
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.CommandHeaders headers,
            String payloadDigest) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("method", Hrm012WorkforceIngestionContract.METHOD);
        binding.put("path", Hrm012WorkforceIngestionContract.OWNER_PATH);
        binding.put("objectRef", command.objectRef());
        binding.put("payloadSha256", payloadDigest);
        binding.put("idempotencyKeySha256",
                Hrm012CanonicalJson.sha256(headers.idempotencyKey()));
        binding.put("ifMatch", headers.ifMatch());
        binding.put("correlationId", headers.correlationId().toString());
        binding.put("purpose", Hrm012WorkforceIngestionContract.PURPOSE);
        return canonical.digest(binding);
    }

    private String replayRequestHash(
            Hrm012WorkforceIngestionContract.Command command,
            Hrm012WorkforceIngestionContract.CommandHeaders headers,
            String payloadDigest,
            UUID originalReceiptId,
            UUID originalRunId) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("requestHash", requestHash(command, headers, payloadDigest));
        binding.put("replayOfReceiptPublicId", originalReceiptId);
        binding.put("replayOfRunId", originalRunId);
        return canonical.digest(binding);
    }
}
