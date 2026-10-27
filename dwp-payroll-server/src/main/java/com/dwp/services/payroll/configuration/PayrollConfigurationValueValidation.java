package com.dwp.services.payroll.configuration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Canonical normalization and validation for payroll model values. */
final class PayrollConfigurationValueValidation {

    static final String AST_REGISTRY_ID = "PAY_RULE_AST_V1";
    static final int AST_REGISTRY_VERSION = 1;

    private PayrollConfigurationValueValidation() {
    }

    static String requireDigest(String value, String name) {
        String normalized = requireText(value, name).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
        return normalized;
    }

    static String canonicalDecimal(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("^(?!-0(?:\\.0+)?$)-?(0|[1-9][0-9]*)(\\.[0-9]{1,12})?$")) {
            throw new IllegalArgumentException(name + " must be a canonical decimal string");
        }
        return normalized;
    }

    static String normalizeCurrency(String value) {
        if (value == null) return null;
        String normalized = requireText(value, "currency").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be a three-letter code");
        }
        return normalized;
    }

    static void requireCurrencyShape(Enum<?> valueType, String currency) {
        if (currencyBearing(valueType) != (currency != null)) {
            throw new IllegalArgumentException(
                    "currency must be present exactly for currency-bearing value types");
        }
    }

    static String normalizeInputKey(String value, String name) {
        if (value == null) return null;
        String normalized = requireText(value, name);
        if (!normalized.matches("[A-Z][A-Z0-9_.]{1,79}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }

    static void requireVersionPair(UUID id, Long expectedVersion, String name) {
        if (id == null && expectedVersion != null) {
            throw new IllegalArgumentException(
                    "expectedVersion requires an existing " + name + " id");
        }
        if (id != null && (expectedVersion == null || expectedVersion <= 0)) {
            throw new IllegalArgumentException(
                    "an existing " + name + " requires a positive expectedVersion");
        }
    }

    static String requireCode(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.strip();
        if (!normalized.equals(value) || normalized.length() > 500) {
            throw new IllegalArgumentException(name + " is not canonical");
        }
        return normalized;
    }

    static String normalizeOptionalText(String value, int max, String name) {
        if (value == null) return null;
        String normalized = requireText(value, name);
        if (normalized.length() > max) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }

    static <T> List<T> immutableList(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " cannot contain null");
        }
        return List.copyOf(values);
    }

    static String ownerRefSchemaId(
            String schemaId,
            UUID publicId,
            long revision) {
        schemaId = requireText(schemaId, "schemaId");
        if (schemaId.length() < 3 || schemaId.length() > 160) {
            throw new IllegalArgumentException("schemaId length is invalid");
        }
        Objects.requireNonNull(publicId, "publicId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision cannot be negative");
        }
        return schemaId;
    }

    static String ownerRefContentHash(
            String contentHash,
            Instant validFrom,
            Instant validToExclusive) {
        contentHash = requireDigest(contentHash, "contentHash");
        if (validFrom != null && validToExclusive != null
                && !validToExclusive.isAfter(validFrom)) {
            throw new IllegalArgumentException("validToExclusive must follow validFrom");
        }
        return contentHash;
    }

    static List<String> formulaNodeArguments(
            List<String> arguments,
            Enum<?> returnType) {
        arguments = immutableList(arguments, "arguments");
        if (arguments.size() > 3) {
            throw new IllegalArgumentException("formula nodes accept at most three arguments");
        }
        Objects.requireNonNull(returnType, "returnType");
        return arguments;
    }

    static String payFormulaAstRegistryId(
            String registryId,
            int registryVersion,
            Enum<?> declaredReturnType) {
        registryId = requireText(registryId, "registryId");
        if (!AST_REGISTRY_ID.equals(registryId)) {
            throw new IllegalArgumentException("formula registry is not allowlisted");
        }
        if (registryVersion != AST_REGISTRY_VERSION) {
            throw new IllegalArgumentException("formula registry version is not supported");
        }
        Objects.requireNonNull(declaredReturnType, "declaredReturnType");
        return registryId;
    }

    private static final Set<RoundingMode> ALLOWED_MODES = Set.of(
            RoundingMode.HALF_UP,
            RoundingMode.HALF_EVEN,
            RoundingMode.DOWN,
            RoundingMode.UP,
            RoundingMode.FLOOR,
            RoundingMode.CEILING);

    static void roundingPolicyFields(
            int scale,
            RoundingMode mode) {
        if (scale < 0 || scale > 12) {
            throw new IllegalArgumentException("rounding scale must be between 0 and 12");
        }
        Objects.requireNonNull(mode, "mode");
        if (!ALLOWED_MODES.contains(mode)) {
            throw new IllegalArgumentException("rounding mode is not allowlisted");
        }
    }

    static String roundingPolicyIncrement(
            int scale,
            String increment) {
        increment = canonicalDecimal(increment, "increment");
        BigDecimal parsed = new BigDecimal(increment);
        if (parsed.signum() <= 0 || parsed.scale() > scale) {
            throw new IllegalArgumentException(
                    "rounding increment must be positive and fit the configured scale");
        }
        return increment;
    }

    static String typedValueValue(
            Enum<?> type,
            String value,
            Boolean booleanValue,
            String currency) {
        if ("BOOLEAN".equals(type.name())) {
            if (booleanValue == null || value != null || currency != null) {
                throw new IllegalArgumentException(
                        "boolean values require booleanValue only");
            }
        } else {
            value = canonicalDecimal(value, "value");
            if (booleanValue != null) {
                throw new IllegalArgumentException(
                        "numeric values cannot contain booleanValue");
            }
            if (currencyBearing(type) && currency == null) {
                throw new IllegalArgumentException(
                        "currency-bearing values require an ISO currency");
            }
            if (!currencyBearing(type) && currency != null) {
                throw new IllegalArgumentException(
                        "non-currency values cannot contain currency");
            }
        }
        return value;
    }

    static String createElementVersionRequestCurrency(
            UUID elementId,
            Long expectedVersion,
            Enum<?> valueType,
            String currency,
            Object rounding,
            LocalDate effectiveFrom) {
        currency = normalizeCurrency(currency);
        Objects.requireNonNull(rounding, "rounding");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        requireVersionPair(elementId, expectedVersion, "element");
        requireCurrencyShape(valueType, currency);
        return currency;
    }

    static <T> Map<String, T> simulationCommandInputs(
            long expectedVersion,
            Map<String, T> inputs,
            Object rounding,
            long timeoutMillis) {
        if (expectedVersion <= 0) {
            throw new IllegalArgumentException("expectedVersion must be positive");
        }
        Objects.requireNonNull(inputs, "inputs");
        LinkedHashMap<String, T> normalized = new LinkedHashMap<>();
        inputs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> normalized.put(
                        normalizeInputKey(entry.getKey(), "input key"),
                        Objects.requireNonNull(entry.getValue(), "input value")));
        inputs = Map.copyOf(normalized);
        Objects.requireNonNull(rounding, "rounding");
        if (timeoutMillis < 1 || timeoutMillis > 2_000) {
            throw new IllegalArgumentException("timeoutMillis must be between 1 and 2000");
        }
        return inputs;
    }

    static <T> String simulationReportOutputDigest(
            boolean succeeded,
            T result,
            String outputDigest,
            Enum<?> failureCode,
            Instant simulatedAt) {
        Objects.requireNonNull(simulatedAt, "simulatedAt");
        if (succeeded) {
            Objects.requireNonNull(result, "result");
            outputDigest = requireDigest(outputDigest, "outputDigest");
            if (failureCode != null) {
                throw new IllegalArgumentException("successful simulation has no failureCode");
            }
        } else if (failureCode == null || result != null || outputDigest != null) {
            throw new IllegalArgumentException("failed simulation must expose only a failureCode");
        }
        return outputDigest;
    }

    static void elementVersionFields(
            long tenantId,
            UUID elementId,
            long version,
            Enum<?> status,
            UUID legalEntityId,
            UUID payrollGroupId,
            long authorId) {
        if (tenantId <= 0 || authorId <= 0 || version <= 0) {
            throw new IllegalArgumentException("tenant, author and version must be positive");
        }
        Objects.requireNonNull(elementId, "elementId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(legalEntityId, "legalEntityId");
        Objects.requireNonNull(payrollGroupId, "payrollGroupId");
    }

    static String elementVersionCurrency(
            Enum<?> valueType,
            String currency,
            Object rounding,
            LocalDate effectiveFrom,
            Instant recordedAt,
            UUID commandId) {
        currency = normalizeCurrency(currency);
        requireCurrencyShape(valueType, currency);
        Objects.requireNonNull(rounding, "rounding");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(commandId, "commandId");
        return currency;
    }

    static void formulaVersionFields(
            long tenantId,
            UUID formulaId,
            long version,
            Enum<?> status,
            UUID legalEntityId,
            UUID payrollGroupId,
            long authorId,
            Long publisherId) {
        if (tenantId <= 0 || authorId <= 0 || version <= 0
                || (publisherId != null && publisherId <= 0)) {
            throw new IllegalArgumentException(
                    "tenant, author, publisher and version identities must be positive");
        }
        Objects.requireNonNull(formulaId, "formulaId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(legalEntityId, "legalEntityId");
        Objects.requireNonNull(payrollGroupId, "payrollGroupId");
    }

    static String formulaVersionGoldenTestDigest(
            Enum<?> status,
            String goldenTestDigest,
            Object ast,
            long authorId,
            Long publisherId,
            Instant recordedAt,
            UUID commandId) {
        goldenTestDigest = requireDigest(goldenTestDigest, "goldenTestDigest");
        Objects.requireNonNull(ast, "ast");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(commandId, "commandId");
        if ("PUBLISHED".equals(status.name()) && publisherId == null) {
            throw new IllegalArgumentException("published formula requires publisherId");
        }
        if (publisherId != null && publisherId == authorId) {
            throw new IllegalArgumentException("formula author cannot be publisher");
        }
        return goldenTestDigest;
    }

    static void commandReceiptFields(
            long tenantId,
            long actorId,
            UUID receiptId,
            UUID commandId,
            Enum<?> commandType,
            long resultVersion) {
        if (tenantId <= 0 || actorId <= 0 || resultVersion <= 0) {
            throw new IllegalArgumentException(
                    "tenant, actor and resultVersion must be positive");
        }
        Objects.requireNonNull(receiptId, "receiptId");
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(commandType, "commandType");
    }

    static void payrollConfigurationPublishedFields(
            String specVersion,
            UUID id,
            String type,
            int schemaVersion,
            long tenantId,
            String aggregateType,
            long aggregateSequence) {
        if (!"1.0".equals(specVersion)
                || !"PayrollConfigurationPublished".equals(type)
                || schemaVersion != 1
                || tenantId <= 0
                || !"PayrollConfiguration".equals(aggregateType)
                || aggregateSequence <= 0) {
            throw new IllegalArgumentException("publication event envelope is invalid");
        }
        Objects.requireNonNull(id, "id");
    }

    static void publishedDataFields(
            UUID configurationId,
            long version,
            LocalDate effectiveFrom) {
        Objects.requireNonNull(configurationId, "configurationId");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    }

    static void mutationResultFields(
            Object receipt,
            Object element,
            Object formula) {
        Objects.requireNonNull(receipt, "receipt");
        if ((element == null) == (formula == null)) {
            throw new IllegalArgumentException(
                    "exactly one element or formula result is required");
        }
    }

    static boolean currencyBearing(Enum<?> type) {
        return "MONEY".equals(type.name()) || type.name().startsWith("UNIT_PRICE_");
    }

    private static final Set<String> OWNERS = Set.of(
            "HRM", "PER", "TIM", "PAY", "SYS", "AUTH", "APPROVAL");

    static String normalizeOwner(String owner) {
        owner = requireText(owner, "owner").toUpperCase(Locale.ROOT);
        if (!OWNERS.contains(owner)) {
            throw new IllegalArgumentException("owner is not allowlisted");
        }
        return owner;
    }
}
