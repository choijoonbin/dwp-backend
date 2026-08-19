package com.dwp.services.notification.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;

/** Enforces a decimal JSON string so JavaScript clients never lose BIGINT precision. */
public final class DecimalVersionStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            throw InvalidFormatException.from(
                    parser,
                    "BIGINT versions must be JSON decimal strings.",
                    parser.getValueAsString(),
                    String.class);
        }
        String value = parser.getText().trim();
        try {
            NotificationVersionCodec.nonNegative(value, "version");
            return value;
        } catch (IllegalArgumentException exception) {
            throw InvalidFormatException.from(
                    parser,
                    exception.getMessage(),
                    value,
                    String.class);
        }
    }
}
