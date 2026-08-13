package com.dwp.services.provider.governance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DataPolicyService {

    private static final String READ = "DATA_GOVERNANCE_READ";
    private static final String WRITE = "DATA_GOVERNANCE_WRITE";
    private static final String APPROVE = "DATA_GOVERNANCE_APPROVE";
    private static final Set<String> CLASSIFICATIONS =
            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Set<String> DELETION_MODES =
            Set.of("SOFT_DELETE", "HARD_DELETE", "ANONYMIZE");
    private static final Set<String> RESTRICTED_HANDLING =
            Set.of("MASK", "DENY", "TOKENIZE");

    private final DataPolicyRepository repository;
    private final DataGovernanceService governanceService;
    private final ProviderAuditService audit;
    private final ObjectMapper objectMapper;

    public DataPolicyService(
            DataPolicyRepository repository,
            DataGovernanceService governanceService,
            ProviderAuditService audit,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.governanceService = governanceService;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<DataPolicyDtos.Policy> policies() {
        ProviderRequestContext.requirePermission(READ);
        return repository.policies().stream().map(this::policy).toList();
    }

    @Transactional
    public DataPolicyDtos.Policy create(
            DataPolicyDtos.CreatePolicyRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        validateScope(request.scopeType(), request.scopeRef());
        validateRule(request.policyType(), request.scopeType(), request.policyRule());
        validateValidity(request.effectiveFrom(), request.effectiveTo());
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        UUID policyId = UUID.randomUUID();
        DataPolicyRepository.PolicyRow created;
        try {
            created = repository.createPolicy(policyId, request, actor.operatorId());
            repository.createRevision(
                    created,
                    UUID.randomUUID(),
                    request.policyRule(),
                    request.justification(),
                    request.effectiveFrom(),
                    request.effectiveTo(),
                    null,
                    actor.operatorId());
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The data policy key already exists or violates the policy contract.",
                    exception);
        }
        audit.success(
                "provider.data-governance.policy-created",
                "DATA_POLICY",
                policyId.toString(),
                correlationId,
                policySnapshot(created));
        return policy(created);
    }

    @Transactional
    public DataPolicyDtos.Revision createRevision(
            UUID policyId,
            DataPolicyDtos.CreateRevisionRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        DataPolicyRepository.PolicyRow policy = requirePolicy(policyId);
        validateRule(policy.policyType(), policy.scopeType(), request.policyRule());
        validateValidity(request.effectiveFrom(), request.effectiveTo());
        DataPolicyRepository.RevisionRow created = repository.createRevision(
                policy,
                UUID.randomUUID(),
                request.policyRule(),
                request.justification(),
                request.effectiveFrom(),
                request.effectiveTo(),
                null,
                ProviderRequestContext.require().operatorId());
        audit.success(
                "provider.data-governance.revision-drafted",
                "DATA_POLICY_REVISION",
                created.revisionId().toString(),
                correlationId,
                revisionSnapshot(policy, created));
        return revision(created);
    }

    @Transactional
    public DataPolicyDtos.Revision preview(
            UUID revisionId,
            DataPolicyDtos.VersionedReasonRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        DataPolicyRepository.RevisionRow before = requireRevision(revisionId);
        DataPolicyRepository.PolicyRow policy = requirePolicy(before.policyId());
        if (!"DRAFT".equals(before.lifecycleState())) {
            throw invalidState("Only a draft revision can refresh its impact preview.");
        }
        DataPolicyDtos.ImpactPreview impact = computeImpact(policy, before);
        DataPolicyRepository.RevisionRow saved = repository.saveImpact(
                revisionId, request.version(), impact);
        if (saved == null) {
            throw conflict("The data policy revision changed before impact was saved.");
        }
        audit.success(
                "provider.data-governance.impact-previewed",
                "DATA_POLICY_REVISION",
                revisionId.toString(),
                correlationId,
                Map.of(
                        "policy", policySnapshot(policy),
                        "affectedAssets", impact.affectedAssetCount(),
                        "blockers", impact.blockers(),
                        "warnings", impact.warnings(),
                        "impactHash", impact.impactHash(),
                        "reason", request.reason()));
        return revision(saved);
    }

    @Transactional
    public DataPolicyDtos.Revision submit(
            UUID revisionId,
            DataPolicyDtos.VersionedReasonRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        DataPolicyRepository.RevisionRow before = requireRevision(revisionId);
        if (!repository.submit(
                revisionId, request.version(), ProviderRequestContext.require().operatorId())) {
            throw conflict(
                    "A fresh blocker-free impact preview is required, or the revision changed.");
        }
        DataPolicyRepository.RevisionRow result = requireRevision(revisionId);
        audit.success(
                "provider.data-governance.revision-submitted",
                "DATA_POLICY_REVISION",
                revisionId.toString(),
                correlationId,
                Map.of("before", revisionSnapshot(requirePolicy(before.policyId()), before),
                        "after", revisionSnapshot(requirePolicy(result.policyId()), result),
                        "reason", request.reason()));
        return revision(result);
    }

    @Transactional
    public DataPolicyDtos.Revision decide(
            UUID revisionId,
            DataPolicyDtos.ApprovalDecisionRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(APPROVE);
        DataPolicyRepository.RevisionRow before = requireRevision(revisionId);
        Long actorId = ProviderRequestContext.require().operatorId();
        if (actorId.equals(before.requestedBy())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "A data policy requester cannot decide the same revision.");
        }
        if (!repository.decide(
                revisionId, request.version(), request.decision(), request.reason(), actorId)) {
            throw conflict("The approval changed or cannot be decided by this operator.");
        }
        DataPolicyRepository.RevisionRow result = requireRevision(revisionId);
        audit.success(
                "provider.data-governance.revision-"
                        + request.decision().toLowerCase().replace('_', '-'),
                "DATA_POLICY_REVISION",
                revisionId.toString(),
                correlationId,
                Map.of("before", revisionSnapshot(requirePolicy(before.policyId()), before),
                        "after", revisionSnapshot(requirePolicy(result.policyId()), result),
                        "reason", request.reason()));
        return revision(result);
    }

    @Transactional
    public DataPolicyDtos.Revision publish(
            UUID revisionId,
            DataPolicyDtos.VersionedReasonRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        DataPolicyRepository.RevisionRow before = requireRevision(revisionId);
        DataPolicyRepository.PolicyRow policy = repository.lockPolicy(before.policyId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        DataPolicyDtos.ImpactPreview currentImpact = computeImpact(policy, before);
        if (before.impactHash() == null || !before.impactHash().equals(currentImpact.impactHash())) {
            throw conflict(
                    "The governed catalog changed after approval. Create a new revision and preview impact again.");
        }
        if (!currentImpact.blockers().isEmpty()) {
            throw invalidState("The approved revision now has blocking governance conflicts.");
        }
        if (!repository.publish(policy.policyId(), revisionId, request.version())) {
            throw conflict("The approved revision changed or cannot be published.");
        }
        DataPolicyRepository.RevisionRow result = requireRevision(revisionId);
        audit.success(
                "provider.data-governance.revision-published",
                "DATA_POLICY_REVISION",
                revisionId.toString(),
                correlationId,
                Map.of("before", revisionSnapshot(policy, before),
                        "after", revisionSnapshot(policy, result),
                        "impactHash", currentImpact.impactHash(),
                        "reason", request.reason()));
        return revision(result);
    }

    @Transactional
    public DataPolicyDtos.Revision requestRollback(
            UUID revisionId,
            DataPolicyDtos.VersionedReasonRequest request,
            String correlationId) {
        ProviderRequestContext.requirePermission(WRITE);
        DataPolicyRepository.RevisionRow active = requireRevision(revisionId);
        if (!"ACTIVE".equals(active.lifecycleState()) || active.previousRevisionId() == null) {
            throw invalidState("Only an active revision with a predecessor can request rollback.");
        }
        if (active.version() != request.version()) {
            throw conflict("The active policy revision changed. Refresh before requesting rollback.");
        }
        DataPolicyRepository.RevisionRow previous = repository.revision(active.previousRevisionId())
                .orElseThrow(() -> invalidState("The previous policy revision is unavailable."));
        DataPolicyRepository.PolicyRow policy = repository.lockPolicy(active.policyId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        DataPolicyRepository.RevisionRow rollback = repository.createRevision(
                policy,
                UUID.randomUUID(),
                previous.rule(),
                request.reason(),
                Instant.now(),
                previous.effectiveTo(),
                active.revisionId(),
                ProviderRequestContext.require().operatorId());
        audit.success(
                "provider.data-governance.rollback-requested",
                "DATA_POLICY_REVISION",
                rollback.revisionId().toString(),
                correlationId,
                Map.of(
                        "activeRevision", active.revisionId(),
                        "restoredFromRevision", previous.revisionId(),
                        "rollbackDraft", rollback.revisionId(),
                        "reason", request.reason()));
        return revision(rollback);
    }

    DataPolicyDtos.ImpactPreview computeImpact(
            DataPolicyRepository.PolicyRow policy,
            DataPolicyRepository.RevisionRow revision) {
        DataGovernanceDtos.Snapshot snapshot = governanceService.snapshot();
        List<DataGovernanceDtos.DataAsset> scoped = snapshot.assets().stream()
                .filter(asset -> !"PARTITION".equals(asset.objectType()))
                .filter(asset -> !"SYSTEM_TABLE".equals(asset.objectType()))
                .filter(asset -> matchesScope(
                        policy.scopeType(), policy.scopeRef(), asset.assetKey(), asset.databaseKey()))
                .toList();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> controls = new ArrayList<>();
        if (scoped.isEmpty()) blockers.add("SCOPE_HAS_NO_LIVE_ASSETS");

        List<DataGovernanceDtos.DataAsset> affected = switch (policy.policyType()) {
            case "RESTRICTED_FIELD" -> fieldScopedAssets(
                    scoped, textArray(revision.rule(), "fields"), blockers, "RESTRICTED_FIELDS_NOT_FOUND");
            case "MINIMIZATION" -> fieldScopedAssets(
                    scoped, textArray(revision.rule(), "allowedFields"), blockers,
                    "MINIMIZATION_FIELDS_NOT_FOUND");
            case "TENANT_RLS" -> tenantRlsAssets(scoped, revision.rule(), blockers, warnings);
            default -> scoped;
        };

        switch (policy.policyType()) {
            case "CLASSIFICATION" -> controls.add(
                    "CLASSIFY:" + revision.rule().path("classification").asText());
            case "MINIMIZATION" -> controls.add("ALLOW_FIELDS_ONLY");
            case "RESIDENCY" -> {
                controls.add("RESIDENCY_ALLOW_LIST");
                warnings.add("RUNTIME_RESIDENCY_ADAPTER_REQUIRES_R3_04");
            }
            case "RETENTION" -> {
                controls.add("RETENTION_SCHEDULE");
                legalHoldConflicts(policy, blockers);
            }
            case "DELETION" -> {
                controls.add("DELETION_WORKFLOW");
                legalHoldConflicts(policy, blockers);
                warnings.add("DELETION_WORKER_REQUIRES_APPROVED_INFRASTRUCTURE");
            }
            case "LEGAL_HOLD" -> controls.add("LEGAL_HOLD_PRECEDENCE");
            case "RESTRICTED_FIELD" -> controls.add(
                    "FIELD_" + revision.rule().path("handling").asText());
            case "TENANT_RLS" -> controls.add("DATABASE_RLS_REQUIRED");
            default -> throw invalid("Unsupported data policy type.");
        }
        List<String> assetKeys = affected.stream()
                .map(DataGovernanceDtos.DataAsset::assetKey)
                .sorted()
                .toList();
        String fingerprint = policy.policyType() + "|" + policy.scopeType() + "|"
                + policy.scopeRef() + "|" + canonical(revision.rule()) + "|"
                + affected.stream()
                        .sorted(Comparator.comparing(DataGovernanceDtos.DataAsset::assetKey))
                        .map(asset -> asset.assetKey() + ":" + asset.tenantScoped() + ":"
                                + asset.columns().stream()
                                        .map(column -> column.name() + ":" + column.dataType())
                                        .sorted().toList())
                        .toList()
                + "|" + blockers.stream().sorted().toList()
                + "|" + warnings.stream().sorted().toList()
                + "|" + controls.stream().sorted().toList();
        Instant now = Instant.now();
        return new DataPolicyDtos.ImpactPreview(
                snapshot.generatedAt(),
                affected.size(),
                assetKeys,
                blockers.stream().distinct().sorted().toList(),
                warnings.stream().distinct().sorted().toList(),
                controls.stream().distinct().sorted().toList(),
                hash(fingerprint),
                now,
                blockers.isEmpty());
    }

    private List<DataGovernanceDtos.DataAsset> fieldScopedAssets(
            List<DataGovernanceDtos.DataAsset> assets,
            List<String> fields,
            List<String> blockers,
            String emptyCode) {
        List<DataGovernanceDtos.DataAsset> affected = assets.stream()
                .filter(asset -> asset.columns().stream()
                        .anyMatch(column -> fields.contains(column.name())))
                .toList();
        if (affected.isEmpty()) blockers.add(emptyCode);
        return affected;
    }

    private List<DataGovernanceDtos.DataAsset> tenantRlsAssets(
            List<DataGovernanceDtos.DataAsset> assets,
            JsonNode rule,
            List<String> blockers,
            List<String> warnings) {
        List<String> tenantColumns = textArray(rule, "tenantColumns");
        List<DataGovernanceDtos.DataAsset> tenantAssets = assets.stream()
                .filter(DataGovernanceDtos.DataAsset::tenantScoped)
                .toList();
        if (tenantAssets.isEmpty()) blockers.add("NO_TENANT_SCOPED_ASSETS");
        tenantAssets.stream()
                .filter(asset -> asset.columns().stream()
                        .noneMatch(column -> tenantColumns.contains(column.name())))
                .forEach(asset -> blockers.add("TENANT_COLUMN_MISSING:" + asset.assetKey()));
        long excluded = assets.size() - tenantAssets.size();
        if (excluded > 0) warnings.add("NON_TENANT_ASSETS_EXCLUDED:" + excluded);
        return tenantAssets;
    }

    private void legalHoldConflicts(
            DataPolicyRepository.PolicyRow policy,
            List<String> blockers) {
        repository.activePolicies("LEGAL_HOLD").stream()
                .filter(hold -> hold.rule().path("active").asBoolean(false))
                .filter(hold -> scopesOverlap(
                        policy.scopeType(), policy.scopeRef(), hold.scopeType(), hold.scopeRef()))
                .forEach(hold -> blockers.add("ACTIVE_LEGAL_HOLD:" + hold.revisionId()));
    }

    private boolean scopesOverlap(String firstType, String firstRef, String secondType, String secondRef) {
        if ("GLOBAL".equals(firstType) || "GLOBAL".equals(secondType)) return true;
        if ("DATABASE".equals(firstType) && "DATABASE".equals(secondType)) {
            return firstRef.equals(secondRef);
        }
        if ("ASSET".equals(firstType) && "ASSET".equals(secondType)) {
            return firstRef.equals(secondRef);
        }
        String database = "DATABASE".equals(firstType) ? firstRef : secondRef;
        String asset = "ASSET".equals(firstType) ? firstRef : secondRef;
        return asset.startsWith(database + ".");
    }

    private boolean matchesScope(
            String scopeType,
            String scopeRef,
            String assetKey,
            String databaseKey) {
        return switch (scopeType) {
            case "GLOBAL" -> true;
            case "DATABASE" -> databaseKey.equals(scopeRef);
            case "ASSET" -> assetKey.equals(scopeRef);
            default -> false;
        };
    }

    private void validateScope(String scopeType, String scopeRef) {
        boolean global = "GLOBAL".equals(scopeType);
        if (global != (scopeRef == null || scopeRef.isBlank())) {
            throw invalid("Global policies cannot have a scope reference; scoped policies require one.");
        }
    }

    private void validateValidity(Instant from, Instant to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw invalid("The policy effective end must be after its start.");
        }
    }

    private void validateRule(String policyType, String scopeType, JsonNode rule) {
        if (!rule.isObject()) throw invalid("A data policy rule must be a JSON object.");
        switch (policyType) {
            case "CLASSIFICATION" -> requireEnum(
                    rule, "classification", CLASSIFICATIONS);
            case "MINIMIZATION" -> {
                if (!"ASSET".equals(scopeType)) {
                    throw invalid("Minimization policies must target one governed asset.");
                }
                requireTextArray(rule, "allowedFields");
                requireText(rule, "purpose");
            }
            case "RESIDENCY" -> requireTextArray(rule, "allowedRegions");
            case "RETENTION" -> requirePositive(rule, "retentionDays");
            case "DELETION" -> {
                requirePositive(rule, "deletionSlaDays");
                requireEnum(rule, "mode", DELETION_MODES);
            }
            case "LEGAL_HOLD" -> {
                requireText(rule, "holdKey");
                if (!rule.has("active") || !rule.get("active").isBoolean()) {
                    throw invalid("Legal hold rules require a boolean active value.");
                }
            }
            case "RESTRICTED_FIELD" -> {
                requireTextArray(rule, "fields");
                requireEnum(rule, "handling", RESTRICTED_HANDLING);
            }
            case "TENANT_RLS" -> {
                requireTextArray(rule, "tenantColumns");
                if (!"REQUIRED".equals(rule.path("enforcement").asText())) {
                    throw invalid("Tenant RLS enforcement must be REQUIRED.");
                }
            }
            default -> throw invalid("Unsupported data policy type.");
        }
    }

    private void requireText(JsonNode rule, String field) {
        if (!rule.hasNonNull(field) || !rule.get(field).isTextual()
                || rule.get(field).asText().isBlank()) {
            throw invalid("The data policy rule requires a non-empty " + field + ".");
        }
    }

    private void requirePositive(JsonNode rule, String field) {
        if (!rule.has(field) || !rule.get(field).canConvertToInt()
                || rule.get(field).asInt() <= 0) {
            throw invalid("The data policy rule requires a positive " + field + ".");
        }
    }

    private void requireEnum(JsonNode rule, String field, Set<String> values) {
        requireText(rule, field);
        if (!values.contains(rule.get(field).asText())) {
            throw invalid("The data policy rule contains an unsupported " + field + ".");
        }
    }

    private void requireTextArray(JsonNode rule, String field) {
        JsonNode node = rule.get(field);
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw invalid("The data policy rule requires a non-empty " + field + " array.");
        }
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw invalid("Every " + field + " value must be non-empty text.");
            }
        }
    }

    private List<String> textArray(JsonNode rule, String field) {
        List<String> values = new ArrayList<>();
        rule.path(field).forEach(value -> values.add(value.asText()));
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private DataPolicyDtos.Policy policy(DataPolicyRepository.PolicyRow row) {
        return new DataPolicyDtos.Policy(
                row.policyId(), row.policyKey(), row.displayName(), row.description(),
                row.policyType(), row.scopeType(), row.scopeRef(), row.ownerService(),
                row.lifecycleState(), row.version(),
                repository.revisions(row.policyId()).stream().map(this::revision).toList());
    }

    private DataPolicyDtos.Revision revision(DataPolicyRepository.RevisionRow row) {
        DataPolicyDtos.ImpactPreview impact = row.impact() == null
                ? null
                : convert(row.impact(), DataPolicyDtos.ImpactPreview.class);
        DataPolicyDtos.Approval approval = repository.approval(row.revisionId())
                .map(item -> new DataPolicyDtos.Approval(
                        item.approvalId(), item.lifecycleState(), item.requestedBy(),
                        item.requestedAt(), item.decidedBy(), item.decidedAt(), item.reason()))
                .orElse(null);
        return new DataPolicyDtos.Revision(
                row.revisionId(), row.revisionNumber(), row.lifecycleState(), row.rule(),
                row.effectiveFrom(), row.effectiveTo(), row.justification(),
                row.previousRevisionId(), row.rollbackOfRevisionId(), impact,
                row.requestedBy(), row.approvedBy(), row.submittedAt(), row.approvedAt(),
                row.publishedAt(), row.version(), approval);
    }

    private DataPolicyRepository.PolicyRow requirePolicy(UUID policyId) {
        return repository.policy(policyId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private DataPolicyRepository.RevisionRow requireRevision(UUID revisionId) {
        return repository.revision(revisionId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private Map<String, Object> policySnapshot(DataPolicyRepository.PolicyRow row) {
        return Map.of(
                "policyKey", row.policyKey(),
                "policyType", row.policyType(),
                "scopeType", row.scopeType(),
                "scopeRef", row.scopeRef() == null ? "" : row.scopeRef(),
                "ownerService", row.ownerService());
    }

    private Map<String, Object> revisionSnapshot(
            DataPolicyRepository.PolicyRow policy,
            DataPolicyRepository.RevisionRow revision) {
        return Map.ofEntries(
                Map.entry("policyKey", policy.policyKey()),
                Map.entry("revisionNumber", revision.revisionNumber()),
                Map.entry("lifecycleState", revision.lifecycleState()),
                Map.entry("ruleHash", hash(canonical(revision.rule()))),
                Map.entry("impactHash", revision.impactHash() == null ? "" : revision.impactHash()),
                Map.entry("version", revision.version()));
    }

    private <T> T convert(JsonNode node, Class<T> type) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored data policy impact is invalid.", exception);
        }
    }

    private String canonical(JsonNode node) {
        try {
            return objectMapper.writer().withDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Data policy canonicalization failed.", exception);
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private BaseException invalidState(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
