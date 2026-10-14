package com.dwp.services.payroll.input.compensationplan;

import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1;
import com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1.ApprovedCompensationPlanLine;
import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.platform.contracts.hris.generated.CanonicalDecimalJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputContracts.AGGREGATE_TYPE;
import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputContracts.SOURCE;
import static com.dwp.services.payroll.input.compensationplan.CompensationPlanInputContracts.TOPIC;

/** Recomputes the producer's exact canonical line and XCON-020 payload digests. */
@Component
final class CompensationPlanInputCanonicalizer {

    private final ObjectMapper mapper;

    CompensationPlanInputCanonicalizer(ObjectMapper objectMapper) {
        this.mapper = objectMapper.copy().findAndRegisterModules()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    CompensationPlanInputContracts.Publication publication(DomainEventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        validateEnvelope(envelope);
        try {
            Xcon021ApprovedCompensationPlanSnapshotPublishedV2 event =
                    mapper.treeToValue(
                            envelope.data(),
                            Xcon021ApprovedCompensationPlanSnapshotPublishedV2.class);
            validateEventIdentity(envelope, event);
            DomainEventEnvelope canonicalEnvelope = new DomainEventEnvelope(
                    envelope.specVersion(), envelope.id(), envelope.source(),
                    envelope.type(), envelope.schemaVersion(), envelope.time(),
                    envelope.subject(), envelope.tenantId(), envelope.aggregateType(),
                    envelope.aggregateId(), envelope.aggregateSequence(),
                    envelope.correlationId(), envelope.causationId(),
                    envelope.traceParent(), mapper.valueToTree(event),
                    envelope.extensions());
            String json = mapper.writeValueAsString(canonicalEnvelope);
            return new CompensationPlanInputContracts.Publication(
                    canonicalEnvelope, event,
                    digest(json.getBytes(StandardCharsets.UTF_8)), json);
        } catch (JsonProcessingException exception) {
            throw invalid("XCON-021 payload is not canonical", exception);
        }
    }

    CompensationPlanInputContracts.MaterializedSnapshot verify(
            CompensationPlanInputContracts.Publication publication,
            Xcon020ApprovedCompensationPlanSnapshotV1 snapshot) {
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(snapshot, "snapshot");
        Xcon021ApprovedCompensationPlanSnapshotPublishedV2 event = publication.event();
        if (!snapshot.snapshotId().equals(event.snapshotId())
                || snapshot.snapshotRevision() != event.snapshotRevision()
                || !snapshot.payloadDigest().equals(event.payloadDigest())
                || !snapshot.planId().equals(event.planId())
                || !snapshot.cycleId().equals(event.cycleId())
                || !snapshot.approvalReceiptId().equals(event.approvalReceiptId())
                || snapshot.sourceVersion() != event.sourceVersion()
                || snapshot.lineCount() != event.lineCount()
                || !snapshot.effectiveFrom().equals(event.effectiveDate())) {
            throw invalid("XCON-020 identity differs from its XCON-021 publication", null);
        }
        validatePeriod(snapshot.effectiveFrom(), snapshot.effectiveTo(), "snapshot");
        Set<UUID> lineIds = new HashSet<>();
        Set<String> assignmentComponents = new HashSet<>();
        for (int index = 0; index < snapshot.lines().size(); index++) {
            ApprovedCompensationPlanLine line = snapshot.lines().get(index);
            if (line.lineSequence() != index + 1 || !lineIds.add(line.lineId())) {
                throw invalid("snapshot line order or identity is invalid", null);
            }
            String assignmentComponent = line.workerId() + ":" + line.assignmentId()
                    + ":" + line.componentCode();
            if (!assignmentComponents.add(assignmentComponent)) {
                throw invalid("snapshot repeats an assignment component", null);
            }
            if (line.approvedAmount().signum() < 0) {
                throw invalid("snapshot contains a negative approved amount", null);
            }
            validatePeriod(line.effectiveFrom(), line.effectiveTo(), "line");
            if (line.effectiveFrom().isBefore(snapshot.effectiveFrom())
                    || (snapshot.effectiveTo() != null
                    && (line.effectiveTo() == null
                    || line.effectiveTo().isAfter(snapshot.effectiveTo())))) {
                throw invalid("snapshot line is outside the sealed effective range", null);
            }
            String recomputed = lineDigest(line);
            if (!recomputed.equals(line.lineDigest())) {
                throw invalid("snapshot line digest differs", null);
            }
        }
        String recomputed = payloadDigest(snapshot);
        if (!recomputed.equals(snapshot.payloadDigest())) {
            throw invalid("snapshot payload digest differs", null);
        }
        return new CompensationPlanInputContracts.MaterializedSnapshot(
                snapshot, snapshot.lines());
    }

    String lineDigest(ApprovedCompensationPlanLine line) {
        Map<String, Object> value = new TreeMap<>();
        value.put("approvedAmount",
                CanonicalDecimalJson.canonicalString(line.approvedAmount()));
        value.put("assignmentId", line.assignmentId().toString());
        value.put("componentCode",
                Normalizer.normalize(line.componentCode(), Normalizer.Form.NFC));
        value.put("currency", line.currency());
        value.put("effectiveFrom", line.effectiveFrom().toString());
        value.put("effectiveTo",
                line.effectiveTo() == null ? null : line.effectiveTo().toString());
        value.put("lineId", line.lineId().toString());
        value.put("lineSequence", line.lineSequence());
        value.put("workerId", line.workerId().toString());
        return digest(bytes(value));
    }

    String payloadDigest(Xcon020ApprovedCompensationPlanSnapshotV1 snapshot) {
        Map<String, Object> value = new TreeMap<>();
        value.put("approvalReceiptId", snapshot.approvalReceiptId().toString());
        value.put("cycleId", snapshot.cycleId().toString());
        value.put("effectiveFrom", snapshot.effectiveFrom().toString());
        value.put("effectiveTo",
                snapshot.effectiveTo() == null ? null : snapshot.effectiveTo().toString());
        value.put("lineCount", snapshot.lines().size());
        List<Map<String, Object>> lines = new ArrayList<>(snapshot.lines().size());
        for (ApprovedCompensationPlanLine line : snapshot.lines()) {
            Map<String, Object> row = new TreeMap<>();
            row.put("approvedAmount",
                    CanonicalDecimalJson.canonicalString(line.approvedAmount()));
            row.put("assignmentId", line.assignmentId().toString());
            row.put("componentCode",
                    Normalizer.normalize(line.componentCode(), Normalizer.Form.NFC));
            row.put("currency", line.currency());
            row.put("effectiveFrom", line.effectiveFrom().toString());
            row.put("effectiveTo",
                    line.effectiveTo() == null ? null : line.effectiveTo().toString());
            row.put("lineDigest", line.lineDigest());
            row.put("lineId", line.lineId().toString());
            row.put("lineSequence", line.lineSequence());
            row.put("workerId", line.workerId().toString());
            lines.add(row);
        }
        value.put("lines", lines);
        value.put("planId", snapshot.planId().toString());
        value.put("snapshotId", snapshot.snapshotId().toString());
        value.put("snapshotRevision", snapshot.snapshotRevision());
        value.put("sourceVersion", snapshot.sourceVersion());
        return digest(bytes(value));
    }

    private void validateEnvelope(DomainEventEnvelope envelope) {
        UUID aggregateId;
        UUID correlationId;
        try {
            aggregateId = UUID.fromString(envelope.aggregateId());
            correlationId = UUID.fromString(envelope.correlationId());
        } catch (RuntimeException exception) {
            throw invalid("event aggregate or correlation identity is not canonical", exception);
        }
        if (!"1.0".equals(envelope.specVersion())
                || envelope.id() == null
                || !SOURCE.equals(envelope.source())
                || !TOPIC.equals(envelope.type())
                || envelope.schemaVersion() != 2
                || envelope.time() == null
                || envelope.tenantId() == null
                || envelope.tenantId() <= 0
                || !AGGREGATE_TYPE.equals(envelope.aggregateType())
                || envelope.aggregateSequence() <= 1
                || envelope.data() == null
                || !envelope.data().isObject()
                || envelope.extensions() == null
                || !envelope.extensions().isEmpty()
                || !Objects.equals(
                "compensation-cycle/" + aggregateId, envelope.subject())
                || !aggregateId.toString().equals(envelope.aggregateId())
                || !correlationId.toString().equals(envelope.correlationId())) {
            throw invalid("event envelope differs from the sealed PAY-016 contract", null);
        }
    }

    private void validateEventIdentity(
            DomainEventEnvelope envelope,
            Xcon021ApprovedCompensationPlanSnapshotPublishedV2 event) {
        UUID aggregateId = UUID.fromString(envelope.aggregateId());
        if (!event.aggregateId().equals(aggregateId)
                || !event.cycleId().equals(aggregateId)
                || !event.planId().equals(aggregateId)
                || event.aggregateVersion() != envelope.aggregateSequence()
                || event.sourceVersion() != event.aggregateVersion() - 1
                || !event.correlationId().toString().equals(envelope.correlationId())
                || !event.occurredAt().equals(envelope.time())
                || !"APPROVED".equals(event.fromState())
                || !"PUBLISHED".equals(event.toState())) {
            throw invalid("XCON-021 causal predecessor or identity differs", null);
        }
    }

    private static void validatePeriod(
            LocalDate from, LocalDate to, String name) {
        if (to != null && !to.isAfter(from)) {
            throw invalid(name + " effective range is not half-open", null);
        }
    }

    private byte[] bytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw invalid("canonical JSON serialization failed", exception);
        }
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static InvalidCompensationPlanInputException invalid(
            String message, Throwable cause) {
        return cause == null
                ? new InvalidCompensationPlanInputException(message)
                : new InvalidCompensationPlanInputException(message, cause);
    }

    static final class InvalidCompensationPlanInputException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidCompensationPlanInputException(String message) {
            super(message);
        }

        InvalidCompensationPlanInputException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
