package com.dwp.services.platform.hrisconfiguration.operations;

import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.operations.HrisOperationsModels.IngressState.APPLIED;
import static com.dwp.services.platform.hrisconfiguration.operations.HrisOperationsModels.IngressState.IGNORED_NOT_ENROLLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HrisOperationalExceptionProjectionContractTest {

    private static final UUID EVENT_ID =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID EXCEPTION_ID =
            UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID CORRELATION_ID =
            UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID EVIDENCE_ID =
            UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final String OCCURRED_AT = "2026-10-17T07:00:00Z";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void transportGroupAndSharedLedgerConsumerHaveDistinctSealedIdentities() {
        assertThat(HrisOperationalExceptionEventContract.GROUP)
                .isEqualTo("dwp-platform-hris-operational-exception-v1");
        assertThat(HrisOperationalExceptionEventContract.CONSUMER)
                .isEqualTo("dwp-platform-server.hris-operational-exception-v1")
                .isNotEqualTo(HrisOperationalExceptionEventContract.GROUP);
    }

    @Test
    void listenerUsesRawBytesAndEnforcesPartitionKeyOnlyForClaimedEvents()
            throws Exception {
        Method listener = HrisOperationalExceptionEventConsumer.class
                .getDeclaredMethod("onMessage", ConsumerRecord.class);
        KafkaListener binding = listener.getAnnotation(KafkaListener.class);
        assertThat(binding.topics()).containsExactly("dwp.domain-events.v1");
        assertThat(binding.groupId()).isEqualTo(
                HrisOperationalExceptionEventContract.GROUP);
        assertThat(binding.properties()).containsExactlyInAnyOrder(
                "key.deserializer=org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer=org.apache.kafka.common.serialization.ByteArrayDeserializer");

        HrisOperationalExceptionRepository repository =
                mock(HrisOperationalExceptionRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<KafkaTemplate<String, String>> kafka =
                mock(ObjectProvider.class);
        HrisOperationalExceptionEventConsumer consumer =
                new HrisOperationalExceptionEventConsumer(
                        fixtureContract(), repository,
                        new HrisOperationsRuntimeControls(true, "42"), kafka);

        byte[] unrelated = "{\"type\":\"dwp.some-other.event.v1\"}"
                .getBytes(StandardCharsets.UTF_8);
        consumer.onMessage(new ConsumerRecord<>(
                "dwp.domain-events.v1", 0, 1L, "wrong-key", unrelated));
        verifyNoInteractions(repository, kafka);

        byte[] claimed = json.writeValueAsBytes(validEnvelope());
        assertThatThrownBy(() -> consumer.onMessage(new ConsumerRecord<>(
                "dwp.domain-events.v1", 0, 2L, "wrong-key", claimed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid SYS-EVT-009 envelope");
        verifyNoInteractions(repository, kafka);

        when(repository.apply(any())).thenReturn(
                HrisOperationsModels.IngressResult.of(APPLIED));
        String key = "42|urn:dwp:people:hrm:operational-exception|"
                + HrisOperationalExceptionEventContract.AGGREGATE + "|"
                + EXCEPTION_ID;
        consumer.onMessage(new ConsumerRecord<>(
                "dwp.domain-events.v1", 0, 3L, key, claimed));
        verify(repository).apply(any());
        verifyNoInteractions(kafka);
    }

    @Test
    void admissionAndProjectionUseIndependentRequiresNewTransactions()
            throws Exception {
        JdbcHrisOperationalExceptionRepository repository =
                new JdbcHrisOperationalExceptionRepository(
                        mock(NamedParameterJdbcTemplate.class),
                        mock(PlatformTransactionManager.class));

        TransactionTemplate admission = transactionTemplate(
                repository, "admissionTransactions");
        TransactionTemplate projection = transactionTemplate(
                repository, "projectionTransactions");
        assertThat(admission).isNotSameAs(projection);
        assertThat(admission.getPropagationBehavior()).isEqualTo(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(projection.getPropagationBehavior()).isEqualTo(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void exactFixtureLocalPairProjectsAndProductionRegistryFailsClosed()
            throws Exception {
        HrisOperationalExceptionEventContract fixtureContract = contract(
                (source, code, summary) ->
                        "urn:dwp:people:hrm:operational-exception".equals(source)
                                && "HRM_ASSIGNMENT_STALLED".equals(code)
                                && "hris.operations.exception.hrm.assignment-stalled"
                                .equals(summary));

        var event = fixtureContract.parse(json.writeValueAsBytes(validEnvelope()));

        assertThat(event.exceptionPublicId()).isEqualTo(EXCEPTION_ID);
        assertThat(event.ownerModule().name()).isEqualTo("HRM");
        assertThat(event.sourceRevision()).isEqualTo(1);
        assertThat(event.evidenceRefs()).containsExactly(
                "urn:dwp:hris:evidence:domain-event:" + EVIDENCE_ID);
        assertThat(event.eventDigest()).matches("^[a-f0-9]{64}$");
        assertThat(event.canonicalPayload()).doesNotContain(
                "stackTrace", "personName", "recoveryCommand");

        HrisOperationalExceptionEventContract production = contract(
                (source, code, summary) -> false);
        assertThatThrownBy(() -> production.parse(
                json.writeValueAsBytes(validEnvelope())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid SYS-EVT-009 envelope");
    }

    @Test
    void sharedTopicPreflightIgnoresUnrelatedShapeButClaimedEventIsStrict()
            throws Exception {
        HrisOperationalExceptionEventContract contract = fixtureContract();
        byte[] unrelated = """
                {"type":"dwp.some-other.event.v7","legacy":true,
                 "extensions":{"ownerSpecific":"allowed-for-that-owner"}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(contract.supports(unrelated)).isFalse();

        ObjectNode claimed = validEnvelope();
        claimed.put("unexpectedEnvelopeField", true);
        assertThat(contract.supports(json.writeValueAsBytes(claimed))).isTrue();
        assertThatThrownBy(() -> contract.parse(
                json.writeValueAsBytes(claimed)))
                .isInstanceOf(IllegalArgumentException.class);

        String duplicateType = "{\"type\":\""
                + HrisOperationalExceptionEventContract.TYPE
                + "\",\"type\":\""
                + HrisOperationalExceptionEventContract.TYPE + "\"}";
        assertThatThrownBy(() -> contract.supports(
                duplicateType.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rawIngressRejectsNonCanonicalAndCoercibleValuesBeforeBinding()
            throws Exception {
        HrisOperationalExceptionEventContract contract = fixtureContract();

        ObjectNode uppercaseId = validEnvelope();
        uppercaseId.put("id", "A0000000-0000-4000-8000-000000000001");
        assertRejected(contract, uppercaseId);

        ObjectNode decimalSequence = validEnvelope();
        decimalSequence.put("aggregateSequence", 1.0d);
        assertRejected(contract, decimalSequence);

        ObjectNode decimalRevision = validEnvelope();
        data(decimalRevision).put("sourceRevision", 1.0d);
        assertRejected(contract, decimalRevision);

        ObjectNode zeroTraceId = validEnvelope();
        zeroTraceId.put("traceParent", "00-" + "0".repeat(32)
                + "-0000000000000001-01");
        assertRejected(contract, zeroTraceId);

        ObjectNode zeroParentId = validEnvelope();
        zeroParentId.put("traceParent", "00-"
                + "00000000000000000000000000000001-"
                + "0".repeat(16) + "-01");
        assertRejected(contract, zeroParentId);

        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};
        assertThatThrownBy(() -> contract.parse(invalidUtf8))
                .isInstanceOf(IllegalArgumentException.class);

        String trailing = json.writeValueAsString(validEnvelope()) + " {}";
        assertThatThrownBy(() -> contract.parse(
                trailing.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactPayloadDeniesMissingCrossProductUnsortedAndForbiddenFields()
            throws Exception {
        HrisOperationalExceptionEventContract contract = fixtureContract();

        ObjectNode missing = validEnvelope();
        data(missing).remove("sourceRef");
        assertRejected(contract, missing);

        ObjectNode crossProduct = validEnvelope();
        data(crossProduct).put("ownerModule", "PAY");
        assertRejected(contract, crossProduct);

        ObjectNode forbiddenTenantOverride = validEnvelope();
        data(forbiddenTenantOverride).put("tenantId", 42);
        assertRejected(contract, forbiddenTenantOverride);

        ObjectNode urlInsteadOfRoute = validEnvelope();
        data(urlInsteadOfRoute).put(
                "ownerRouteKey", "https://owner.example/recover?token=secret");
        assertRejected(contract, urlInsteadOfRoute);

        ObjectNode unsorted = validEnvelope();
        ArrayNode refs = json.createArrayNode()
                .add("urn:dwp:hris:evidence:owner-projection:"
                        + "50000000-0000-4000-8000-000000000005")
                .add("urn:dwp:hris:evidence:domain-event:" + EVIDENCE_ID);
        data(unsorted).set("evidenceRefs", refs);
        assertRejected(contract, unsorted);
    }

    @Test
    void disabledOrUnenrolledTenantMakesZeroRepositoryCalls() {
        HrisOperationalExceptionRepository repository =
                mock(HrisOperationalExceptionRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<KafkaTemplate<String, String>> kafka =
                mock(ObjectProvider.class);
        HrisOperationalExceptionEventConsumer consumer =
                new HrisOperationalExceptionEventConsumer(
                        fixtureContract(), repository,
                        new HrisOperationsRuntimeControls(false, ""), kafka);
        DomainEventEnvelope claimedButUnvalidated = new DomainEventEnvelope(
                "1.0", EVENT_ID,
                "urn:dwp:people:hrm:operational-exception",
                HrisOperationalExceptionEventContract.TYPE, 1,
                Instant.parse(OCCURRED_AT),
                "HRIS_OPERATIONAL_EXCEPTION/" + EXCEPTION_ID,
                42L, HrisOperationalExceptionEventContract.AGGREGATE,
                EXCEPTION_ID.toString(), 1, CORRELATION_ID.toString(),
                null, null, null, Map.of());

        assertThat(consumer.consume(claimedButUnvalidated).state())
                .isEqualTo(IGNORED_NOT_ENROLLED);
        verifyNoInteractions(repository, kafka);
    }

    @Test
    void enrollmentParserRejectsDuplicateWhitespaceAndNonPositiveTenants() {
        assertThatThrownBy(() -> new HrisOperationsRuntimeControls(
                true, "42,42")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HrisOperationsRuntimeControls(
                true, "42, 43")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new HrisOperationsRuntimeControls(
                true, "0")).isInstanceOf(IllegalStateException.class);
        assertThat(new HrisOperationsRuntimeControls(true, "42,43")
                .enrollmentCount()).isEqualTo(2);
    }

    private HrisOperationalExceptionEventContract fixtureContract() {
        return contract((source, code, summary) ->
                "HRM_ASSIGNMENT_STALLED".equals(code)
                        && "hris.operations.exception.hrm.assignment-stalled"
                        .equals(summary));
    }

    private TransactionTemplate transactionTemplate(
            JdbcHrisOperationalExceptionRepository repository,
            String fieldName) throws Exception {
        Field field = JdbcHrisOperationalExceptionRepository.class
                .getDeclaredField(fieldName);
        field.setAccessible(true);
        return (TransactionTemplate) field.get(repository);
    }

    private HrisOperationalExceptionEventContract contract(
            HrisOperationalExceptionSummaryKeyRegistry registry) {
        return new HrisOperationalExceptionEventContract(json, registry);
    }

    private void assertRejected(
            HrisOperationalExceptionEventContract contract, ObjectNode envelope)
            throws Exception {
        assertThatThrownBy(() -> contract.parse(json.writeValueAsBytes(envelope)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid SYS-EVT-009 envelope");
    }

    private ObjectNode validEnvelope() {
        ObjectNode data = json.createObjectNode();
        data.put("exceptionPublicId", EXCEPTION_ID.toString());
        data.put("ownerService", "dwp-people-server");
        data.put("ownerModule", "HRM");
        data.put("sourceRef", "urn:dwp:hris:hrm:assignment:" + EXCEPTION_ID);
        data.put("sourceRevision", 1);
        data.put("type", "ASSIGNMENT_PROCESSING");
        data.put("severity", "HIGH");
        data.put("state", "OPEN");
        data.put("code", "HRM_ASSIGNMENT_STALLED");
        data.put("summaryKey",
                "hris.operations.exception.hrm.assignment-stalled");
        data.put("ownerRouteKey",
                "route.hcm.operations.employment-assignments.page");
        data.putArray("evidenceRefs").add(
                "urn:dwp:hris:evidence:domain-event:" + EVIDENCE_ID);
        data.put("correlationId", CORRELATION_ID.toString());
        data.put("occurredAt", OCCURRED_AT);

        ObjectNode envelope = json.createObjectNode();
        envelope.put("specVersion", "1.0");
        envelope.put("id", EVENT_ID.toString());
        envelope.put("source", "urn:dwp:people:hrm:operational-exception");
        envelope.put("type", HrisOperationalExceptionEventContract.TYPE);
        envelope.put("schemaVersion", 1);
        envelope.put("time", OCCURRED_AT);
        envelope.put("subject", "HRIS_OPERATIONAL_EXCEPTION/" + EXCEPTION_ID);
        envelope.put("tenantId", 42);
        envelope.put("aggregateType",
                HrisOperationalExceptionEventContract.AGGREGATE);
        envelope.put("aggregateId", EXCEPTION_ID.toString());
        envelope.put("aggregateSequence", 1);
        envelope.put("correlationId", CORRELATION_ID.toString());
        envelope.putNull("causationId");
        envelope.putNull("traceParent");
        envelope.set("data", data);
        envelope.set("extensions", json.createObjectNode());
        return envelope;
    }

    private ObjectNode data(ObjectNode envelope) {
        return (ObjectNode) envelope.get("data");
    }
}
