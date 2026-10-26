package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;

/** Pure lifecycle and evidence guards shared by HTTP and worker paths. */
final class IntegrationDomainPolicy {

    private static final Map<DefinitionState, Set<DefinitionState>> DEFINITION = Map.of(
            DefinitionState.DRAFT, Set.of(DefinitionState.VALIDATED),
            DefinitionState.VALIDATED, Set.of(DefinitionState.TESTED),
            DefinitionState.TESTED, Set.of(DefinitionState.PENDING_APPROVAL),
            DefinitionState.PENDING_APPROVAL, Set.of(DefinitionState.APPROVED),
            DefinitionState.APPROVED, Set.of(DefinitionState.ACTIVE),
            DefinitionState.ACTIVE, Set.of(DefinitionState.SUSPENDED, DefinitionState.RETIRED),
            DefinitionState.SUSPENDED, Set.of(DefinitionState.ACTIVE, DefinitionState.RETIRED),
            DefinitionState.RETIRED, Set.of());

    private static final Map<MappingState, Set<MappingState>> MAPPING = Map.of(
            MappingState.DRAFT, Set.of(MappingState.VALIDATED),
            MappingState.VALIDATED, Set.of(MappingState.PENDING_APPROVAL),
            MappingState.PENDING_APPROVAL, Set.of(MappingState.APPROVED),
            MappingState.APPROVED, Set.of(MappingState.ACTIVE),
            MappingState.ACTIVE, Set.of(MappingState.SUSPENDED, MappingState.RETIRED),
            MappingState.SUSPENDED, Set.of(MappingState.ACTIVE, MappingState.RETIRED),
            MappingState.RETIRED, Set.of());

    private static final Map<ExecutionState, Set<ExecutionState>> EXECUTION = Map.ofEntries(
            Map.entry(ExecutionState.ACCEPTED,
                    Set.of(ExecutionState.TRANSFORMING, ExecutionState.CANCELLED)),
            Map.entry(ExecutionState.TRANSFORMING,
                    Set.of(ExecutionState.DISPATCHED, ExecutionState.FAILED,
                            ExecutionState.CANCELLED)),
            Map.entry(ExecutionState.DISPATCHED,
                    Set.of(ExecutionState.ACKNOWLEDGED, ExecutionState.RESULT_UNKNOWN,
                            ExecutionState.FAILED, ExecutionState.CANCELLED)),
            Map.entry(ExecutionState.ACKNOWLEDGED,
                    Set.of(ExecutionState.RECONCILING, ExecutionState.CANCELLED)),
            Map.entry(ExecutionState.RESULT_UNKNOWN,
                    Set.of(ExecutionState.RECONCILING, ExecutionState.CANCELLED)),
            Map.entry(ExecutionState.RECONCILING,
                    Set.of(ExecutionState.RECONCILED, ExecutionState.PARTIAL,
                            ExecutionState.FAILED, ExecutionState.RESULT_UNKNOWN,
                            ExecutionState.CANCELLED)),
            Map.entry(ExecutionState.FAILED,
                    Set.of(ExecutionState.ACCEPTED, ExecutionState.DEAD_LETTER,
                            ExecutionState.CANCELLED)),
            Map.entry(ExecutionState.PARTIAL,
                    Set.of(ExecutionState.ACCEPTED, ExecutionState.DEAD_LETTER,
                            ExecutionState.CANCELLED)),
            Map.entry(ExecutionState.RECONCILED, Set.of()),
            Map.entry(ExecutionState.DEAD_LETTER, Set.of()),
            Map.entry(ExecutionState.CANCELLED, Set.of()));

    private static final Map<ItemState, Set<ItemState>> ITEM = Map.ofEntries(
            Map.entry(ItemState.PENDING, Set.of(ItemState.TRANSFORMED,
                    ItemState.REJECTED_RETRYABLE, ItemState.REJECTED_FINAL,
                    ItemState.CANCELLED)),
            Map.entry(ItemState.TRANSFORMED,
                    Set.of(ItemState.DISPATCHED, ItemState.CANCELLED)),
            Map.entry(ItemState.DISPATCHED, Set.of(ItemState.RESULT_UNKNOWN,
                    ItemState.ACCEPTED, ItemState.REJECTED_RETRYABLE,
                    ItemState.REJECTED_FINAL, ItemState.CANCELLED)),
            Map.entry(ItemState.RESULT_UNKNOWN, Set.of(ItemState.ACCEPTED,
                    ItemState.REJECTED_RETRYABLE, ItemState.REJECTED_FINAL,
                    ItemState.RECONCILED, ItemState.CANCELLED)),
            Map.entry(ItemState.ACCEPTED,
                    Set.of(ItemState.RECONCILED, ItemState.CANCELLED)),
            Map.entry(ItemState.REJECTED_RETRYABLE,
                    Set.of(ItemState.DISPATCHED, ItemState.REJECTED_FINAL,
                            ItemState.CANCELLED)),
            Map.entry(ItemState.REJECTED_FINAL, Set.of()),
            Map.entry(ItemState.RECONCILED, Set.of()),
            Map.entry(ItemState.CANCELLED, Set.of()));

