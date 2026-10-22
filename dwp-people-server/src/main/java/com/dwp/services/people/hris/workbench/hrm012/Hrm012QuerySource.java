package com.dwp.services.people.hris.workbench.hrm012;

import com.dwp.services.people.hr.HcmPopulationRepository;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tenant/RLS and target-population constrained IAQ-012 source. */
public interface Hrm012QuerySource {
    Page read(
            long tenantId,
            QuerySpec query,
            HcmPopulationRepository.PopulationScope population);

    record QuerySpec(
            Instant asOf,
            LocalDate effectiveOn,
            Hrm012QueryModels.Filter filter,
            List<String> sort,
            int limit,
            UUID afterRunPublicId) {
        public QuerySpec {
            sort = sort == null ? List.of() : List.copyOf(sort);
        }
    }

    record Page(
            List<Hrm012QueryModels.SourceRow> rows,
            boolean cursorPositionFound,
            boolean snapshotDrift,
            boolean hasMore,
            UUID lastRunPublicId,
            List<ComponentFailure> partialFailures) {
        public Page {
            rows = rows == null ? List.of() : List.copyOf(rows);
            partialFailures = partialFailures == null
                    ? List.of() : List.copyOf(partialFailures);
            if (partialFailures.size() > 32) {
                throw new IllegalArgumentException(
                        "An IAQ-012 page cannot expose more than 32 partial failures.");
            }
            if (hasMore && (rows.isEmpty() || lastRunPublicId == null)) {
                throw new IllegalArgumentException(
                        "A continued IAQ-012 page requires a stable position.");
            }
        }
    }

    /** Already-redacted component failure; transport correlation is added by the service. */
    record ComponentFailure(String componentKey, String errorCode, boolean retryable) {
        public ComponentFailure {
            if (componentKey == null
                    || !componentKey.matches("[a-z][a-z0-9._-]{0,127}")
                    || errorCode == null
                    || !errorCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException(
                        "An IAQ-012 partial failure must be redacted and canonical.");
            }
        }
    }
}
