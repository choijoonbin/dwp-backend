package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.ReferenceConfigurationService.*;

/** Additive PER boundary. Missing providers fail before receipt disclosure, mutation or owner calls. */
@Component
class Per004ConfigurationRuntime {
    private final FeatureDecisionPort features;
    private final OwnerPort owners;
    private final Per004OwnerEvidenceStore evidence;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired Per004ConfigurationRuntime(ObjectProvider<FeatureDecisionPort> features,
            ObjectProvider<OwnerPort> owners, Per004OwnerEvidenceStore evidence, ObjectMapper mapper) {
        this(features.getIfAvailable(), owners.getIfAvailable(), evidence, mapper, Clock.systemUTC());
    }
    Per004ConfigurationRuntime(FeatureDecisionPort features, OwnerPort owners,
            Per004OwnerEvidenceStore evidence, ObjectMapper mapper, Clock clock) {
        this.features = features; this.owners = owners; this.evidence = evidence;
        this.mapper = mapper; this.clock = clock;
    }
    static Per004ConfigurationRuntime unavailable(ObjectMapper mapper, Clock clock) {
        return new Per004ConfigurationRuntime(null, null, null, mapper, clock);
    }

    void validateCreate(CreateVersionCommand body) {
        if (body == null || body.domain() == null || body.effectiveFrom() == null
                || body.schemaKey() == null || body.schemaKey().isBlank() || body.schemaKey().length() > 128
                || body.schemaVersion() == null || body.schemaVersion() < 1) throw invalid();
        boolean typed = typedOwner(body.domain().name(), body.schemaKey());
        if (typed) {
            if (body.payload() != null || body.ownerPayload() == null || !body.ownerPayload().isObject()) throw invalid();
            try {
                if (mapper.writeValueAsBytes(body.ownerPayload()).length > MAX_OWNER_PAYLOAD_UTF8) throw invalid();
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw invalid(); }
        } else if (body.ownerPayload() != null || body.payload() == null
                || body.payload().reference() == null
                || !body.payload().reference().matches("^ref_[A-Za-z0-9_-]{8,248}$") || body.payload().digest() == null
                || !body.payload().digest().matches("^(sha256:)?[a-fA-F0-9]{64}$")) throw invalid();
    }

    void guard(AccessContext access, CommandContext command, UUID configurationId,
               Domain domain, String action, String schemaKey) {
        if (domain != Domain.PER) return;
        String route = route(action);
        if (route == null) {
            if (typedOwner("PER", schemaKey)) throw unavailable();
            return;
        }
        if (!route.equals(command.originRouteContractKey())) throw unavailable();
        FeatureRequest request = new FeatureRequest(access.tenantId(), access.subjectPublicId(), route,
                configurationId, "PER", access.scope().digest(), access.authorizationRevision(), command.correlationId());
        FeatureDecision decision;
        try { decision = features == null ? null : features.decide(request); }
        catch (RuntimeException exception) { throw unavailable(); }
        if (decision == null || !request.equals(decision.binding()) || !FEATURE.equals(decision.featureKey())
                || !FEATURE_ROUTE.equals(decision.decisionRoute()) || !"ACTIVE".equals(decision.featureState())
                || !"ALLOWED".equals(decision.decision()) || decision.featureDecisionRevision() == null
                || decision.featureDecisionRevision().isBlank() || !fresh(decision.evaluatedAt(), decision.revalidateAt())) {
            throw unavailable();
        }
    }

