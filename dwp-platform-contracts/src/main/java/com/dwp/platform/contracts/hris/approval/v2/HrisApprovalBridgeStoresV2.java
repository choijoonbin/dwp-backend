package com.dwp.platform.contracts.hris.approval.v2;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** In-memory contract fixtures; production adapters replace these with owner ledgers. */
public final class HrisApprovalBridgeStoresV2 {

    private HrisApprovalBridgeStoresV2() {
    }

    public static final class Create<R>
            implements HrisApprovalBridgeV2.CreateIdempotencyStore<R> {
        private final Map<String, Entry<R>> aliases = new HashMap<>();

        @Override
        public synchronized R execute(
                HrisApprovalBridgeV2.CreateAliases value,
                String commandDigest,
                byte[] exactRequestBytes,
                Supplier<R> operation) {
            Set<String> keys = Set.of(
                    "semantic\n" + value.tenantId() + "\n" + value.subjectRef()
                            + "\n" + value.subjectRevision(),
                    "request\n" + value.tenantId() + "\n" + value.requestId(),
                    "idempotency\n" + value.tenantId() + "\n" + value.idempotencyKey());
            Set<Entry<R>> matches = new HashSet<>();
            for (String key : keys) {
                Entry<R> entry = aliases.get(key);
                if (entry != null) {
                    matches.add(entry);
                }
            }
            if (matches.size() > 1) {
                throw HrisApprovalBridgeV2.conflict("IDEMPOTENCY_ALIAS_SPLIT_BRAIN");
            }
            if (!matches.isEmpty()) {
                Entry<R> prior = matches.iterator().next();
                if (!constantTimeEquals(prior.commandDigest(), commandDigest)) {
                    throw HrisApprovalBridgeV2.conflict(
                            "IDEMPOTENCY_IDENTITY_COMMAND_MISMATCH");
                }
                return prior.response();
            }
            R response = Objects.requireNonNull(operation.get(), "operation response");
            Entry<R> entry = new Entry<>(commandDigest, exactRequestBytes, response);
            keys.forEach(key -> aliases.put(key, entry));
            return response;
        }
    }

    public static final class Callback<R>
            implements HrisApprovalBridgeV2.CallbackIdempotencyStore<R> {
        private final Map<String, Entry<R>> entries = new HashMap<>();

        @Override
        public synchronized R execute(
                long tenantId,
                UUID decisionEventId,
                String commandDigest,
                byte[] exactRequestBytes,
                Supplier<R> operation) {
            String key = tenantId + "\n" + decisionEventId;
            Entry<R> prior = entries.get(key);
            if (prior != null) {
                if (!constantTimeEquals(prior.commandDigest(), commandDigest)) {
                    throw HrisApprovalBridgeV2.conflict(
                            "IDEMPOTENCY_IDENTITY_COMMAND_MISMATCH");
                }
                return prior.response();
            }
            R response = Objects.requireNonNull(operation.get(), "operation response");
            entries.put(key, new Entry<>(commandDigest, exactRequestBytes, response));
            return response;
        }
    }

    public static final class Replay implements HrisWorkloadProofV2.ReplayStore {
        private final Map<UUID, Instant> tokens = new HashMap<>();

        @Override
        public synchronized boolean reserve(
                UUID proofId,
                UUID requestNonce,
                Instant expiresAt) {
            if (tokens.containsKey(proofId) || tokens.containsKey(requestNonce)) {
                return false;
            }
            tokens.put(proofId, expiresAt);
            tokens.put(requestNonce, expiresAt);
            return true;
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private record Entry<R>(String commandDigest, byte[] exactRequestBytes, R response) {
        private Entry {
            Objects.requireNonNull(commandDigest, "commandDigest");
            exactRequestBytes = Objects.requireNonNull(
                    exactRequestBytes, "exactRequestBytes").clone();
            Objects.requireNonNull(response, "response");
        }

        @Override
        public byte[] exactRequestBytes() {
            return exactRequestBytes.clone();
        }
    }
}
