package com.dwp.platform.contracts.hris.command.v1;

import com.dwp.platform.contracts.hris.generated.CanonicalHrisJson;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict, cross-service receipt for HRIS commands. The wire object is closed:
 * callers must not silently accept fields from a different receipt version.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonDeserialize(using = CommandReceiptV1.Deserializer.class)
public record CommandReceiptV1(
        @JsonSerialize(using = CanonicalHrisJson.UuidSerializer.class) UUID receiptId,
        String commandType,
        String originatingAction,
        @JsonSerialize(using = CanonicalHrisJson.UuidSerializer.class)
        UUID subjectPrincipalPublicId,
        String populationScopeDigest,
        long fieldPolicyRevision,
        String purposeCode,
        long authorizationRevision,
        String status,
        @JsonSerialize(using = CanonicalHrisJson.UuidSerializer.class) UUID correlationId,
        String requestDigest,
        @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class) Instant createdAt) {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9._-]{0,127}$");
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> STATUSES = Set.of(
            "ACCEPTED", "RUNNING", "SUCCEEDED", "REJECTED", "FAILED", "RESULT_UNKNOWN");

    public CommandReceiptV1 {
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        requireCode(commandType, "commandType");
        requireCode(originatingAction, "originatingAction");
        Objects.requireNonNull(subjectPrincipalPublicId,
                "subjectPrincipalPublicId must not be null");
        requireDigest(populationScopeDigest, "populationScopeDigest");
        if (fieldPolicyRevision < 1) {
            throw new IllegalArgumentException("fieldPolicyRevision must be positive");
        }
        requireCode(purposeCode, "purposeCode");
        if (authorizationRevision < 1) {
            throw new IllegalArgumentException("authorizationRevision must be positive");
        }
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("status is not canonical");
        }
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        requireDigest(requestDigest, "requestDigest");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static void requireCode(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a canonical code");
        }
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    /** Non-coercing, duplicate-rejecting, additionalProperties=false reader. */
    public static final class Deserializer extends JsonDeserializer<CommandReceiptV1> {
        @Override
        public CommandReceiptV1 deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {
            if (!parser.isExpectedStartObjectToken()) {
                return context.reportInputMismatch(
                        CommandReceiptV1.class,
                        "CommandReceiptV1 must be encoded as a JSON object");
            }
            UUID receiptId = null;
            String commandType = null;
            String originatingAction = null;
            UUID subjectPrincipalPublicId = null;
            String populationScopeDigest = null;
            long fieldPolicyRevision = 0;
            String purposeCode = null;
            long authorizationRevision = 0;
            String status = null;
            UUID correlationId = null;
            String requestDigest = null;
            Instant createdAt = null;
            Set<String> seen = new java.util.HashSet<>();

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (!parser.hasToken(JsonToken.FIELD_NAME)) {
                    return context.reportInputMismatch(
                            CommandReceiptV1.class,
                            "CommandReceiptV1 contains a non-field token");
                }
                String name = parser.currentName();
                if (!seen.add(name)) {
                    return context.reportInputMismatch(
                            CommandReceiptV1.class, "duplicate property %s", name);
                }
                if (parser.nextToken() == null) {
                    return context.reportInputMismatch(
                            CommandReceiptV1.class,
                            "CommandReceiptV1 ends before a property value");
                }
                switch (name) {
                    case "receiptId" -> receiptId = CanonicalHrisJson.readUuid(
                            parser, context, name, false);
                    case "commandType" -> commandType = CanonicalHrisJson.readString(
                            parser, context, name, false);
                    case "originatingAction" -> originatingAction = CanonicalHrisJson.readString(
                            parser, context, name, false);
                    case "subjectPrincipalPublicId" -> subjectPrincipalPublicId =
                            CanonicalHrisJson.readUuid(parser, context, name, false);
                    case "populationScopeDigest" -> populationScopeDigest =
                            CanonicalHrisJson.readString(parser, context, name, false);
                    case "fieldPolicyRevision" -> fieldPolicyRevision =
                            CanonicalHrisJson.readLong(parser, context, name, false);
                    case "purposeCode" -> purposeCode = CanonicalHrisJson.readString(
                            parser, context, name, false);
                    case "authorizationRevision" -> authorizationRevision =
                            CanonicalHrisJson.readLong(parser, context, name, false);
                    case "status" -> status = CanonicalHrisJson.readString(
                            parser, context, name, false);
                    case "correlationId" -> correlationId = CanonicalHrisJson.readUuid(
                            parser, context, name, false);
                    case "requestDigest" -> requestDigest = CanonicalHrisJson.readString(
                            parser, context, name, false);
                    case "createdAt" -> createdAt = CanonicalHrisJson.readInstant(
                            parser, context, name, false);
                    default -> {
                        return context.reportInputMismatch(
                                CommandReceiptV1.class, "unknown property %s", name);
                    }
                }
            }
            Set<String> required = Set.of(
                    "receiptId", "commandType", "originatingAction",
                    "subjectPrincipalPublicId", "populationScopeDigest",
                    "fieldPolicyRevision", "purposeCode", "authorizationRevision",
                    "status", "correlationId", "requestDigest", "createdAt");
            if (!seen.containsAll(required)) {
                Set<String> missing = new java.util.TreeSet<>(required);
                missing.removeAll(seen);
                return context.reportInputMismatch(
                        CommandReceiptV1.class, "missing required properties %s", missing);
            }
            return new CommandReceiptV1(
                    receiptId, commandType, originatingAction, subjectPrincipalPublicId,
                    populationScopeDigest, fieldPolicyRevision, purposeCode,
                    authorizationRevision, status, correlationId, requestDigest, createdAt);
        }
    }
}
