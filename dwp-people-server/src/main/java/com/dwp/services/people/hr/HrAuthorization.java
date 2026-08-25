package com.dwp.services.people.hr;

import java.util.Map;
import java.util.List;

/** Shared immutable HR domain-to-authority vocabulary. */
final class HrAuthorization {

    static final Map<String, String> DOMAIN_RESOURCES = Map.of(
            "TIME", "DATA.HR_TIME",
            "ABSENCE", "DATA.HR_ABSENCE",
            "BENEFITS", "DATA.HR_BENEFITS",
            "PAY", "DATA.HR_PAY",
            "TALENT", "DATA.HR_TALENT");
    static final List<String> DOMAINS = List.of(
            "TIME", "ABSENCE", "BENEFITS", "PAY", "TALENT");

    private HrAuthorization() {
    }

    static String role(String domain) {
        return switch (domain) {
            case "TIME" -> "TIME_ADMIN";
            case "ABSENCE" -> "ABSENCE_ADMIN";
            case "BENEFITS" -> "BENEFITS_ADMIN";
            case "PAY" -> "PAYROLL_ADMIN";
            case "TALENT" -> "TALENT_ADMIN";
            default -> "";
        };
    }
}
