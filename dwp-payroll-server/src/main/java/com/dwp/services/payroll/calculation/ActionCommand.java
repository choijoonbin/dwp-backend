package com.dwp.services.payroll.calculation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Closed transport contract for Payroll calculation actions. */
@JsonPropertyOrder({"reasonCode", "approvalReceiptId", "comment"})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonDeserialize(using = ActionCommand.StrictDeserializer.class)
record ActionCommand(
        String reasonCode,
        String approvalReceiptId,
        String comment) {

    ActionCommand {
        codePoints(reasonCode, 1, 64, "reasonCode");
        if (approvalReceiptId != null) {
            if (!approvalReceiptId.matches(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                            + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")) {
                throw new IllegalArgumentException(
                        "approvalReceiptId must be a UUID");
            }
            UUID.fromString(approvalReceiptId);
        }
        codePoints(comment, 0, 1000, "comment");
    }

    /** Strict parser for the closed ActionCommand JSON shape. */
    static final class StrictDeserializer extends JsonDeserializer<ActionCommand> {

        private static final Set<String> FIELDS = Set.of(
                "reasonCode", "approvalReceiptId", "comment");

        @Override
        public ActionCommand deserialize(
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
                    return mismatch(
                            parser,
                            "ActionCommand contains an unknown or duplicate field");
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
                                    parser,
                                    "approvalReceiptId must be a UUID",
                                    exception);
                        }
                    }
                    default -> throw new IllegalStateException(
                            "closed field set changed");
                }
            }
            if (!seen.contains("reasonCode")) {
                return mismatch(parser, "reasonCode is required");
            }
            if (parser.nextToken() != null) {
                return mismatch(
                        parser,
                        "ActionCommand contains trailing JSON content");
            }
            try {
                return new ActionCommand(reasonCode, approvalReceiptId, comment);
            } catch (IllegalArgumentException exception) {
                throw JsonMappingException.from(
                        parser, exception.getMessage(), exception);
            }
        }

        private ActionCommand mismatch(
                JsonParser parser,
                String message) throws JsonMappingException {
            throw JsonMappingException.from(parser, message);
        }
    }

    private static void codePoints(
            String value,
            int minimum,
            int maximum,
            String field) {
        if (value == null) {
            if (minimum == 0) return;
            throw new IllegalArgumentException(field + " is required");
        }
        int length = value.codePointCount(0, value.length());
        if (length < minimum || length > maximum) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
    }
}
