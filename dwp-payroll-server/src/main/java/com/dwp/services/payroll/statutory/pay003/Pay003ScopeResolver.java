package com.dwp.services.payroll.statutory.pay003;

interface Pay003ScopeResolver {
    Pay003Authority.Scope resolve(Pay003Authority.Subject subject);
}
