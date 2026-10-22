package com.dwp.services.people.hris.contracts.hrm012;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Closed owner contract for PEP-HRM-015 and its immutable evidence. */
public final class Hrm012WorkforceIngestionContract {
    public static final String COMMAND_TYPE = "WORKFORCE_INGESTION";
    public static final String ORIGINATING_ACTION = "INITIATE_WORKFORCE_INGESTION";
    public static final String ROUTE = "route.hcm.operations.workforce-ingestion.action";
    public static final String METHOD = "POST";
    public static final String OWNER_PATH = "/v1/hris/integrations/workforce-ingestions";
    public static final String PUBLIC_PATH = "/api/people/v1/hris/integrations/workforce-ingestions";
    public static final String CAPABILITY = "hcm.integration.workforce-ingestion.execute";
    public static final String FORBIDDEN_LEGACY_CAPABILITY = "hcm.integration.execute";
    public static final String DUTY = "HRIS_DUTY_INTEGRATION_EXECUTOR";
    public static final String PURPOSE = "HRIS_INTEGRATION";
    public static final String STEP_UP_POLICY = "STEPUP-MGMT-HIGH-V1";
    public static final String FEATURE = "HRIS_HRM_DATA_QUALITY_WORKBENCH";
    public static final String EVENT_TYPE = "WorkforceIngestionReconciled.v1";
    public static final Set<String> FIELD_GROUPS =
            Set.of("INTEGRATION_METADATA", "REDACTED_PAYLOAD");

    private Hrm012WorkforceIngestionContract() {
    }

    public enum Mode { VALIDATE_ONLY, APPLY }

    public enum ItemOutcome {
        VALIDATED,
        REJECTED,
        DUPLICATE,
        APPLIED,
        QUARANTINED_AFTER_ACCEPTANCE
    }

    public enum FieldDecision { VIEW, MASK, OMIT }

    public enum RunState {
        RECEIVED, RUNNING, FAILED_RETRYABLE, SUCCEEDED, REJECTED, DEAD
    }

    public record Command(
            String objectRef,
            String objectDigest,
            String schemaKey,
            int schemaVersion,
            Mode mode,
            LocalDate effectiveDate) {
        public Command {
            if (objectRef == null || !objectRef.matches("^obj_[A-Za-z0-9_-]{16,128}$")
                    || objectDigest == null || !objectDigest.matches("^[a-f0-9]{64}$")
                    || !canonical(schemaKey, 128) || schemaVersion < 1
                    || mode == null || effectiveDate == null) {
                throw Hrm012Problem.invalid("The closed workforce-ingestion command is invalid.");
            }
        }
    }

    public record CommandHeaders(
            String authorization,
            String idempotencyKey,
            String ifMatch,
            UUID correlationId,
            String purposeCode) {
        public CommandHeaders {
            if (!canonical(authorization, 8_192)
                    || !canonicalToken(idempotencyKey, 16, 128)
                    || !strongEtag(ifMatch) || correlationId == null
                    || purposeCode != null && !PURPOSE.equals(purposeCode)) {
                throw Hrm012Problem.invalid("The closed workforce-ingestion headers are invalid.");
            }
        }

        public long expectedVersion() {
            try {
                return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
            } catch (RuntimeException exception) {
                throw Hrm012Problem.invalid("If-Match is invalid.");
            }
        }
    }

