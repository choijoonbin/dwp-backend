package com.dwp.services.people.hris.contracts.workforce.v1;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** RFC 8785/JCS-compatible canonical UTF-8 preimages for the closed scalar schemas. */
final class CompensationPopulationCanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CompensationPopulationCanonicalJson() {
    }

    static byte[] fullPopulationContent(
            Instant asOf,
            long ownerRevision,
            List<CompensationPopulationLine> lines) {
        Map<String, Object> content = new TreeMap<>();
        content.put("asOf", asOf.toString());
        content.put("hrmOwnerRevision", ownerRevision);
        content.put("lines", canonicalLines(lines));
        return bytes(content);
    }

    static byte[] page(
            UUID snapshotPublicId,
            long ownerRevision,
            Instant asOf,
            WorkforceSnapshotProjection projection,
            String purposeCode,
            String fullPopulationDigest,
            List<CompensationPopulationLine> lines,
            String nextCursor) {
        Map<String, Object> page = new TreeMap<>();
        page.put("asOf", asOf.toString());
        page.put("fullPopulationDigest", fullPopulationDigest);
        page.put("lines", canonicalLines(lines));
        page.put("nextCursor", nextCursor);
        page.put("ownerRevision", ownerRevision);
        page.put("projection", projection.name());
        page.put("purposeCode", purposeCode);
        page.put("snapshotPublicId", snapshotPublicId.toString());
        return bytes(page);
    }

    private static List<Map<String, Object>> canonicalLines(
            List<CompensationPopulationLine> lines) {
        List<Map<String, Object>> canonical = new ArrayList<>(lines.size());
        for (CompensationPopulationLine line : lines) {
            Map<String, Object> value = new TreeMap<>();
            value.put("assignmentOwnerRevision", line.assignmentOwnerRevision());
            value.put("assignmentPublicId", line.assignmentPublicId().toString());
            value.put("eligibleComponentCodes", line.eligibleComponentCodes());
            value.put("gradePublicId", nullableUuid(line.gradePublicId()));
            value.put("jobProfilePublicId", nullableUuid(line.jobProfilePublicId()));
            value.put("organizationPublicId", line.organizationPublicId().toString());
            value.put("workerPublicId", line.workerPublicId().toString());
            canonical.add(value);
        }
        return canonical;
    }

    private static String nullableUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static byte[] bytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "compensation population canonical JSON serialization failed", exception);
        }
    }
}
