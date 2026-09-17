package com.dwp.services.approval.deployment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;

@Service
public class ApprovalDeploymentService {
    private static final Duration MAXIMUM_STEP_UP_AGE = Duration.ofMinutes(15);
    private static final Duration MAXIMUM_EVIDENCE_AGE = Duration.ofMinutes(10);

    private final ApprovalDeploymentRepository repository;
    private final ApprovalDeploymentCanonical canonical;
    private final ApprovalDeploymentAttestationVerifier attestationVerifier;
    private final Clock clock;

    @Autowired
    public ApprovalDeploymentService(
            ApprovalDeploymentRepository repository,
            ObjectMapper mapper,
            ApprovalDeploymentAttestationVerifier attestationVerifier) {
        this(repository, mapper, Clock.systemUTC(), attestationVerifier);
    }

    ApprovalDeploymentService(
            ApprovalDeploymentRepository repository,
            ObjectMapper mapper,
            Clock clock,
            ApprovalDeploymentAttestationVerifier attestationVerifier) {
        this.repository = repository;
        this.canonical = new ApprovalDeploymentCanonical(mapper);
        this.attestationVerifier = attestationVerifier;
        this.clock = clock;
    }

    @Transactional
    public PackageRecord createPackage(Scope scope, PackageCommand requested) {
        String idempotencyKey = requested == null || requested.packageId() == null
                ? "package-invalid"
                : "package:" + requested.packageId();
        return createPackage(scope, requested, new GovernedCommand(
                scope == null ? -1 : scope.actorUserId(), idempotencyKey,
                null, null, null, null, null));
    }

    @Transactional
    public PackageRecord createPackage(
            Scope scope,
            PackageCommand requested,
            GovernedCommand governed) {
        require(scope, Capability.CREATE_PACKAGE);
        requireActor(scope, governed, false);
        PackageCommand command = validatePackage(requested);
        RollbackDisposition rollback = rollback(command.assets());
        Manifest manifest = new Manifest(
                "approval-deployment-package/v1", command.packageKey(),
                command.packageVersion(), command.assets(), command.dependencies(),
                Map.of(
                        "rollbackClass", rollback.name(),
                        "externalEffectsAreNotReversedByPackageRollback", true));
        String hash = canonical.sha256(manifest);
        String requestHash = canonical.sha256(command);
        PackageRecord prior = repository.priorPackage(
                scope, scope.actorUserId(), governed.idempotencyKey());
        if (prior != null) {
            if (!requestHash.equals(repository.packageRequestHash(
                    scope, prior.packageId()))) {
                throw conflict("The idempotency key is bound to another package command.");
            }
            return prior;
        }
        PackageRecord byId = repository.packageById(scope, command.packageId());
        PackageRecord byKey = repository.packageByKey(
                scope, command.packageKey(), command.packageVersion());
        PackageRecord existing = byId != null ? byId : byKey;
        if (existing != null) {
            if (!existing.manifestSha256().equals(hash)) {
                throw conflict("The immutable package identity already has different content.");
            }
            return existing;
        }
        repository.insertPackage(
                scope, command, manifest, hash, rollback,
                governed, requestHash, clock.instant());
        return repository.packageById(scope, command.packageId());
    }

