package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySeals;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.*;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyRepository.CanonicalReceipt;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.AccessContext;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyService.CommandContext;

/** Governed maker-checker lifecycle for the seven-workbench aggregate. */
@Service
public class NavigationPolicyAggregateV2LifecycleService {

    static final String PURPOSE = "HRIS_SYSTEM_ADMIN";
    static final String VIEW_PERMISSION = "CONFIG.HRIS_NAVIGATION_POLICY:VIEW";
    static final String UPDATE_PERMISSION = "CONFIG.HRIS_NAVIGATION_POLICY:UPDATE";
    static final String PUBLISH_PERMISSION = "ACTION.HRIS_NAVIGATION_POLICY_PUBLISH:PUBLISH";
    static final String PUBLISHED_EVENT =
            "dwp.hris.navigation-policy.aggregate.published.v2";

    private final NavigationPolicyAggregateV2Repository repository;
    private final LegalEntityContextResolver legalEntityResolver;
    private final StepUpEvidenceVerifier stepUpVerifier;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public NavigationPolicyAggregateV2LifecycleService(
            NavigationPolicyAggregateV2Repository repository,
            LegalEntityContextResolver legalEntityResolver,
            StepUpEvidenceVerifier stepUpVerifier,
            ObjectMapper objectMapper) {
        this(repository, legalEntityResolver, stepUpVerifier,
                objectMapper, Clock.systemUTC());
    }

