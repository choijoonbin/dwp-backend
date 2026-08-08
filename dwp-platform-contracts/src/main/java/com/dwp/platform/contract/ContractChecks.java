package com.dwp.platform.contract;

final class ContractChecks {

    private ContractChecks() {
    }

    static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static int limit(int value, int maximum) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException("limit must be between 1 and " + maximum);
        }
        return value;
    }
}
