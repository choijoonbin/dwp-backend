package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.services.people.hr.performance.typedartifact.PostedMoney;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public final class CompensationPlanningCanonicalizer {

    private final ObjectMapper mapper;

    public CompensationPlanningCanonicalizer(ObjectMapper objectMapper) {
        this.mapper = objectMapper.copy().findAndRegisterModules();
    }

    public String commandDigest(
            String method,
            String path,
            Long expectedVersion,
            long tenantId,
            UUID subjectId,
            Object body) {
        Map<String, Object> request = new TreeMap<>();
        request.put("body", canonicalNode(mapper.valueToTree(body)));
        request.put("expectedVersion", expectedVersion);
        request.put("method", method);
        request.put("path", path);
        request.put("subjectId", subjectId.toString());
        request.put("tenantId", tenantId);
        return digest(bytes(request));
    }

    public String resultDigest(Object value) {
        return digest(bytes(canonicalNode(mapper.valueToTree(value))));
    }

    public List<CompensationPlanningDtos.SnapshotLine> materializeLines(
            List<ProposalForSnapshot> proposals,
            Supplier<UUID> lineIdSupplier) {
        Objects.requireNonNull(proposals, "proposals must not be null");
        if (proposals.isEmpty() || proposals.size() > 100_000) {
            throw new IllegalArgumentException(
                    "approval requires between 1 and 100000 proposals");
        }
        List<ProposalForSnapshot> ordered = new ArrayList<>(proposals);
        ordered.sort(PROPOSAL_ORDER);
        rejectOverlaps(ordered);
        List<CompensationPlanningDtos.SnapshotLine> result =
                new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            ProposalForSnapshot proposal = ordered.get(index);
            String amount = PostedMoney.require(proposal.amount());
            if (PostedMoney.decimal(amount).signum() <= 0) {
                throw new IllegalArgumentException(
                        "approved snapshot lines must have a positive amount");
            }
            UUID lineId = Objects.requireNonNull(lineIdSupplier.get(),
                    "line ID supplier returned null");
            if (lineId.version() != 4) {
                throw new IllegalArgumentException("snapshot line IDs must be UUIDv4");
            }
            int sequence = index + 1;
            String normalizedComponent = Normalizer.normalize(
                    proposal.componentCode(), Normalizer.Form.NFC);
            String lineDigest = lineDigest(
                    lineId, sequence, proposal.workerId(), proposal.assignmentId(),
                    normalizedComponent, proposal.currency(), amount,
                    proposal.effectiveFrom(), proposal.effectiveTo());
            result.add(new CompensationPlanningDtos.SnapshotLine(
                    lineId, sequence, proposal.proposalId(), proposal.workerId(),
                    proposal.assignmentId(), normalizedComponent, proposal.currency(), amount,
                    proposal.effectiveFrom(), proposal.effectiveTo(), lineDigest));
        }
        return List.copyOf(result);
    }

    public String lineDigest(
            UUID lineId,
            int lineSequence,
            UUID workerId,
            UUID assignmentId,
            String componentCode,
            String currency,
            String approvedAmount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        Map<String, Object> value = new TreeMap<>();
        value.put("approvedAmount", PostedMoney.require(approvedAmount));
        value.put("assignmentId", assignmentId.toString());
        value.put("componentCode",
                Normalizer.normalize(componentCode, Normalizer.Form.NFC));
        value.put("currency", currency);
        value.put("effectiveFrom", effectiveFrom.toString());
        value.put("effectiveTo", effectiveTo == null ? null : effectiveTo.toString());
        value.put("lineId", lineId.toString());
        value.put("lineSequence", lineSequence);
        value.put("workerId", workerId.toString());
        return digest(bytes(value));
    }

    public String payloadDigest(
            UUID approvalReceiptId,
            UUID cycleId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            List<CompensationPlanningDtos.SnapshotLine> lines,
            UUID snapshotId,
            long snapshotRevision,
            long sourceVersion) {
        Map<String, Object> value = new TreeMap<>();
        value.put("approvalReceiptId", approvalReceiptId.toString());
        value.put("cycleId", cycleId.toString());
        value.put("effectiveFrom", effectiveFrom.toString());
        value.put("effectiveTo", effectiveTo == null ? null : effectiveTo.toString());
        value.put("lineCount", lines.size());
        List<Map<String, Object>> canonicalLines = new ArrayList<>(lines.size());
        for (CompensationPlanningDtos.SnapshotLine line : lines) {
            Map<String, Object> row = new TreeMap<>();
            row.put("approvedAmount", PostedMoney.require(line.approvedAmount()));
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
            canonicalLines.add(row);
        }
        value.put("lines", canonicalLines);
        value.put("planId", cycleId.toString());
        value.put("snapshotId", snapshotId.toString());
        value.put("snapshotRevision", snapshotRevision);
        value.put("sourceVersion", sourceVersion);
        return digest(bytes(value));
    }

    public void verifySnapshot(CompensationPlanningDtos.ApprovedSnapshot snapshot) {
        if (snapshot.lineCount() != snapshot.lines().size()
                || snapshot.lineCount() < 1 || snapshot.lineCount() > 100_000) {
            throw new IllegalArgumentException("snapshot line count is invalid");
        }
        java.util.HashSet<UUID> ids = new java.util.HashSet<>();
        for (int index = 0; index < snapshot.lines().size(); index++) {
            CompensationPlanningDtos.SnapshotLine line = snapshot.lines().get(index);
            if (line.lineSequence() != index + 1 || !ids.add(line.lineId())) {
                throw new IllegalArgumentException("snapshot order or line identity is invalid");
            }
            String digest = lineDigest(
                    line.lineId(), line.lineSequence(), line.workerId(), line.assignmentId(),
                    line.componentCode(), line.currency(), line.approvedAmount(),
                    line.effectiveFrom(), line.effectiveTo());
            if (!digest.equals(line.lineDigest())) {
                throw new IllegalArgumentException("snapshot line digest is invalid");
            }
        }
        String digest = payloadDigest(
                snapshot.approvalReceiptId(), snapshot.cycleId(),
                snapshot.effectiveFrom(), snapshot.effectiveTo(), snapshot.lines(),
                snapshot.snapshotId(), snapshot.snapshotRevision(), snapshot.sourceVersion());
        if (!digest.equals(snapshot.payloadDigest())) {
            throw new IllegalArgumentException("snapshot payload digest is invalid");
        }
    }

    private void rejectOverlaps(List<ProposalForSnapshot> proposals) {
        Map<String, List<ProposalForSnapshot>> groups = new HashMap<>();
        for (ProposalForSnapshot proposal : proposals) {
            String key = proposal.workerId() + ":" + proposal.assignmentId() + ":"
                    + Normalizer.normalize(proposal.componentCode(), Normalizer.Form.NFC);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(proposal);
        }
        for (List<ProposalForSnapshot> group : groups.values()) {
            group.sort(Comparator.comparing(ProposalForSnapshot::effectiveFrom));
            for (int index = 1; index < group.size(); index++) {
                ProposalForSnapshot previous = group.get(index - 1);
                ProposalForSnapshot current = group.get(index);
                if (previous.effectiveTo() == null
                        || !previous.effectiveTo().isBefore(current.effectiveFrom())) {
                    throw new IllegalArgumentException(
                            "overlapping worker-assignment-component proposals are forbidden");
                }
            }
        }
    }

    private Object canonicalNode(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            node.fields().forEachRemaining(entry ->
                    result.put(entry.getKey(), canonicalNode(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            List<Object> result = new ArrayList<>();
            node.forEach(value -> result.add(canonicalNode(value)));
            return result;
        }
        if (node.isTextual()) return node.textValue();
        if (node.isIntegralNumber()) return node.bigIntegerValue();
        if (node.isBoolean()) return node.booleanValue();
        throw new IllegalArgumentException("command contains a non-canonical scalar");
    }

    private byte[] bytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("canonical JSON serialization failed", exception);
        }
    }

    private String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static int compareUuid(UUID left, UUID right) {
        return Arrays.compareUnsigned(uuidBytes(left), uuidBytes(right));
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static int compareUtf8(String left, String right) {
        return Arrays.compareUnsigned(
                Normalizer.normalize(left, Normalizer.Form.NFC)
                        .getBytes(StandardCharsets.UTF_8),
                Normalizer.normalize(right, Normalizer.Form.NFC)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static final Comparator<ProposalForSnapshot> PROPOSAL_ORDER =
            (left, right) -> {
                int comparison = compareUuid(left.workerId(), right.workerId());
                if (comparison != 0) return comparison;
                comparison = compareUuid(left.assignmentId(), right.assignmentId());
                if (comparison != 0) return comparison;
                comparison = compareUtf8(left.componentCode(), right.componentCode());
                if (comparison != 0) return comparison;
                comparison = left.effectiveFrom().compareTo(right.effectiveFrom());
                if (comparison != 0) return comparison;
                return compareUuid(left.proposalId(), right.proposalId());
            };

    public record ProposalForSnapshot(
            UUID proposalId,
            UUID workerId,
            UUID assignmentId,
            String componentCode,
            String currency,
            String amount,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            UUID createdBy,
            long aggregateVersion,
            long currentRevision) {
    }
}