    NavigationPolicyAggregateV2LifecycleService(
            NavigationPolicyAggregateV2Repository repository,
            LegalEntityContextResolver legalEntityResolver,
            StepUpEvidenceVerifier stepUpVerifier,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.legalEntityResolver = legalEntityResolver;
        this.stepUpVerifier = stepUpVerifier;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AggregateWorkspaceResponse workspace(
            AccessContext access, ScopeKind scopeKind, UUID scopePublicId) {
        requireViewer(access);
        UUID legalEntity = resolveScope(access, scopeKind, scopePublicId);
        return new AggregateWorkspaceResponse(
                SCHEMA_VERSION, scopeKind, legalEntity,
                repository.findLatestDraft(
                        access.tenantId(), scopeKind, legalEntity).orElse(null),
                repository.findPublishedRevision(
                        access.tenantId(), scopeKind, legalEntity).orElse(null),
                repository.findPublishedHistory(access.tenantId(), scopeKind, legalEntity));
    }

    @Transactional
    public CommandReceipt createDraft(
            AccessContext access, CommandContext command, AggregateDraftCommand body) {
        requireAuthor(access);
        requireClientDigest(body.requestDigest());
        if (body.expectedVersion() != 0) {
            throw conflict("HRIS_NAV_V2_CREATE_VERSION_CONFLICT");
        }
        AggregatePolicyDocument policy = body.policy();
        verifyPolicyScope(access, policy, body.scopePublicId());
        validateForScope(access.tenantId(), policy);
        return execute(access, command, "CREATE_DRAFT", body.requestDigest(), body,
                ignored -> repository.createDraft(
                        access.tenantId(), UUID.randomUUID(), UUID.randomUUID(),
                        access.subjectId(), policy, digest(policy), now()));
    }

    @Transactional
    public CommandReceipt updateDraft(
            AccessContext access, CommandContext command, UUID revisionId,
            AggregateDraftCommand body) {
        requireAuthor(access);
        requireClientDigest(body.requestDigest());
        AggregateRevisionView current = required(access.tenantId(), revisionId);
        AggregatePolicyDocument policy = body.policy();
        verifyPolicyScope(access, policy, body.scopePublicId());
        if (current.lifecycleState() != LifecycleState.DRAFT
                || current.policy().scopeKind() != policy.scopeKind()
                || !java.util.Objects.equals(
                current.policy().legalEntityPublicId(), policy.legalEntityPublicId())) {
            throw conflict("HRIS_NAV_V2_DRAFT_IMMUTABLE_OR_SCOPE_MISMATCH");
        }
        validateForScope(access.tenantId(), policy);
        return execute(access, command, "UPDATE_DRAFT", body.requestDigest(), body,
                ignored -> {
                    if (!repository.updateDraft(
                            access.tenantId(), revisionId, body.expectedVersion(), policy,
                            digest(policy), now())) {
                        throw conflict("HRIS_NAV_V2_OBJECT_VERSION_CONFLICT");
                    }
                    return required(access.tenantId(), revisionId);
                });
    }

    @Transactional
    public CommandReceipt validate(
            AccessContext access, CommandContext command, UUID revisionId,
            LifecycleCommand body) {
        requireAuthor(access);
        return transition(access, command, revisionId, body, "VALIDATE",
                LifecycleState.DRAFT, LifecycleState.VALIDATED,
                current -> validateForScope(access.tenantId(), current.policy()));
    }

    @Transactional
    public CommandReceipt submit(
            AccessContext access, CommandContext command, UUID revisionId,
            LifecycleCommand body) {
        requireAuthor(access);
        return transition(access, command, revisionId, body, "SUBMIT",
                LifecycleState.VALIDATED, LifecycleState.PENDING_APPROVAL,
                current -> { });
    }

    @Transactional
    public CommandReceipt approve(
            AccessContext access, CommandContext command, UUID revisionId,
            LifecycleCommand body) {
        requirePublisher(access);
        requireReason(body.reason());
        return transition(access, command, revisionId, body, "APPROVE",
                LifecycleState.PENDING_APPROVAL, LifecycleState.APPROVED, current -> {
                    requireStepUp(access, command, "APPROVE", revisionId,
                            body.expectedVersion(), body);
                    if (current.authoredBy() == access.subjectId()) {
                        throw forbidden("HRIS_NAV_V2_MAKER_CANNOT_APPROVE");
                    }
                });
    }

    @Transactional
    public CommandReceipt publish(
            AccessContext access, CommandContext command, UUID revisionId,
            LifecycleCommand body) {
        requirePublisher(access);
        requireReason(body.reason());
        return execute(access, command, "PUBLISH", body.requestDigest(), body, () -> {
            requireStepUp(access, command, "PUBLISH", revisionId,
                    body.expectedVersion(), body);
            AggregateRevisionView current = required(access.tenantId(), revisionId);
            if (current.authoredBy() == access.subjectId()) {
                throw forbidden("HRIS_NAV_V2_MAKER_CANNOT_PUBLISH");
            }
            validateForScope(access.tenantId(), current.policy());
            verifyRevisionScope(access, current);
        }, ignored -> {
            if (!repository.publish(
                    access.tenantId(), revisionId, body.expectedVersion(),
                    access.subjectId(), evidenceDigest(body), now())) {
                throw conflict("HRIS_NAV_V2_OBJECT_VERSION_CONFLICT");
            }
            AggregateRevisionView published = required(access.tenantId(), revisionId);
            repository.activate(access.tenantId(), published);
            repository.appendOutbox(
                    access.tenantId(), published, publishedEvent(published), now());
            return published;
        });
    }

    @Transactional
    public CommandReceipt restore(
            AccessContext access, CommandContext command, UUID sourceRevisionId,
            RestoreCommand body) {
        requirePublisher(access);
        requireReason(body.reason());
        requireClientDigest(body.requestDigest());
        return execute(access, command, "RESTORE", body.requestDigest(), body, () -> {
            requireStepUp(access, command, "RESTORE", sourceRevisionId,
                    body.expectedVersion(), body);
            AggregateRevisionView source = required(
                    access.tenantId(), sourceRevisionId);
            if (source.authoredBy() == access.subjectId()) {
                throw forbidden("HRIS_NAV_V2_MAKER_CANNOT_RESTORE");
            }
            validateForScope(access.tenantId(), source.policy());
            verifyRevisionScope(access, source);
        }, ignored -> {
            try {
                AggregateRevisionView restored = repository.restore(
                        access.tenantId(), sourceRevisionId, UUID.randomUUID(),
                        body.expectedVersion(), access.subjectId(),
                        evidenceDigest(body), now());
                repository.activate(access.tenantId(), restored);
                repository.appendOutbox(
                        access.tenantId(), restored, publishedEvent(restored), now());
                return restored;
            } catch (IllegalStateException exception) {
                throw conflict(exception.getMessage());
            }
        });
    }

    private CommandReceipt transition(
            AccessContext access, CommandContext command, UUID revisionId,
            LifecycleCommand body, String action, LifecycleState expected,
            LifecycleState target, Consumer<AggregateRevisionView> guard) {
        requireClientDigest(body.requestDigest());
        return execute(access, command, action, body.requestDigest(), body, () -> {
                    AggregateRevisionView current = required(
                            access.tenantId(), revisionId);
                    guard.accept(current);
                    verifyRevisionScope(access, current);
                },
                ignored -> {
                    if (!repository.transition(
                            access.tenantId(), revisionId, body.expectedVersion(), expected,
                            target, access.subjectId(), evidenceDigest(body), now())) {
                        throw conflict("HRIS_NAV_V2_OBJECT_VERSION_CONFLICT");
                    }
                    return required(access.tenantId(), revisionId);
                });
    }

    private CommandReceipt execute(
            AccessContext access, CommandContext command, String action,
            String clientRequestDigest, Object body,
            Function<UUID, AggregateRevisionView> mutation) {
        return execute(access, command, action, clientRequestDigest,
                body, () -> { }, mutation);
    }

    private CommandReceipt execute(
            AccessContext access, CommandContext command, String action,
            String clientRequestDigest, Object body, Runnable guard,
            Function<UUID, AggregateRevisionView> mutation) {
        validateCommand(command);
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("action", action);
        material.put("body", body);
        material.put("subjectPrincipalPublicId", access.subjectPublicId());
        material.put("contextScopeKey", access.contextScopeKey());
        material.put("clientRequestDigest", normalizeDigest(clientRequestDigest));
        material.put("decisionRevision", command.expectedDecisionRevision() == null
                ? "<none>" : command.expectedDecisionRevision());
        material.put("fieldPolicyRevision", command.fieldPolicyRevision());
        material.put("authorizationRevision", command.authorizationRevision());
        String serverDigest = digest(material);
        repository.lockIdempotency(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey());
        var prior = repository.findReceipt(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey());
        if (prior.isPresent()) {
            if (!prior.get().requestDigest().equals(serverDigest)) {
                throw conflict("HRIS_NAV_V2_IDEMPOTENCY_PAYLOAD_MISMATCH");
            }
            return prior.get().receipt();
        }
        validateCanonicalSeal(access, command, action);
        guard.run();
        requireFreshAccess(access);
        AggregateRevisionView result = mutation.apply(UUID.randomUUID());
        requireFreshAccess(access);
        OffsetDateTime createdAt = now();
        CommandReceipt receipt = new CommandReceipt(
                UUID.randomUUID(), action, result.policyId(), result.revisionId(),
                result.revisionNumber(), result.lifecycleState(), result.objectVersion(),
                normalizeDigest(clientRequestDigest), command.correlationId(), createdAt);
        repository.saveReceipt(
                access.tenantId(), access.subjectId(), action, command.idempotencyKey(),
                serverDigest, receipt);
        long publicFieldRevision = HrisExplorerAuthoritySeals.publicRevision(
                command.fieldPolicyRevision());
        long publicAuthorizationRevision = HrisExplorerAuthoritySeals.publicRevision(
                command.authorizationRevision());
        repository.saveCanonicalReceipt(access.tenantId(), new CanonicalReceipt(
                receipt.receiptId(), receipt.commandType(), action, access.subjectPublicId(),
                command.populationScopeDigest(),
                HrisExplorerAuthoritySeals.populationScopeDigest(access.contextScopeKey()),
                publicFieldRevision, command.fieldPolicyRevision(), access.purposeCode(),
                publicAuthorizationRevision, command.authorizationRevision(),
                command.originRouteContractKey(), command.originContextKey(),
                command.originContextScopeKey(), command.originDecisionRevision(),
                command.idempotencyKey(), serverDigest, receipt.policyId(),
                receipt.objectVersion(), "SUCCEEDED",
                receipt.policyId() + "/" + receipt.revisionId() + "/"
                        + receipt.revisionNumber(),
                receipt.correlationId(), receipt.createdAt()));
        repository.appendAudit(
                access.tenantId(), result, action, access.subjectId(),
                command.correlationId(), serverDigest, createdAt);
        return receipt;
    }

    private void validateForScope(long tenantId, AggregatePolicyDocument policy) {
        try {
            NavigationPolicyAggregateV2Canonical.validate(policy);
            if (policy.scopeKind() == ScopeKind.LEGAL_ENTITY) {
                AggregateRevisionView tenant = repository.findPublishedRevision(
                                tenantId, ScopeKind.TENANT, null)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "HRIS_NAV_V2_TENANT_BASELINE_REQUIRED"));
                NavigationPolicyAggregateV2Canonical.validateRestrictiveOverlay(
                        tenant.policy(), policy);
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(exception.getMessage());
        }
    }

