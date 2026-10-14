package com.dwp.services.people.hris.people;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tenant-owned compensation policy SPI. No default is permitted: workforce
 * data alone cannot truthfully infer eligible compensation components.
 */
interface HrisCompensationComponentEligibilitySource {

    EligibilitySnapshot resolve(
            long tenantId,
            Instant asOf,
            Set<UUID> assignmentPublicIds);

    record EligibilitySnapshot(
            String revision,
            Map<UUID, List<String>> eligibleComponentCodesByAssignment) {
        public EligibilitySnapshot {
            eligibleComponentCodesByAssignment =
                    eligibleComponentCodesByAssignment == null
                            ? Map.of() : Map.copyOf(eligibleComponentCodesByAssignment);
        }
    }
}
