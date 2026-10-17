package com.dwp.services.payroll.preparation;

interface PayrollPreparationScopeResolver {
    PayrollPreparationAuthority.Scope resolve(
            PayrollPreparationAuthority.Subject subject);
}