    @Transactional(readOnly = true)
    public PackageRecord packageById(Scope scope, UUID packageId) {
        require(scope, Capability.VIEW);
        PackageRecord value = repository.packageById(scope, packageId);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    @Transactional(readOnly = true)
    public List<PackageRecord> packages(Scope scope, int limit) {
        require(scope, Capability.VIEW);
        if (limit < 1 || limit > 100) {
            throw invalid("The deployment package list limit is invalid.");
        }
        return repository.packages(scope, limit);
    }

    @Transactional(readOnly = true)
    public List<Promotion> promotions(Scope scope, String status, int limit) {
        require(scope, Capability.VIEW);
        if (limit < 1 || limit > 100
                || status != null && !Set.of(
                        "PENDING_REVIEW", "APPROVED", "SCHEDULED", "ACTIVATING",
                        "ACTIVE", "PARTIAL", "UNKNOWN", "FAILED",
                        "ROLLBACK_PENDING", "PACKAGE_HEAD_RESTORED").contains(status)) {
            throw invalid("The deployment promotion list filter is invalid.");
        }
        return repository.promotions(scope, status, limit);
    }

    @Transactional(readOnly = true)
    public Promotion promotionById(Scope scope, UUID promotionId) {
        require(scope, Capability.VIEW);
        return requiredPromotion(scope, promotionId, false);
    }

    @Transactional(readOnly = true)
    public DeploymentDashboard dashboard(Scope scope) {
        require(scope, Capability.VIEW);
        return new DeploymentDashboard(
                clock.instant(), repository.environmentHeads(scope),
                repository.promotions(scope, null, 25));
    }

    @Transactional(readOnly = true)
    public PromotionDetail promotionDetail(Scope scope, UUID promotionId) {
        require(scope, Capability.VIEW);
        Promotion promotion = requiredPromotion(scope, promotionId, false);
        return new PromotionDetail(
                promotion, requiredPackage(scope, promotion.packageId()),
                repository.environmentHead(scope, promotion.sourceEnvironment(), false),
                repository.environmentHead(scope, promotion.targetEnvironment(), false),
                repository.evidence(scope, promotionId),
                rollbackFeasibilityUnchecked(scope, promotionId));
    }

    @Transactional(readOnly = true)
    public PackageDiff diff(Scope scope, UUID fromPackageId, UUID toPackageId) {
        require(scope, Capability.VIEW);
        PackageRecord from = requiredPackage(scope, fromPackageId);
        PackageRecord to = requiredPackage(scope, toPackageId);
        Map<String, Asset> left = byAssetKey(from.assets());
        Map<String, Asset> right = byAssetKey(to.assets());
        List<String> added = right.keySet().stream().filter(key -> !left.containsKey(key)).sorted().toList();
        List<String> removed = left.keySet().stream().filter(key -> !right.containsKey(key)).sorted().toList();
        List<String> changed = right.keySet().stream()
                .filter(left::containsKey)
                .filter(key -> !left.get(key).equals(right.get(key)))
                .sorted().toList();
        Set<String> leftDependencies = dependencyKeys(from.dependencies());
        Set<String> rightDependencies = dependencyKeys(to.dependencies());
        List<String> dependencyChanges = java.util.stream.Stream.concat(
                        leftDependencies.stream().filter(key -> !rightDependencies.contains(key))
                                .map(key -> "REMOVED:" + key),
                        rightDependencies.stream().filter(key -> !leftDependencies.contains(key))
                                .map(key -> "ADDED:" + key))
                .sorted().toList();
        boolean introducesExternal = to.assets().stream().anyMatch(Asset::externalSideEffects)
                && from.assets().stream().noneMatch(Asset::externalSideEffects);
        return new PackageDiff(
                fromPackageId, toPackageId, added, removed, changed,
                dependencyChanges, introducesExternal, to.rollbackDisposition());
    }

    @Transactional
    public Promotion requestPromotion(
            Scope scope,
            PromotionCommand requested,
            GovernedCommand governed) {
        require(scope, Capability.REQUEST_PROMOTION);
        requireActor(scope, governed, false);
        PromotionCommand command = validatePromotion(requested);
        requiredPackage(scope, command.packageId());
        EnvironmentHead source = repository.environmentHead(
                scope, command.sourceEnvironment(), true);
        if (source == null || !command.packageId().equals(source.activePackageId())) {
            throw conflict("The package is not active in the source environment.");
        }
        String requestHash = canonical.sha256(command);
        Promotion prior = repository.priorPromotion(
                scope, scope.actorUserId(), governed.idempotencyKey());
        if (prior != null) {
            String priorHash = repository.promotionRequestHash(scope, prior.promotionId());
            if (!requestHash.equals(priorHash)) {
                throw conflict("The idempotency key is bound to another promotion request.");
            }
            return prior;
        }
        repository.insertPromotion(
                scope, command, governed, requestHash, clock.instant());
        return requiredPromotion(scope, command.promotionId(), false);
    }

    @Transactional
    public Promotion approve(
            Scope scope,
            UUID promotionId,
            long expectedVersion,
            String reviewComment,
            GovernedCommand command) {
        require(scope, Capability.REVIEW_PROMOTION);
        requireActor(scope, command, true);
        if (reviewComment == null || !reviewComment.equals(reviewComment.strip())
                || reviewComment.length() < 10 || reviewComment.length() > 1000) {
            throw invalid("A substantive review comment is required.");
        }
        Promotion current = requiredPromotion(scope, promotionId, true);
        if (current.makerUserId() == scope.actorUserId()) {
            throw new BaseException(ErrorCode.SOD_CONFLICT,
                    "The package maker cannot approve its promotion.");
        }
        if (!repository.approve(
                scope, promotionId, expectedVersion, reviewComment, command, clock.instant())) {
            throw versionConflict();
        }
        repository.journal(scope, promotionId, "PROMOTION_APPROVED",
                scope.actorUserId(), canonical.json(Map.of(
                        "decisionRevision", command.decisionRevision(),
                        "authorizationContextKey", command.authorizationContextKey())),
                clock.instant());
        return requiredPromotion(scope, promotionId, false);
    }

    @Transactional
    public Promotion schedule(
            Scope scope,
            UUID promotionId,
            long expectedVersion,
            Instant scheduledFor,
            GovernedCommand command) {
        require(scope, Capability.ACTIVATE);
        requireActor(scope, command, true);
        Instant now = clock.instant();
        if (scheduledFor == null || scheduledFor.isBefore(now)
                || scheduledFor.isAfter(now.plus(Duration.ofDays(365)))) {
            throw invalid("The activation schedule is invalid.");
        }
        if (!repository.transition(scope, promotionId, expectedVersion,
                List.of("APPROVED"), "SCHEDULED", scheduledFor, null, null, now)) {
            throw versionConflict();
        }
        repository.journal(scope, promotionId, "PROMOTION_SCHEDULED",
                scope.actorUserId(), canonical.json(Map.of("scheduledFor", scheduledFor)), now);
        return requiredPromotion(scope, promotionId, false);
    }

    @Transactional
    public Promotion beginActivation(
            Scope scope,
            UUID promotionId,
            long expectedVersion,
            GovernedCommand command) {
        require(scope, Capability.ACTIVATE);
        requireActor(scope, command, true);
        Promotion current = requiredPromotion(scope, promotionId, true);
        Instant now = clock.instant();
        EnvironmentHead source = repository.environmentHead(
                scope, current.sourceEnvironment(), true);
        if (source == null || !current.packageId().equals(source.activePackageId())) {
            throw conflict("The package is no longer active in the source environment.");
        }
        if ("SCHEDULED".equals(current.status())
                && current.scheduledFor() != null && current.scheduledFor().isAfter(now)) {
            throw conflict("The scheduled activation time has not arrived.");
        }
        if (!repository.transition(scope, promotionId, expectedVersion,
                List.of("APPROVED", "SCHEDULED"), "ACTIVATING",
                null, now, null, now)) {
            throw versionConflict();
        }
        repository.journal(scope, promotionId, "ACTIVATION_STARTED",
                scope.actorUserId(), "{}", now);
        return requiredPromotion(scope, promotionId, false);
    }

    @Transactional
    public Promotion recordActivationEvidence(
            Scope scope,
            UUID promotionId,
            long expectedVersion,
            ExternalHealthEvidenceSubmission submitted,
            GovernedCommand command) {
        require(scope, Capability.RECORD_EXTERNAL_EVIDENCE);
        requireActor(scope, command, true);
        Promotion promotion = requiredPromotion(scope, promotionId, true);
        ExternalHealthEvidence evidence = attestationVerifier.verify(
                scope, promotionId, expectedVersion, submitted);
        validateEvidence(evidence, Set.of("CANARY_HEALTH", "ACTIVATION"));
        if (promotion.activationStartedAt() == null
                || evidence.sourceGeneratedAt().isBefore(promotion.activationStartedAt())) {
            throw conflict("The health evidence predates this activation attempt.");
        }
        String status = switch (evidence.outcome()) {
            case HEALTHY -> "ACTIVE";
            case DEGRADED -> "PARTIAL";
            case UNKNOWN -> "UNKNOWN";
            case FAILED -> "FAILED";
        };
        Instant now = clock.instant();
        repository.insertEvidence(scope, promotionId, evidence, now);
        if (!repository.transition(scope, promotionId, expectedVersion,
                List.of("ACTIVATING", "PARTIAL", "UNKNOWN"), status,
                null, null, now, now)) {
            throw versionConflict();
        }
        if (evidence.outcome() == HealthOutcome.HEALTHY) {
            repository.activateHead(scope, promotion.targetEnvironment(), promotion.packageId(), now);
        }
        repository.journal(scope, promotionId, "ACTIVATION_EVIDENCE_RECORDED",
                scope.actorUserId(), canonical.json(Map.of(
                        "outcome", evidence.outcome(),
                        "externalReference", evidence.externalReference(),
                        "providerSideEffectsReversed", false)), now);
        return requiredPromotion(scope, promotionId, false);
    }

    @Transactional(readOnly = true)
    public RollbackFeasibility rollbackFeasibility(Scope scope, UUID promotionId) {
        require(scope, Capability.VIEW);
        return rollbackFeasibilityUnchecked(scope, promotionId);
    }

    private RollbackFeasibility rollbackFeasibilityUnchecked(
            Scope scope,
            UUID promotionId) {
        Promotion promotion = requiredPromotion(scope, promotionId, false);
        PackageRecord pack = requiredPackage(scope, promotion.packageId());
        EnvironmentHead head = repository.environmentHead(
                scope, promotion.targetEnvironment(), false);
        List<String> conditions = new ArrayList<>();
        if (head == null || !promotion.packageId().equals(head.activePackageId())) {
            conditions.add("PACKAGE_IS_NOT_THE_ACTIVE_ENVIRONMENT_HEAD");
        }
        if (head == null || head.previousPackageId() == null) {
            conditions.add("NO_PREVIOUS_PACKAGE_HEAD");
        }
        if (pack.rollbackDisposition() == RollbackDisposition.IRREVERSIBLE) {
            conditions.add("PACKAGE_DECLARED_IRREVERSIBLE");
        }
        if (pack.assets().stream().anyMatch(Asset::externalSideEffects)) {
            conditions.add("EXTERNAL_SIDE_EFFECTS_REQUIRE_SEPARATE_RECONCILIATION");
        }
        String status = conditions.stream().anyMatch(condition ->
                condition.equals("PACKAGE_IS_NOT_THE_ACTIVE_ENVIRONMENT_HEAD")
                        || condition.equals("NO_PREVIOUS_PACKAGE_HEAD")
                        || condition.equals("PACKAGE_DECLARED_IRREVERSIBLE"))
                ? "BLOCKED" : conditions.isEmpty() ? "ALLOWED" : "CONDITIONAL";
        return new RollbackFeasibility(
                status, head == null ? null : head.activePackageId(),
                head == null ? null : head.previousPackageId(), List.copyOf(conditions),
                false, "NOT_ASSERTED");
    }

    @Transactional
    public Promotion requestRollback(
            Scope scope,
            UUID promotionId,
            long expectedVersion,
            GovernedCommand command) {
        return requestRollback(
                scope, promotionId, expectedVersion,
                "Rollback requested through a governed command.", command);
    }

    @Transactional
    public Promotion requestRollback(
            Scope scope,
            UUID promotionId,
            long expectedVersion,
            String reason,
            GovernedCommand command) {
        require(scope, Capability.REQUEST_ROLLBACK);
        requireActor(scope, command, true);
        if (reason == null || !reason.equals(reason.strip())
                || reason.length() < 10 || reason.length() > 1000) {
            throw invalid("A substantive rollback reason is required.");
        }
        RollbackFeasibility feasibility = rollbackFeasibilityUnchecked(scope, promotionId);
        if ("BLOCKED".equals(feasibility.status())) {
            throw conflict("The package head cannot be rolled back.");
        }
        Instant now = clock.instant();
        if (!repository.transition(scope, promotionId, expectedVersion,
                List.of("ACTIVE", "PARTIAL", "UNKNOWN", "FAILED"),
                "ROLLBACK_PENDING", null, null, null, now)) {
            throw versionConflict();
        }
        repository.journal(scope, promotionId, "ROLLBACK_REQUESTED",
                scope.actorUserId(), canonical.json(Map.of(
                        "reason", reason,
                        "feasibility", feasibility.status(),
                        "externalSideEffectsStatus", "NOT_ASSERTED")), now);
        return requiredPromotion(scope, promotionId, false);
    }

    @Transactional
    public Promotion recordRollbackEvidence(
            Scope scope,
            UUID promotionId,
            long expectedVersion,
            ExternalHealthEvidenceSubmission submitted,
            GovernedCommand command) {
        require(scope, Capability.RECORD_EXTERNAL_EVIDENCE);
        requireActor(scope, command, true);
        Promotion promotion = requiredPromotion(scope, promotionId, true);
        ExternalHealthEvidence evidence = attestationVerifier.verify(
                scope, promotionId, expectedVersion, submitted);
        validateEvidence(evidence, Set.of("ROLLBACK"));
        if (evidence.sourceGeneratedAt().isBefore(promotion.updatedAt())) {
            throw conflict("The rollback evidence predates this rollback attempt.");
        }
        Instant now = clock.instant();
        repository.insertEvidence(scope, promotionId, evidence, now);
        String status = switch (evidence.outcome()) {
            case HEALTHY -> "PACKAGE_HEAD_RESTORED";
            case DEGRADED -> "PARTIAL";
            case UNKNOWN -> "UNKNOWN";
            case FAILED -> "FAILED";
        };
        if (!repository.transition(scope, promotionId, expectedVersion,
                List.of("ROLLBACK_PENDING"), status, null, null, now, now)) {
            throw versionConflict();
        }
        if (evidence.outcome() == HealthOutcome.HEALTHY) {
            repository.restorePreviousHead(scope, promotion.targetEnvironment(), now);
        }
        repository.journal(scope, promotionId, "ROLLBACK_EVIDENCE_RECORDED",
                scope.actorUserId(), canonical.json(Map.of(
                        "outcome", evidence.outcome(),
                        "packageHeadRestored", evidence.outcome() == HealthOutcome.HEALTHY,
                        "externalSideEffectsStatus", "NOT_ASSERTED")), now);
        return requiredPromotion(scope, promotionId, false);
    }

    private PackageCommand validatePackage(PackageCommand command) {
        if (command == null || command.packageId() == null
                || command.packageKey() == null
                || !command.packageKey().matches("[A-Z][A-Z0-9_.-]{2,119}")
                || command.packageVersion() < 1
                || command.displayName() == null || command.displayName().isBlank()
                || !command.displayName().equals(command.displayName().strip())
                || command.displayName().length() > 200
                || command.assets() == null || command.assets().isEmpty()
                || command.assets().size() > 500
                || command.dependencies() == null || command.dependencies().size() > 2_000) {
            throw invalid("The deployment package is invalid.");
        }
        List<Asset> assets = command.assets().stream()
                .sorted(Comparator.comparing(Asset::assetKey)).toList();
        List<Dependency> dependencies = command.dependencies().stream()
                .sorted(Comparator.comparing(Dependency::assetKey)
                        .thenComparing(Dependency::dependsOnAssetKey)).toList();
        validateAssets(assets, dependencies);
        return new PackageCommand(
                command.packageId(), command.packageKey(), command.packageVersion(),
                command.displayName(), assets, dependencies);
    }

    private void validateAssets(List<Asset> assets, List<Dependency> dependencies) {
        Map<String, Asset> byKey = new HashMap<>();
        for (Asset asset : assets) {
            if (asset == null || asset.assetKey() == null
                    || !asset.assetKey().matches("[A-Za-z][A-Za-z0-9_.:-]{1,199}")
                    || asset.assetType() == null || asset.assetId() == null
                    || asset.assetVersion() == null || asset.assetVersion().isBlank()
                    || asset.assetVersion().length() > 80
                    || asset.contentSha256() == null
                    || !asset.contentSha256().matches("[a-f0-9]{64}")
                    || asset.rollbackDisposition() == null
                    || byKey.putIfAbsent(asset.assetKey(), asset) != null) {
                throw invalid("A package asset is invalid or duplicated.");
            }
        }
        Set<String> edges = new HashSet<>();
        for (Dependency dependency : dependencies) {
            Asset source = byKey.get(dependency.assetKey());
            Asset target = byKey.get(dependency.dependsOnAssetKey());
            String edge = dependency.assetKey() + "->" + dependency.dependsOnAssetKey();
            if (source == null || target == null || source == target
                    || !target.contentSha256().equals(dependency.requiredSha256())
                    || !edges.add(edge)) {
                throw invalid("A package dependency is invalid or has a stale digest.");
            }
        }
        detectCycle(byKey.keySet(), dependencies);
    }

    private void detectCycle(Set<String> assets, List<Dependency> dependencies) {
        Map<String, List<String>> graph = dependencies.stream().collect(Collectors.groupingBy(
                Dependency::assetKey,
                Collectors.mapping(Dependency::dependsOnAssetKey, Collectors.toList())));
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String asset : assets) {
            visit(asset, graph, visiting, visited);
        }
    }