    long create(ReferenceConfigurationRepository repository, AccessContext access, CommandContext command,
                UUID id, CreateVersionCommand body, UUID platformReceiptId, String requestDigest) {
        if (owners == null || evidence == null) throw unavailable();
        evidence.checkCreateEligible(access.tenantId(), access.scope(), id, body.schemaKey());
        PrepareRequest request = new PrepareRequest(access.tenantId(), id, access.subjectPublicId(), body.schemaKey(),
                body.schemaVersion(), access.scope().digest(), command.idempotencyKey(), requestDigest,
                command.correlationId(), body.ownerPayload());
        OwnerReceipt prepared = call(() -> owners.prepare(request));
        OwnerTuple expected = new OwnerTuple(access.tenantId(), id, null, "PER", body.schemaKey(),
                body.schemaVersion(), prepared == null || prepared.tuple() == null ? null : prepared.tuple().payloadReference(),
                prepared == null || prepared.tuple() == null ? null : prepared.tuple().payloadDigest(), access.scope().digest());
        requireReceipt(prepared, ReceiptKind.PREPARATION, expected, null);
        CreateIdentity identity = new CreateIdentity(access.tenantId(), id, access.subjectPublicId(),
                "configuration.version.create", command.idempotencyKey(), requestDigest, prepared.ownerReceiptId());
        evidence.admitCreate(identity);
        CreateVersionCommand reference = new CreateVersionCommand(Domain.PER, body.effectiveFrom(), body.effectiveTo(),
                body.schemaKey(), body.schemaVersion(), new PayloadReference(expected.payloadReference(), expected.payloadDigest()));
        long version = repository.createVersion(access.tenantId(), access.scope(), id, access.subjectId(), reference, now());
        OwnerTuple allocated = new OwnerTuple(expected.tenantId(), id, version, "PER", expected.schemaKey(),
                expected.schemaVersion(), expected.payloadReference(), expected.payloadDigest(), expected.scopeDigest());
        evidence.recordPreparation(identity, version, new OwnerReceipt(prepared.kind(), allocated, prepared.ownerService(),
                prepared.ownerReceiptId(), null, prepared.evidenceDigest(), prepared.outcome(), prepared.occurredAt(),
                prepared.revalidateAt(), null), platformReceiptId, access.scope(), now());
        return version;
    }

    boolean ownerTransition(AccessContext access, VersionDetail detail, String commandType, Object body) {
        if (!typedOwner(detail.domain().name(), detail.schemaKey())
                || !(commandType.equals("VALIDATE_VERSION") || commandType.equals("SIMULATE_VERSION"))) return false;
        if (owners == null || evidence == null) throw unavailable();
        OwnerTuple tuple = tuple(access, detail);
        OwnerReceipt receipt;
        if (body instanceof ValidationCommand validation) {
            if (detail.state() != LifecycleState.DRAFT || validation.schemaRevision() != detail.schemaVersion()
                    || !normalize(validation.inputScopeDigest()).equals(tuple.scopeDigest())) throw invalid();
            OwnerReceipt prior = required(tuple, ReceiptKind.FINALIZATION);
            receipt = call(() -> owners.validate(new ValidateRequest(tuple, prior.ownerReceiptId())));
            requireReceipt(receipt, ReceiptKind.VALIDATION, tuple, prior.ownerReceiptId());
        } else if (body instanceof SimulationCommand simulation) {
            OwnerReceipt prior = required(tuple, ReceiptKind.VALIDATION);
            if (detail.state() != LifecycleState.VALIDATED || !prior.ownerReceiptId().equals(simulation.validationReceiptId())) throw invalid();
            receipt = call(() -> owners.simulate(new SimulateRequest(tuple, prior.ownerReceiptId(),
                    simulation.sampleScopeRef(), normalize(simulation.sampleScopeDigest()))));
            requireReceipt(receipt, ReceiptKind.SIMULATION, tuple, prior.ownerReceiptId());
        } else throw invalid();
        evidence.transition(detail, access.scope(), receipt, now());
        return true;
    }

    @Transactional public FinalizationAcknowledgement finalizePending(long tenantId, UUID id, long version) {
        if (owners == null || evidence == null) throw unavailable();
        FinalizeRequest request = evidence.pendingFinalization(tenantId, id, version);
        OwnerReceipt receipt = call(() -> owners.finalizeBinding(request));
        return acknowledge(request, receipt);
    }

    @Transactional public FinalizationAcknowledgement acknowledge(FinalizeRequest request, OwnerReceipt receipt) {
        if (evidence == null) throw unavailable();
        receipt = canonical(receipt);
        if (receipt == null || receipt.kind() != ReceiptKind.FINALIZATION || !request.tuple().equals(receipt.tuple())
                || !request.ownerPreparationReceiptId().equals(receipt.priorOwnerReceiptId())) throw unavailable();
        var prior = evidence.receipt(request.tuple().tenantId(), request.tuple().configurationId(),
                request.tuple().version(), ReceiptKind.FINALIZATION);
        if (prior.isPresent() && prior.get().equals(receipt)) return evidence.acknowledge(request, receipt, now());
        requireReceipt(receipt, ReceiptKind.FINALIZATION, request.tuple(), request.ownerPreparationReceiptId());
        return evidence.acknowledge(request, receipt, now());
    }

