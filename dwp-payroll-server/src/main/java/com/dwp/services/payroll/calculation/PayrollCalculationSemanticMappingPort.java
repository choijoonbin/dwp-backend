package com.dwp.services.payroll.calculation;

import static com.dwp.services.payroll.calculation.PayrollCalculationModels.FrozenSnapshot;
import static com.dwp.services.payroll.calculation.PayrollCalculationModels.SourceInput;
import static com.dwp.services.payroll.calculation.PayrollCalculationSemanticContract.Resolution;

/**
 * Owner boundary for the semantic meaning of a frozen compensation component.
 * A component code is an opaque identifier; it never implies a payroll category.
 */
interface PayrollCalculationSemanticMappingPort {
    Resolution resolve(FrozenSnapshot snapshot, SourceInput input);
}