    private void visit(
            String asset,
            Map<String, List<String>> graph,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(asset)) {
            return;
        }
        if (!visiting.add(asset)) {
            throw invalid("The package dependency graph contains a cycle.");
        }
        graph.getOrDefault(asset, List.of()).forEach(next -> visit(next, graph, visiting, visited));
        visiting.remove(asset);
        visited.add(asset);
    }

    private PromotionCommand validatePromotion(PromotionCommand command) {
        if (command == null || command.promotionId() == null || command.packageId() == null
                || command.sourceEnvironment() == null || command.targetEnvironment() == null
                || command.sourceEnvironment() == command.targetEnvironment()
                || command.sourceEnvironment() == Environment.PRODUCTION
                || command.sourceEnvironment() == Environment.DEVELOPMENT
                    && command.targetEnvironment() != Environment.TEST
                || command.sourceEnvironment() == Environment.TEST
                    && command.targetEnvironment() != Environment.PRODUCTION) {
            throw invalid("The environment promotion path is invalid.");
        }
        return command;
    }

    private void requireActor(Scope scope, GovernedCommand command, boolean stepUp) {
        Instant now = clock.instant();
        if (command == null || command.actorUserId() != scope.actorUserId()
                || command.idempotencyKey() == null
                || !command.idempotencyKey().matches("[A-Za-z0-9._:-]{1,120}")) {
            throw invalid("The governed command identity is invalid.");
        }
        if (stepUp && (command.authorizationContextKey() == null
                || command.authorizationContextKey().isBlank()
                || command.decisionRevision() == null || command.decisionRevision().isBlank()
                || command.stepUpEvidenceReference() == null
                || !command.stepUpEvidenceReference().matches("verified:[A-Za-z0-9._:-]{8,240}")
                || command.stepUpVerifiedAt() == null || command.stepUpValidUntil() == null
                || command.stepUpVerifiedAt().isAfter(now.plusSeconds(30))
                || command.stepUpVerifiedAt().isBefore(now.minus(MAXIMUM_STEP_UP_AGE))
                || !command.stepUpValidUntil().isAfter(now))) {
            throw new BaseException(
                    ErrorCode.STEP_UP_REQUIRED,
                    "Fresh upstream-verified step-up evidence is required.");
        }
    }

    private void validateEvidence(ExternalHealthEvidence evidence, Set<String> expectedTypes) {
        Instant now = clock.instant();
        if (evidence == null || evidence.evidenceId() == null
                || !expectedTypes.contains(evidence.evidenceType())
                || evidence.outcome() == null
                || evidence.externalReference() == null || evidence.externalReference().isBlank()
                || evidence.externalReference().length() > 500
                || evidence.payloadSha256() == null
                || !evidence.payloadSha256().matches("[a-f0-9]{64}")
                || evidence.sourceGeneratedAt() == null
                || evidence.sourceGeneratedAt().isBefore(now.minus(MAXIMUM_EVIDENCE_AGE))
                || evidence.sourceGeneratedAt().isAfter(now.plusSeconds(60))
                || evidence.verificationReference() == null
                || !evidence.verificationReference().matches("verified:[A-Za-z0-9._:-]{8,240}")) {
            throw invalid("Fresh verified external health evidence is required.");
        }
    }

    private PackageRecord requiredPackage(Scope scope, UUID packageId) {
        PackageRecord value = repository.packageById(scope, packageId);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    private Promotion requiredPromotion(Scope scope, UUID promotionId, boolean lock) {
        Promotion value = repository.promotion(scope, promotionId, lock);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    private RollbackDisposition rollback(List<Asset> assets) {
        if (assets.stream().anyMatch(asset ->
                asset.rollbackDisposition() == RollbackDisposition.IRREVERSIBLE)) {
            return RollbackDisposition.IRREVERSIBLE;
        }
        if (assets.stream().anyMatch(asset -> asset.externalSideEffects()
                || asset.rollbackDisposition() == RollbackDisposition.CONDITIONAL)) {
            return RollbackDisposition.CONDITIONAL;
        }
        return RollbackDisposition.REVERSIBLE;
    }

    private Map<String, Asset> byAssetKey(List<Asset> assets) {
        return assets.stream().collect(Collectors.toMap(Asset::assetKey, Function.identity()));
    }

    private Set<String> dependencyKeys(List<Dependency> dependencies) {
        return dependencies.stream().map(dependency ->
                dependency.assetKey() + "->" + dependency.dependsOnAssetKey()
                        + "@" + dependency.requiredSha256() + ":" + dependency.optional())
                .collect(Collectors.toSet());
    }

    private void require(Scope scope, Capability capability) {
        if (scope == null || !scope.has(capability)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The deployment capability is not available.");
        }
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException versionConflict() {
        return new BaseException(
                ErrorCode.OBJECT_VERSION_CONFLICT,
                "The deployment state changed. Refresh and retry.");
    }

    private static BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }
}
