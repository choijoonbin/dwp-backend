package com.dwp.services.payroll.configuration;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Exact transport and persisted values for BASE-TFR-PAY-006. */
public final class PayrollConfigurationModels {

    public static final String AST_REGISTRY_ID = "PAY_RULE_AST_V1";
    public static final int AST_REGISTRY_VERSION = 1;

    private PayrollConfigurationModels() {
    }

    public enum PayValueType {
        BOOLEAN,
        NUMBER,
        RATE,
        MONEY,
        QUANTITY_MINUTE,
        QUANTITY_HOUR,
        QUANTITY_DAY,
        UNIT_PRICE_PER_MINUTE,
        UNIT_PRICE_PER_HOUR,
        UNIT_PRICE_PER_DAY;

        boolean numeric() {
            return this != BOOLEAN;
        }

        boolean currencyBearing() {
            return this == MONEY || name().startsWith("UNIT_PRICE_");
        }
    }

    public enum FormulaOperator {
        LITERAL,
        INPUT,
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        MIN,
        MAX,
        COMPARE_EQ,
        COMPARE_LT,
        AND,
        OR,
        IF,
        ROUND,
        CONVERT_MINUTE_HOUR,
        LOOKUP,
        SUM,
        DATE_DIFFERENCE;

        @JsonCreator
        public static FormulaOperator fromJson(String value) {
            try {
                return FormulaOperator.valueOf(value);
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new IllegalArgumentException(
                        "FUNCTION_NOT_ALLOWED: formula operator is not allowlisted", exception);
            }
        }

        @JsonValue
        public String jsonValue() {
            return name();
        }
    }

    public enum Lifecycle {
        DRAFT,
        VALIDATED,
        PUBLISHED
    }

    public enum ResourceType {
        ELEMENT,
        FORMULA
    }

    public enum CommandType {
        CREATE_ELEMENT_VERSION,
        CREATE_FORMULA_VERSION,
        VALIDATE_FORMULA,
        SIMULATE_FORMULA,
        PUBLISH_FORMULA
    }

    public enum ReceiptStatus {
        ACCEPTED,
        RUNNING,
        SUCCEEDED,
        REJECTED,
        FAILED,
        RESULT_UNKNOWN
    }

    public enum FormulaErrorCode {
        TYPE,
        CYCLE,
        UNKNOWN_NODE,
        DUPLICATE_NODE,
        INVALID_ARITY,
        INVALID_LITERAL,
        INPUT_REQUIRED,
        UNIT_MISMATCH,
        CURRENCY_MISMATCH,
        ROUNDING_REQUIRED,
        LIMIT_EXCEEDED,
        TIMEOUT,
        SANDBOX_VIOLATION,
        FUNCTION_NOT_ALLOWED
    }

    public record OwnerRef(
            @NotBlank String owner,
            @NotBlank String schemaId,
            @NotNull UUID publicId,
            @Min(0) long revision,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contentHash,
            Instant validFrom,
            Instant validToExclusive) {

        private static final Set<String> OWNERS = Set.of(
                "HRM", "PER", "TIM", "PAY", "SYS", "AUTH", "APPROVAL");

        public OwnerRef {
            owner = requireText(owner, "owner").toUpperCase(Locale.ROOT);
            if (!OWNERS.contains(owner)) {
                throw new IllegalArgumentException("owner is not allowlisted");
            }
            schemaId = requireText(schemaId, "schemaId");
            if (schemaId.length() < 3 || schemaId.length() > 160) {
                throw new IllegalArgumentException("schemaId length is invalid");
            }
            Objects.requireNonNull(publicId, "publicId");
            if (revision < 0) {
                throw new IllegalArgumentException("revision cannot be negative");
            }
            contentHash = requireDigest(contentHash, "contentHash");
            if (validFrom != null && validToExclusive != null
                    && !validToExclusive.isAfter(validFrom)) {
                throw new IllegalArgumentException("validToExclusive must follow validFrom");
            }
        }
    }