    /** Complete authority data obtained from Auth, never from browser selectors. */
    public record TrustedAuthoritySnapshot(
            long tenantId,
            long numericUserId,
            UUID personPublicId,
            String markerJti,
            String markerSha256,
            String contextKey,
            String scopeRef,
            String populationScopeDigest,
            long authorizationRevision,
            long populationRevision,
            long fieldPolicyRevision,
            String postReceiptDecisionRevision,
            String dutyEvidenceRevision,
            String featureDecisionRevision,
            String purpose,
            UUID correlationId,
            String idempotencyKeySha256,
            String canonicalRequestHash) {
        public TrustedAuthoritySnapshot {
            if (tenantId < 1 || numericUserId < 1 || personPublicId == null
                    || !canonical(markerJti, 160) || !digest(markerSha256)
                    || !canonical(contextKey, 256) || !canonical(scopeRef, 512)
                    || !digest(populationScopeDigest) || authorizationRevision < 1
                    || populationRevision < 1 || fieldPolicyRevision < 1
                    || !canonical(postReceiptDecisionRevision, 500)
                    || !canonical(dutyEvidenceRevision, 500)
                    || !canonical(featureDecisionRevision, 500)
                    || !PURPOSE.equals(purpose) || correlationId == null
                    || !digest(idempotencyKeySha256) || !digest(canonicalRequestHash)) {
                throw Hrm012Problem.unavailable(
                        "A complete trusted pre-receipt authority snapshot is required.");
            }
        }

        public String originatingContextDigest() {
            String material = String.join("\n",
                    "tenantId=" + tenantId,
                    "numericUserId=" + numericUserId,
                    "personPublicId=" + personPublicId,
                    "markerJti=" + markerJti,
                    "markerSha256=" + markerSha256,
                    "contextKey=" + contextKey,
                    "scopeRef=" + scopeRef,
                    "populationScopeDigest=" + populationScopeDigest,
                    "authorizationRevision=" + authorizationRevision,
                    "populationRevision=" + populationRevision,
                    "fieldPolicyRevision=" + fieldPolicyRevision,
                    "postReceiptDecisionRevision=" + postReceiptDecisionRevision,
                    "dutyEvidenceRevision=" + dutyEvidenceRevision,
                    "featureDecisionRevision=" + featureDecisionRevision,
                    "purpose=" + purpose,
                    "correlationId=" + correlationId,
                    "idempotencyKeySha256=" + idempotencyKeySha256,
                    "canonicalRequestHash=" + canonicalRequestHash);
            return Hrm012CanonicalJson.sha256(material);
        }
    }

    public record Receipt(
            UUID receiptPublicId,
            long tenantId,
            UUID subjectPersonPublicId,
            String idempotencyKey,
            String requestHash,
            String lifecycleState,
            String resultCode,
            Map<String, Object> resultReference,
            UUID correlationId,
            UUID actorPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            String originatingContextDigest,
            Long expectedVersion,
            Instant createdAt,
            TrustedAuthoritySnapshot frozenAuthority,
            boolean newlyReserved) {
        public Receipt {
            resultReference = resultReference == null ? Map.of() : Map.copyOf(resultReference);
            Objects.requireNonNull(createdAt, "createdAt");
        }

        public boolean terminal() {
            return Set.of("SUCCEEDED", "REJECTED", "DEAD", "CANCELLED")
                    .contains(lifecycleState);
        }
    }

    public record OwnerMetadata(
            long tenantId,
            UUID subjectPersonPublicId,
            String objectRef,
            UUID targetPublicId,
            long objectVersion,
            UUID connectorInstanceId,
            UUID connectorPublicId,
            long connectorVersion,
            UUID mappingProfileId,
            UUID mappingPublicId,
            long mappingVersion,
            String mappingSha256,
            List<UUID> legalEntityPublicIds,
            long populationRevision,
            long fieldPolicyRevision,
            String populationScopeDigest,
            List<UUID> coveredConnectorPublicIds,
            List<UUID> coveredLegalEntityPublicIds,
            String cursorBeforeDigest,
            long cursorExpectedVersion,
            Map<String, FieldDecision> fieldDecisions) {
        public OwnerMetadata {
            legalEntityPublicIds = legalEntityPublicIds == null
                    ? List.of() : legalEntityPublicIds.stream().distinct().sorted().toList();
            coveredConnectorPublicIds = coveredConnectorPublicIds == null
                    ? List.of() : coveredConnectorPublicIds.stream().distinct().sorted().toList();
            coveredLegalEntityPublicIds = coveredLegalEntityPublicIds == null
                    ? List.of() : coveredLegalEntityPublicIds.stream().distinct().sorted().toList();
            if (tenantId < 1 || subjectPersonPublicId == null
                    || !canonical(objectRef, 160)
                    || targetPublicId == null || objectVersion < 0 || connectorInstanceId == null
                    || connectorPublicId == null || connectorVersion < 1
                    || mappingProfileId == null || mappingPublicId == null || mappingVersion < 1
                    || !digest(mappingSha256) || legalEntityPublicIds.isEmpty()
                    || populationRevision < 1 || fieldPolicyRevision < 1
                    || !digest(populationScopeDigest)
                    || coveredConnectorPublicIds.isEmpty()
                    || coveredLegalEntityPublicIds.isEmpty()
                    || !digest(cursorBeforeDigest) || cursorExpectedVersion < 0
                    || fieldDecisions == null
                    || !fieldDecisions.keySet().equals(FIELD_GROUPS)
                    || fieldDecisions.values().stream().anyMatch(Objects::isNull)) {
                throw Hrm012Problem.unavailable("Trusted ingestion metadata is incomplete.");
            }
            fieldDecisions = Map.copyOf(fieldDecisions);
        }
    }

