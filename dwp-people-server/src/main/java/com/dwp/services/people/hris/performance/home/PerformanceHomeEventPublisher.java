package com.dwp.services.people.hris.performance.home;

import com.dwp.contracts.hris.xcon.v1.Xcon012PerformanceHomeContributionChangedV1;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Publishes minimized XCON-012 references through the shared transactional outbox. */
@Component
@ConditionalOnProperty(prefix = "dwp.people.hris.performance-home", name = "enabled",
        havingValue = "true")
public final class PerformanceHomeEventPublisher {

    static final String EVENT_TYPE = "dwp.hris.home.contribution-changed.v1";
    private static final String SOURCE = "/dwp-people-server/hris/performance/home";
    private static final String AGGREGATE = "PERFORMANCE_HOME_PROJECTION";

    private final PerformanceHomeProjectionPort projections;
    private final PerformanceHomeMaterializer materializer;
    private final DomainEventRecorder recorder;
    private final ObjectMapper mapper;
    private final boolean publicationEnabled;

    PerformanceHomeEventPublisher(
            PerformanceHomeProjectionPort projections,
            PerformanceHomeMaterializer materializer,
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper mapper,
            @Value("${dwp.people.hris.performance-home.event-publication-enabled:false}")
            boolean publicationEnabled) {
        this.projections = projections;
        this.materializer = materializer;
        this.recorder = recorder;
        this.mapper = mapper.copy().findAndRegisterModules();
        this.publicationEnabled = publicationEnabled;
        contracts.register(EVENT_TYPE, 1, 1);
    }

    /** Domain owners call this after their state transaction establishes a new source version. */
    @Transactional
    public Optional<Publication> publish(
            PerformanceHomeAuthorityPort.AuthorityDecision authority,
            UUID correlationId) {
        if (!publicationEnabled) return Optional.empty();
        if (correlationId == null) {
            throw new IllegalArgumentException("Performance Home correlationId is required.");
        }
        PerformanceHomeProjectionPort.Snapshot snapshot = projections.load(authority);
        PerformanceHomeMaterializer.Prepared prepared = materializer.prepare(authority, snapshot);
        HrisHomeContributionMaterializationV1 materialization = prepared.materialization();
        var event = new Xcon012PerformanceHomeContributionChangedV1(
                authority.ownerPopulationScope().name(), materialization.freshUntil(),
                materialization.generatedAt(), "PER", prepared.payloadDigest(),
                prepared.payloadRef(), authority.scopeRevision(), materialization.staleAfter(),
                materialization.state().name(), authority.widgetKey());
        UUID eventId = stableEventId(authority, materialization);
        recorder.record(new DomainEventEnvelope(
                "1.0", eventId, SOURCE, EVENT_TYPE, 1, materialization.generatedAt(),
                "performance-home/" + eventId, authority.tenantId(),
                AGGREGATE, eventId.toString(), 1,
                correlationId.toString(), null, null, mapper.valueToTree(event), Map.of()));
        return Optional.of(new Publication(eventId, event, materialization));
    }

    private String aggregateId(PerformanceHomeAuthorityPort.AuthorityDecision authority) {
        return authority.tenantId() + ":" + authority.widgetKey() + ":"
                + authority.ownerPopulationScope() + ":" + authority.populationScopeDigest();
    }

    private UUID stableEventId(
            PerformanceHomeAuthorityPort.AuthorityDecision authority,
            HrisHomeContributionMaterializationV1 materialization) {
        try {
            String identity = "PER-HOME\u0000" + aggregateId(authority) + '\u0000'
                    + materialization.sourceVersion() + '\u0000'
                    + materialization.payloadDigest();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            long high = bytes.getLong();
            long low = bytes.getLong();
            high = (high & 0xffffffffffff0fffL) | 0x0000000000005000L;
            low = (low & 0x3fffffffffffffffL) | 0x8000000000000000L;
            return new UUID(high, low);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public record Publication(
            UUID eventId,
            Xcon012PerformanceHomeContributionChangedV1 event,
            HrisHomeContributionMaterializationV1 materialization) {
    }
}
