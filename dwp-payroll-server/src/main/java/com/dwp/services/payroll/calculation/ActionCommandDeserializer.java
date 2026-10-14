package com.dwp.services.payroll.calculation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Strict parser for the CLOSED Payroll ActionCommand transport schema. */
final class ActionCommandDeserializer
        extends JsonDeserializer<PayrollCalculationModels.ActionCommand> {

    private static final Set<String> FIELDS = Set.of(
            "reasonCode", "approvalReceiptId", "comment");

    @Override
    public PayrollCalculationModels.ActionCommand deserialize(
            JsonParser parser,
            DeserializationContext context) throws IOException {
        if (!parser.isExpectedStartObjectToken()) {
            return mismatch(parser, "ActionCommand must be a JSON object");
        }
        Set<String> seen = new LinkedHashSet<>();
        String reasonCode = null;
        String approvalReceiptId = null;
        String comment = null;
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            if (field == null || !FIELDS.contains(field) || !seen.add(field)) {
                return mismatch(parser, "ActionCommand contains an unknown or duplicate field");
            }
            JsonToken value = parser.nextToken();
            if (value != JsonToken.VALUE_STRING) {
                return mismatch(parser, field + " must be a JSON string");
            }
            String text = parser.getText();
            switch (field) {
                case "reasonCode" -> reasonCode = text;
                case "comment" -> comment = text;
                case "approvalReceiptId" -> {
                    try {
                        UUID.fromString(text);
                        approvalReceiptId = text;
                    } catch (IllegalArgumentException exception) {
                        throw JsonMappingException.from(
                                parser, "approvalReceiptId must be a UUID", exception);
                    }
                }
                default -> throw new IllegalStateException("closed field set changed");
            }
        }
        if (!seen.contains("reasonCode")) {
            return mismatch(parser, "reasonCode is required");
        }
        if (parser.nextToken() != null) {
            return mismatch(parser, "ActionCommand contains trailing JSON content");
        }
        try {
            return new PayrollCalculationModels.ActionCommand(
                    reasonCode, approvalReceiptId, comment);
        } catch (IllegalArgumentException exception) {
            throw JsonMappingException.from(parser, exception.getMessage(), exception);
        }
    }

    private PayrollCalculationModels.ActionCommand mismatch(
            JsonParser parser, String message) throws JsonMappingException {
        throw JsonMappingException.from(parser, message);
    }
}
