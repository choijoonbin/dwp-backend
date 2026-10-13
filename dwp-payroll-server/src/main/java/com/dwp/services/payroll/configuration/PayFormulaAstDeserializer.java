package com.dwp.services.payroll.configuration;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.Arrays;

import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaNode;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.FormulaOperator;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayFormulaAst;
import static com.dwp.services.payroll.configuration.PayrollConfigurationModels.PayValueType;

/** Rejects source strings with the exact typed-formula TYPE failure. */
final class PayFormulaAstDeserializer extends StdDeserializer<PayFormulaAst> {

    PayFormulaAstDeserializer() {
        super(PayFormulaAst.class);
    }

    @Override
    public PayFormulaAst deserialize(
            JsonParser parser, DeserializationContext context) throws IOException {
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
                } catch (IllegalArgumentException | NullPointerException exception) {
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
                    PayValueType.valueOf(node.required("declaredReturnType").textValue()),
                    node.required("rootNodeId").textValue(),
                    Arrays.asList(nodes));
        } catch (IllegalArgumentException exception) {
            return (PayFormulaAst) context.handleInstantiationProblem(
                    PayFormulaAst.class, node, exception);
        }
    }
}
