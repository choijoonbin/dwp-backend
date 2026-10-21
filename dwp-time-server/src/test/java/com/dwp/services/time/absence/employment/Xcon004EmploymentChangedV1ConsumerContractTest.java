package com.dwp.services.time.absence.employment;

import com.dwp.core.event.DomainEventEnvelope;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Xcon004EmploymentChangedV1ConsumerContractTest {

    private final Xcon004EmploymentChangedV1Consumer consumer =
            new Xcon004EmploymentChangedV1Consumer(EmploymentAbsenceTestSupport.mapper());

    @Test
    void acceptsOnlyExactNamespacedEnvelopeAndGeneratedFourFieldBody() {
        var event = EmploymentAbsenceTestSupport.event(7);

        var validated = consumer.validate(event, EmploymentAbsenceTestSupport.DIGEST_A);

        assertThat(validated.body().relationshipPublicId())
                .isEqualTo(EmploymentAbsenceTestSupport.RELATIONSHIP);
        assertThat(validated.body().newAggregateVersion()).isEqualTo(7);
        assertThat(validated.invalidation().eventSequence()).isEqualTo(7);
    }

    @Test
    void rejectsUnsupportedMajorAndUnknownTransportType() {
        DomainEventEnvelope original = EmploymentAbsenceTestSupport.event(1);
        DomainEventEnvelope wrongMajor = copy(original,
                original.type(), 2, original.aggregateId(), original.subject(), original.data());
        DomainEventEnvelope wrongType = copy(original,
                "dwp.hris.people.unknown.v1", 1,
                original.aggregateId(), original.subject(), original.data());

        assertThatThrownBy(() -> consumer.validate(
                wrongMajor, EmploymentAbsenceTestSupport.DIGEST_A))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> consumer.validate(
                wrongType, EmploymentAbsenceTestSupport.DIGEST_A))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsEnvelopeBodyIdentityAndSequenceMismatch() {
        DomainEventEnvelope original = EmploymentAbsenceTestSupport.event(2);
        DomainEventEnvelope wrongAggregate = copy(original, original.type(), 1,
                "90000000-0000-4000-8000-000000000041",
                original.subject(), original.data());

        assertThatThrownBy(() -> consumer.validate(
                wrongAggregate, EmploymentAbsenceTestSupport.DIGEST_A))
                .isInstanceOf(Xcon004EmploymentChangedV1Consumer.InvalidEmploymentEvent.class)
                .hasMessageContaining("equality");
    }

    @Test
    void rejectsExtraBodyFieldAndNoncanonicalProfileMetadata() {
        DomainEventEnvelope original = EmploymentAbsenceTestSupport.event(1);
        var extra = original.data().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) extra).put("personName", "forbidden");
        DomainEventEnvelope extraBody = copy(original, original.type(), 1,
                original.aggregateId(), original.subject(), extra);
        DomainEventEnvelope wrongProfile = new DomainEventEnvelope(
                original.specVersion(), original.id(), original.source(), original.type(),
                original.schemaVersion(), original.time(), original.subject(),
                original.tenantId(), original.aggregateType(), original.aggregateId(),
                original.aggregateSequence(), original.correlationId(),
                original.causationId(), original.traceParent(), original.data(),
                Map.of("xcon", "XCON-004", "profile", "Other.v1"));

        assertThatThrownBy(() -> consumer.validate(
                extraBody, EmploymentAbsenceTestSupport.DIGEST_A))
                .isInstanceOf(Xcon004EmploymentChangedV1Consumer.InvalidEmploymentEvent.class);
        assertThatThrownBy(() -> consumer.validate(
                wrongProfile, EmploymentAbsenceTestSupport.DIGEST_A))
                .isInstanceOf(Xcon004EmploymentChangedV1Consumer.InvalidEmploymentEvent.class);
    }

    private static DomainEventEnvelope copy(
            DomainEventEnvelope value,
            String type,
            int schemaVersion,
            String aggregateId,
            String subject,
            com.fasterxml.jackson.databind.JsonNode data) {
        return new DomainEventEnvelope(
                value.specVersion(), value.id(), value.source(), type, schemaVersion,
                value.time(), subject, value.tenantId(), value.aggregateType(), aggregateId,
                value.aggregateSequence(), value.correlationId(), value.causationId(),
                value.traceParent(), data, value.extensions());
    }
}