    private void verifyPolicyScope(
            AccessContext access, AggregatePolicyDocument policy, UUID scopePublicId) {
        if (policy.scopeKind() == ScopeKind.TENANT) {
            if (scopePublicId != null || policy.legalEntityPublicId() != null) {
                throw forbidden("HRIS_NAV_V2_TENANT_SCOPE_MISMATCH");
            }
            return;
        }
        UUID verified = legalEntityResolver.resolve(ownerRequest(
                access, LegalEntityContextResolver.ContextSurface.MANAGEMENT, scopePublicId));
        if (!verified.equals(scopePublicId)
                || !verified.equals(policy.legalEntityPublicId())) {
            throw forbidden("HRIS_NAV_V2_LEGAL_ENTITY_SCOPE_MISMATCH");
        }
    }

    private void verifyRevisionScope(
            AccessContext access, AggregateRevisionView revision) {
        verifyPolicyScope(access, revision.policy(),
                revision.policy().legalEntityPublicId());
    }

    private UUID resolveScope(
            AccessContext access, ScopeKind scopeKind, UUID scopePublicId) {
        if (scopeKind == ScopeKind.TENANT) {
            if (scopePublicId != null) {
                throw forbidden("HRIS_NAV_V2_TENANT_SCOPE_MISMATCH");
            }
            return null;
        }
        UUID verified = legalEntityResolver.resolve(ownerRequest(
                access, LegalEntityContextResolver.ContextSurface.MANAGEMENT, scopePublicId));
        if (!verified.equals(scopePublicId)) {
            throw forbidden("HRIS_NAV_V2_LEGAL_ENTITY_SCOPE_MISMATCH");
        }
        return verified;
    }

