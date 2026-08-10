package com.dwp.services.people.organization;

import java.util.List;

final class OrganizationHealthPolicy {

    private OrganizationHealthPolicy() {
    }

    static int score(List<String> signals) {
        int penalty = signals.stream().mapToInt(OrganizationHealthPolicy::penalty).sum();
        return Math.max(0, 100 - penalty);
    }

    static String status(List<String> signals) {
        int score = score(signals);
        if (score < 70) return "CRITICAL";
        if (score < 100) return "ATTENTION";
        return "HEALTHY";
    }

    private static int penalty(String signal) {
        return switch (signal) {
            case "WIDE_SPAN", "EXCESS_LAYERS" -> 35;
            case "HIGH_CONTINGENT", "HIGH_VACANCY" -> 20;
            case "NARROW_SPAN" -> 10;
            default -> 15;
        };
    }
}
