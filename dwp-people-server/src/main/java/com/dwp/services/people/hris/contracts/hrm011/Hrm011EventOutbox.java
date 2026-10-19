package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.contracts.hris.xcon.v1.Xcon023WorkerDependentEligibilityChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon024WorkerTaxIdentityChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon025WorkerBankAccountTokenChangedV1;
import com.dwp.contracts.hris.xcon.v1.Xcon026EmploymentContractIssuedV1;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractCanonicalizer;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Strict XCON-023..026 mapper and envelope equality guard. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.contract-compensation-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class Hrm011EventOutbox {
    public static final String XCON_023 = "WorkerDependentEligibilityChanged.v1";
    public static final String XCON_024 = "WorkerTaxIdentityChanged.v1";
    public static final String XCON_025 = "WorkerBankAccountTokenChanged.v1";
    public static final String XCON_026 = "EmploymentContractIssued.v1";
    private static final String SOURCE = "/dwp-people-server/hris/hrm011";

    private final ObjectMapper mapper;
    private final EmploymentContractCanonicalizer canonicalizer;

    public Hrm011EventOutbox(
            ObjectMapper mapper, EmploymentContractCanonicalizer canonicalizer) {
        this.mapper = Objects.requireNonNull(mapper);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
    }

    public DomainEventEnvelope dependent(DependentChange change) {
        UUID eventId = eventId(change.tenantId(), XCON_023,
                change.dependentPublicId(), change.aggregateVersion());
        String digest = canonicalizer.digest(business(
                "dependentPublicId", change.dependentPublicId(),
                "workerId", change.workerId(),
                "effectiveFrom", change.effectiveFrom(),
                "effectiveTo", change.effectiveTo(),
                "newAggregateVersion", change.aggregateVersion()));
        var data = new Xcon023WorkerDependentEligibilityChangedV1(
                change.dependentPublicId(), change.aggregateVersion(), change.causationId(),
                change.correlationId(), change.dependentPublicId(), change.effectiveFrom(),
                change.effectiveTo(), eventId, change.aggregateVersion(), change.occurredAt(),
                digest, 1, change.tenantId(), change.workerId());
        return envelope(XCON_023, data.aggregateId(), data.aggregateVersion(),
                data.eventId(), data.tenantId(), data.correlationId(), data.causationId(),
                data.occurredAt(), data.schemaVersion(), data.payloadDigest(), data);
    }

    public DomainEventEnvelope taxIdentity(RestrictedChange change) {
        UUID eventId = eventId(change.tenantId(), XCON_024,
                change.aggregatePublicId(), change.aggregateVersion());
        String digest = canonicalizer.digest(business(
                "taxIdentityPublicId", change.aggregatePublicId(),
                "workerId", change.workerId(),
                "effectiveFrom", change.effectiveFrom(),
                "effectiveTo", change.effectiveTo(),
                "newAggregateVersion", change.aggregateVersion()));
        var data = new Xcon024WorkerTaxIdentityChangedV1(
                change.aggregatePublicId(), change.aggregateVersion(), change.causationId(),
                change.correlationId(), change.effectiveFrom(), change.effectiveTo(),
                eventId, change.aggregateVersion(), change.occurredAt(), digest, 1,
                change.aggregatePublicId(), change.tenantId(), change.workerId());
        return envelope(XCON_024, data.aggregateId(), data.aggregateVersion(),
                data.eventId(), data.tenantId(), data.correlationId(), data.causationId(),
                data.occurredAt(), data.schemaVersion(), data.payloadDigest(), data);
    }

    public DomainEventEnvelope bankAccountToken(RestrictedChange change) {
        UUID eventId = eventId(change.tenantId(), XCON_025,
                change.aggregatePublicId(), change.aggregateVersion());
        String digest = canonicalizer.digest(business(
                "paymentDestinationPublicId", change.aggregatePublicId(),
                "workerId", change.workerId(),
                "effectiveFrom", change.effectiveFrom(),
                "effectiveTo", change.effectiveTo(),
                "newAggregateVersion", change.aggregateVersion()));
        var data = new Xcon025WorkerBankAccountTokenChangedV1(
                change.aggregatePublicId(), change.aggregateVersion(), change.causationId(),
                change.correlationId(), change.effectiveFrom(), change.effectiveTo(),
                eventId, change.aggregateVersion(), change.occurredAt(), digest,
                change.aggregatePublicId(), 1, change.tenantId(), change.workerId());
        return envelope(XCON_025, data.aggregateId(), data.aggregateVersion(),
                data.eventId(), data.tenantId(), data.correlationId(), data.causationId(),
                data.occurredAt(), data.schemaVersion(), data.payloadDigest(), data);
    }

    public DomainEventEnvelope employmentContract(ContractIssued change) {
        UUID eventId = eventId(change.tenantId(), XCON_026,
                change.contractPublicId(), change.aggregateVersion());
        String digest = canonicalizer.digest(business(
                "contractPublicId", change.contractPublicId(),
                "workerPublicId", change.workerPublicId(),
                "contractType", change.contractType(),
                "validFrom", change.validFrom(),
                "validTo", change.validTo(),
                "documentReference", change.documentReference()));
        var reference = new Xcon026EmploymentContractIssuedV1.DocumentReference(
                change.documentReference().objectPublicId(),
                change.documentReference().objectVersion(),
                change.documentReference().contentSha256());
        var data = new Xcon026EmploymentContractIssuedV1(
                change.contractPublicId(), change.aggregateVersion(), change.causationId(),
                change.contractPublicId(), change.contractType(), change.correlationId(),
                reference, eventId, change.occurredAt(), digest, 1, change.tenantId(),
                change.validFrom(), change.validTo(), change.workerPublicId());
        return envelope(XCON_026, data.aggregateId(), data.aggregateVersion(),
                data.eventId(), data.tenantId(), data.correlationId(), data.causationId(),
                data.occurredAt(), data.schemaVersion(), data.payloadDigest(), data);
    }

    private DomainEventEnvelope envelope(
            String type, UUID aggregateId, long aggregateVersion, UUID eventId,
            long tenantId, UUID correlationId, UUID causationId, Instant occurredAt,
            int schemaVersion, String payloadDigest, Object data) {
        if (aggregateVersion < 1 || eventId == null || tenantId < 1
                || correlationId == null || causationId == null || occurredAt == null
                || schemaVersion != 1 || payloadDigest == null
                || !payloadDigest.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("The HRM-011 event metadata is invalid.");
        }
        JsonNode node = mapper.valueToTree(data);
        DomainEventEnvelope envelope = new DomainEventEnvelope(
                "1.0", eventId, SOURCE, type, schemaVersion, occurredAt,
                type + "/" + aggregateId, tenantId, eventAggregateType(type),
                aggregateId.toString(), aggregateVersion, correlationId.toString(),
                causationId.toString(), null, node,
                Map.of("payloadDigest", payloadDigest,
                        "x-dwp-envelope-data-equality", "STRICT_EIGHT_FIELDS"));
        requireEquality(envelope);
        return envelope;
    }

    public static void requireEquality(DomainEventEnvelope envelope) {
        JsonNode data = envelope == null ? null : envelope.data();
        if (data == null || !envelope.id().toString().equals(text(data, "eventId"))
                || !Objects.equals(envelope.tenantId(), number(data, "tenantId"))
                || !envelope.aggregateId().equals(text(data, "aggregateId"))
                || envelope.aggregateSequence() != number(data, "aggregateVersion")
                || !envelope.correlationId().equals(text(data, "correlationId"))
                || !envelope.causationId().equals(text(data, "causationId"))
                || !envelope.time().toString().equals(text(data, "occurredAt"))
                || envelope.schemaVersion() != number(data, "schemaVersion")) {
            throw new IllegalArgumentException(
                    "All eight HRM-011 envelope/data fields must be equal.");
        }
    }

    private UUID eventId(long tenantId, String type, UUID aggregateId, long version) {
        if (tenantId < 1 || aggregateId == null || version < 1) {
            throw new IllegalArgumentException("Persisted event versions must be positive.");
        }
        return canonicalizer.deterministicUuid(tenantId, type, aggregateId, version);
    }

    private Map<String, Object> business(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || !value.isTextual() ? null : value.textValue();
    }

    private static long number(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || !value.canConvertToLong() ? Long.MIN_VALUE : value.longValue();
    }

    private String eventAggregateType(String eventType) {
        return switch (eventType) {
            case XCON_023 -> "DEPENDENT";
            case XCON_024 -> "TAX_IDENTITY";
            case XCON_025 -> "PAYMENT_DESTINATION";
            case XCON_026 -> "EMPLOYMENT_CONTRACT";
            default -> throw new IllegalArgumentException("Unknown HRM-011 event type.");
        };
    }

    public record DependentChange(
            long tenantId, UUID dependentPublicId, UUID workerId,
            LocalDate effectiveFrom, LocalDate effectiveTo, long aggregateVersion,
            UUID correlationId, UUID causationId, Instant occurredAt) {
    }

    public record RestrictedChange(
            long tenantId, UUID aggregatePublicId, UUID workerId,
            LocalDate effectiveFrom, LocalDate effectiveTo, long aggregateVersion,
            UUID correlationId, UUID causationId, Instant occurredAt) {
    }

    public record ContractIssued(
            long tenantId, UUID contractPublicId, UUID workerPublicId,
            String contractType, LocalDate validFrom, LocalDate validTo,
            EmploymentContractDtos.DocumentReference documentReference,
            long aggregateVersion, UUID correlationId, UUID causationId,
            Instant occurredAt) {
    }

    /** Stateful consumer policy useful to PAY adapters without wiring a trigger here. */
    public static final class ReplayLedger {
        private final Map<UUID, String> hashes = new java.util.HashMap<>();
        private final Map<UUID, Long> versions = new java.util.HashMap<>();

        public synchronized Disposition evaluate(
                UUID eventId, UUID aggregateId, long version, String payloadDigest) {
            String priorHash = hashes.get(eventId);
            if (priorHash != null) {
                return priorHash.equals(payloadDigest)
                        ? Disposition.DUPLICATE : Disposition.CONFLICT_QUARANTINED;
            }
            long prior = versions.getOrDefault(aggregateId, 0L);
            if (version <= prior) return Disposition.STALE_IGNORED;
            if (version != prior + 1) return Disposition.GAP_BLOCKED_REFETCH_REQUIRED;
            hashes.put(eventId, payloadDigest);
            versions.put(aggregateId, version);
            return Disposition.ACCEPTED;
        }
    }

    public enum Disposition {
        ACCEPTED,
        DUPLICATE,
        CONFLICT_QUARANTINED,
        STALE_IGNORED,
        GAP_BLOCKED_REFETCH_REQUIRED
    }
}