    public record Artifact(String objectRef, long objectVersion, byte[] bytes) {
        public Artifact {
            bytes = bytes == null ? null : bytes.clone();
            if (objectRef == null || objectVersion < 0 || bytes == null) {
                throw Hrm012Problem.unavailable("The workforce object is unavailable.");
            }
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    public record ItemEvidence(
            int sequence,
            String sourceRecordKeyDigest,
            String sourceVersionDigest,
            String inputDigest,
            ItemOutcome outcome,
            String outcomeDigest,
            String findingCode,
            String redactedSummary,
            String appliedEffectDigest,
            byte[] quarantineCiphertext,
            String quarantineKeyReference,
            String quarantineEnvelopeDigest) {
        public ItemEvidence {
            quarantineCiphertext = quarantineCiphertext == null
                    ? null : quarantineCiphertext.clone();
            if (sequence < 1 || !digest(sourceRecordKeyDigest)
                    || !digest(sourceVersionDigest) || !digest(inputDigest)
                    || outcome == null || !digest(outcomeDigest)) {
                throw Hrm012Problem.invalid("Immutable item evidence is invalid.");
            }
            boolean quarantined = outcome == ItemOutcome.QUARANTINED_AFTER_ACCEPTANCE;
            boolean findingRequired = outcome == ItemOutcome.REJECTED || quarantined;
            if (findingRequired != (canonical(findingCode, 80)
                    && canonical(redactedSummary, 512))) {
                throw Hrm012Problem.invalid("Finding evidence shape is invalid.");
            }
            if (!findingRequired && (findingCode != null || redactedSummary != null)) {
                throw Hrm012Problem.invalid("Unexpected finding evidence is forbidden.");
            }
            if (quarantined != (quarantineCiphertext != null
                    && quarantineCiphertext.length > 0
                    && canonical(quarantineKeyReference, 255)
                    && digest(quarantineEnvelopeDigest))) {
                throw Hrm012Problem.invalid("Quarantine evidence shape is invalid.");
            }
            if (!quarantined && (quarantineCiphertext != null
                    || quarantineKeyReference != null || quarantineEnvelopeDigest != null)) {
                throw Hrm012Problem.invalid("Unexpected quarantine evidence is forbidden.");
            }
            if ((outcome == ItemOutcome.APPLIED) != digest(appliedEffectDigest)
                    || outcome != ItemOutcome.APPLIED && appliedEffectDigest != null) {
                throw Hrm012Problem.invalid("Applied-effect evidence shape is invalid.");
            }
        }

        @Override
        public byte[] quarantineCiphertext() {
            return quarantineCiphertext == null ? null : quarantineCiphertext.clone();
        }
    }

    public record Counts(
            long sourceCount,
            long acceptedCount,
            long rejectedCount,
            long duplicateCount,
            long quarantinedCount,
            long appliedCount) {
        public Counts {
            if (sourceCount < 0 || acceptedCount < 0 || rejectedCount < 0
                    || duplicateCount < 0 || quarantinedCount < 0 || appliedCount < 0) {
                throw Hrm012Problem.invalid("Ingestion counts cannot be negative.");
            }
        }

        public static Counts prove(Mode mode, List<ItemEvidence> items) {
            Objects.requireNonNull(mode, "mode");
            List<ItemEvidence> evidence = List.copyOf(items);
            long validated = count(evidence, ItemOutcome.VALIDATED);
            long rejected = count(evidence, ItemOutcome.REJECTED);
            long duplicate = count(evidence, ItemOutcome.DUPLICATE);
            long applied = count(evidence, ItemOutcome.APPLIED);
            long quarantined = count(evidence, ItemOutcome.QUARANTINED_AFTER_ACCEPTANCE);
            if (mode == Mode.VALIDATE_ONLY) {
                if (applied != 0 || quarantined != 0) {
                    throw Hrm012Problem.conflict("HRM012_VALIDATE_ONLY_EFFECT",
                            "VALIDATE_ONLY contains apply-side evidence.");
                }
                return new Counts(evidence.size(), 0, rejected, duplicate, 0, 0);
            }
            if (validated != 0) {
                throw Hrm012Problem.conflict("HRM012_APPLY_VALIDATED_OUTCOME",
                        "APPLY cannot contain VALIDATED evidence.");
            }
            return new Counts(evidence.size(), applied + quarantined,
                    rejected, duplicate, quarantined, applied);
        }

        public void requireConservation(Mode mode, long derivedValidatedCount) {
            if (mode == Mode.VALIDATE_ONLY) {
                if (acceptedCount != 0 || appliedCount != 0 || quarantinedCount != 0
                        || sourceCount != derivedValidatedCount + rejectedCount + duplicateCount) {
                    throw Hrm012Problem.conflict("HRM012_VALIDATE_ONLY_ACCOUNTING",
                            "VALIDATE_ONLY accounting is inconsistent.");
                }
                return;
            }
            if (derivedValidatedCount != 0
                    || sourceCount != acceptedCount + rejectedCount + duplicateCount
                    || acceptedCount != appliedCount + quarantinedCount) {
                throw Hrm012Problem.conflict("HRM012_APPLY_ACCOUNTING",
                        "APPLY accounting is inconsistent.");
            }
        }

        private static long count(List<ItemEvidence> items, ItemOutcome outcome) {
            return items.stream().filter(item -> item.outcome() == outcome).count();
        }
    }

    /** Exact ten-field business payload. */
    public record ReconciledEvent(
            UUID runPublicId,
            UUID connectorPublicId,
            long mappingVersion,
            long sourceCount,
            long acceptedCount,
            long rejectedCount,
            long duplicateCount,
            long quarantinedCount,
            long appliedCount,
            String resultDigest) {
        public ReconciledEvent {
            if (runPublicId == null || connectorPublicId == null || mappingVersion < 1
                    || sourceCount < 0 || acceptedCount < 0 || rejectedCount < 0
                    || duplicateCount < 0 || quarantinedCount < 0 || appliedCount < 0
                    || !digest(resultDigest)) {
                throw Hrm012Problem.invalid("The reconciled event is invalid.");
            }
        }
    }

    /** Exact canonical command-receipt response; no run or replay internals leak. */
    public record RunReceipt(
            UUID receiptId,
            String commandType,
            String status,
            String requestHash,
            UUID correlationId,
            Instant receivedAt,
            String originatingAction,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            Map<String, String> links) {
        public RunReceipt {
            if (receiptId == null || !COMMAND_TYPE.equals(commandType)
                    || !Set.of("RECEIVED", "VALIDATING", "RUNNING", "SUCCEEDED",
                            "REJECTED", "FAILED_RETRYABLE", "DEAD", "CANCELLED")
                            .contains(status)
                    || !digest(requestHash) || correlationId == null || receivedAt == null
                    || !ORIGINATING_ACTION.equals(originatingAction)
                    || subjectPrincipalPublicId == null || !digest(populationScopeDigest)
                    || fieldPolicyRevision < 1 || !PURPOSE.equals(purposeCode)
                    || authorizationRevision < 1 || links == null
                    || !links.keySet().equals(Set.of("self"))
                    || !canonical(links.get("self"), 2_048)) {
                throw Hrm012Problem.unavailable(
                        "The canonical workforce command receipt is incomplete.");
            }
            links = Map.copyOf(links);
        }
    }

    public static boolean digest(String value) {
        return value != null && value.matches("^[a-f0-9]{64}$");
    }

    public static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.equals(value.strip()) && value.indexOf(',') < 0
                && value.chars().noneMatch(character -> character < 0x20 || character == 0x7f);
    }

    private static boolean canonicalToken(String value, int minimum, int maximum) {
        return canonical(value, maximum) && value.length() >= minimum
                && value.matches("^[A-Za-z0-9._:-]+$");
    }

    private static boolean strongEtag(String value) {
        return value != null && value.matches("\"(?:0|[1-9][0-9]{0,18})\"");
    }
}
