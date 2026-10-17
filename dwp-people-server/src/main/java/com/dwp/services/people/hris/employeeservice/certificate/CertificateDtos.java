package com.dwp.services.people.hris.employeeservice.certificate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Closed Stage-A request, receipt metadata, and IA projection shapes. */
final class CertificateDtos {

    static final String PROJECTION_VERSION = "1";
    static final String INVALIDATION_EVENT = "dwp.hris.access.assignment.changed.v1";
    static final String REQUEST_CERTIFICATE_ACTION =
            "hris.action.hrm.request-certificate";

    private CertificateDtos() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @JsonDeserialize(using = RequestCertificateDeserializer.class)
    record RequestCertificate(
            @NotNull UUID personPublicId,
            @NotBlank @Size(max = 64) String certificateType,
            @NotBlank @Pattern(regexp =
                    "EMPLOYEE_SELF_SERVICE|TEAM_MANAGEMENT|HR_OPERATIONS")
            String purposeCode,
            @NotBlank @Pattern(regexp = "^[a-z]{2}(?:-[A-Z]{2})?$") String locale,
            @NotNull Instant asOf) {
    }

    /** Enforces additionalProperties:false regardless of the application's mapper defaults. */
    static final class RequestCertificateDeserializer
            extends StdDeserializer<RequestCertificate> {

        private static final long serialVersionUID = 1L;
        private static final Set<String> FIELDS = Set.of(
                "personPublicId", "certificateType", "purposeCode", "locale", "asOf");

        RequestCertificateDeserializer() {
            super(RequestCertificate.class);
        }

        @Override
        public RequestCertificate deserialize(
                JsonParser parser,
                DeserializationContext context) throws IOException {
            if (!parser.isExpectedStartObjectToken()) {
                return context.reportInputMismatch(
                        RequestCertificate.class,
                        "Certificate request must be encoded as a JSON object.");
            }
            UUID personPublicId = null;
            String certificateType = null;
            String purposeCode = null;
            String locale = null;
            Instant asOf = null;
            Set<String> seen = new HashSet<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (!parser.hasToken(JsonToken.FIELD_NAME)) {
                    return context.reportInputMismatch(
                            RequestCertificate.class,
                            "Certificate request contains a non-field token.");
                }
                String name = parser.currentName();
                if (!seen.add(name)) {
                    return context.reportInputMismatch(
                            RequestCertificate.class, "Duplicate property %s.", name);
                }
                if (parser.nextToken() == null) {
                    return context.reportInputMismatch(
                            RequestCertificate.class,
                            "Certificate request ends before a property value.");
                }
                switch (name) {
                    case "personPublicId" -> personPublicId = uuid(parser, context, name);
                    case "certificateType" -> certificateType = text(parser, context, name);
                    case "purposeCode" -> purposeCode = text(parser, context, name);
                    case "locale" -> locale = text(parser, context, name);
                    case "asOf" -> asOf = instant(parser, context, name);
                    default -> {
                        return context.reportInputMismatch(
                                RequestCertificate.class, "Unknown property %s.", name);
                    }
                }
            }
            if (!seen.equals(FIELDS)) {
                Set<String> missing = new java.util.TreeSet<>(FIELDS);
                missing.removeAll(seen);
                return context.reportInputMismatch(
                        RequestCertificate.class,
                        "Certificate request is missing required properties %s.", missing);
            }
            return new RequestCertificate(
                    personPublicId, certificateType, purposeCode, locale, asOf);
        }

