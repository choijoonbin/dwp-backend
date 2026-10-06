package com.dwp.services.people.hr.assignment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AssignmentProposalValidator {

    private static final Map<String, Set<String>> REQUIRED_FIELDS = Map.of(
            "TRANSFER", Set.of("organizationId"),
            "PROMOTION", Set.of("jobProfileKey"),
            "DEMOTION", Set.of("jobProfileKey"),
            "CHANGE_MANAGER", Set.of("managerAssignmentId"),
            "CHANGE_LOCATION", Set.of("locationKey"),
            "CORRECTION", Set.of());

    private final Clock clock;

    public AssignmentProposalValidator() {
        this(Clock.systemUTC());
    }

    AssignmentProposalValidator(Clock clock) {
        this.clock = clock;
    }

    public List<AssignmentProposalDtos.ValidationFinding> validate(
            String changeType,
            LocalDate effectiveDate,
            Map<String, Object> changes,
            CurrentAssignment current,
            ReferenceValidation references) {
        List<AssignmentProposalDtos.ValidationFinding> findings = new ArrayList<>();
        String type = changeType == null ? "" : changeType.trim().toUpperCase();
        Set<String> required = REQUIRED_FIELDS.get(type);
        if (required == null) {
            findings.add(error("UNSUPPORTED_CHANGE_TYPE", "changeType",
                    "The assignment change type is not supported."));
            return List.copyOf(findings);
        }
        for (String field : required) {
            if (!changes.containsKey(field)) {
                findings.add(error("REQUIRED_CHANGE_FIELD", field,
                        field + " is required for " + type + '.'));
            }
        }

        LocalDate today = LocalDate.now(clock);
        if ("CORRECTION".equals(type)) {
            if (effectiveDate == null
                    || effectiveDate.isBefore(current.effectiveStartDate())
                    || (current.effectiveEndDate() != null
                        && effectiveDate.isAfter(current.effectiveEndDate()))
                    || effectiveDate.isAfter(today)) {
                findings.add(error("CORRECTION_DATE_OUTSIDE_SLICE", "effectiveDate",
                        "A correction date must fall in the target assignment slice and not be future-dated."));
            }
        } else if (effectiveDate == null || effectiveDate.isBefore(today)) {
            findings.add(error("PAST_EFFECTIVE_DATE", "effectiveDate",
                    "A prospective assignment change cannot be backdated."));
        }

        decimalRange(changes, "workerHours", new BigDecimal("0.01"),
                new BigDecimal("168"), findings);
        decimalRange(changes, "fullTimeEquivalent", new BigDecimal("0.0001"),
                BigDecimal.ONE, findings);
        if (changes.entrySet().stream().allMatch(entry ->
                equivalent(entry.getValue(), current.values().get(entry.getKey())))) {
            findings.add(error("NO_EFFECTIVE_CHANGE", "proposedChanges",
                    "The proposal does not change the target assignment."));
        }
        if (!references.reasonActive()) {
            findings.add(error("INACTIVE_REASON", "reasonCode",
                    "The assignment change reason is not active on the effective date."));
        }
        references.missingFields().stream().sorted().forEach(field ->
                findings.add(error("REFERENCE_NOT_FOUND", field,
                        "The referenced workforce value is unavailable for this tenant.")));
        return List.copyOf(findings);
    }

    public void requireNoBlocking(List<AssignmentProposalDtos.ValidationFinding> findings) {
        findings.stream().filter(finding -> "ERROR".equals(finding.severity()))
                .findFirst().ifPresent(finding -> {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                            finding.code() + ": " + finding.message());
                });
    }

    private void decimalRange(
            Map<String, Object> changes,
            String key,
            BigDecimal minimum,
            BigDecimal maximum,
            List<AssignmentProposalDtos.ValidationFinding> findings) {
        Object raw = changes.get(key);
        if (raw == null) return;
        BigDecimal value = raw instanceof BigDecimal decimal
                ? decimal : new BigDecimal(raw.toString());
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            findings.add(error("VALUE_OUT_OF_RANGE", key,
                    key + " must be between " + minimum + " and " + maximum + '.'));
        }
    }

    private boolean equivalent(Object proposed, Object current) {
        if (proposed == null || current == null) return proposed == current;
        if (proposed instanceof BigDecimal proposedDecimal) {
            try {
                return proposedDecimal.compareTo(new BigDecimal(current.toString())) == 0;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return proposed.toString().equals(current.toString());
    }

    private AssignmentProposalDtos.ValidationFinding error(
            String code, String field, String message) {
        return new AssignmentProposalDtos.ValidationFinding(code, field, "ERROR", message);
    }

    public record CurrentAssignment(
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate,
            Map<String, Object> values) {
    }

    public record ReferenceValidation(
            boolean reasonActive,
            Set<String> missingFields) {
        public ReferenceValidation {
            missingFields = missingFields == null ? Set.of() : Set.copyOf(missingFields);
        }
    }
}
