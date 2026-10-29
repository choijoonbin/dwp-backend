package com.dwp.services.payroll.statutory.pay004;

/** Persisted lifecycle vocabulary shared by the retirement aggregate and projections. */
final class RetirementYearEndCaseContract {

    private RetirementYearEndCaseContract() {
    }

    enum State {
        DRAFT,
        VALIDATED,
        BLOCKED_PACK_REQUIRED,
        CALCULATED,
        APPROVED,
        FINALIZED,
        CORRECTED,
        CANCELLED
    }

    enum CountryPackState {
        NOT_REQUIRED,
        NOT_INSTALLED,
        DISABLED,
        ACTIVE
    }
}
