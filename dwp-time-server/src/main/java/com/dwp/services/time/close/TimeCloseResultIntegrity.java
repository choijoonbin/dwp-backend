package com.dwp.services.time.close;

import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1;
import com.dwp.services.time.close.TimeCloseModels.ResultLine;
import com.dwp.services.time.close.TimeCloseModels.SourceEvidence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Canonical immutable-result digest rules shared by persistence and refetch. */
final class TimeCloseResultIntegrity {

    private TimeCloseResultIntegrity() {}

    static String sourceEntryDigest(List<SourceEvidence> sources) {
        Objects.requireNonNull(sources, "sources");
        List<Map<String, Object>> ordered = new ArrayList<>(sources.size());
        for (SourceEvidence source : sources) {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("ledgerEntryId", source.ledgerEntryId().toString());
            value.put("postedAt", source.postedAt().toString());
            value.put("ledgerRevision", source.ledgerRevision());
            value.put("quantity", source.quantity().stripTrailingZeros().toPlainString());
            value.put("unit", source.unit());
            value.put("entryDigest", source.entryDigest());
            ordered.add(value);
        }
        return TimeCloseDigests.sha256Utf8(TimeCloseDigests.canonicalJson(ordered));
    }

    static String sourceDigest(List<ResultLine> lines) {
        Objects.requireNonNull(lines, "lines");
        List<Map<String, Object>> ordered = new ArrayList<>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            ResultLine line = lines.get(index);
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("lineIndex", index);
            value.put("workerId", line.workerId().toString());
            value.put("assignmentId", line.assignmentId().toString());
            value.put("sourceEntryCount", line.sources().size());
            value.put("sourceEntryDigest", line.sourceEntryDigest());
            ordered.add(value);
        }
        return TimeCloseDigests.sha256Utf8(TimeCloseDigests.canonicalJson(ordered));
    }

    static String payloadDigest(
            UUID closePeriodId,
            long closeRevision,
            UUID resultId,
            long ledgerRevisionFrom,
            long ledgerRevisionTo,
            UUID payPeriodId,
            UUID payrollEntityId,
            String ruleDigest,
            String sourceDigest,
            UUID supersedesResultId,
            int workerCount,
            List<Xcon008ClosedTimeResultV1.ClosedTimeLine> lines) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("closePeriodId", closePeriodId.toString());
        value.put("closeRevision", closeRevision);
        value.put("closedTimeResultId", resultId.toString());
        value.put("ledgerRevisionFrom", ledgerRevisionFrom);
        value.put("ledgerRevisionTo", ledgerRevisionTo);
        value.put("lineCount", lines.size());
        value.put("lines", lines);
        value.put("payPeriodId", payPeriodId.toString());
        value.put("payrollEntityId", payrollEntityId.toString());
        value.put("ruleDigest", ruleDigest);
        value.put("sourceDigest", sourceDigest);
        value.put("supersedesResultId",
                supersedesResultId == null ? null : supersedesResultId.toString());
        value.put("workerCount", workerCount);
        return TimeCloseDigests.sha256Utf8(TimeCloseDigests.canonicalJson(value));
    }

    static void verifyEvidence(List<ResultLine> lines, String expectedSourceDigest) {
        if (lines == null || lines.size() > 1_000_000) {
            throw corrupt("Closed-time result lines exceed the sealed bound.");
        }
        for (ResultLine line : lines) {
            if (!line.sourceEntryDigest().equals(sourceEntryDigest(line.sources()))) {
                throw corrupt("Closed-time result source-entry digest is mismatched.");
            }
        }
        if (!sourceDigest(lines).equals(expectedSourceDigest)) {
            throw corrupt("Closed-time result source digest is mismatched.");
        }
    }

    static IllegalStateException corrupt(String message) {
        return new IllegalStateException(message);
    }
}
