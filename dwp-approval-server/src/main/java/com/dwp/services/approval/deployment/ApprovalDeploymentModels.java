package com.dwp.services.approval.deployment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ApprovalDeploymentModels {
    private ApprovalDeploymentModels() {
    }

    public enum Capability {
        VIEW,
        CREATE_PACKAGE,
        REQUEST_PROMOTION,
        REVIEW_PROMOTION,
        ACTIVATE,
        RECORD_EXTERNAL_EVIDENCE,
        REQUEST_ROLLBACK
    }

    public record Scope(
            long tenantId,
            String resourceSetKey,
            long actorUserId,
            Set<Capability> capabilities) {
        public Scope {
            if (tenantId <= 0 || actorUserId <= 0
                    || resourceSetKey == null
                    || !resourceSetKey.matches("[A-Z][A-Z0-9_]{2,79}")) {
                throw new IllegalArgumentException("The deployment scope is invalid.");
            }
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }

        public boolean has(Capability capability) {
            return capabilities.contains(capability);
        }
    }

    public enum Environment {
        DEVELOPMENT,
        TEST,
        PRODUCTION
    }

    public enum AssetType {
        FORM,
        WORKFLOW,
        POLICY,
        TEMPLATE
    }

    public enum RollbackDisposition {
        REVERSIBLE,
        CONDITIONAL,
        IRREVERSIBLE
    }

    public enum HealthOutcome {
        HEALTHY,
        DEGRADED,
        UNKNOWN,
        FAILED
    }

    public record Asset(
            String assetKey,
            AssetType assetType,
            UUID assetId,
            String assetVersion,
            String contentSha256,
            RollbackDisposition rollbackDisposition,
            boolean externalSideEffects) {
    }

    public record Dependency(
            String assetKey,
            String dependsOnAssetKey,
            String requiredSha256,
            boolean optional) {
    }

    public record PackageCommand(
            UUID packageId,
            String packageKey,
            int packageVersion,
            String displayName,
            List<Asset> assets,
            List<Dependency> dependencies) {
    }

    public record PackageRecord(
            UUID packageId,
            String packageKey,
            int packageVersion,
            String displayName,
            String manifestSha256,
            RollbackDisposition rollbackDisposition,
            List<Asset> assets,
            List<Dependency> dependencies,
            Instant createdAt,
            long createdBy) {
    }

    public record PackageDiff(
            UUID fromPackageId,
            UUID toPackageId,
            List<String> addedAssets,
            List<String> removedAssets,
            List<String> changedAssets,
            List<String> dependencyChanges,
            boolean introducesExternalSideEffects,
            RollbackDisposition rollbackDisposition) {
    }

    public record PromotionCommand(
            UUID promotionId,
            UUID packageId,
            Environment sourceEnvironment,
            Environment targetEnvironment) {
    }

    public record GovernedCommand(
            long actorUserId,
            String idempotencyKey,
            String authorizationContextKey,
            String decisionRevision,
            String stepUpEvidenceReference,
            Instant stepUpVerifiedAt,
            Instant stepUpValidUntil) {
    }

    public record Promotion(
            UUID promotionId,
            UUID packageId,
            Environment sourceEnvironment,
            Environment targetEnvironment,
            String status,
            long makerUserId,
            Long checkerUserId,
            String reviewComment,
            Instant scheduledFor,
            Instant activationStartedAt,
            Instant completedAt,
            long version,
            Instant updatedAt) {
    }

    public record ExternalHealthEvidence(
            UUID evidenceId,
            String evidenceType,
            HealthOutcome outcome,
            String externalReference,
            String payloadSha256,
            Instant sourceGeneratedAt,
            String verificationReference) {
    }

    public record ExternalHealthEvidenceSubmission(
            UUID evidenceId,
            String evidenceType,
            HealthOutcome outcome,
            String externalReference,
            String payloadSha256,
            Instant sourceGeneratedAt,
            String evidencePayloadBase64Url,
            String evidenceSignatureBase64Url) {
    }

    public record EvidenceRecord(
            ExternalHealthEvidence evidence,
            Instant recordedAt) {
    }

    public record RollbackFeasibility(
            String status,
            UUID activePackageId,
            UUID previousPackageId,
            List<String> conditions,
            boolean externalSideEffectsUndone,
            String externalSideEffectsStatus) {
    }

    public record EnvironmentHead(
            Environment environment,
            UUID activePackageId,
            UUID previousPackageId,
            long version,
            Instant updatedAt) {
    }

    public record DeploymentDashboard(
            Instant generatedAt,
            List<EnvironmentHead> environmentHeads,
            List<Promotion> recentPromotions) {
    }

    public record PromotionDetail(
            Promotion promotion,
            PackageRecord deploymentPackage,
            EnvironmentHead sourceHead,
            EnvironmentHead targetHead,
            List<EvidenceRecord> evidence,
            RollbackFeasibility rollbackFeasibility) {
    }

    public record Manifest(
            String schemaVersion,
            String packageKey,
            int packageVersion,
            List<Asset> assets,
            List<Dependency> dependencies,
            Map<String, Object> assurance) {
    }
}