    private LegalEntityContextResolver.OwnerRequest ownerRequest(
            AccessContext access, LegalEntityContextResolver.ContextSurface surface,
            UUID legalEntityPublicId) {
        return new LegalEntityContextResolver.OwnerRequest(
                access.tenantId(), access.subjectId(), access.subjectPublicId(),
                access.roles(), access.permissions(), surface,
                access.contextScopeKey(), legalEntityPublicId);
    }

    private void requireViewer(AccessContext access) {
        validateAccess(access);
        if (!permissions(access.permissions()).contains(VIEW_PERMISSION)) {
            throw forbidden("HRIS_NAV_V2_VIEW_DENIED");
        }
    }

    private void requireAuthor(AccessContext access) {
        validateAccess(access);
        if (!permissions(access.permissions()).contains(UPDATE_PERMISSION)) {
            throw forbidden("HRIS_NAV_V2_UPDATE_DENIED");
        }
    }

    private void requirePublisher(AccessContext access) {
        validateAccess(access);
        if (!permissions(access.permissions()).contains(PUBLISH_PERMISSION)) {
            throw forbidden("HRIS_NAV_V2_PUBLISH_DENIED");
        }
    }

    private void validateAccess(AccessContext access) {
        if (access == null || access.tenantId() < 1 || access.subjectId() < 1
                || access.subjectPublicId() == null || access.contextKey() == null
                || access.contextKey().isBlank() || access.contextKey().length() > 500
                || access.contextScopeKey() == null
                || !access.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$")
                || access.decisionRevision() == null || access.decisionRevision().isBlank()
                || access.revalidateAt() == null || !access.revalidateAt().isAfter(now())
                || !PURPOSE.equals(access.purposeCode())) {
            throw forbidden("HRIS_NAV_V2_TRUSTED_CONTEXT_REQUIRED");
        }
        Set<String> grants = permissions(access.permissions());
        if (!grants.contains("APP.HCM:VIEW") && !grants.contains("APP.HRIS:VIEW")) {
            throw forbidden("HRIS_NAV_V2_ENTITLEMENT_REQUIRED");
        }
        if (permissions(access.roles()).stream().anyMatch(role -> role.startsWith("PROVIDER"))) {
            throw forbidden("HRIS_NAV_V2_PROVIDER_IDENTITY_DENIED");
        }
    }

    private void requireFreshAccess(AccessContext access) {
        if (access.revalidateAt() == null || !access.revalidateAt().isAfter(now())) {
            throw forbidden("HRIS_NAV_V2_TRUSTED_CONTEXT_STALE_AT_COMPLETION");
        }
    }

    private void validateCommand(CommandContext command) {
        if (command == null || command.idempotencyKey() == null
                || command.idempotencyKey().length() < 16
                || command.idempotencyKey().length() > 128
                || command.correlationId() == null) {
            throw invalid("HRIS_NAV_V2_COMMAND_CONTEXT_INVALID");
        }
    }

    private void validateCanonicalSeal(
            AccessContext access, CommandContext command, String action) {
        if (!originRoute(action).equals(command.originRouteContractKey())
                || !access.contextKey().equals(command.originContextKey())
                || !access.contextScopeKey().equals(command.originContextScopeKey())
                || !access.decisionRevision().equals(command.originDecisionRevision())
                || command.populationScopeDigest() == null
                || !command.populationScopeDigest().matches("^[a-f0-9]{64}$")
                || command.fieldPolicyRevision() < 1
                || command.authorizationRevision() < 1
                || command.requestAuthority() == null
                || !command.originRouteContractKey().equals(
                command.requestAuthority().routeContractKey())) {
            throw invalid("HRIS_NAV_V2_COMMAND_CONTEXT_INVALID");
        }
    }

