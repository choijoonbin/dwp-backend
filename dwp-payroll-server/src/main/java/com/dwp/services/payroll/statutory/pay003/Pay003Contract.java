package com.dwp.services.payroll.statutory.pay003;

/** Closed purpose contract shared by PAY-003 routes, commands, and receipts. */
final class Pay003Contract {

    static final String ACTIVATION_PURPOSE = "STATUTORY_CONFIGURATION";
    static final String CONFIGURATION_PURPOSE = "PAYROLL_CONFIGURATION";
    static final String OPERATIONS_PURPOSE = "PAYROLL_OPERATIONS";

    private Pay003Contract() {
    }
}
