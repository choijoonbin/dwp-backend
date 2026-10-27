package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.HrisConfigurationPublishedV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.typedOwner;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationGenericActions.*;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationRepository.*;

import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationContext.*;
/** Evidence, access guards and serialization shared by configuration commands and reads. */
final class ReferenceConfigurationSupport {
    static final String PUBLISHED_EVENT = "dwp.hris.configuration.published.v1";
    static final String INVALIDATION_EVENT = "dwp.hris.access.assignment.changed.v1";
    static final String WORKBENCH_ROUTE = "/hr/settings/reference-data";
    static final String PURPOSE = "HRIS_SYSTEM_ADMIN";
    static final String UPDATE_PERMISSION = "CONFIG.HRIS_POLICY:UPDATE";
    static final String PUBLISH_PERMISSION = "ACTION.HRIS_POLICY_PUBLISH:PUBLISH";
    static final String VIEW_PERMISSION = "CONFIG.HRIS_WORKBENCH:VIEW";
    static final Set<String> FIELD_GROUPS = Set.of(
            "POLICY_VERSION", "REFERENCE_DATA", "EFFECTIVE_STATUS");
    static final Set<String> CATALOG_FIELDS = Set.of(
            "publicId", "key", "type", "state", "version", "updatedAt");
    static final Set<String> ALLOWED_SORTS = Set.of(
            "policyVersion:ASC", "referenceData:ASC", "effectiveStatus:ASC",
            "policyVersion:DESC", "referenceData:DESC", "effectiveStatus:DESC",
            "itemPublicId:ASC", "itemPublicId:DESC");

    private final ReferenceConfigurationRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    ReferenceConfigurationSupport(ReferenceConfigurationRepository repository,
            ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    void appendPublishedEvent(Access access, VersionDetail detail) {
        if (detail.approval() == null) {
            throw conflict("REFERENCE_CONFIGURATION_APPROVAL_EVIDENCE_MISSING");
        }
        OffsetDateTime publishedAt = now();
        HrisConfigurationPublishedV1 event = new HrisConfigurationPublishedV1(
                detail.configurationId(),
                "HRIS_REFERENCE_" + detail.domain().name(),
                detail.scopeType() == ScopeType.POLICY_NODE ? "POPULATION" : "LEGAL_ENTITY",
                detail.scopeId(),
                detail.version(),
                detail.effectiveFrom().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                detail.effectiveTo() == null
                        ? null
                        : detail.effectiveTo().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                payloadSchemaVersion(detail),
                detail.payloadDigest(),
                detail.approval().receiptId(),
                publishedAt.toInstant(),
                detail.version());
        String payload = json(event);
        try {
            HrisConfigurationPublishedV1 roundTrip = objectMapper.readValue(
                    payload, HrisConfigurationPublishedV1.class);
            if (!event.equals(roundTrip)) {
                throw new IllegalStateException("REFERENCE_CONFIGURATION_EVENT_ROUND_TRIP_MISMATCH");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "REFERENCE_CONFIGURATION_EVENT_ROUND_TRIP_FAILED", exception);
        }
        repository.appendOutbox(
                access.tenantId(), detail.configurationId(), detail.version(),
                PUBLISHED_EVENT, payload, publishedAt);
    }

    ValidationFact requireValidation(VersionDetail detail, UUID receiptId) {
        if (detail.validation() == null || !detail.validation().receiptId().equals(receiptId)) {
            throw conflict("REFERENCE_CONFIGURATION_VALIDATION_EVIDENCE_MISMATCH");
        }
        return detail.validation();
    }

    SimulationFact requireSimulation(
            VersionDetail detail,
            UUID receiptId,
            UUID validationReceiptId) {
        if (detail.simulation() == null
                || !detail.simulation().receiptId().equals(receiptId)
                || !detail.simulation().validationReceiptId().equals(validationReceiptId)) {
            throw conflict("REFERENCE_CONFIGURATION_SIMULATION_EVIDENCE_MISMATCH");
        }
        return detail.simulation();
    }

    SubmissionFact requireSubmission(VersionDetail detail, UUID receiptId) {
        if (detail.submission() == null || !detail.submission().receiptId().equals(receiptId)) {
            throw conflict("REFERENCE_CONFIGURATION_SUBMITTED_EVIDENCE_MISMATCH");
        }
        return detail.submission();
    }

    void requireStoredReceipt(
            long tenantId,
            UUID receiptId,
            String commandType,
            UUID configurationId,
            long version) {
        StoredReceipt stored = repository.findReceiptById(tenantId, receiptId)
                .orElseThrow(() -> conflict("REFERENCE_CONFIGURATION_RECEIPT_NOT_FOUND"));
        if (!commandType.equals(stored.receipt().commandType())
                || !(configurationId + "/" + version).equals(stored.receipt().resultRef())) {
            throw conflict("REFERENCE_CONFIGURATION_RECEIPT_EVIDENCE_MISMATCH");
        }
    }

    void requirePublishEvidence(
            VersionDetail detail,
            UUID approvalReceiptId,
            String impactDigest) {
        requireApproval(detail, approvalReceiptId);
        if (detail.simulation() == null
                || !detail.simulation().impactDigest().equals(normalizeDigest(impactDigest))) {
            throw conflict("REFERENCE_CONFIGURATION_IMPACT_EVIDENCE_MISMATCH");
        }
    }

    void requireApproval(VersionDetail detail, UUID approvalReceiptId) {
        if (detail.approval() == null
                || !detail.approval().receiptId().equals(approvalReceiptId)) {
            throw conflict("REFERENCE_CONFIGURATION_APPROVAL_EVIDENCE_MISMATCH");
        }
    }

    void requireCompatibility(VersionDetail current, VersionDetail candidate) {
        if (current.domain() != candidate.domain()
                || current.scopeType() != candidate.scopeType()
                || !current.scopeId().equals(candidate.scopeId())
                || !current.schemaKey().equals(candidate.schemaKey())
                || current.schemaVersion() != candidate.schemaVersion()) {
            throw conflict("REFERENCE_CONFIGURATION_SCHEMA_INCOMPATIBLE");
        }
    }

    void requireEffectiveWindow(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || (to != null && !to.isAfter(from))) {
            throw invalid("REFERENCE_CONFIGURATION_EFFECTIVE_WINDOW_INVALID");
        }
    }