        private String text(
                JsonParser parser,
                DeserializationContext context,
                String name) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(
                        RequestCertificate.class, "%s must be a JSON string.", name);
            }
            return parser.getText();
        }

        private UUID uuid(
                JsonParser parser,
                DeserializationContext context,
                String name) throws IOException {
            String value = text(parser, context, name);
            try {
                return UUID.fromString(value);
            } catch (RuntimeException exception) {
                return context.reportInputMismatch(
                        RequestCertificate.class, "%s must be a canonical UUID.", name);
            }
        }

        private Instant instant(
                JsonParser parser,
                DeserializationContext context,
                String name) throws IOException {
            String value = text(parser, context, name);
            try {
                return Instant.parse(value);
            } catch (RuntimeException exception) {
                return context.reportInputMismatch(
                        RequestCertificate.class, "%s must be an instant.", name);
            }
        }
    }

    record QueryRequest(
            @Min(1) @Max(100) int limit,
            @NotNull Object filter,
            @NotEmpty @Size(max = 3) List<String> sort,
            @NotBlank @Size(min = 2, max = 35) String locale,
            @NotBlank @Size(max = 255) String timeZone,
            @NotNull UUID correlationId,
            Instant asOf,
            String cursor) {
        QueryRequest {
            sort = sort == null ? null : List.copyOf(sort);
        }
    }

    record SelfRequestsFilter(
            AggregateFilter requestSummary,
            StatusFilter requestStatus,
            ReceiptFilter commandReceipt,
            LocalDate effectiveOn) {
    }

    record OperationsFilter(
            MetadataFilter certificateMetadata,
            MetadataFilter documentMetadata,
            LocalDate effectiveOn) {
    }

    record AggregateFilter(Integer minimumItemCount, Integer maximumItemCount) {
    }

    record StatusFilter(List<String> codes, Long minimumRevision) {
        StatusFilter {
            codes = codes == null ? List.of() : List.copyOf(codes);
        }
    }

    record ReceiptFilter(List<String> receiptIds, List<String> states) {
        ReceiptFilter {
            receiptIds = receiptIds == null ? List.of() : List.copyOf(receiptIds);
            states = states == null ? List.of() : List.copyOf(states);
        }
    }

    record MetadataFilter(List<String> keys, List<String> versions) {
        MetadataFilter {
            keys = keys == null ? List.of() : List.copyOf(keys);
            versions = versions == null ? List.of() : List.copyOf(versions);
        }
    }

    record Projection(
            String projectionVersion,
            Instant asOf,
            String variantKey,
            QueryState queryState,
            List<Item> items) {
        Projection {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record QueryState(
            String state,
            String scopeDecisionRevision,
            String freshness,
            String nextCursor,
            FieldDecisionSummary fieldDecisionSummary,
            List<PartialFailure> partialFailures,
            UUID correlationId,
            CachePolicy cachePolicy) {
        QueryState {
            partialFailures = partialFailures == null
                    ? List.of() : List.copyOf(partialFailures);
        }
    }

    record FieldDecisionSummary(int viewCount, int maskedCount, int omittedCount) {
    }

    record PartialFailure(
            String componentKey,
            String errorCode,
            UUID correlationId,
            boolean retryable) {
    }

    record CachePolicy(
            String authorizationDecisionRevision,
            String invalidationEvent,
            int maxAgeSeconds) {
    }

    record Item(
            String itemPublicId,
            String title,
            String statusCode,
            Map<String, Object> fieldGroups,
            DeepLink deepLink,
            List<AvailableAction> availableActions) {
        Item {
            fieldGroups = fieldGroups == null
                    ? Map.of() : Map.copyOf(new LinkedHashMap<>(fieldGroups));
            availableActions = availableActions == null
                    ? List.of() : List.copyOf(availableActions);
        }
    }

    record ViewedField(String decision, Object value) {
        ViewedField(Object value) {
            this("VIEW", value);
        }
    }

    record MaskedField(
            String decision,
            @JsonInclude(JsonInclude.Include.ALWAYS) Object value,
            String maskReason,
            String maskedDisplayKey) {
        MaskedField(String maskReason, String maskedDisplayKey) {
            this("MASK", null, maskReason, maskedDisplayKey);
        }
    }

    record AggregateValue(int itemCount, long revision, Instant asOf) {
    }

    record StatusValue(String code, long revision) {
    }

    record ReceiptValue(UUID receiptId, String state, Instant occurredAt, long revision) {
    }

    record MetadataValue(String key, String version, String digest) {
    }

    record DeepLink(String route, String resourcePublicId) {
    }

    record AvailableAction(
            String actionKey,
            String method,
            String hrefTemplate,
            String idempotencyPolicy,
            String stepUpPolicy) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    record Problem(
            String type,
            String title,
            int status,
            String code,
            UUID correlationId,
            boolean retryable) {
    }

    record ArtifactRequest(
            UUID requestPublicId,
            UUID subjectPersonPublicId,
            long subjectExpectedVersion,
            String subjectSnapshotDigest,
            String certificateType,
            String locale,
            Instant asOf,
            UUID templatePublicId,
            int templateVersion,
            UUID sealPublicId,
            int sealVersion) {
    }

    record ArtifactResult(
            UUID objectPublicId,
            int objectVersion,
            String contentSha256,
            String verificationTokenHash,
            Instant issuedAt,
            Instant expiresAt) {
    }
}
