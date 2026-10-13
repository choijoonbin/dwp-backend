package com.dwp.services.people.hris.employment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.assignment.AssignmentProposalCanonicalizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strict decoder and digest owner for the closed EmploymentEventCommand union. */
@Component
final class EmploymentEventCanonicalizer {

    private static final Set<String> HIRE_FIELDS = Set.of(
            "personPublicId", "legalEmployerRef", "employmentType",
            "relationshipStart", "workerCreation", "assignment");
    private static final Set<String> CONCURRENT_FIELDS = Set.of(
            "workerPublicId", "expectedWorkerVersion", "legalEmployerRef",
            "relationshipStart", "assignment");
    private static final Set<String> ASSIGNMENT_FIELDS = Set.of(
            "organizationRef", "positionRef", "jobRef", "workLocationRef",
            "validFrom", "validToExclusive", "primary", "fte");
    private static final Set<String> OWNER_REF_REQUIRED_FIELDS = Set.of(
            "owner", "schemaId", "publicId", "revision", "contentHash");
    private static final Set<String> OWNER_REF_ALLOWED_FIELDS = Set.of(
            "owner", "schemaId", "publicId", "revision", "contentHash",
            "validFrom", "validToExclusive");

    private final ObjectMapper mapper;
    private final AssignmentProposalCanonicalizer proposalCanonicalizer;

    EmploymentEventCanonicalizer(
            ObjectMapper mapper,
            AssignmentProposalCanonicalizer proposalCanonicalizer) {
        this.mapper = mapper;
        this.proposalCanonicalizer = proposalCanonicalizer;
    }

    EmploymentEventDtos.NormalizedCommand normalize(
            EmploymentEventDtos.CreateCommand request) {
        if (request == null || request.context() == null || request.payload() == null) {
            throw invalid("Employment event context and payload are required.");
        }
        EmploymentEventDtos.CommandContext context = request.context();
        requireCanonicalKey(context.idempotencyKey());
        if (!EmploymentEventDtos.PURPOSE.equals(context.purpose())) {
            throw forbidden("The employment command purpose is not authorized.");
        }
        ObjectNode payload = object(request.payload(), "payload");
        return switch (request.eventType()) {
            case "HIRE" -> hire(context, payload);
            case "CONCURRENT_EMPLOYMENT" -> concurrent(context, payload);
            default -> throw invalid("The employment event type is not supported.");
        };
    }

    EmploymentEventDtos.NormalizedCommand normalizeStored(
            EmploymentEventRepository.EventRow row) {
        EmploymentEventDtos.CommandContext context = mapper.convertValue(
                row.context(), EmploymentEventDtos.CommandContext.class);
        return normalize(new EmploymentEventDtos.CreateCommand(
                context, row.eventType(), mapper.valueToTree(row.payload())));
    }

    String requestDigest(
            String method,
            String path,
            String ifMatch,
            String idempotencyKey,
            java.util.UUID correlationId,
            String purpose,
            Object body) {
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("method", method);
        binding.put("path", path);
        binding.put("ifMatch", ifMatch);
        binding.put("idempotencyKey", idempotencyKey);
        binding.put("correlationId", correlationId);
        binding.put("purposeCode", purpose);
        binding.put("body", body);
        return proposalCanonicalizer.sha256(binding);
    }

    String digest(Object value) {
        return proposalCanonicalizer.sha256(value);
    }

    String ownerRefDigest(String schemaId, java.util.UUID publicId, long revision) {
        return proposalCanonicalizer.sha256(Map.of(
                "owner", "HRM",
                "schemaId", schemaId,
                "publicId", publicId,
                "revision", revision));
    }

    String json(Object value) {
        return proposalCanonicalizer.canonicalJson(value);
    }

    long positiveRevision(String value) {
        String digest = sha256Text(value == null ? "" : value);
        return Long.parseLong(digest.substring(0, 15), 16) + 1;
    }

    private EmploymentEventDtos.NormalizedCommand hire(
            EmploymentEventDtos.CommandContext context,
            ObjectNode payloadNode) {
        requireFields(payloadNode, HIRE_FIELDS, "HIRE payload");
        validateAssignmentNode(payloadNode.get("assignment"));
        EmploymentEventDtos.HirePayload payload = convert(
                payloadNode, EmploymentEventDtos.HirePayload.class);
        requireOwnerRef(payload.legalEmployerRef(), "HRM.LegalEmployer.v1");
        requireAssignment(payload.assignment());
        if (!"CREATE_NEW".equals(payload.workerCreation())) {
            throw invalid("HIRE requires workerCreation CREATE_NEW.");
        }
        if (context.expectedVersion() != 0) {
            throw invalid("HIRE context expectedVersion must be 0.");
        }
        if (!Set.of("EMPLOYEE", "CONTINGENT", "INTERN", "APPRENTICE")
                .contains(payload.employmentType())) {
            throw invalid("The employment type is not supported.");
        }
        requirePeriod(payload.relationshipStart(), payload.assignment());
        Map<String, Object> canonicalPayload = map(payloadNode);
        return new EmploymentEventDtos.NormalizedCommand(
                context, "HIRE", payload.personPublicId(), null, 0,
                payload.legalEmployerRef(), payload.employmentType(),
                payload.relationshipStart(), payload.assignment(), canonicalPayload,
                json(context), json(canonicalPayload));
    }

