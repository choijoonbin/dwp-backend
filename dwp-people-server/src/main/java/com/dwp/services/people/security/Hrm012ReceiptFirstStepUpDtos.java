package com.dwp.services.people.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Hrm012ReceiptFirstStepUpDtos {

    private Hrm012ReceiptFirstStepUpDtos() {
    }

    public record VerifyRequest(
            Long tenantId,
            String onBehalfOfSubject,
            String routeContractKey,
            String commandMethod,
            String publicCommandPath,
            String serviceCommandPath,
            String objectRef,
            String ifMatch,
            String purposeCode,
            String opaqueStepUpChallengeRef,
            String idempotencyKey,
            String contextKey,
            String scopeRef,
            String payloadSha256,
            String decisionRevision,
            String receiptPublicId,
            String receiptRequestHash,
            String correlationId) {
    }

    @JsonDeserialize(using = VerifyResponseDeserializer.class)
    public record VerifyResponse(
            String decision,
            String authorizationRevision,
            String dutyEvidenceRevision,
            String featureDecisionRevision,
            String stepUpChallengeId,
            OffsetDateTime stepUpExpiresAt) {
    }

    public static final class VerifyResponseDeserializer
            extends StdDeserializer<VerifyResponse> {

        private static final Set<String> FIELDS = Set.of(
                "decision", "authorizationRevision", "dutyEvidenceRevision",
                "featureDecisionRevision", "stepUpChallengeId", "stepUpExpiresAt");

        public VerifyResponseDeserializer() {
            super(VerifyResponse.class);
        }

        @Override
        public VerifyResponse deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = parser.getCodec().readTree(parser);
            LinkedHashSet<String> actual = new LinkedHashSet<>();
            if (node == null || !node.isObject()) throw invalid(parser);
            node.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(FIELDS)) throw invalid(parser);
            try {
                return new VerifyResponse(
                        text(node, "decision", parser),
                        text(node, "authorizationRevision", parser),
                        text(node, "dutyEvidenceRevision", parser),
                        text(node, "featureDecisionRevision", parser),
                        text(node, "stepUpChallengeId", parser),
                        OffsetDateTime.parse(text(node, "stepUpExpiresAt", parser)));
            } catch (DateTimeParseException exception) {
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
                    parser, "HRM-012 receipt-first response schema is invalid.");
        }
    }
}
