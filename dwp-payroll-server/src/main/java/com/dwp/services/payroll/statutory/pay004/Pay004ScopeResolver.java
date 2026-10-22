package com.dwp.services.payroll.statutory.pay004;

interface Pay004ScopeResolver {
    Pay004Authority.Scope resolve(Pay004Authority.Subject subject);
}
