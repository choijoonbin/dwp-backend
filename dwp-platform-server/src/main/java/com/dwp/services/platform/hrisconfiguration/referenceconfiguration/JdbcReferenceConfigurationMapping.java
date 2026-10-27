package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationRepository.*;

/** Internal jdbc reference configuration mapping operations shared by the owner facade. */
final class JdbcReferenceConfigurationMapping {
    private final ObjectMapper objectMapper;

    JdbcReferenceConfigurationMapping(
            ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }


    StoredReceipt storedReceipt(ResultSet rs) throws SQLException {
        return new StoredReceipt(
                rs.getString("request_digest"),
                read(rs.getString("receipt_payload"), CommandReceipt.class));
    }

    VersionDetail version(ResultSet rs, int row) throws SQLException {
        UUID validationReceipt = rs.getObject("validation_receipt_id", UUID.class);
        ValidationFact validation = validationReceipt == null ? null : new ValidationFact(
                validationReceipt,
                rs.getString("validation_evidence_digest"),
                rs.getInt("validation_schema_revision"),
                rs.getString("validation_scope_digest"),
                rs.getObject("validated_at", OffsetDateTime.class));
        UUID simulationReceipt = rs.getObject("simulation_receipt_id", UUID.class);
        SimulationFact simulation = simulationReceipt == null ? null : new SimulationFact(
                simulationReceipt,
                rs.getObject("simulation_validation_receipt_id", UUID.class),
                rs.getString("simulation_evidence_digest"),
                rs.getString("simulation_impact_digest"),
                rs.getObject("simulated_at", OffsetDateTime.class),
                rs.getObject("simulation_fresh_until", OffsetDateTime.class));
        UUID submissionReceipt = rs.getObject("submission_receipt_id", UUID.class);
        SubmissionFact submission = submissionReceipt == null ? null : new SubmissionFact(
                submissionReceipt,
                rs.getObject("submission_validation_receipt_id", UUID.class),
                rs.getObject("submission_simulation_receipt_id", UUID.class),
                rs.getString("submitted_evidence_digest"),
                rs.getString("approval_workflow_key"),
                rs.getObject("submitted_at", OffsetDateTime.class));
        UUID approvalReceipt = rs.getObject("approval_receipt_id", UUID.class);
        ApprovalFact approval = approvalReceipt == null ? null : new ApprovalFact(
                approvalReceipt,
                rs.getObject("approval_submission_receipt_id", UUID.class),
                rs.getString("approval_decision_digest"),
                rs.getString("approval_evidence_digest"),
                rs.getObject("approved_at", OffsetDateTime.class));
        return new VersionDetail(
                rs.getObject("configuration_id", UUID.class),
                rs.getLong("version"),
                Domain.valueOf(rs.getString("domain")),
                ScopeType.valueOf(rs.getString("scope_type")),
                rs.getObject("scope_id", UUID.class),
                LifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getObject("effective_from", OffsetDateTime.class),
                rs.getObject("effective_to", OffsetDateTime.class),
                rs.getString("schema_key"),
                rs.getInt("schema_version"),
                rs.getString("payload_reference"),
                rs.getString("payload_digest"),
                validation,
                simulation,
                submission,
                approval,
                rs.getLong("aggregate_version"),
                nullableLong(rs, "authored_by"),
                nullableLong(rs, "approved_by"),
                rs.getObject("scheduled_at", OffsetDateTime.class),
                nullableLong(rs, "supersedes_version"),
                nullableLong(rs, "replacement_version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    boolean matchesCatalog(WorkbenchRow row, CatalogFilter filter) {
        if (filter == null) return true;
        if (filter.key() != null && !row.item().key().equals(filter.key())) return false;
        if (filter.domain() != null && row.item().domain() != filter.domain()) return false;
        if (filter.state() != null && row.item().state() != filter.state()) return false;
        return filter.minimumVersion() == null || row.item().version() >= filter.minimumVersion();
    }

    boolean matchesWorkbench(WorkbenchRow row, WorkbenchFilter filter) {
        if (filter == null) return true;
        CatalogItem item = row.item();
        VersionDetail detail = row.detail();
        if (notEmpty(filter.policyKeys()) && !filter.policyKeys().contains(detail.schemaKey())) return false;
        if (filter.minimumPolicyRevision() != null
                && detail.version() < filter.minimumPolicyRevision()) return false;
        if (notEmpty(filter.referencePublicIds())
                && !filter.referencePublicIds().contains(item.publicId().toString())) return false;
        if (notEmpty(filter.referenceCodes())
                && !filter.referenceCodes().contains(item.domain().name())) return false;
        if (notEmpty(filter.effectiveStatuses())
                && !filter.effectiveStatuses().contains(item.state().name())) return false;
        if (filter.minimumStatusRevision() != null
                && detail.version() < filter.minimumStatusRevision()) return false;
        if (filter.effectiveOn() != null) {
            if (filter.effectiveOn().isBefore(detail.effectiveFrom().toLocalDate())) return false;
            if (detail.effectiveTo() != null
                    && !filter.effectiveOn().isBefore(detail.effectiveTo().toLocalDate())) return false;
        }
        return true;
    }

    Comparator<WorkbenchRow> workbenchComparator(List<String> sorts) {
        Comparator<WorkbenchRow> comparator = null;
        for (String sort : sorts) {
            Comparator<WorkbenchRow> next = switch (sort) {
                case "policyVersion:ASC" -> Comparator.comparingLong(row -> row.detail().version());
                case "policyVersion:DESC" -> Comparator.comparingLong(
                        (WorkbenchRow row) -> row.detail().version()).reversed();
                case "referenceData:ASC" -> Comparator.comparing(row -> row.item().key());
                case "referenceData:DESC" -> Comparator.comparing(
                        (WorkbenchRow row) -> row.item().key()).reversed();
                case "effectiveStatus:ASC" -> Comparator.comparing(
                        row -> row.item().state().name());
                case "effectiveStatus:DESC" -> Comparator.comparing(
                        (WorkbenchRow row) -> row.item().state().name()).reversed();
                case "itemPublicId:ASC" -> Comparator.comparing(row -> row.item().publicId());
                case "itemPublicId:DESC" -> Comparator.comparing(
                        (WorkbenchRow row) -> row.item().publicId()).reversed();
                default -> throw new IllegalArgumentException("REFERENCE_CONFIGURATION_SORT_INVALID");
            };
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        Comparator<WorkbenchRow> stable = Comparator.comparing(row -> row.item().publicId());
        return comparator == null ? stable : comparator.thenComparing(stable);
    }

    int seekIndex(List<WorkbenchRow> candidates, UUID seekAfter) {
        if (seekAfter == null) return -1;
        for (int index = 0; index < candidates.size(); index++) {
            if (candidates.get(index).item().publicId().equals(seekAfter)) return index;
        }
        return -1;
    }

    boolean notEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    long advisoryKey(
            long tenantId,
            long subjectId,
            String originatingAction,
            String idempotencyKey) {
        String material = tenantId + "|" + subjectId + "|" + originatingAction + "|" + idempotencyKey;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    String normalizeDigest(String digest) {
        return digest.startsWith("sha256:")
                ? digest.substring(7).toLowerCase(Locale.ROOT)
                : digest.toLowerCase(Locale.ROOT);
    }

    String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_SERIALIZATION_FAILED", exception);
        }
    }

    <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("REFERENCE_CONFIGURATION_RECEIPT_INVALID", exception);
        }
    }

    UUID resultConfigurationId(String resultRef) {
        return UUID.fromString(resultRef.split("/", -1)[0]);
    }

    long resultVersion(String resultRef) {
        return Long.parseLong(resultRef.split("/", -1)[1]);
    }

    <T, R> R value(T source, java.util.function.Function<T, R> extractor) {
        return source == null ? null : extractor.apply(source);
    }
}
