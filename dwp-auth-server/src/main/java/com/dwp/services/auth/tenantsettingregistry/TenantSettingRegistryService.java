package com.dwp.services.auth.tenantsettingregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.IdentityAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TenantSettingRegistryService {

    private final TenantSettingRegistryRepository repository;
    private final TenantSettingOwnerAdapterRegistry adapters;
    private final TenantSettingRegistryAuthorization authorization;
    private final IdentityAuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public TenantSettingRegistryService(
            TenantSettingRegistryRepository repository,
            TenantSettingOwnerAdapterRegistry adapters,
            TenantSettingRegistryAuthorization authorization,
            IdentityAuditService audit,
            ObjectMapper mapper) {
        this(repository, adapters, authorization, audit, mapper, Clock.systemUTC());
    }

    TenantSettingRegistryService(
            TenantSettingRegistryRepository repository,
            TenantSettingOwnerAdapterRegistry adapters,
            TenantSettingRegistryAuthorization authorization,
            IdentityAuditService audit,
            ObjectMapper mapper,
            Clock clock) {
        this.repository = repository;
        this.adapters = adapters;
        this.authorization = authorization;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TenantSettingRegistryDtos.OwnerDescriptor> owners(Long tenantId, Long actorId) {
        authorization.require(tenantId, actorId, "VIEW");
        Instant now = clock.instant();
        return repository.owners().stream().map(owner -> {
            TenantSettingOwnerAdapter adapter = adapters.find(
                    owner.settingKey(), owner.ownerKey()).orElse(null);
            boolean supported = adapter != null;
            String editorKind = supported ? adapter.editorKind() : "OWNER_ONLY";
            OwnerEvidence evidence = supported
                    ? ownerEvidence(tenantId, owner, adapter, requireObserved(adapter.read(tenantId)))
                    : OwnerEvidence.unavailable();
            List<String> actions = new ArrayList<>();
            actions.add("VIEW_EFFECTIVE");
            if (supported && adapter.tenantEditable()
                    && !"OWNER_ONLY".equals(editorKind)
                    && "TENANT_ALLOWED".equals(owner.overridePolicy())
                    && authorization.can(tenantId, actorId, "MANAGE")) {
                actions.add("CREATE_CHANGE");
                actions.add("RESTORE_INHERITANCE");
            }
            return new TenantSettingRegistryDtos.OwnerDescriptor(
                    owner.ownerKey(), owner.ownerVersion(), owner.settingKey(),
                    owner.ownerService(), owner.valueType(), editorKind,
                    owner.resolutionStrategy(),
                    owner.overridePolicy(), owner.activationMode(), owner.defaultValue(),
                    owner.localizedLabelKey(), owner.lifecycleState(),
                    supported ? "CONNECTED" : "UNAVAILABLE", now,
                    evidence.sourceUpdatedAt(), evidence.freshnessState(), actions);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<TenantSettingRegistryDtos.Change> changes(Long tenantId, Long actorId) {
        authorization.require(tenantId, actorId, "VIEW");
        return repository.changes(tenantId).stream()
                .map(change -> decorate(tenantId, actorId, change)).toList();
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TenantSettingRegistryDtos.Change create(
            Long tenantId,
            Long actorId,
            String correlationId,
            TenantSettingRegistryDtos.CreateChangeRequest request) {
        authorization.require(tenantId, actorId, "MANAGE");
        TenantSettingRegistryRepository.OwnerRow owner = repository.requireOwner(request.settingKey());
        if (!"TENANT_ALLOWED".equals(owner.overridePolicy())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "This setting is locked by its owner.");
        }
        TenantSettingOwnerAdapter adapter = adapters.require(owner.settingKey(), owner.ownerKey());
        JsonNode before = requireObserved(adapter.read(tenantId));
        JsonNode after = resolvedAfter(owner, adapter, request.desiredState(), request.proposedValue());
        if (before.equals(after)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The proposed value does not change the effective owner value.");
        }
        Instant now = clock.instant();
        TenantSettingRegistryDtos.Change created;
        try {
            created = repository.insert(
                    tenantId, owner, request.desiredState(), before,
                    "VALUE".equals(request.desiredState()) ? after : null,
                    digest(before), digest(after), repository.activePrincipalCount(tenantId),
                    now, request.justification(), actorId);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "An open change already exists for this setting.", exception);
        }
        repository.event(tenantId, created.changeId(), "DRAFT_CREATED", actorId,
                correlationId, created.version(), null, evidence(created), now);
        audit.success(tenantId, actorId, "tenant-setting-override.drafted",
                "TENANT_SETTING_OVERRIDE", created.changeId().toString(), correlationId,
                Map.of("settingKey", created.settingKey()),
                Map.of("desiredState", created.desiredState()));
        return decorate(tenantId, actorId, created);
    }

    @Transactional
    public TenantSettingRegistryDtos.Change submit(
            Long tenantId, Long actorId, String correlationId, UUID id,
            TenantSettingRegistryDtos.VersionedCommand command) {
        authorization.require(tenantId, actorId, "MANAGE");
        TenantSettingRegistryDtos.Change changed = repository.submit(
                tenantId, id, command.version(), actorId, clock.instant());
        event(tenantId, changed, "SUBMITTED", actorId, correlationId, null);
        return decorate(tenantId, actorId, changed);
    }

    @Transactional
    public TenantSettingRegistryDtos.Change decide(
            Long tenantId, Long actorId, String correlationId, UUID id,
            TenantSettingRegistryDtos.DecisionCommand command) {
        authorization.require(tenantId, actorId, "APPROVE");
        TenantSettingRegistryDtos.Change previous = repository.requireChange(tenantId, id);
        if (previous.requestedBy().equals(actorId)) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "The requester cannot approve their own setting change.");
        }
        String next = "APPROVE".equals(command.decision()) ? "APPROVED" : "REJECTED";
        TenantSettingRegistryDtos.Change changed = repository.decide(
                tenantId, id, command.version(), next, command.reason(), actorId, clock.instant());
        event(tenantId, changed, next, actorId, correlationId, null);
        return decorate(tenantId, actorId, changed);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TenantSettingRegistryDtos.Change publish(
            Long tenantId, Long actorId, String correlationId, UUID id,
            TenantSettingRegistryDtos.VersionedCommand command) {
        authorization.require(tenantId, actorId, "PUBLISH");
        TenantSettingRegistryDtos.Change previous = repository.requireChange(tenantId, id);
        if (previous.requestedBy().equals(actorId) || previous.approvedBy().equals(actorId)) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "Publication requires an actor independent from request and approval.");
        }
        TenantSettingRegistryRepository.OwnerRow owner = repository.requireOwner(previous.settingKey());
        if (!owner.ownerKey().equals(previous.ownerKey())
                || owner.ownerVersion() != previous.ownerVersion()) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "The setting owner contract changed after this draft was created.");
        }
        TenantSettingOwnerAdapter adapter = adapters.require(owner.settingKey(), owner.ownerKey());
        JsonNode current = requireObserved(adapter.read(tenantId));
        if (!digest(current).equals(digest(previous.beforeValue()))) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The owner value changed after preview; create a new draft.");
        }
        JsonNode applied = "INHERIT".equals(previous.desiredState())
                ? owner.defaultValue() : previous.proposedValue();
        adapter.validate(applied);
        adapter.apply(tenantId, applied, actorId);
        JsonNode observed = requireObserved(adapter.read(tenantId));
        if (!observed.equals(applied)) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "The setting owner did not confirm the published value.");
        }
        Instant now = clock.instant();
        UUID receiptId = UUID.randomUUID();
        TenantSettingRegistryDtos.Change changed = repository.publish(
                tenantId, id, command.version(), actorId, now, receiptId);
        event(tenantId, changed, "PUBLISHED", actorId, correlationId, receiptId);
        audit.success(tenantId, actorId, "tenant-setting-override.published",
                "TENANT_SETTING_OVERRIDE", id.toString(), correlationId,
                Map.of("settingKey", changed.settingKey()),
                Map.of("receiptId", receiptId.toString(), "desiredState", changed.desiredState()));
        return decorate(tenantId, actorId, changed);
    }

    @Transactional(readOnly = true)
    public List<TenantSettingRegistryDtos.EffectiveSetting> effective(Long tenantId) {
        Instant now = clock.instant();
        return repository.owners().stream().map(owner -> {
            TenantSettingOwnerAdapter adapter = adapters.require(owner.settingKey(), owner.ownerKey());
            JsonNode value = requireObserved(adapter.read(tenantId));
            OwnerEvidence evidence = ownerEvidence(tenantId, owner, adapter, value);
            return new TenantSettingRegistryDtos.EffectiveSetting(
                    owner.settingKey(), owner.localizedLabelKey(), value,
                    evidence.source(), evidence.overrideState(),
                    List.of(new TenantSettingRegistryDtos.Provenance(
                            "TENANT", ownerLabelKey(owner.ownerKey()), owner.ownerVersion(), "WINNER",
                            evidence.reason())), evidence.freshnessState(),
                    evidence.sourceUpdatedAt(), now);
        }).toList();
    }

    private OwnerEvidence ownerEvidence(
            Long tenantId,
            TenantSettingRegistryRepository.OwnerRow owner,
            TenantSettingOwnerAdapter adapter,
            JsonNode observed) {
        Instant sourceUpdatedAt = adapter.sourceUpdatedAt(tenantId);
        if ("AUTH_POLICY".equals(owner.ownerKey())) {
            var publication = repository.latestCanonicalAuthPolicyPublication(tenantId);
            if (publication.isEmpty()) {
                return new OwnerEvidence(
                        "OWNER_CURRENT", "OWNER_LOCKED", "DIRECT_OWNER_BASELINE",
                        "FRESH", sourceUpdatedAt);
            }
            JsonNode expected = canonicalAuthPolicyValue(
                    publication.get().proposedState(), owner.settingKey());
            if (expected == null || !observed.equals(expected)) {
                return new OwnerEvidence(
                        "OWNER_CURRENT", "DRIFTED", "OWNER_CHANGED_OUTSIDE_WORKFLOW",
                        "DRIFTED", sourceUpdatedAt);
            }
            return new OwnerEvidence(
                    "OWNER_PUBLICATION", "OWNER_LOCKED", "PUBLISHED_AUTH_POLICY",
                    "FRESH", sourceUpdatedAt);
        }
        var publication = repository.latestPublished(tenantId, owner.settingKey());
        if (publication.isEmpty()) {
            return new OwnerEvidence(
                    "OWNER_CURRENT", "UNMANAGED_BASELINE", "DIRECT_OWNER_BASELINE",
                    "FRESH", sourceUpdatedAt);
        }
        JsonNode expected = "VALUE".equals(publication.get().desiredState())
                ? publication.get().proposedValue() : owner.defaultValue();
        if (!observed.equals(expected)) {
            return new OwnerEvidence(
                    "OWNER_CURRENT", "DRIFTED", "OWNER_CHANGED_OUTSIDE_REGISTRY",
                    "DRIFTED", sourceUpdatedAt);
        }
        return "VALUE".equals(publication.get().desiredState())
                ? new OwnerEvidence(
                        "TENANT_OVERRIDE", "OVERRIDDEN", "PUBLISHED_TENANT_OVERRIDE",
                        "FRESH", sourceUpdatedAt)
                : new OwnerEvidence(
                        "OWNER_DEFAULT", "INHERITED", "PUBLISHED_INHERIT_RESTORE",
                        "FRESH", sourceUpdatedAt);
    }

    private JsonNode canonicalAuthPolicyValue(JsonNode policy, String settingKey) {
        return switch (settingKey) {
            case "authentication.defaultLoginType" -> policy.get("defaultLoginType");
            case "authentication.requireMfa" -> policy.get("requireMfa");
            case "authentication.tokenTtlSec" -> policy.get("tokenTtlSec");
            default -> null;
        };
    }

    private String ownerLabelKey(String ownerKey) {
        return switch (ownerKey) {
            case "AUTH_POLICY" -> "managed.effective.owners.authPolicy";
            case "AUTH_TENANT_DIRECTORY" -> "managed.effective.owners.tenantDirectory";
            default -> "managed.effective.owners.registeredOwner";
        };
    }

    private TenantSettingRegistryDtos.Change decorate(
            Long tenantId, Long actorId, TenantSettingRegistryDtos.Change change) {
        TenantSettingRegistryRepository.OwnerRow owner = repository.requireOwner(change.settingKey());
        JsonNode after = "INHERIT".equals(change.desiredState())
                ? owner.defaultValue() : change.proposedValue();
        TenantSettingRegistryDtos.Preview preview = new TenantSettingRegistryDtos.Preview(
                change.settingKey(), change.beforeValue(), after,
                "INHERIT".equals(change.desiredState()) ? "OWNER_DEFAULT" : "TENANT_OVERRIDE",
                change.impactCount(), change.impactCoverage(), change.impactObservedAt(),
                List.of("EXTERNAL_APPLICATION_SESSIONS_NOT_COUNTED"));
        List<String> actions = new ArrayList<>();
        if ("DRAFT".equals(change.lifecycleState())
                && authorization.can(tenantId, actorId, "MANAGE")) actions.add("SUBMIT");
        if ("IN_REVIEW".equals(change.lifecycleState())
                && !change.requestedBy().equals(actorId)
                && authorization.can(tenantId, actorId, "APPROVE")) {
            actions.add("APPROVE");
            actions.add("REJECT");
        }
        if ("APPROVED".equals(change.lifecycleState())
                && !change.requestedBy().equals(actorId)
                && !change.approvedBy().equals(actorId)
                && authorization.can(tenantId, actorId, "PUBLISH")) actions.add("PUBLISH");
        return new TenantSettingRegistryDtos.Change(
                change.changeId(), change.settingKey(), change.ownerKey(), change.ownerVersion(),
                change.desiredState(), change.beforeValue(), change.proposedValue(),
                change.lifecycleState(), change.impactCount(), change.impactCoverage(),
                change.impactObservedAt(), change.justification(), change.requestedBy(),
                change.submittedAt(), change.approvedBy(), change.approvedAt(),
                change.decisionReason(), change.publishedBy(), change.publishedAt(),
                change.publishReceiptId(), change.version(), change.createdAt(),
                change.updatedAt(), preview, actions);
    }

    private JsonNode resolvedAfter(
            TenantSettingRegistryRepository.OwnerRow owner,
            TenantSettingOwnerAdapter adapter,
            String desiredState,
            JsonNode proposedValue) {
        if ("INHERIT".equals(desiredState)) {
            if (proposedValue != null && !proposedValue.isNull()) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                        "An INHERIT change cannot carry a tenant value.");
            }
            adapter.validate(owner.defaultValue());
            return owner.defaultValue();
        }
        if (proposedValue == null || proposedValue.isNull()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "A VALUE change requires a proposed value.");
        }
        adapter.validate(proposedValue);
        return proposedValue.deepCopy();
    }

    private JsonNode requireObserved(JsonNode value) {
        if (value == null) {
            throw new BaseException(ErrorCode.INVALID_STATE,
                    "The registered setting owner did not return an observed value.");
        }
        return value;
    }

    private void event(
            Long tenantId,
            TenantSettingRegistryDtos.Change change,
            String type,
            Long actorId,
            String correlationId,
            UUID receiptId) {
        repository.event(tenantId, change.changeId(), type, actorId, correlationId,
                change.version(), receiptId, evidence(change), clock.instant());
    }

    private ObjectNode evidence(TenantSettingRegistryDtos.Change change) {
        ObjectNode node = mapper.createObjectNode();
        node.put("settingKey", change.settingKey());
        node.put("ownerKey", change.ownerKey());
        node.put("desiredState", change.desiredState());
        node.put("lifecycleState", change.lifecycleState());
        return node;
    }

    private String digest(JsonNode value) {
        try {
            byte[] canonical = mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Unable to hash the tenant setting value.", exception);
        }
    }

    private record OwnerEvidence(
            String source,
            String overrideState,
            String reason,
            String freshnessState,
            Instant sourceUpdatedAt) {

        private static OwnerEvidence unavailable() {
            return new OwnerEvidence(
                    "OWNER_CURRENT", "UNMANAGED_BASELINE", "OWNER_UNAVAILABLE",
                    "NO_DATA", null);
        }
    }
}
