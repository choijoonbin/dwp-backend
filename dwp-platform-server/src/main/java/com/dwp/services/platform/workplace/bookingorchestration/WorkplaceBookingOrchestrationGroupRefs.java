package com.dwp.services.platform.workplace.bookingorchestration;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

final class WorkplaceBookingOrchestrationGroupRefs {
    private static final String EMPTY_GROUP = "00000000-0000-0000-0000-000000000000";

    private WorkplaceBookingOrchestrationGroupRefs() { }

    static String csv(String verifiedGroupRefs) {
        if (verifiedGroupRefs == null || verifiedGroupRefs.isBlank()) return EMPTY_GROUP;
        List<String> values = Arrays.stream(verifiedGroupRefs.split(","))
                .map(String::trim)
                .filter(value -> {
                    try {
                        UUID.fromString(value);
                        return true;
                    } catch (IllegalArgumentException exception) {
                        return false;
                    }
                })
                .distinct()
                .toList();
        return values.isEmpty() ? EMPTY_GROUP : String.join(",", values);
    }
}