    public record FormulaNode(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,63}") String nodeId,
            @NotNull FormulaOperator operator,
            @NotNull @Size(max = 3) List<@NotBlank String> arguments,
            @NotNull PayValueType returnType,
            Object value,
            String currency,
            String inputKey,
            @Valid OwnerRef roundingRef,
            @Valid OwnerRef lookupRef,
            String collectionInputKey) {

        public FormulaNode {
            nodeId = requireCode(nodeId, "nodeId");
            Objects.requireNonNull(operator, "operator");
            arguments = immutableList(arguments, "arguments");
            if (arguments.size() > 3) {
                throw new IllegalArgumentException("formula nodes accept at most three arguments");
            }
            Objects.requireNonNull(returnType, "returnType");
            currency = normalizeCurrency(currency);
            inputKey = normalizeInputKey(inputKey, "inputKey");
            collectionInputKey = normalizeInputKey(collectionInputKey, "collectionInputKey");
        }
    }

    @JsonDeserialize(using = PayFormulaAstDeserializer.class)
    public record PayFormulaAst(
            @NotBlank String registryId,
            @Positive int registryVersion,
            @NotNull PayValueType declaredReturnType,
            @NotBlank String rootNodeId,
            @NotNull @Size(min = 1, max = 2048) List<@Valid FormulaNode> nodes) {

        public PayFormulaAst {
            registryId = requireText(registryId, "registryId");
            if (!AST_REGISTRY_ID.equals(registryId)) {
                throw new IllegalArgumentException("formula registry is not allowlisted");
            }
            if (registryVersion != AST_REGISTRY_VERSION) {
                throw new IllegalArgumentException("formula registry version is not supported");
            }
            Objects.requireNonNull(declaredReturnType, "declaredReturnType");
            rootNodeId = requireCode(rootNodeId, "rootNodeId");
            nodes = immutableList(nodes, "nodes");
            if (nodes.isEmpty() || nodes.size() > 2048) {
                throw new IllegalArgumentException("formula node count must be between 1 and 2048");
            }
        }
    }

    public record RoundingPolicy(
            @Min(0) @Max(12) int scale,
            @NotNull RoundingMode mode,
            @NotBlank String increment) {

        private static final Set<RoundingMode> ALLOWED_MODES = Set.of(
                RoundingMode.HALF_UP,
                RoundingMode.HALF_EVEN,
                RoundingMode.DOWN,
                RoundingMode.UP,
                RoundingMode.FLOOR,
                RoundingMode.CEILING);

        public RoundingPolicy {
            if (scale < 0 || scale > 12) {
                throw new IllegalArgumentException("rounding scale must be between 0 and 12");
            }
            Objects.requireNonNull(mode, "mode");
            if (!ALLOWED_MODES.contains(mode)) {
                throw new IllegalArgumentException("rounding mode is not allowlisted");
            }
            increment = canonicalDecimal(increment, "increment");
            BigDecimal parsed = new BigDecimal(increment);
            if (parsed.signum() <= 0 || parsed.scale() > scale) {
                throw new IllegalArgumentException(
                        "rounding increment must be positive and fit the configured scale");
            }
        }

        BigDecimal apply(BigDecimal value) {
            BigDecimal step = new BigDecimal(increment);
            return value.divide(step, 0, mode).multiply(step).setScale(scale, mode);
        }
    }

    public record TypedValue(
            @NotNull PayValueType type,
            String value,
            Boolean booleanValue,
            String currency) {

        public TypedValue {
            Objects.requireNonNull(type, "type");
            currency = normalizeCurrency(currency);
            if (type == PayValueType.BOOLEAN) {
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
                if (type.currencyBearing() && currency == null) {
                    throw new IllegalArgumentException(
                            "currency-bearing values require an ISO currency");
                }
                if (!type.currencyBearing() && currency != null) {
                    throw new IllegalArgumentException(
                            "non-currency values cannot contain currency");
                }
            }
        }

        BigDecimal decimal() {
            if (!type.numeric()) {
                throw new IllegalStateException("boolean has no decimal value");
            }
            return new BigDecimal(value);
        }
    }

    public record CreateElementVersionRequest(
            UUID elementId,
            Long expectedVersion,
            @NotNull UUID legalEntityId,
            @NotNull UUID payrollGroupId,
            @NotBlank String elementCode,
            @NotBlank String displayName,
            @NotNull PayValueType valueType,
            String currency,
            @NotNull @Valid RoundingPolicy rounding,
            @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate effectiveFrom) {

        public CreateElementVersionRequest {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(payrollGroupId, "payrollGroupId");
            elementCode = requireCode(elementCode, "elementCode").toUpperCase(Locale.ROOT);
            displayName = requireText(displayName, "displayName");
            Objects.requireNonNull(valueType, "valueType");
            currency = normalizeCurrency(currency);
            Objects.requireNonNull(rounding, "rounding");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            requireVersionPair(elementId, expectedVersion, "element");
            requireCurrencyShape(valueType, currency);
        }
    }

    public record CreateFormulaVersionRequest(
            UUID formulaId,
            Long expectedVersion,
            @NotNull UUID legalEntityId,
            @NotNull UUID payrollGroupId,
            @NotBlank String formulaCode,
            @NotBlank String displayName,
            @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate effectiveFrom,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String goldenTestDigest,
            @NotNull @Valid PayFormulaAst ast) {

        public CreateFormulaVersionRequest {
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(payrollGroupId, "payrollGroupId");
            formulaCode = requireCode(formulaCode, "formulaCode").toUpperCase(Locale.ROOT);
            displayName = requireText(displayName, "displayName");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            goldenTestDigest = requireDigest(goldenTestDigest, "goldenTestDigest");
            Objects.requireNonNull(ast, "ast");
            requireVersionPair(formulaId, expectedVersion, "formula");
        }
    }

    public record VersionCommand(@Positive long expectedVersion) {
        public VersionCommand {
            if (expectedVersion <= 0) {
                throw new IllegalArgumentException("expectedVersion must be positive");
            }
        }
    }

    public record SimulationCommand(
            @Positive long expectedVersion,
            @NotNull Map<@NotBlank String, @Valid TypedValue> inputs,
            @NotNull @Valid RoundingPolicy rounding,
            @Min(1) @Max(2_000) long timeoutMillis) {

        public SimulationCommand {
            if (expectedVersion <= 0) {
                throw new IllegalArgumentException("expectedVersion must be positive");
            }
            Objects.requireNonNull(inputs, "inputs");
            LinkedHashMap<String, TypedValue> normalized = new LinkedHashMap<>();
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
        }
    }

    public record FormulaFinding(
            @NotNull FormulaErrorCode code,
            String nodeId,
            @NotBlank String message) {
        public FormulaFinding {
            Objects.requireNonNull(code, "code");
            message = requireText(message, "message");
        }
    }

    public record ValidationReport(
            boolean valid,
            @NotBlank String astDigest,
            @NotBlank String validationDigest,
            @NotNull List<@Valid FormulaFinding> findings,
            @NotNull Instant validatedAt) {
        public ValidationReport {
            astDigest = requireDigest(astDigest, "astDigest");
            validationDigest = requireDigest(validationDigest, "validationDigest");
            findings = immutableList(findings, "findings");
            Objects.requireNonNull(validatedAt, "validatedAt");
            if (valid == !findings.isEmpty()) {
                throw new IllegalArgumentException("validation state and findings disagree");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SimulationReport(
            boolean succeeded,
            @Valid TypedValue result,
            String outputDigest,
            FormulaErrorCode failureCode,
            @NotBlank String astDigest,
            @NotBlank String roundingDigest,
            @Min(0) int evaluatedNodeCount,
            @NotNull Instant simulatedAt) {
        public SimulationReport {
            astDigest = requireDigest(astDigest, "astDigest");
            roundingDigest = requireDigest(roundingDigest, "roundingDigest");
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
        }
    }

    public record ElementVersion(
            long tenantId,
            @NotNull UUID elementId,
            @Positive long version,
            @NotNull Lifecycle status,
            @NotNull UUID legalEntityId,
            @NotNull UUID payrollGroupId,
            @NotBlank String elementCode,
            @NotBlank String displayName,
            @NotNull PayValueType valueType,
            String currency,
            @NotNull @Valid RoundingPolicy rounding,
            @NotNull LocalDate effectiveFrom,
            long authorId,
            @NotNull Instant recordedAt,
            @NotNull UUID commandId) {
        public ElementVersion {
            if (tenantId <= 0 || authorId <= 0 || version <= 0) {
                throw new IllegalArgumentException("tenant, author and version must be positive");
            }
            Objects.requireNonNull(elementId, "elementId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(payrollGroupId, "payrollGroupId");
            elementCode = requireCode(elementCode, "elementCode");
            displayName = requireText(displayName, "displayName");
            Objects.requireNonNull(valueType, "valueType");
            currency = normalizeCurrency(currency);
            requireCurrencyShape(valueType, currency);
            Objects.requireNonNull(rounding, "rounding");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            Objects.requireNonNull(recordedAt, "recordedAt");
            Objects.requireNonNull(commandId, "commandId");
        }
    }

    public record FormulaVersion(
            long tenantId,
            @NotNull UUID formulaId,
            @Positive long version,
            @NotNull Lifecycle status,
            @NotNull UUID legalEntityId,
            @NotNull UUID payrollGroupId,
            @NotBlank String formulaCode,
            @NotBlank String displayName,
            @NotNull LocalDate effectiveFrom,
            @NotBlank String goldenTestDigest,
            @NotNull @Valid PayFormulaAst ast,
            @Valid ValidationReport validation,
            @Valid SimulationReport simulation,
            long authorId,
            Long publisherId,
            @NotNull Instant recordedAt,
            @NotNull UUID commandId) {
        public FormulaVersion {
            if (tenantId <= 0 || authorId <= 0 || version <= 0
                    || (publisherId != null && publisherId <= 0)) {
                throw new IllegalArgumentException(
                        "tenant, author, publisher and version identities must be positive");
            }
            Objects.requireNonNull(formulaId, "formulaId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(legalEntityId, "legalEntityId");
            Objects.requireNonNull(payrollGroupId, "payrollGroupId");
            formulaCode = requireCode(formulaCode, "formulaCode");
            displayName = requireText(displayName, "displayName");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            goldenTestDigest = requireDigest(goldenTestDigest, "goldenTestDigest");
            Objects.requireNonNull(ast, "ast");
            Objects.requireNonNull(recordedAt, "recordedAt");
            Objects.requireNonNull(commandId, "commandId");
            if (status == Lifecycle.PUBLISHED && publisherId == null) {
                throw new IllegalArgumentException("published formula requires publisherId");
            }
            if (publisherId != null && publisherId == authorId) {
                throw new IllegalArgumentException("formula author cannot be publisher");
            }
        }
    }

    public record CommandReceipt(
            @JsonIgnore long tenantId,
            @JsonIgnore long actorId,
            @NotNull UUID receiptId,
            @JsonIgnore @NotNull UUID commandId,
            @NotNull CommandType commandType,
            @NotBlank String originatingAction,
            @NotNull UUID subjectPrincipalPublicId,
            @NotBlank String populationScopeDigest,
            @NotBlank String fieldPolicyRevision,
            @NotBlank String purposeCode,
            @NotBlank String authorizationRevision,
            @NotNull ReceiptStatus status,
            @NotBlank String correlationId,
            @NotBlank String requestDigest,
            @NotNull Instant createdAt,
            @JsonIgnore @NotNull ResourceType resourceType,
            @JsonIgnore @NotNull UUID resourceId,
            @JsonIgnore @Positive long resultVersion) {

        public CommandReceipt {
            if (tenantId <= 0 || actorId <= 0 || resultVersion <= 0) {
                throw new IllegalArgumentException(
                        "tenant, actor and resultVersion must be positive");
            }
            Objects.requireNonNull(receiptId, "receiptId");
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(commandType, "commandType");
            originatingAction = requireText(originatingAction, "originatingAction");
            Objects.requireNonNull(subjectPrincipalPublicId, "subjectPrincipalPublicId");
            populationScopeDigest = requireDigest(
                    populationScopeDigest, "populationScopeDigest");
            fieldPolicyRevision = requireText(fieldPolicyRevision, "fieldPolicyRevision");
            purposeCode = requireText(purposeCode, "purposeCode");
            authorizationRevision = requireText(
                    authorizationRevision, "authorizationRevision");
            Objects.requireNonNull(status, "status");
            correlationId = requireText(correlationId, "correlationId");
            if (correlationId.length() > 160) {
                throw new IllegalArgumentException("correlationId is too long");
            }
            requestDigest = requireDigest(requestDigest, "requestDigest");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(resourceType, "resourceType");
            Objects.requireNonNull(resourceId, "resourceId");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PayrollConfigurationPublished(
            @NotBlank String specVersion,
            @NotNull UUID id,
            @NotBlank String source,
            @NotBlank String type,
            int schemaVersion,
            @NotNull Instant time,
            long tenantId,
            @NotBlank String aggregateType,
            @NotNull UUID aggregateId,
            @Positive long aggregateSequence,
            @NotBlank String correlationId,
            @NotNull PublishedData data) {

        public PayrollConfigurationPublished {
            if (!"1.0".equals(specVersion)
                    || !"PayrollConfigurationPublished".equals(type)
                    || schemaVersion != 1
                    || tenantId <= 0
                    || !"PayrollConfiguration".equals(aggregateType)
                    || aggregateSequence <= 0) {
                throw new IllegalArgumentException("publication event envelope is invalid");
            }
            Objects.requireNonNull(id, "id");
            source = requireText(source, "source");
            Objects.requireNonNull(time, "time");
            Objects.requireNonNull(aggregateId, "aggregateId");
            correlationId = requireText(correlationId, "correlationId");
            Objects.requireNonNull(data, "data");
        }
    }

    public record PublishedData(
            @NotNull UUID configurationId,
            @Positive long version,
            @NotNull LocalDate effectiveFrom,
            @NotBlank String validationDigest) {
        public PublishedData {
            Objects.requireNonNull(configurationId, "configurationId");
            if (version <= 0) {
                throw new IllegalArgumentException("version must be positive");
            }
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            validationDigest = requireDigest(validationDigest, "validationDigest");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MutationResult(
            @NotNull CommandReceipt receipt,
            @Valid ElementVersion element,
            @Valid FormulaVersion formula,
            @Valid ValidationReport validation,
            @Valid SimulationReport simulation,
            @Valid PayrollConfigurationPublished publicationEvent) {
        public MutationResult {
            Objects.requireNonNull(receipt, "receipt");
            if ((element == null) == (formula == null)) {
                throw new IllegalArgumentException(
                        "exactly one element or formula result is required");
            }
        }
    }

    public record StatusFilter(List<String> codes, Long minimumRevision) {
        public StatusFilter {
            codes = codes == null ? List.of() : immutableList(codes, "codes");
            if (codes.size() > 50 || codes.stream().distinct().count() != codes.size()
                    || codes.stream().anyMatch(code ->
                    !code.matches("[A-Z][A-Z0-9_]{1,63}"))) {
                throw new IllegalArgumentException("status filter codes are invalid");
            }
            if (minimumRevision != null && minimumRevision < 1) {
                throw new IllegalArgumentException("minimumRevision must be positive");
            }
        }
    }

    public record PolicyFilter(List<String> policyKeys, Long minimumRevision) {
        public PolicyFilter {
            policyKeys = policyKeys == null
                    ? List.of() : immutableList(policyKeys, "policyKeys");
            if (policyKeys.size() > 50
                    || policyKeys.stream().distinct().count() != policyKeys.size()
                    || policyKeys.stream().anyMatch(key ->
                    !key.matches("[A-Za-z0-9._:-]{1,128}"))) {
                throw new IllegalArgumentException("policy filter keys are invalid");
            }
            if (minimumRevision != null && minimumRevision < 1) {
                throw new IllegalArgumentException("minimumRevision must be positive");
            }
        }
    }

    public record WorkbenchFilter(
            @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate effectiveOn,
            StatusFilter effectiveStatus,
            PolicyFilter payrollPolicy,
            PolicyFilter policyVersion) {
    }

    public record WorkbenchQuery(
            Instant asOf,
            String cursor,
            @NotNull WorkbenchFilter filter,
            @Min(1) @Max(100) int limit,
            @NotBlank String locale,
            @NotNull @Size(min = 1, max = 3) List<String> sort,
            @NotBlank String timeZone,
            @NotNull UUID correlationId) {

        private static final Set<String> SORTS = Set.of(
                "policyVersion:ASC", "payrollPolicy:ASC", "effectiveStatus:ASC",
                "policyVersion:DESC", "payrollPolicy:DESC", "effectiveStatus:DESC",
                "itemPublicId:ASC", "itemPublicId:DESC");

        public WorkbenchQuery {
            cursor = normalizeOptionalText(cursor, 1024, "cursor");
            if (cursor != null && !cursor.matches("[A-Za-z0-9_-]{16,1024}")) {
                throw new IllegalArgumentException("cursor is invalid");
            }
            Objects.requireNonNull(filter, "filter");
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("limit must be between 1 and 100");
            }
            locale = requireText(locale, "locale");
            java.util.Locale parsedLocale = java.util.Locale.forLanguageTag(locale);
            if (locale.length() < 2 || locale.length() > 35
                    || parsedLocale.getLanguage().isBlank()
                    || !parsedLocale.toLanguageTag().equals(locale)) {
                throw new IllegalArgumentException("locale must be a canonical BCP 47 tag");
            }
            sort = immutableList(sort, "sort");
            if (sort.isEmpty() || sort.size() > 3
                    || sort.stream().distinct().count() != sort.size()
                    || !SORTS.containsAll(sort)) {
                throw new IllegalArgumentException("sort is invalid");
            }
            timeZone = requireText(timeZone, "timeZone");
            try {
                java.time.ZoneId parsedZone = java.time.ZoneId.of(timeZone);
                if (!parsedZone.getId().equals(timeZone)
                        || (!"UTC".equals(timeZone) && !timeZone.contains("/"))) {
                    throw new IllegalArgumentException("timeZone must be canonical");
                }
            } catch (java.time.DateTimeException exception) {
                throw new IllegalArgumentException("timeZone must be canonical", exception);
            }
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    public record PolicyValue(
            @NotBlank String policyKey,
            @Positive long revision,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank String status) {
        public PolicyValue {
            if (!requireText(policyKey, "policyKey")
                    .matches("[a-z][a-z0-9._-]{1,127}")) {
                throw new IllegalArgumentException("policyKey is invalid");
            }
            if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            if (effectiveTo != null && effectiveTo.isBefore(effectiveFrom)) {
                throw new IllegalArgumentException("effectiveTo cannot precede effectiveFrom");
            }
            if (!requireText(status, "status").matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("status is invalid");
            }
        }
    }

    public record StatusValue(@NotBlank String code, @Positive long revision) {
        public StatusValue {
            if (!requireText(code, "code").matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("status code is invalid");
            }
            if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldDecision(
            @NotBlank String decision,
            @JsonInclude(JsonInclude.Include.ALWAYS) Object value,
            String maskReason,
            String maskedDisplayKey) {
        public FieldDecision {
            if (!Set.of("VIEW", "MASK").contains(decision)) {
                throw new IllegalArgumentException("field decision is invalid");
            }
            if ("VIEW".equals(decision)) {
                Objects.requireNonNull(value, "view field value");
                if (maskReason != null || maskedDisplayKey != null) {
                    throw new IllegalArgumentException("view fields cannot expose mask metadata");
                }
            } else {
                if (value != null
                        || maskReason == null
                        || !maskReason.matches("[A-Z][A-Z0-9_]{1,63}")
                        || maskedDisplayKey == null
                        || !maskedDisplayKey.matches("[a-z][a-z0-9._-]{1,127}")) {
                    throw new IllegalArgumentException("masked field decision is invalid");
                }
            }
        }

        static FieldDecision view(Object value) {
            return new FieldDecision("VIEW", value, null, null);
        }
    }

    public record DeepLink(
            @NotBlank String route,
            @NotBlank String resourcePublicId) {
        public DeepLink {
            if (!"/hr/settings/payroll".equals(route)) {
                throw new IllegalArgumentException("payroll deep link route is invalid");
            }
            if (!requireText(resourcePublicId, "resourcePublicId")
                    .matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new IllegalArgumentException("resourcePublicId is invalid");
            }
        }
    }

    public record ActionDescriptor(
            @NotBlank String actionKey,
            @NotBlank String method,
            @NotBlank String hrefTemplate,
            @NotBlank String idempotencyPolicy,
            @NotBlank String stepUpPolicy) {
        public ActionDescriptor {
            if (!"hris.action.pay.formula.publish".equals(actionKey)
                    || !"POST".equals(method)
                    || !"/api/payroll/v1/formulas/{formulaId}/publish".equals(hrefTemplate)
                    || !"REQUIRED_IDEMPOTENCY_KEY".equals(idempotencyPolicy)
                    || !"OWNER_PEP_RISK_POLICY_DECISION_REQUIRED".equals(stepUpPolicy)) {
                throw new IllegalArgumentException("payroll action descriptor is invalid");
            }
        }
    }

    public record WorkbenchItem(
            @NotBlank String itemPublicId,
            @NotBlank String title,
            @NotBlank String statusCode,
            @NotNull Map<String, @Valid FieldDecision> fieldGroups,
            @NotNull @Valid DeepLink deepLink,
            @NotNull List<@Valid ActionDescriptor> availableActions) {
        public WorkbenchItem {
            if (!requireText(itemPublicId, "itemPublicId")
                    .matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new IllegalArgumentException("itemPublicId is invalid");
            }
            title = requireText(title, "title");
            if (title.length() > 512) throw new IllegalArgumentException("title is too long");
            if (!requireText(statusCode, "statusCode")
                    .matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("statusCode is invalid");
            }
            fieldGroups = Map.copyOf(new LinkedHashMap<>(fieldGroups));
            if (fieldGroups.isEmpty()
                    || !Set.of("POLICY_VERSION", "PAYROLL_POLICY", "EFFECTIVE_STATUS")
                    .containsAll(fieldGroups.keySet())) {
                throw new IllegalArgumentException("fieldGroups are invalid");
            }
            Objects.requireNonNull(deepLink, "deepLink");
            availableActions = immutableList(availableActions, "availableActions");
            if (availableActions.size() > 6
                    || availableActions.stream().distinct().count()
                    != availableActions.size()) {
                throw new IllegalArgumentException("availableActions are invalid");
            }
        }
    }

    public record FieldDecisionSummary(
            @Min(0) int viewCount,
            @Min(0) int maskedCount,
            @Min(0) int omittedCount) {
        public FieldDecisionSummary {
            if (viewCount < 0 || maskedCount < 0 || omittedCount < 0) {
                throw new IllegalArgumentException("field decision counts cannot be negative");
            }
        }
    }

    @Schema(name = "PayrollConfigurationPartialFailureV1")
    public record PartialFailure(
            @NotBlank String componentKey,
            @NotBlank String errorCode,
            @NotNull UUID correlationId,
            boolean retryable) {
        public PartialFailure {
            if (!requireText(componentKey, "componentKey")
                    .matches("[a-z][a-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("componentKey is invalid");
            }
            if (!requireText(errorCode, "errorCode")
                    .matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("errorCode is invalid");
            }
            Objects.requireNonNull(correlationId, "correlationId");
        }
    }

    public record CachePolicy(
            @NotBlank String authorizationDecisionRevision,
            @NotBlank String invalidationEvent,
            @Min(0) @Max(300) int maxAgeSeconds) {
        public CachePolicy {
            authorizationDecisionRevision = requireText(
                    authorizationDecisionRevision, "authorizationDecisionRevision");
            if (!"dwp.hris.access.assignment.changed.v1".equals(invalidationEvent)) {
                throw new IllegalArgumentException("cache invalidation event is invalid");
            }
            if (maxAgeSeconds < 0 || maxAgeSeconds > 300) {
                throw new IllegalArgumentException("maxAgeSeconds is invalid");
            }
        }
    }

    public record QueryState(
            @NotBlank String state,
            @NotBlank String scopeDecisionRevision,
            @NotBlank String freshness,
            String nextCursor,
            @NotNull FieldDecisionSummary fieldDecisionSummary,
            @NotNull @Size(max = 32) List<@Valid PartialFailure> partialFailures,
            @NotNull UUID correlationId,
            @NotNull CachePolicy cachePolicy) {
        public QueryState {
            if (!Set.of("COMPLETE", "EMPTY", "PARTIAL", "UNAVAILABLE").contains(state)) {
                throw new IllegalArgumentException("query state is invalid");
            }
            scopeDecisionRevision = requireText(
                    scopeDecisionRevision, "scopeDecisionRevision");
            if (!Set.of("CURRENT", "STALE", "UNAVAILABLE").contains(freshness)) {
                throw new IllegalArgumentException("freshness is invalid");
            }
            nextCursor = normalizeOptionalText(nextCursor, 1024, "nextCursor");
            if (nextCursor != null && !nextCursor.matches("[A-Za-z0-9_-]{16,1024}")) {
                throw new IllegalArgumentException("nextCursor is invalid");
            }
            Objects.requireNonNull(fieldDecisionSummary, "fieldDecisionSummary");
            partialFailures = immutableList(partialFailures, "partialFailures");
            if (partialFailures.size() > 32) {
                throw new IllegalArgumentException("too many partialFailures");
            }
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(cachePolicy, "cachePolicy");
            if (("EMPTY".equals(state) || "COMPLETE".equals(state))
                    && !partialFailures.isEmpty()) {
                throw new IllegalArgumentException("complete query state cannot have failures");
            }
            if ("PARTIAL".equals(state) && partialFailures.isEmpty()) {
                throw new IllegalArgumentException("partial query state requires a failure");
            }
            if ("UNAVAILABLE".equals(state)
                    && (!"UNAVAILABLE".equals(freshness) || nextCursor != null)) {
                throw new IllegalArgumentException("unavailable query state is inconsistent");
            }
        }
    }

    public record WorkbenchView(
            @NotBlank String projectionVersion,
            @NotNull Instant asOf,
            @NotBlank String variantKey,
            @NotNull QueryState queryState,
            @NotNull @Size(max = 500) List<@Valid WorkbenchItem> items) {
        public WorkbenchView {
            if (!"1".equals(projectionVersion) || !"PAYROLL".equals(variantKey)) {
                throw new IllegalArgumentException("payroll projection identity is invalid");
            }
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(queryState, "queryState");
            items = immutableList(items, "items");
            if (items.size() > 500) throw new IllegalArgumentException("too many items");
            if (("EMPTY".equals(queryState.state())
                    || "UNAVAILABLE".equals(queryState.state())) && !items.isEmpty()) {
                throw new IllegalArgumentException("empty/unavailable response cannot contain items");
            }
        }
    }

    public record Problem(
            @NotBlank String type,
            @NotBlank String title,
            int status,
            @NotBlank String code,
            @NotNull UUID correlationId,
            boolean retryable) {
        public Problem {
            try {
                java.net.URI.create(requireText(type, "type"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("problem type must be a URI", exception);
            }
            title = requireText(title, "title");
            if (title.length() > 256) throw new IllegalArgumentException("title is too long");
            if (!Set.of(400, 401, 403, 404, 409, 429, 503).contains(status)) {
                throw new IllegalArgumentException("problem status is invalid");
            }
            if (!requireText(code, "code").matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("problem code is invalid");
            }
            Objects.requireNonNull(correlationId, "correlationId");
        }
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

    private static String normalizeCurrency(String value) {
        if (value == null) return null;
        String normalized = requireText(value, "currency").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be a three-letter code");
        }
        return normalized;
    }

    private static void requireCurrencyShape(PayValueType valueType, String currency) {
        if (valueType.currencyBearing() != (currency != null)) {
            throw new IllegalArgumentException(
                    "currency must be present exactly for currency-bearing value types");
        }
    }

    private static String normalizeInputKey(String value, String name) {
        if (value == null) return null;
        String normalized = requireText(value, name);
        if (!normalized.matches("[A-Z][A-Z0-9_.]{1,79}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }

    private static void requireVersionPair(UUID id, Long expectedVersion, String name) {
        if (id == null && expectedVersion != null) {
            throw new IllegalArgumentException(
                    "expectedVersion requires an existing " + name + " id");
        }
        if (id != null && (expectedVersion == null || expectedVersion <= 0)) {
            throw new IllegalArgumentException(
                    "an existing " + name + " requires a positive expectedVersion");
        }
    }

    private static String requireCode(String value, String name) {
        String normalized = requireText(value, name);
        if (!normalized.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return normalized;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.strip();
        if (!normalized.equals(value) || normalized.length() > 500) {
            throw new IllegalArgumentException(name + " is not canonical");
        }
        return normalized;
    }

    private static String normalizeOptionalText(String value, int max, String name) {
        if (value == null) return null;
        String normalized = requireText(value, name);
        if (normalized.length() > max) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }

    private static <T> List<T> immutableList(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(name + " cannot contain null");
        }
        return List.copyOf(values);
    }
}
