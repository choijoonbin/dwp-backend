package com.dwp.services.payroll.configuration;

import static com.dwp.services.payroll.configuration.PayrollConfigurationValueValidation.*;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Exact transport and persisted values for BASE-TFR-PAY-006. */
public final class PayrollConfigurationModels {

    public static final String AST_REGISTRY_ID = PayrollConfigurationValueValidation.AST_REGISTRY_ID;
    public static final int AST_REGISTRY_VERSION = PayrollConfigurationValueValidation.AST_REGISTRY_VERSION;

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

        public OwnerRef {
            owner = PayrollConfigurationValueValidation.normalizeOwner(owner);
            schemaId = PayrollConfigurationValueValidation.ownerRefSchemaId(schemaId, publicId, revision);
            contentHash = PayrollConfigurationValueValidation.ownerRefContentHash(contentHash, validFrom, validToExclusive);
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
            arguments = PayrollConfigurationValueValidation.formulaNodeArguments(arguments, returnType);
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
            registryId = PayrollConfigurationValueValidation.payFormulaAstRegistryId(registryId, registryVersion, declaredReturnType);
            rootNodeId = requireCode(rootNodeId, "rootNodeId");
            nodes = PayrollConfigurationWorkbenchValidation.payFormulaAstNodes(
                    nodes);
        }
    }

    public record RoundingPolicy(
            @Min(0) @Max(12) int scale,
            @NotNull RoundingMode mode,
            @NotBlank String increment) {

        public RoundingPolicy {
            PayrollConfigurationValueValidation.roundingPolicyFields(scale, mode);
            increment = PayrollConfigurationValueValidation.roundingPolicyIncrement(scale, increment);
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
            value = PayrollConfigurationValueValidation.typedValueValue(type, value, booleanValue, currency);
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
            currency = PayrollConfigurationValueValidation.createElementVersionRequestCurrency(
                    elementId, expectedVersion, valueType, currency, rounding, effectiveFrom);
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
            inputs = PayrollConfigurationValueValidation.simulationCommandInputs(
                    expectedVersion, inputs, rounding, timeoutMillis);
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
            findings = PayrollConfigurationWorkbenchValidation.validationReportFindings(
                    valid, findings, validatedAt);
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
            outputDigest = PayrollConfigurationValueValidation.simulationReportOutputDigest(
                    succeeded, result, outputDigest, failureCode, simulatedAt);
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
            PayrollConfigurationValueValidation.elementVersionFields(
                    tenantId, elementId, version, status, legalEntityId, payrollGroupId, authorId);
            elementCode = requireCode(elementCode, "elementCode");
            displayName = requireText(displayName, "displayName");
            Objects.requireNonNull(valueType, "valueType");
            currency = PayrollConfigurationValueValidation.elementVersionCurrency(
                    valueType, currency, rounding, effectiveFrom, recordedAt, commandId);
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
            PayrollConfigurationValueValidation.formulaVersionFields(
                    tenantId, formulaId, version, status, legalEntityId, payrollGroupId, authorId, publisherId);
            formulaCode = requireCode(formulaCode, "formulaCode");
            displayName = requireText(displayName, "displayName");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            goldenTestDigest = PayrollConfigurationValueValidation.formulaVersionGoldenTestDigest(
                    status, goldenTestDigest, ast, authorId, publisherId, recordedAt, commandId);
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
            PayrollConfigurationValueValidation.commandReceiptFields(tenantId, actorId, receiptId, commandId, commandType, resultVersion);
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
            PayrollConfigurationValueValidation.payrollConfigurationPublishedFields(
                    specVersion, id, type, schemaVersion, tenantId, aggregateType, aggregateSequence);
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
            PayrollConfigurationValueValidation.publishedDataFields(configurationId, version, effectiveFrom);
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
            PayrollConfigurationValueValidation.mutationResultFields(receipt, element, formula);
        }
    }

    public record StatusFilter(List<String> codes, Long minimumRevision) {
        public StatusFilter {
            codes = PayrollConfigurationWorkbenchValidation.statusFilterCodes(codes, minimumRevision);
        }
    }

    public record PolicyFilter(List<String> policyKeys, Long minimumRevision) {
        public PolicyFilter {
            policyKeys = PayrollConfigurationWorkbenchValidation.policyFilterPolicyKeys(policyKeys, minimumRevision);
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

        public WorkbenchQuery {
            cursor = PayrollConfigurationWorkbenchValidation.workbenchQueryCursor(cursor, filter, limit);
            locale = PayrollConfigurationWorkbenchValidation.workbenchQueryLocale(locale);
            sort = PayrollConfigurationWorkbenchValidation.workbenchQuerySort(sort);
            timeZone = PayrollConfigurationWorkbenchValidation.workbenchQueryTimeZone(timeZone, correlationId);
        }
    }

    public record PolicyValue(
            @NotBlank String policyKey,
            @Positive long revision,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank String status) {
        public PolicyValue {
            PayrollConfigurationWorkbenchValidation.policyValueFields(policyKey, revision, effectiveFrom, effectiveTo, status);
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
            PayrollConfigurationWorkbenchValidation.fieldDecisionFields(decision, value, maskReason, maskedDisplayKey);
        }

        static FieldDecision view(Object value) {
            return new FieldDecision("VIEW", value, null, null);
        }
    }

    public record DeepLink(
            @NotBlank String route,
            @NotBlank String resourcePublicId) {
        public DeepLink {
            PayrollConfigurationWorkbenchValidation.deepLinkFields(route, resourcePublicId);
        }
    }

    public record ActionDescriptor(
            @NotBlank String actionKey,
            @NotBlank String method,
            @NotBlank String hrefTemplate,
            @NotBlank String idempotencyPolicy,
            @NotBlank String stepUpPolicy) {
        public ActionDescriptor {
            PayrollConfigurationWorkbenchValidation.actionDescriptorFields(
                    actionKey, method, hrefTemplate, idempotencyPolicy, stepUpPolicy);
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
            title = PayrollConfigurationWorkbenchValidation.workbenchItemTitle(title, statusCode);
            fieldGroups = PayrollConfigurationWorkbenchValidation.workbenchItemFieldGroups(
                    fieldGroups, deepLink);            availableActions = PayrollConfigurationWorkbenchValidation.workbenchItemAvailableActions(
                    availableActions);
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
            PayrollConfigurationWorkbenchValidation.partialFailureFields(componentKey, errorCode, correlationId);
        }
    }

    public record CachePolicy(
            @NotBlank String authorizationDecisionRevision,
            @NotBlank String invalidationEvent,
            @Min(0) @Max(300) int maxAgeSeconds) {
        public CachePolicy {
            authorizationDecisionRevision = PayrollConfigurationWorkbenchValidation.cachePolicyAuthorizationDecisionRevision(
                    authorizationDecisionRevision, invalidationEvent, maxAgeSeconds);
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
            scopeDecisionRevision = PayrollConfigurationWorkbenchValidation.queryStateScopeDecisionRevision(
                    scopeDecisionRevision, freshness);
            nextCursor = PayrollConfigurationWorkbenchValidation.queryStateNextCursor(nextCursor, fieldDecisionSummary);
            partialFailures = PayrollConfigurationWorkbenchValidation.queryStatePartialFailures(
                    state, freshness, nextCursor, partialFailures, correlationId, cachePolicy);
        }
    }

    public record WorkbenchView(
            @NotBlank String projectionVersion,
            @NotNull Instant asOf,
            @NotBlank String variantKey,
            @NotNull QueryState queryState,
            @NotNull @Size(max = 500) List<@Valid WorkbenchItem> items) {
        public WorkbenchView {
            PayrollConfigurationWorkbenchValidation.workbenchViewFields(projectionVersion, asOf, variantKey, queryState);
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
            PayrollConfigurationWorkbenchValidation.problemFields(type);
            title = PayrollConfigurationWorkbenchValidation.problemTitle2(title, status, code, correlationId);
        }
    }

    static String requireDigest(String value, String name) {
        return PayrollConfigurationValueValidation.requireDigest(value, name);
    }

    static String canonicalDecimal(String value, String name) {
        return PayrollConfigurationValueValidation.canonicalDecimal(value, name);
    }

}
