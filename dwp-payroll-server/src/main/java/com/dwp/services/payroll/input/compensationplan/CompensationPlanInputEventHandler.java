package com.dwp.services.payroll.input.compensationplan;

import com.dwp.core.event.DomainEventEnvelope;

interface CompensationPlanInputEventHandler {

    CompensationPlanInputContracts.ConsumptionResult consume(
            DomainEventEnvelope envelope);
}
