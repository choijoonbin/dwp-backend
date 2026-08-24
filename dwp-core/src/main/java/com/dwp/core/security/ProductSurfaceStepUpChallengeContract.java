package com.dwp.core.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

/** Canonical wire contract shared by Auth issuers and product-service verifiers. */
public final class ProductSurfaceStepUpChallengeContract {

    public static final Set<String> HEADER_FIELDS = Set.of("alg", "typ", "kid");
    public static final Set<String> CLAIM_FIELDS = Set.of(
            "iss", "sub", "aud", "jti", "nonce", "iat", "nbf", "exp",
            "auth_time", "acr", "amr", "tenant_id", "owner_service_key",
            "command_contract_key", "activation_policy", "capability_contract_key",
            "context_key", "scope_ref", "target_type", "target_id", "target_version",
            "command_method", "command_path", "idempotency_key", "payload_sha256",
            "command_sha256", "decision_revision");

    private ProductSurfaceStepUpChallengeContract() {
    }

    public static String commandSha256(CommandMaterial material) {
        Objects.requireNonNull(material, "material");
        try {
            byte[] canonical = String.join("\n",
                    required(material.commandContractKey(), "commandContractKey"),
                    required(material.ownerServiceKey(), "ownerServiceKey"),
                    required(material.audience(), "audience"),
                    required(material.commandMethod(), "commandMethod"),
                    required(material.commandPath(), "commandPath"),
                    required(material.contextKey(), "contextKey"),
                    required(material.scopeRef(), "scopeRef"),
                    required(material.targetType(), "targetType"),
                    required(material.targetId(), "targetId"),
                    Long.toString(material.targetVersion()),
                    required(material.idempotencyKey(), "idempotencyKey"),
                    required(material.payloadSha256(), "payloadSha256"),
                    required(material.decisionRevision(), "decisionRevision"))
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(field + " is not canonical.");
        }
        return value;
    }

    public record CommandMaterial(
            String commandContractKey,
            String ownerServiceKey,
            String audience,
            String commandMethod,
            String commandPath,
            String contextKey,
            String scopeRef,
            String targetType,
            String targetId,
            long targetVersion,
            String idempotencyKey,
            String payloadSha256,
            String decisionRevision) {

        public CommandMaterial {
            if (targetVersion < 0) {
                throw new IllegalArgumentException("targetVersion must be non-negative.");
            }
        }
    }
}
