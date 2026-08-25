package com.dwp.services.people.organization;

import java.util.List;
import java.util.UUID;

/** Small pure helpers used by organization chart analysis. */
final class OrganizationChartMetrics {

    private OrganizationChartMetrics() {
    }

    static int countPeople(
            List<OrganizationChartDtos.Person> people, String status, String type) {
        return (int) people.stream()
                .filter(person -> status == null || status.equals(person.workerStatus()))
                .filter(person -> type == null || type.equals(person.workerType()))
                .count();
    }

    static double average(List<Integer> values) {
        return values.stream().mapToInt(Integer::intValue).average().orElse(0.0);
    }

    static double percentage(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : (numerator * 100.0) / denominator;
    }

    static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    static void addSignal(
            List<OrganizationChartDtos.AnalysisSignal> signals,
            String code,
            String severity,
            int count,
            UUID organizationId) {
        if (count > 0) {
            signals.add(new OrganizationChartDtos.AnalysisSignal(
                    code, severity, count, organizationId));
        }
    }
}
