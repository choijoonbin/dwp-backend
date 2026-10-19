package com.dwp.services.people.hris.workbench.hrm011;

import com.dwp.services.people.hr.HcmPopulationRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Tenant- and target-population-scoped IAQ-008 source. */
public interface Hrm011QuerySource {
    Page read(
            long tenantId,
            QuerySpec query,
            HcmPopulationRepository.PopulationScope population);

    record QuerySpec(
            LocalDate effectiveOn,
            Hrm011QueryModels.MoneyFilter money,
            List<String> sort,
            int limit,
            UUID afterRelationshipPublicId) {
        public QuerySpec {
            sort = sort == null ? List.of() : List.copyOf(sort);
        }
    }

    record Page(
            List<Hrm011QueryModels.SourceRow> rows,
            boolean cursorPositionFound,
            boolean hasMore,
            UUID lastScannedRelationshipPublicId) {
        public Page {
            rows = rows == null ? List.of() : List.copyOf(rows);
            if (hasMore && (rows.isEmpty() || lastScannedRelationshipPublicId == null)) {
                throw new IllegalArgumentException(
                        "A continued IAQ-008 source page requires a scan position.");
            }
        }
    }
}
