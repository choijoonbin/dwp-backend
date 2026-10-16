package com.dwp.services.time.home;

import com.dwp.contracts.hris.xcon.v1.Xcon014TimeLeaveHomeContributionChangedV1;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Emits the minimized XCON-014 fact through the shared transactional outbox. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.home.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class TimeHomeEventPublisher {

    static final String EVENT_TYPE = "dwp.hris.home.contribution-changed.v1";
    static final int SCHEMA_VERSION = 1;

    private final DomainEventRecorder recorder;
    private final ObjectMapper mapper;
    private final boolean publicationEnabled;

    TimeHomeEventPublisher(
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper mapper,
            @Value("${dwp.hris.time.home.event-publication-enabled:false}")
                    boolean publicationEnabled) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.publicationEnabled = publicationEnabled;
        Objects.requireNonNull(contracts, "contracts")
                .register(EVENT_TYPE, SCHEMA_VERSION, SCHEMA_VERSION);
    }

    private static UUID stableEventId(
            HrisHomeContributionMaterializationV1 materialization) {
        return UUID.nameUUIDFromBytes((EVENT_TYPE + "|"
                + materialization.payloadDigest() + "|"
                + materialization.payloadRef()).getBytes(StandardCharsets.UTF_8));
    }

    Optional<UUID> publish(
            long tenantId, HrisHomeContributionMaterializationV1 materialization) {
        Objects.requireNonNull(materialization, "materialization");
        if (!publicationEnabled) {
            return Optional.empty();
        }
        if (tenantId < 1) {
            throw new IllegalArgumentException("Time Home event tenant is required");
        }
        Xcon014TimeLeaveHomeContributionChangedV1 fact = new
                Xcon014TimeLeaveHomeContributionChangedV1(
                        materialization.ownerPopulationScope().name(),
                        materialization.freshUntil(), materialization.generatedAt(), "TIM",
                        materialization.payloadDigest(), materialization.payloadRef(),
                        materialization.scopeRevision(), materialization.staleAfter(),
                        materialization.state().name(), materialization.widgetKey());
        UUID eventId = stableEventId(materialization);
        DomainEventEnvelope event = new DomainEventEnvelope(
                "1.0", eventId, "urn:dwp:dwp-time-server", EVENT_TYPE,
                SCHEMA_VERSION, materialization.generatedAt(),
                "HrisHomeContribution/" + eventId, tenantId,
                "HrisHomeContribution",
                eventId.toString(), 1, eventId.toString(), null, null,
                mapper.valueToTree(fact),
                Map.of("xcon", "XCON-014", "profile", "TimeLeaveHomeContribution.v1"));
        return Optional.of(recorder.record(event));
    }
}
