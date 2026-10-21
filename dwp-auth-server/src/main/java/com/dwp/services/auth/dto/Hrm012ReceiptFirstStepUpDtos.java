package com.dwp.services.auth.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Hrm012ReceiptFirstStepUpDtos {

    private Hrm012ReceiptFirstStepUpDtos() {
    }

    @JsonDeserialize(using = VerifyRequestDeserializer.class)
    public record VerifyRequest(
            @NotNull @Positive Long tenantId,
            @NotBlank @Size(max = 160) String onBehalfOfSubject,
            @NotBlank @Size(max = 240) String routeContractKey,
            @NotBlank @Size(max = 16) String commandMethod,
            @NotBlank @Size(max = 500) String publicCommandPath,
            @NotBlank @Size(max = 500) String serviceCommandPath,
            @NotBlank @Size(max = 200) String objectRef,
            @NotBlank @Size(max = 100) String ifMatch,
            @NotBlank @Size(max = 100) String purposeCode,
            @NotBlank @Size(max = 8192) String opaqueStepUpChallengeRef,
            @NotBlank @Size(max = 200) String idempotencyKey,
            @NotBlank @Size(max = 500) String contextKey,
            @NotBlank @Size(max = 500) String scopeRef,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String payloadSha256,
            @NotBlank @Size(max = 200) String decisionRevision,
            @NotBlank @Size(max = 200) String receiptPublicId,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String receiptRequestHash,
            @NotBlank @Size(max = 200) String correlationId) {
    }

    public record VerifyResponse(
            String decision,
            String authorizationRevision,
            String dutyEvidenceRevision,
            String featureDecisionRevision,
            String stepUpChallengeId,
            OffsetDateTime stepUpExpiresAt) {
    }

    public static final class VerifyRequestDeserializer
            extends StdDeserializer<VerifyRequest> {

        private static final Set<String> FIELDS = Set.of(
                "tenantId", "onBehalfOfSubject", "routeContractKey", "commandMethod",
                "publicCommandPath", "serviceCommandPath", "objectRef", "ifMatch",
                "purposeCode", "opaqueStepUpChallengeRef", "idempotencyKey", "contextKey",
                "scopeRef", "payloadSha256", "decisionRevision", "receiptPublicId",
                "receiptRequestHash", "correlationId");

        public VerifyRequestDeserializer() {
            super(VerifyRequest.class);
        }

        @Override
        public VerifyRequest deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = parser.getCodec().readTree(parser);
            LinkedHashSet<String> actual = new LinkedHashSet<>();
            if (node == null || !node.isObject()) throw invalid(parser);
            node.fieldNames().forEachRemaining(actual::add);
            if (!actual.equals(FIELDS)) throw invalid(parser);
            JsonNode tenant = node.get("tenantId");
            if (!tenant.isIntegralNumber() || !tenant.canConvertToLong()) throw invalid(parser);
            return new VerifyRequest(
                    tenant.longValue(), text(node, "onBehalfOfSubject", parser),
                    text(node, "routeContractKey", parser),
                    text(node, "commandMethod", parser),
                    text(node, "publicCommandPath", parser),
                    text(node, "serviceCommandPath", parser),
                    text(node, "objectRef", parser), text(node, "ifMatch", parser),
                    text(node, "purposeCode", parser),
                    text(node, "opaqueStepUpChallengeRef", parser),
                    text(node, "idempotencyKey", parser), text(node, "contextKey", parser),
                    text(node, "scopeRef", parser), text(node, "payloadSha256", parser),
                    text(node, "decisionRevision", parser),
                    text(node, "receiptPublicId", parser),
                    text(node, "receiptRequestHash", parser),
                    text(node, "correlationId", parser));
        }

        private String text(JsonNode node, String field, JsonParser parser)
                throws JsonMappingException {
            JsonNode value = node.get(field);
            if (value == null || !value.isTextual()) throw invalid(parser);
            return value.textValue();
        }

        private JsonMappingException invalid(JsonParser parser) {
            return JsonMappingException.from(
                    parser, "HRM-012 receipt-first request schema is invalid.");
        }
    }
}
