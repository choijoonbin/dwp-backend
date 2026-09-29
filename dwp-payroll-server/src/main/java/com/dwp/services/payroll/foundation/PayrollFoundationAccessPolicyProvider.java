package com.dwp.services.payroll.foundation;

interface PayrollFoundationAccessPolicyProvider {

    PayrollFoundationAccess.AccessPolicy policyFor(long tenantId);
}
