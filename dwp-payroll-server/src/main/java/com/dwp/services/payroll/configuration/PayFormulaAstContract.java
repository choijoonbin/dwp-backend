package com.dwp.services.payroll.configuration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.services.payroll.configuration.PayrollConfigurationValueValidation.normalizeCurrency;
import static com.dwp.services.payroll.configuration.PayrollConfigurationValueValidation.normalizeInputKey;
import static com.dwp.services.payroll.configuration.PayrollConfigurationValueValidation.requireCode;

/** Closed, typed PAY_RULE_AST_V1 transport contract and strict parser. */
public final class PayFormulaAstContract {

    private PayFormulaAstContract() {
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
                        "FUNCTION_NOT_ALLOWED: formula operator is not allowlisted",
                        exception);
            }
        }

        @JsonValue
        public String jsonValue() {
            return name();
        }
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
            schemaId = PayrollConfigurationValueValidation.ownerRefSchemaId(
                    schemaId, publicId, revision);
            contentHash = PayrollConfigurationValueValidation.ownerRefContentHash(
                    contentHash, validFrom, validToExclusive);
        }
    }

    public record FormulaNode(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,63}")
            String nodeId,
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
            arguments = PayrollConfigurationValueValidation.formulaNodeArguments(
                    arguments, returnType);
            currency = normalizeCurrency(currency);
            inputKey = normalizeInputKey(inputKey, "inputKey");
            collectionInputKey = normalizeInputKey(
                    collectionInputKey, "collectionInputKey");
        }
    }

    @JsonDeserialize(using = StrictDeserializer.class)
    public record PayFormulaAst(
            @NotBlank String registryId,
            @Positive int registryVersion,
            @NotNull PayValueType declaredReturnType,
            @NotBlank String rootNodeId,
            @NotNull @Size(min = 1, max = 2048)
            List<@Valid FormulaNode> nodes) {

        public PayFormulaAst {
            registryId = PayrollConfigurationValueValidation.payFormulaAstRegistryId(
                    registryId, registryVersion, declaredReturnType);
            rootNodeId = requireCode(rootNodeId, "rootNodeId");
            nodes = PayrollConfigurationWorkbenchValidation.payFormulaAstNodes(
                    nodes);
        }
    }

    /** Rejects source strings and non-allowlisted operators before binding. */
    public static final class StrictDeserializer
            extends StdDeserializer<PayFormulaAst> {

        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public StrictDeserializer() {
            super(PayFormulaAst.class);
        }

        @Override
        public PayFormulaAst deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {
            JsonNode node = parser.getCodec().readTree(parser);
            if (!node.isObject()) {
                throw JsonMappingException.from(
                        parser,
                        "TYPE: formula must be a PAY_RULE_AST_V1 object, never source text");
            }
            try {
                for (JsonNode formulaNode : node.required("nodes")) {
                    String operator = formulaNode.required("operator").textValue();
                    try {
                        FormulaOperator.valueOf(operator);
                    } catch (IllegalArgumentException
                             | NullPointerException exception) {
                        throw JsonMappingException.from(
                                parser,
                                "FUNCTION_NOT_ALLOWED: formula operator is not allowlisted",
                                exception);
                    }
                }
                FormulaNode[] nodes = parser.getCodec().treeToValue(
                        node.required("nodes"), FormulaNode[].class);
                return new PayFormulaAst(
                        node.required("registryId").textValue(),
                        node.required("registryVersion").intValue(),
                        PayValueType.valueOf(
                                node.required("declaredReturnType").textValue()),
                        node.required("rootNodeId").textValue(),
                        Arrays.asList(nodes));
            } catch (IllegalArgumentException exception) {
                return (PayFormulaAst) context.handleInstantiationProblem(
                        PayFormulaAst.class, node, exception);
            }
        }
    }
}
