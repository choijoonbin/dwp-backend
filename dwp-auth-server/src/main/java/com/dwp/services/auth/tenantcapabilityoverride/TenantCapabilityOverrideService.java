package com.dwp.services.auth.tenantcapabilityoverride;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.AppGovernanceAuthorization;
import com.dwp.services.auth.service.IdentityAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TenantCapabilityOverrideService {

    private static final List<String> INCLUDED_OWNERS = List.of(
            "AUTH_PRODUCT_AUTHORIZATION_CATALOG",
            "AUTH_TENANT_CAPABILITY_OVERRIDE",
            "AUTH_RUNTIME_PEP");
    private static final List<String> EXCLUSIONS = List.of(
            "EXTERNAL_SAAS_CAPABILITY_APPLICATION_UNAVAILABLE");

    private final TenantCapabilityOverrideRepository repository;
    private final IdentityAuditService audit;
    private final AppGovernanceAuthorization authorization;
    private final Clock clock;

    @Autowired
    public TenantCapabilityOverrideService(
            TenantCapabilityOverrideRepository repository,
            IdentityAuditService audit,
            JdbcTemplate jdbc) {
        this(repository, audit, new AppGovernanceAuthorization(jdbc), Clock.systemUTC());
    }

    public TenantCapabilityOverrideService(
            TenantCapabilityOverrideRepository repository,
            IdentityAuditService audit,
            AppGovernanceAuthorization authorization,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TenantCapabilityOverrideDtos.Projection projection(Long tenantId, Long actorId) {
        AppGovernanceAuthorization.Visibility visibility =
                authorization.requireVisibility(tenantId, actorId);
        Authority authority = authority(tenantId, actorId);
        Instant now = clock.instant();
        List<TenantCapabilityOverrideDtos.Policy> policies = repository.policies().stream()
                .filter(policy -> visibility.queueReader()
                        || visibility.appResourceKeys().contains(policy.appResourceKey()))
                .toList();
        Set<String> contractKeys = policies.stream()
                .map(TenantCapabilityOverrideDtos.Policy::contractKey)
                .collect(java.util.stream.Collectors.toSet());
        List<TenantCapabilityOverrideDtos.Change> changes = repository.changes(tenantId).stream()
                .filter(change -> contractKeys.contains(change.contractKey()))
                .map(change -> withActions(change, actorId, authority))
                .toList();
        Map<String, TenantCapabilityOverrideDtos.Change> activeByContract = changes.stream()
                .filter(change -> "ACTIVE".equals(change.lifecycleState()))
                .collect(java.util.stream.Collectors.toMap(
                        TenantCapabilityOverrideDtos.Change::contractKey,
                        change -> change, (left, right) -> left));
        List<TenantCapabilityOverrideDtos.EffectiveCapability> capabilities = policies.stream()
                .map(policy -> effective(
                        withActions(policy, tenantId, actorId, authority, now),
                        activeByContract.get(policy.contractKey()), now))
                .toList();
        return new TenantCapabilityOverrideDtos.Projection(
                now, "COMPLETE_INTERNAL_OWNERS", INCLUDED_OWNERS, EXCLUSIONS,
                capabilities, changes);
    }

    @Transactional
    public TenantCapabilityOverrideDtos.Change create(
            Long tenantId,
            Long actorId,
            String correlationId,
            TenantCapabilityOverrideDtos.CreateRequest request) {
        TenantCapabilityOverrideDtos.Policy policy = repository.policy(request.contractKey())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_OWNER", policy.appResourceKey(), correlationId,
                "TENANT_CAPABILITY_OVERRIDE", request.contractKey());
        requireMutablePolicy(policy);
        Instant now = clock.instant();
        validateDesiredState(tenantId, policy, request, now);
        TenantCapabilityOverrideDtos.Change change;
        try {
            change = repository.insert(tenantId, actorId, policy, request, now);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "An open capability override change already exists.", exception);
        }
        event(tenantId, change, "OVERRIDE_DRAFTED", actorId, correlationId, null,
                Map.of("desiredState", change.desiredState(),
                        "baseActiveRevision", change.baseActiveRevision()));
        return withActions(change, actorId, authority(tenantId, actorId));
    }

    @Transactional
    public TenantCapabilityOverrideDtos.Change submit(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID changeId,
            TenantCapabilityOverrideDtos.VersionedCommand command) {
        TenantCapabilityOverrideDtos.Change current = repository.requireChange(tenantId, changeId);
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_OWNER", current.appResourceKey(), correlationId,
                "TENANT_CAPABILITY_OVERRIDE", changeId.toString());
        if (!actorId.equals(current.requestedBy())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        TenantCapabilityOverrideDtos.Change changed = repository.submit(
                tenantId, changeId, command.version(), actorId, clock.instant());
        event(tenantId, changed, "OVERRIDE_SUBMITTED", actorId, correlationId, null, Map.of());
        return withActions(changed, actorId, authority(tenantId, actorId));
    }

    @Transactional
    public TenantCapabilityOverrideDtos.Change decide(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID changeId,
            TenantCapabilityOverrideDtos.DecisionCommand command) {
        TenantCapabilityOverrideDtos.Change current = repository.requireChange(tenantId, changeId);
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_ACCESS_APPROVER", current.appResourceKey(), correlationId,
                "TENANT_CAPABILITY_OVERRIDE", changeId.toString());
        requireIndependent(current.requestedBy(), actorId, "reviewer");
        String next = "APPROVE".equals(command.decision()) ? "APPROVED" : "REJECTED";
        TenantCapabilityOverrideDtos.Change changed = repository.decide(
                tenantId, changeId, command.version(), next, command.reason(),
                actorId, clock.instant());
        event(tenantId, changed, "OVERRIDE_" + next, actorId, correlationId, null,
                Map.of("reason", command.reason().trim()));
        return withActions(changed, actorId, authority(tenantId, actorId));
    }

    @Transactional
    public TenantCapabilityOverrideDtos.Change activate(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID changeId,
            TenantCapabilityOverrideDtos.ReasonedCommand command) {
        TenantCapabilityOverrideDtos.Change current = repository.lockChange(tenantId, changeId);
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_ACCESS_MANAGER", current.appResourceKey(), correlationId,
                "TENANT_CAPABILITY_OVERRIDE", changeId.toString());
        requireIndependent(current.requestedBy(), actorId, "activator");
        requireIndependent(current.approvedBy(), actorId, "activator");
        TenantCapabilityOverrideDtos.Policy policy = repository.policy(current.contractKey())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_STATE, "The baseline capability is no longer active."));
        if (!policy.activeBundleId().equals(current.baseBundleId())
                || policy.activeRevision() != current.baseActiveRevision()) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The active authorization bundle changed after this override was drafted.");
        }
        if ("DISABLED".equals(current.desiredState())
                && (current.validTo() == null || !current.validTo().isAfter(clock.instant()))) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE, "The approved suppression validity window expired.");
        }
        UUID receiptId = UUID.randomUUID();
        TenantCapabilityOverrideDtos.Change changed = repository.activate(
                tenantId, changeId, command.version(), actorId, command.reason(),
                receiptId, clock.instant());
        event(tenantId, changed, "OVERRIDE_ACTIVATED", actorId, correlationId, receiptId,
                Map.of("effectiveState",
                        "DISABLED".equals(changed.desiredState()) ? "DISABLED" : "ENABLED"));
        audit.success(
                tenantId, actorId, "tenant-capability-override.activated",
                "TENANT_CAPABILITY_OVERRIDE", changeId.toString(), correlationId,
                Map.of("lifecycleState", current.lifecycleState(), "version", current.version()),
                Map.of("lifecycleState", changed.lifecycleState(),
                        "desiredState", changed.desiredState(),
                        "activationReceiptId", receiptId.toString()));
        return withActions(changed, actorId, authority(tenantId, actorId));
    }

    @Transactional
    public TenantCapabilityOverrideDtos.Change revoke(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID changeId,
            TenantCapabilityOverrideDtos.ReasonedCommand command) {
        TenantCapabilityOverrideDtos.Change current = repository.lockChange(tenantId, changeId);
        authorization.requireAppResponsibility(
                tenantId, actorId, "APP_ACCESS_MANAGER", current.appResourceKey(), correlationId,
                "TENANT_CAPABILITY_OVERRIDE", changeId.toString());
        TenantCapabilityOverrideDtos.Change changed = repository.revoke(
                tenantId, changeId, command.version(), actorId, command.reason(), clock.instant());
        event(tenantId, changed, "OVERRIDE_REVOKED", actorId, correlationId, null,
                Map.of("reason", command.reason().trim(), "effectiveState", "ENABLED"));
        return withActions(changed, actorId, authority(tenantId, actorId));
    }

    private TenantCapabilityOverrideDtos.EffectiveCapability effective(
            TenantCapabilityOverrideDtos.Policy policy,
            TenantCapabilityOverrideDtos.Change active,
            Instant now) {
        boolean disabled = active != null && "DISABLED".equals(active.desiredState())
                && active.validTo() != null && active.validTo().isAfter(now);
        boolean expired = active != null && "DISABLED".equals(active.desiredState())
                && active.validTo() != null && !active.validTo().isAfter(now);
        List<TenantCapabilityOverrideDtos.Lineage> lineage = new ArrayList<>();
        lineage.add(new TenantCapabilityOverrideDtos.Lineage(
                "BASELINE", "AUTH_PRODUCT_AUTHORIZATION_CATALOG", "ENABLED",
                "ACTIVE_IMMUTABLE_BUNDLE", null, null, null));
        if (active != null) {
            lineage.add(new TenantCapabilityOverrideDtos.Lineage(
                    "TENANT", "AUTH_TENANT_CAPABILITY_OVERRIDE",
                    disabled ? "DISABLED" : expired ? "EXPIRED" : "INHERIT",
                    active.desiredState(), active.activationReceiptId(),
                    active.activatedAt(), active.validTo()));
        }
        return new TenantCapabilityOverrideDtos.EffectiveCapability(
                policy, "ENABLED", disabled ? "DISABLED" : "ENABLED",
                disabled ? "TENANT_OVERRIDE" : "GLOBAL_AUTHORIZATION_BUNDLE",
                "OWNER_LOCKED".equals(policy.overrideMode())
                        ? "OWNER_LOCKED"
                        : disabled ? "TENANT_DISABLED"
                        : expired ? "EXPIRED" : "INHERITED",
                active, lineage, now);
    }

    private TenantCapabilityOverrideDtos.Policy withActions(
            TenantCapabilityOverrideDtos.Policy policy,
            Long tenantId,
            Long actorId,
            Authority authority,
            Instant now) {
        List<String> actions = new ArrayList<>();
        if ("ALLOW_DISABLE".equals(policy.overrideMode())
                && authority.owners().contains(policy.appResourceKey())) {
            if (!repository.isDisabled(tenantId, policy.contractKey(), now)) {
                actions.add("REQUEST_DISABLE");
            } else {
                actions.add("REQUEST_INHERIT");
            }
        }
        return new TenantCapabilityOverrideDtos.Policy(
                policy.contractKey(), policy.productKey(), policy.appResourceKey(),
                policy.surfaceKey(), policy.resolvedCapabilityCode(), policy.action(),
                policy.riskTier(), policy.contractOwner(), policy.activeBundleId(),
                policy.activeRevision(), policy.ruleKey(), policy.ruleVersion(),
                policy.overrideMode(), policy.maxDurationDays(), policy.ruleOwner(),
                policy.reasonCode(), policy.planEligibilityState(), actions);
    }

    private TenantCapabilityOverrideDtos.Change withActions(
            TenantCapabilityOverrideDtos.Change change, Long actorId, Authority authority) {
        List<String> actions = new ArrayList<>();
        if ("DRAFT".equals(change.lifecycleState())
                && actorId.equals(change.requestedBy())
                && authority.owners().contains(change.appResourceKey())) {
            actions.add("SUBMIT");
        }
        if ("IN_REVIEW".equals(change.lifecycleState())
                && !actorId.equals(change.requestedBy())
                && authority.approvers().contains(change.appResourceKey())) {
            actions.add("APPROVE");
            actions.add("REJECT");
        }
        if ("APPROVED".equals(change.lifecycleState())
                && !actorId.equals(change.requestedBy())
                && !actorId.equals(change.approvedBy())
                && authority.managers().contains(change.appResourceKey())) {
            actions.add("ACTIVATE");
        }
        if ("ACTIVE".equals(change.lifecycleState())
                && authority.managers().contains(change.appResourceKey())) {
            actions.add("REVOKE");
        }
        return new TenantCapabilityOverrideDtos.Change(
                change.overrideChangeId(), change.contractKey(), change.productKey(),
                change.appResourceKey(), change.policyRuleKey(), change.policyRuleVersion(),
                change.baseBundleId(), change.baseActiveRevision(), change.desiredState(),
                change.lifecycleState(), change.validTo(), change.justification(),
                change.requestedBy(), change.submittedAt(), change.approvedBy(),
                change.approvedAt(), change.decisionReason(), change.activatedBy(),
                change.activatedAt(), change.activationReceiptId(), change.revokedBy(),
                change.revokedAt(), change.revocationReason(), change.version(),
                change.createdAt(), change.updatedAt(), actions);
    }

    private void validateDesiredState(
            Long tenantId,
            TenantCapabilityOverrideDtos.Policy policy,
            TenantCapabilityOverrideDtos.CreateRequest request,
            Instant now) {
        if ("DISABLED".equals(request.desiredState())) {
            if (request.validTo() == null || !request.validTo().isAfter(now)) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "A future validity end is required for a tenant suppression.");
            }
            Instant latest = now.plus(policy.maxDurationDays(), ChronoUnit.DAYS);
            if (request.validTo().isAfter(latest)) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The suppression exceeds the immutable policy duration.");
            }
        } else {
            if (request.validTo() != null) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Inheritance restoration does not accept a validity end.");
            }
            if (!repository.isDisabled(tenantId, policy.contractKey(), now)) {
                throw new BaseException(
                        ErrorCode.INVALID_STATE,
                        "The capability is not currently disabled by this tenant.");
            }
        }
    }

    private static void requireMutablePolicy(TenantCapabilityOverrideDtos.Policy policy) {
        if (!"ALLOW_DISABLE".equals(policy.overrideMode())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "This capability is locked by its immutable catalog owner.");
        }
    }

    private static void requireIndependent(Long previousActor, Long actorId, String role) {
        if (previousActor != null && previousActor.equals(actorId)) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The " + role + " must be independent from earlier workflow actors.");
        }
    }

    private Authority authority(Long tenantId, Long actorId) {
        return new Authority(
                authorization.appResourceKeys(tenantId, actorId, "APP_OWNER"),
                authorization.appResourceKeys(tenantId, actorId, "APP_ACCESS_APPROVER"),
                authorization.appResourceKeys(tenantId, actorId, "APP_ACCESS_MANAGER"));
    }

    private void event(
            Long tenantId,
            TenantCapabilityOverrideDtos.Change change,
            String eventType,
            Long actorId,
            String correlationId,
            UUID receiptId,
            Map<String, Object> evidence) {
        repository.appendEvent(
                tenantId, change.overrideChangeId(), eventType, actorId, correlationId,
                change.version(), receiptId, evidence);
    }

    private record Authority(Set<String> owners, Set<String> approvers, Set<String> managers) {
        private Authority {
            owners = Set.copyOf(new LinkedHashSet<>(owners));
            approvers = Set.copyOf(new LinkedHashSet<>(approvers));
            managers = Set.copyOf(new LinkedHashSet<>(managers));
        }
    }
}
