package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.MAX_OWNER_PAYLOAD_UTF8;

/** Reads the union without ever letting a parser exception expose transit-only content. */
final class Per004CreateCommandReader {
    private static final Set<String> FIELDS = Set.of("domain", "effectiveFrom", "effectiveTo", "schemaKey",
            "schemaVersion", "payload", "ownerPayload");
    static CreateVersionCommand read(HttpServletRequest request, ObjectMapper mapper) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_OWNER_PAYLOAD_UTF8 + 4097);
            if (bytes.length == 0 || bytes.length > MAX_OWNER_PAYLOAD_UTF8 + 4096) throw invalid();
            var root = mapper.createObjectNode();
            try (JsonParser parser = mapper.getFactory().createParser(bytes)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME || !FIELDS.contains(parser.currentName())) throw invalid();
                    String name = parser.currentName();
                    parser.nextToken();
                    long start = parser.currentTokenLocation().getByteOffset();
                    JsonNode value = mapper.readTree(parser);
                    if (name.equals("ownerPayload") && parser.currentLocation().getByteOffset() - start > MAX_OWNER_PAYLOAD_UTF8) throw invalid();
                    root.set(name, value);
                }
                if (parser.nextToken() != null) throw invalid();
            }
            boolean typed = com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.typedOwner(
                    root.path("domain").asText(), root.path("schemaKey").asText());
            if (typed ? root.has("payload") : root.has("ownerPayload")) throw invalid();
            if (!root.path("schemaVersion").isIntegralNumber() || !root.path("schemaVersion").canConvertToInt()) throw invalid();
            return mapper.readerFor(CreateVersionCommand.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS).readValue(root);
        } catch (IOException | RuntimeException exception) { throw invalid(); }
    }
    private static BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "REFERENCE_CONFIGURATION_CREATE_BODY_INVALID"); }
    private Per004CreateCommandReader() { }
}