    private static final Map<AttemptState, Set<AttemptState>> ATTEMPT = Map.of(
            AttemptState.READY, Set.of(AttemptState.DISPATCHED, AttemptState.CANCELLED),
            AttemptState.DISPATCHED, Set.of(AttemptState.RESULT_UNKNOWN,
                    AttemptState.ACKNOWLEDGED, AttemptState.FAILED,
                    AttemptState.CANCELLED),
            AttemptState.RESULT_UNKNOWN, Set.of(AttemptState.RECONCILED,
                    AttemptState.FAILED, AttemptState.CANCELLED),
            AttemptState.ACKNOWLEDGED, Set.of(AttemptState.RECONCILED,
                    AttemptState.FAILED, AttemptState.CANCELLED),
            AttemptState.RECONCILED, Set.of(),
            AttemptState.FAILED, Set.of(),
            AttemptState.CANCELLED, Set.of());

    private IntegrationDomainPolicy() {}

    static void requireTransition(DefinitionState from, DefinitionState to) {
        require(DEFINITION, from, to, "definition");
    }

    static void requireTransition(MappingState from, MappingState to) {
        require(MAPPING, from, to, "mapping");
    }

    static void requireTransition(ExecutionState from, ExecutionState to) {
        require(EXECUTION, from, to, "execution");
    }

    static void requireTransition(ItemState from, ItemState to) {
        require(ITEM, from, to, "item");
    }

    static void requireTransition(AttemptState from, AttemptState to) {
        require(ATTEMPT, from, to, "attempt");
    }

    private static <T> void require(
            Map<T, Set<T>> matrix, T from, T to, String aggregate) {
        if (!matrix.getOrDefault(Objects.requireNonNull(from), Set.of())
                .contains(Objects.requireNonNull(to))) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "Illegal " + aggregate + " transition: " + from + " -> " + to);
        }
    }

    static void requireMakerChecker(
            long authorActorId, long decisionActorId, String authorScopeDigest,
            String decisionScopeDigest) {
        if (authorActorId == decisionActorId
                || constantTimeEquals(authorScopeDigest, decisionScopeDigest)) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "Author and approver/executor must be separate actors and scopes");
        }
    }

    static void requireOriginalProviderEvidence(
            String frozenProviderKey,
            String evidenceProviderKey,
            String frozenRequestDigest,
            String evidenceRequestDigest) {
        if (!constantTimeEquals(frozenProviderKey, evidenceProviderKey)
                || !constantTimeEquals(frozenRequestDigest, evidenceRequestDigest)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "Provider evidence is not bound to the original key and digest");
        }
    }

    static List<UUID> requireExactRetryableSet(
            List<UUID> requested,
            List<UUID> retryable,
            String requestedDigest) {
        List<UUID> normalizedRequested = requested.stream().sorted().toList();
        List<UUID> normalizedRetryable = retryable.stream().sorted().toList();
        if (!normalizedRequested.equals(normalizedRetryable)
                || !sha256(normalizedRequested.toString())
                .equals(requestedDigest)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "Retry selection must equal the frozen retryable failed-item set");
        }
        return normalizedRequested;
    }

    static void requireCancellationEvidence(
            boolean providerDispatchOccurred,
            String compensationPolicyRef,
            String terminalCompensationReceiptDigest) {
        if (providerDispatchOccurred
                && (compensationPolicyRef == null
                || terminalCompensationReceiptDigest == null
                || !terminalCompensationReceiptDigest.matches("^[a-f0-9]{64}$"))) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "Post-dispatch cancellation requires terminal compensation evidence");
        }
    }

    static String sha256(Object value) {
        return sha256(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value));
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }
}
