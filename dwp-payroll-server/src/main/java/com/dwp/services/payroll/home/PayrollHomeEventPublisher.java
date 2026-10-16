package com.dwp.services.payroll.home;

import com.dwp.contracts.hris.xcon.v1.Xcon015PayrollHomeContributionChangedV1;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

final class PayrollHomeEventPublisher {

    static final String EVENT_TYPE = "dwp.hris.home.contribution-changed.v1";
    private static final String SOURCE = "urn:dwp:payroll:home";
    private final DomainEventRecorder recorder;
    private final ObjectMapper mapper;
    private final boolean enabled;

    PayrollHomeEventPublisher(
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper mapper,
            boolean enabled) {
        this.recorder = recorder;
        this.mapper = mapper;
        this.enabled = enabled;
        contracts.register(EVENT_TYPE, 1, 1);
    }

    void record(long tenantId, HrisHomeContributionMaterializationV1 materialization) {
        if (!enabled) return;
        var xcon = new Xcon015PayrollHomeContributionChangedV1(
                materialization.ownerPopulationScope().name(),
                materialization.freshUntil(), materialization.generatedAt(), "PAY",
                materialization.payloadDigest(), materialization.payloadRef(),
                materialization.scopeRevision(), materialization.staleAfter(),
                materialization.state().name(), materialization.widgetKey());
        UUID eventId = UUID.nameUUIDFromBytes((tenantId + "|" + materialization.widgetKey()
                + "|" + materialization.scopeRevision() + "|"
                + materialization.payloadDigest()).getBytes(StandardCharsets.UTF_8));
        var envelope = new DomainEventEnvelope(
                "1.0", eventId, SOURCE, EVENT_TYPE, 1,
                materialization.generatedAt(),
                "PAYROLL_HOME/" + eventId, tenantId,
                "PAYROLL_HOME", eventId.toString(),
                1, eventId.toString(), null, null,
                mapper.valueToTree(xcon), Map.of("xcon", "XCON-015"));
        recorder.record(envelope);
    }
}
