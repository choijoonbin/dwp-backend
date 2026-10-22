package com.dwp.services.payroll.statutory.pay004;

import java.util.Map;

final class Pay004Ports {
    private Pay004Ports() {
    }

    interface ProjectionDecisionPort {
        Map<String, Pay004Models.Decision> decisions(
                Pay004Authority.Actor actor, String variantKey);
    }
}