    private EmploymentEventDtos.NormalizedCommand concurrent(
            EmploymentEventDtos.CommandContext context,
            ObjectNode payloadNode) {
        requireFields(payloadNode, CONCURRENT_FIELDS, "CONCURRENT_EMPLOYMENT payload");
        validateAssignmentNode(payloadNode.get("assignment"));
        EmploymentEventDtos.ConcurrentEmploymentPayload payload = convert(
                payloadNode, EmploymentEventDtos.ConcurrentEmploymentPayload.class);
        requireOwnerRef(payload.legalEmployerRef(), "HRM.LegalEmployer.v1");
        requireAssignment(payload.assignment());
        if (payload.expectedWorkerVersion() < 0) {
            throw invalid("expectedWorkerVersion must not be negative.");
        }
        if (payload.expectedWorkerVersion() != context.expectedVersion()) {
            throw invalid("Context and worker expected versions must match.");
        }
        requirePeriod(payload.relationshipStart(), payload.assignment());
        Map<String, Object> canonicalPayload = map(payloadNode);
        return new EmploymentEventDtos.NormalizedCommand(
                context, "CONCURRENT_EMPLOYMENT", null, payload.workerPublicId(),
                payload.expectedWorkerVersion(), payload.legalEmployerRef(), "EMPLOYEE",
                payload.relationshipStart(), payload.assignment(), canonicalPayload,
                json(context), json(canonicalPayload));
    }

    private void validateAssignmentNode(JsonNode value) {
        ObjectNode assignment = object(value, "assignment");
        requireFields(assignment, ASSIGNMENT_FIELDS, "assignment");
        requireOwnerFields(object(assignment.get("organizationRef"), "organizationRef"),
                "organizationRef");
        requireOwnerFields(object(assignment.get("positionRef"), "positionRef"),
                "positionRef");
        requireOwnerFields(object(assignment.get("jobRef"), "jobRef"), "jobRef");
        requireOwnerFields(object(assignment.get("workLocationRef"), "workLocationRef"),
                "workLocationRef");
        JsonNode fte = assignment.get("fte");
        if (fte == null || !fte.isTextual()
                || !fte.textValue().matches("^(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$")) {
            throw invalid("fte must be a canonical non-negative decimal string.");
        }
    }

    private void requireAssignment(EmploymentEventDtos.AssignmentDraft assignment) {
        if (assignment == null) throw invalid("assignment is required.");
        requireOwnerRef(assignment.organizationRef(), "HRM.Organization.v1");
        requireOwnerRef(assignment.positionRef(), "HRM.Position.v1");
        requireOwnerRef(assignment.jobRef(), "HRM.Job.v1");
        requireOwnerRef(assignment.workLocationRef(), "HRM.WorkLocation.v1");
        if (assignment.fte() == null
                || assignment.fte().compareTo(BigDecimal.ZERO) <= 0
                || assignment.fte().compareTo(BigDecimal.ONE) > 0) {
            throw problem("FTE_OUT_OF_RANGE", "fte must be greater than 0 and at most 1.");
        }
        if (assignment.validToExclusive() != null
                && !assignment.validToExclusive().isAfter(assignment.validFrom())) {
            throw invalid("validToExclusive must be after validFrom.");
        }
    }

    private void requirePeriod(
            java.time.LocalDate relationshipStart,
            EmploymentEventDtos.AssignmentDraft assignment) {
        if (relationshipStart == null || assignment.validFrom() == null
                || assignment.validFrom().isBefore(relationshipStart)) {
            throw invalid("The assignment period must be inside the relationship period.");
        }
    }

    private void requireOwnerRef(EmploymentEventDtos.OwnerRef ref, String schema) {
        if (ref == null || !"HRM".equals(ref.owner()) || !schema.equals(ref.schemaId())
                || ref.publicId() == null || ref.revision() < 0
                || ref.contentHash() == null
                || !ref.contentHash().matches("^[0-9a-f]{64}$")) {
            throw invalid("A canonical " + schema + " owner reference is required.");
        }
        if (ref.validFrom() != null && ref.validToExclusive() != null
                && !ref.validToExclusive().isAfter(ref.validFrom())) {
            throw invalid("The owner reference validity must be half-open.");
        }
    }

    private void requireCanonicalKey(String value) {
        if (value == null || value.length() < 16 || value.length() > 128
                || !value.equals(value.trim())
                || !value.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw invalid("A canonical Idempotency-Key is required.");
        }
    }

    private ObjectNode object(JsonNode value, String field) {
        if (!(value instanceof ObjectNode object)) {
            throw invalid(field + " must be a JSON object.");
        }
        return object;
    }

    private void requireFields(ObjectNode object, Set<String> expected, String field) {
        Set<String> actual = new java.util.TreeSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid(field + " must contain exactly " + expected + '.');
        }
    }

    private void requireOwnerFields(ObjectNode object, String field) {
        Set<String> actual = new java.util.TreeSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.containsAll(OWNER_REF_REQUIRED_FIELDS)
                || !OWNER_REF_ALLOWED_FIELDS.containsAll(actual)) {
            throw invalid(field + " is not a closed canonical OwnerRef.");
        }
    }

    private <T> T convert(ObjectNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException exception) {
            throw invalid("The employment event payload is not canonical.");
        }
    }

    private Map<String, Object> map(ObjectNode node) {
        return java.util.Collections.unmodifiableMap(mapper.convertValue(
                node, new TypeReference<LinkedHashMap<String, Object>>() { }));
    }

    private String sha256Text(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private EmploymentEventProblem problem(String code, String message) {
        return new EmploymentEventProblem(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                code.toUpperCase(Locale.ROOT), message, null, null, false);
    }
}
