package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012WorkforceIngestionContract;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Hrm012WorkforceIngestionDtos {
    private Hrm012WorkforceIngestionDtos() {
    }

    @JsonDeserialize(using = CommandDeserializer.class)
    public record Command(
            String objectRef,
            String objectDigest,
            String schemaKey,
            int schemaVersion,
            Hrm012WorkforceIngestionContract.Mode mode,
            LocalDate effectiveDate) {
        Hrm012WorkforceIngestionContract.Command contract() {
            return new Hrm012WorkforceIngestionContract.Command(
                    objectRef, objectDigest, schemaKey, schemaVersion, mode, effectiveDate);
        }
    }

    public record Receipt(
            UUID receiptId,
            String commandType,
            String status,
            String requestHash,
            UUID correlationId,
            Instant receivedAt,
            String originatingAction,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            Map<String, String> links) {

        static Receipt from(Hrm012WorkforceIngestionContract.RunReceipt receipt) {
            return new Receipt(
                    receipt.receiptId(), receipt.commandType(), receipt.status(),
                    receipt.requestHash(), receipt.correlationId(), receipt.receivedAt(),
                    receipt.originatingAction(), receipt.subjectPrincipalPublicId(),
                    receipt.populationScopeDigest(), receipt.fieldPolicyRevision(),
                    receipt.purposeCode(), receipt.authorizationRevision(), receipt.links());
        }
    }

    static final class CommandDeserializer extends StdDeserializer<Command> {
        private static final Set<String> FIELDS = Set.of(
                "objectRef", "objectDigest", "schemaKey",
                "schemaVersion", "mode", "effectiveDate");

        CommandDeserializer() {
            super(Command.class);
        }

        @Override
        public Command deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = parser.getCodec().readTree(parser);
            JsonToken trailing = parser.nextToken();
            if (trailing != null) throw invalid(parser);
            LinkedHashSet<String> actual = new LinkedHashSet<>();
            if (node == null || !node.isObject()) throw invalid(parser);
            node.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(FIELDS)) throw invalid(parser);
            try {
                JsonNode version = node.get("schemaVersion");
                if (version == null || !version.isIntegralNumber()
                        || !version.canConvertToInt()) throw invalid(parser);
                return new Command(
                        text(node, "objectRef", parser),
                        text(node, "objectDigest", parser),
                        text(node, "schemaKey", parser),
                        version.intValue(),
                        Hrm012WorkforceIngestionContract.Mode.valueOf(
                                text(node, "mode", parser)),
                        LocalDate.parse(text(node, "effectiveDate", parser)));
            } catch (RuntimeException exception) {
                throw invalid(parser);
            }
        }

        private String text(JsonNode node, String field, JsonParser parser)
                throws JsonMappingException {
            JsonNode value = node.get(field);
            if (value == null || !value.isTextual()) throw invalid(parser);
            return value.textValue();
        }

        private JsonMappingException invalid(JsonParser parser) {
            return JsonMappingException.from(
                    parser, "The HRM-012 command must contain exactly six fields.");
        }
    }
}