    void requireActiveAt(VersionDetail detail, OffsetDateTime instant, String error) {
        if (detail.effectiveFrom().isAfter(instant)
                || (detail.effectiveTo() != null && !detail.effectiveTo().isAfter(instant))) {
            throw conflict(error);
        }
    }

    void requireDifferentActor(VersionDetail detail, long actorId) {
        if (detail.authoredBy() != null && detail.authoredBy() == actorId) {
            throw new BaseException(ErrorCode.SOD_CONFLICT, "NO_PUBLISH_OWN_POLICY");
        }
    }

    void requireExpectedVersion(Command command, long aggregateVersion) {
        if (command.expectedObjectVersion() == null
                || command.expectedObjectVersion() != aggregateVersion) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "REFERENCE_CONFIGURATION_OBJECT_VERSION_CONFLICT");
        }
    }

    void requireView(Access access) {
        validateAccess(access);
        Set<String> permissions = permissions(access.permissions());
        if (!permissions.contains(VIEW_PERMISSION)
                && !permissions.contains(UPDATE_PERMISSION)
                && !permissions.contains(PUBLISH_PERMISSION)) {
            throw forbidden("REFERENCE_CONFIGURATION_VIEW_DENIED");
        }
    }

    void requireManager(Access access) {
        validateAccess(access);
        Set<String> permissions = permissions(access.permissions());
        if (!permissions.contains(UPDATE_PERMISSION) && !permissions.contains(PUBLISH_PERMISSION)) {
            throw forbidden("REFERENCE_CONFIGURATION_MANAGER_DENIED");
        }
    }

    void requireAuthor(Access access) {
        validateAccess(access);
        if (!permissions(access.permissions()).contains(UPDATE_PERMISSION)) {
            throw forbidden("REFERENCE_CONFIGURATION_AUTHOR_DENIED");
        }
    }

    void requirePublisher(Access access, Command command) {
        validateAccess(access);
        if (!permissions(access.permissions()).contains(PUBLISH_PERMISSION)) {
            throw forbidden("REFERENCE_CONFIGURATION_PUBLISHER_DENIED");
        }
        if (command.stepUpChallenge() == null || command.stepUpChallenge().isBlank()) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED, "REFERENCE_CONFIGURATION_STEP_UP_REQUIRED");
        }
        if (command.expectedDecisionRevision() == null
                || !command.expectedDecisionRevision().equals(access.scope().decisionRevision())) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "REFERENCE_CONFIGURATION_DECISION_REVISION_MISMATCH");
        }
    }

    void validateAccess(Access access) {
        if (access == null || access.tenantId() <= 0 || access.subjectId() <= 0
                || access.subjectPublicId() == null || access.scope() == null
                || access.scope().type() == null || access.scope().id() == null
                || access.scope().digest() == null
                || !access.scope().digest().matches("^[a-f0-9]{64}$")
                || access.scope().decisionRevision() == null
                || access.scope().decisionRevision().isBlank()
                || access.fieldPolicyRevision() < 1 || access.authorizationRevision() < 1
                || access.revalidateAt() == null || !access.revalidateAt().isAfter(now())
                || !PURPOSE.equals(access.purposeCode())) {
            throw forbidden("REFERENCE_CONFIGURATION_TRUSTED_CONTEXT_REQUIRED");
        }
        Set<String> roles = permissions(access.roles());
        if (roles.stream().anyMatch(value -> value.startsWith("PROVIDER"))) {
            throw forbidden("REFERENCE_CONFIGURATION_PROVIDER_IDENTITY_DENIED");
        }
        Set<String> permissions = permissions(access.permissions());
        if (!permissions.contains("APP.HCM:VIEW") && !permissions.contains("APP.HRIS:VIEW")) {
            throw forbidden("REFERENCE_CONFIGURATION_ENTITLEMENT_REQUIRED");
        }
        if (!FIELD_GROUPS.containsAll(access.maskedFieldGroups())
                || !FIELD_GROUPS.containsAll(access.omittedFieldGroups())
                || !java.util.Collections.disjoint(
                        access.maskedFieldGroups(), access.omittedFieldGroups())
                || access.omittedFieldGroups().containsAll(FIELD_GROUPS)) {
            throw forbidden("REFERENCE_CONFIGURATION_FIELD_POLICY_INVALID");
        }
    }

    void validateLegacyCommand(Command command) {
        if (command == null
                || command.idempotencyKey() == null
                || command.idempotencyKey().length() < 16
                || command.idempotencyKey().length() > 128
                || command.correlationId() == null
                || !PURPOSE.equals(command.purposeCode())) {
            throw invalid("REFERENCE_CONFIGURATION_COMMAND_CONTEXT_INVALID");
        }
    }

    void validateCanonicalSeal(
            Access access, Command command, String action) {
        if (!originRoute(action).equals(command.originRouteContractKey())
                || access.contextKey() == null
                || !access.contextKey().equals(command.originContextKey())
                || access.contextScopeKey() == null
                || !access.contextScopeKey().equals(command.originContextScopeKey())
                || !access.scope().decisionRevision().equals(
                command.originDecisionRevision())) {
            throw invalid("REFERENCE_CONFIGURATION_COMMAND_CONTEXT_INVALID");
        }
    }

    String originRoute(String action) {
        return switch (action) {
            case "configuration.version.create" ->
                    "route.hcm.configuration.configuration-version-create.action";
            case "configuration.version.validate" ->
                    "route.hcm.configuration.configuration-version-validate.action";
            case "configuration.version.simulate" ->
                    "route.hcm.configuration.configuration-version-simulate.action";
            case "configuration.version.submit" ->
                    "route.hcm.configuration.configuration-version-submit.action";
            case "configuration.version.approve" ->
                    "route.hcm.configuration.configuration-version-approve.action";
            case "configuration.version.schedule" ->
                    "route.hcm.configuration.configuration-version-schedule.action";
            case "configuration.version.publish" ->
                    "route.hcm.configuration.configuration-version-publish.action";
            case "configuration.version.rollback" ->
                    "route.hcm.configuration.configuration-version-rollback.action";
            case "configuration.version.retire" ->
                    "route.hcm.configuration.configuration-version-retire.action";
            default -> "";
        };
    }

    VersionDetail requiredVersion(
            Access access,
            UUID configurationId,
            long version) {
        return repository.findVersion(
                        access.tenantId(), access.scope(), configurationId, version)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_NOT_AVAILABLE,
                        "REFERENCE_CONFIGURATION_NOT_FOUND"));
    }

    VersionResponse response(VersionDetail detail) {
        return new VersionResponse(
                detail.configurationId(), detail.version(), detail.state(),
                detail.effectiveFrom(), detail.effectiveTo(), detail.schemaKey(),
                detail.schemaVersion(), detail.payloadDigest(),
                detail.approval() == null ? null : detail.approval().receiptId(),
                detail.aggregateVersion());
    }

    String scopeRevision(Access access) {
        return "scope-" + digest(Map.of(
                "scopeDecision", access.scope().decisionRevision(),
                "scopeDigest", access.scope().digest(),
                "fieldPolicyRevision", access.fieldPolicyRevision(),
                "authorizationRevision", access.authorizationRevision(),
                "masked", access.maskedFieldGroups().stream().sorted().toList(),
                "omitted", access.omittedFieldGroups().stream().sorted().toList()))
                .substring(0, 40);
    }

    String authorizationContextDigest(Access access, Command command) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("tenantId", access.tenantId());
        context.put("subjectPrincipalPublicId", access.subjectPublicId());
        context.put("populationScopeDigest", access.scope().digest());
        context.put("permissions", permissions(access.permissions()).stream().sorted().toList());
        context.put("roles", permissions(access.roles()).stream().sorted().toList());
        context.put("fieldPolicyRevision", access.fieldPolicyRevision());
        context.put("authorizationRevision", access.authorizationRevision());
        if (command.expectedDecisionRevision() != null) {
            context.put("decisionRevision", command.expectedDecisionRevision());
        }
        if (command.stepUpChallenge() != null) {
            context.put("stepUpProofDigest", digest(command.stepUpChallenge()));
        }
        return digest(context);
    }

    Set<String> permissions(String value) {
        if (value == null || value.isBlank() || value.length() > 32_000
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.trim())) return Set.of();
            result.add(token.toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    boolean notEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    String payloadSchemaVersion(VersionDetail detail) {
        String candidate = detail.schemaKey();
        if (candidate.matches("^[A-Za-z][A-Za-z0-9._-]{0,127}\\.v[1-9][0-9]*$")) {
            return candidate;
        }
        String prefix = candidate.replaceAll("[^A-Za-z0-9._-]", "_");
        if (prefix.isBlank() || !Character.isLetter(prefix.charAt(0))) prefix = "Hris_" + prefix;
        int maximumPrefix = 124 - Integer.toString(detail.schemaVersion()).length();
        if (prefix.length() > maximumPrefix) prefix = prefix.substring(0, maximumPrefix);
        return prefix + ".v" + detail.schemaVersion();
    }

    String normalizeDigest(String value) {
        return value.startsWith("sha256:")
                ? value.substring(7).toLowerCase(Locale.ROOT)
                : value.toLowerCase(Locale.ROOT);
    }

    Object nullable(Object value) {
        return value == null ? "<null>" : value;
    }

    OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    String digest(Object value) {
        byte[] bytes;
        try {
            bytes = value instanceof String string
                    ? string.getBytes(StandardCharsets.UTF_8)
                    : objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_DIGEST_FAILED", exception);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_EVENT_SERIALIZATION_FAILED", exception);
        }
    }

    BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

}
