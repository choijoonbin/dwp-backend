package com.dwp.services.payroll.calculation;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FrozenSnapshot;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SourceInput;

/** Fail-closed production default until the authoritative semantic owner is connected. */
final class UnavailablePayrollCalculationSemanticMappingPort
        implements PayrollCalculationSemanticMappingPort {

    @Override
    public Resolution resolve(FrozenSnapshot snapshot, SourceInput input) {
        return Resolution.unavailable();
    }
}