    private String originRoute(String action) {
        return switch (action) {
            case "CREATE_DRAFT" ->
                    "route.hcm.configuration.navigation-policy-v2-draft-create.action";
            case "UPDATE_DRAFT" ->
                    "route.hcm.configuration.navigation-policy-v2-draft-update.action";
            case "VALIDATE" ->
                    "route.hcm.configuration.navigation-policy-v2-draft-validate.action";
            case "SUBMIT" ->
                    "route.hcm.configuration.navigation-policy-v2-draft-submit.action";
            case "APPROVE" ->
                    "route.hcm.configuration.navigation-policy-v2-revision-approve.action";
            case "PUBLISH" ->
                    "route.hcm.configuration.navigation-policy-v2-revision-publish.action";
            case "RESTORE" ->
                    "route.hcm.configuration.navigation-policy-v2-revision-restore.action";
            default -> "";
        };
    }

    private void requireStepUp(
            AccessContext access, CommandContext command, String action,
            UUID revisionId, long expectedVersion, Object payload) {
        if (command.stepUpProof() == null || command.stepUpProof().isBlank()
                || command.expectedDecisionRevision() == null
                || !command.expectedDecisionRevision().equals(access.decisionRevision())
                || !stepUpVerifier.verify(new StepUpEvidenceVerifier.StepUpEvidence(
                access.tenantId(), access.subjectId(), action, revisionId,
                expectedVersion, access.contextKey(), access.contextScopeKey(),
                command.idempotencyKey(), command.commandMethod(), command.commandPath(),
                payload, command.expectedDecisionRevision(), command.stepUpProof(),
                command.requestAuthority()))) {
            throw forbidden("HRIS_NAV_V2_FRESH_STEP_UP_REQUIRED");
        }
    }

    private void requireReason(String reason) {
        if (reason == null || reason.isBlank() || !reason.equals(reason.strip())) {
            throw invalid("HRIS_NAV_V2_HIGH_RISK_REASON_REQUIRED");
        }
    }

    private void requireClientDigest(String value) {
        if (value == null || !normalizeDigest(value).matches("^[a-f0-9]{64}$")) {
            throw invalid("HRIS_NAV_V2_REQUEST_DIGEST_INVALID");
        }
    }

    private AggregateRevisionView required(long tenantId, UUID revisionId) {
        return repository.findByRevisionId(tenantId, revisionId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.RESOURCE_NOT_AVAILABLE,
                        "HRIS_NAV_V2_REVISION_NOT_FOUND"));
    }

    private String publishedEvent(AggregateRevisionView revision) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", SCHEMA_VERSION);
        event.put("policyId", revision.policyId());
        event.put("revision", revision.revisionNumber());
        event.put("revisionId", revision.revisionId());
        event.put("scopeKind", revision.policy().scopeKind());
        event.put("legalEntityPublicId", revision.policy().legalEntityPublicId());
        event.put("rolloutMode", revision.rolloutMode());
        event.put("payloadDigest", revision.payloadDigest());
        event.put("topologyRevision", revision.topologyRevision());
        event.put("topologySha256", revision.topologySha256());
        event.put("iaRegisterSha256", revision.iaRegisterSha256());
        event.put("shellRegisterSha256", revision.shellRegisterSha256());
        event.put("taskGroupRegisterSha256", revision.taskGroupRegisterSha256());
        event.put("generatedIaSha256", revision.generatedIaSha256());
        event.put("publishedAt", revision.publishedAt());
        event.put("eventSequence", revision.revisionNumber());
        return json(event);
    }

    private String evidenceDigest(Object value) {
        return digest(value);
    }

    private String normalizeDigest(String value) {
        return value != null && value.startsWith("sha256:")
                ? value.substring(7).toLowerCase(Locale.ROOT)
                : value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private Set<String> permissions(String value) {
        if (value == null || value.isBlank() || value.length() > 32_000
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            if (token.isBlank() || !token.equals(token.trim())) return Set.of();
            result.add(token.toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private String digest(Object value) {
        byte[] bytes;
        try {
            bytes = value instanceof String string
                    ? string.getBytes(StandardCharsets.UTF_8)
                    : objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRIS_NAV_V2_DIGEST_FAILED", exception);
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("HRIS_NAV_V2_EVENT_SERIALIZATION_FAILED", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