    private OwnerReceipt required(OwnerTuple tuple, ReceiptKind kind) {
        OwnerReceipt receipt = evidence.receipt(tuple.tenantId(), tuple.configurationId(), tuple.version(), kind)
                .orElseThrow(Per004ConfigurationRuntime::unavailable);
        if (!tuple.equals(receipt.tuple())) throw unavailable();
        return receipt;
    }
    private OwnerTuple tuple(AccessContext access, VersionDetail detail) {
        return new OwnerTuple(access.tenantId(), detail.configurationId(), detail.version(), "PER", detail.schemaKey(),
                detail.schemaVersion(), detail.payloadReference(), detail.payloadDigest(), access.scope().digest());
    }
    private void requireReceipt(OwnerReceipt r, ReceiptKind kind, OwnerTuple tuple, UUID prior) {
        if (r == null || r.kind() != kind || !tuple.equals(r.tuple()) || !"PER".equals(tuple.domain())
                || !SCHEMAS.contains(tuple.schemaKey()) || tuple.schemaVersion() < 1
                || !ownerReference(tuple.payloadReference())
                || !digest(tuple.payloadDigest()) || !digest(tuple.scopeDigest()) || !"dwp-people-server".equals(r.ownerService())
                || r.ownerReceiptId() == null || !Objects.equals(prior, r.priorOwnerReceiptId())
                || !digest(r.evidenceDigest()) || !"SUCCEEDED".equals(r.outcome()) || !fresh(r.occurredAt(), r.revalidateAt())
                || kind == ReceiptKind.SIMULATION && !digest(r.impactDigest())
                || kind != ReceiptKind.SIMULATION && r.impactDigest() != null) throw unavailable();
    }
    static String route(String action) {
        String suffix = switch (action) {
            case "configuration.version.create" -> "create";
            case "configuration.version.validate" -> "validate";
            case "configuration.version.simulate" -> "simulate";
            case "configuration.version.submit" -> "submit";
            case "configuration.version.approve" -> "approve";
            case "configuration.version.publish" -> "publish";
            default -> null;
        };
        return suffix == null ? null : "route.hcm.configuration.configuration-version-" + suffix + ".action";
    }
    private boolean fresh(OffsetDateTime at, OffsetDateTime until) {
        return at != null && until != null && !at.isAfter(now()) && until.isAfter(now()) && until.isAfter(at);
    }
    private boolean digest(String value) { return value != null && value.matches("^[a-f0-9]{64}$"); }
    private String normalize(String value) { return value == null ? "" : value.replaceFirst("^sha256:", "").toLowerCase(java.util.Locale.ROOT); }
    private OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private OwnerReceipt call(java.util.function.Supplier<OwnerReceipt> call) {
        try { return canonical(call.get()); } catch (RuntimeException exception) { throw unavailable(); }
    }
    private OwnerReceipt canonical(OwnerReceipt receipt) {
        if (receipt == null) return null;
        return new OwnerReceipt(receipt.kind(), receipt.tuple(), receipt.ownerService(), receipt.ownerReceiptId(),
                receipt.priorOwnerReceiptId(), receipt.evidenceDigest(), receipt.outcome(),
                receipt.occurredAt() == null ? null : receipt.occurredAt().withOffsetSameInstant(ZoneOffset.UTC),
                receipt.revalidateAt() == null ? null : receipt.revalidateAt().withOffsetSameInstant(ZoneOffset.UTC), receipt.impactDigest());
    }
    private static BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "PER004_CREATE_OR_OWNER_BINDING_INVALID"); }
    private static BaseException unavailable() { return new BaseException(ErrorCode.FORBIDDEN, "PER004_TRUSTED_EVIDENCE_UNAVAILABLE"); }
}
