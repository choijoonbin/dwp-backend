package com.dwp.services.platform.hrisconfiguration.explorer;

import org.springframework.stereotype.Component;

import java.util.List;

import com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.Module;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

@Component
final class HrisExplorerCommandReceiptOwnerEvidenceProvider
        implements HrisExplorerOwnerEvidenceProvider {

    private final HrisExplorerRepository repository;
    private final HrisExplorerRuntimeControls controls;

    HrisExplorerCommandReceiptOwnerEvidenceProvider(
            HrisExplorerRepository repository,
            HrisExplorerRuntimeControls controls) {
        this.repository = repository;
        this.controls = controls;
    }

    @Override
    public SourceKind sourceKind() {
        return SourceKind.COMMAND_RECEIPT;
    }

    @Override
    public Module module() {
        return Module.SYS;
    }

    @Override
    public ProviderResult findInProgress(ProviderQuery query) {
        HrisExplorerRepository.ReceiptActivityProjection projection =
                repository.findActiveReceiptActivities(
                        query.tenantId(), query.subjectPrincipalPublicId(),
                        query.allowedRouteKeys(), query.asOf());
        if (projection.rows().stream().anyMatch(row ->
                !java.util.Objects.equals(
                        row.authorityPopulationScopeDigest(),
                        query.authorityPopulationScopeDigest())
                        || !java.util.Objects.equals(
                        row.purposeCode(), query.purposeCode())
                        || row.authorityAuthorizationRevision()
                        != query.authorizationRevision()
                        || row.authorityFieldPolicyRevision()
                        != query.fieldPolicyRevision())) {
            throw new HrisExplorerProviderEvidenceException(
                    DegradedReason.STALE,
                    "Receipt authority no longer matches current Explorer authority");
        }
        List<ExplorerActivity> activities = projection.rows().stream()
                .map(row -> activity(row, query, projection.sourceRevision()))
                .filter(java.util.Objects::nonNull)
                .toList();
        return new ProviderResult(
                query.tenantId(), query.subjectPrincipalPublicId(),
                query.contextScopeKey(), query.authorizationRevision(),
                query.fieldPolicyRevision(), projection.sourceRevision(),
                projection.generatedAt(),
                projection.generatedAt().plus(controls.providerFreshness()), activities);
    }

    private ExplorerActivity activity(
            HrisExplorerRepository.ReceiptActivityRow row, ProviderQuery query,
            long sourceRevision) {
        if (row.resultRef() == null || !row.resultRef().startsWith("route:")
                || row.resultRef().length() == "route:".length()) {
            return null;
        }
        String routeKey = row.resultRef().substring("route:".length());
        HrisExplorerCatalog.CatalogNode node = HrisExplorerCatalog.node(routeKey);
        if (node == null || !query.allowedRouteKeys().contains(routeKey)
                || !query.allowedTargets().contains(node.target())) return null;
        ActivityState state = switch (row.status()) {
            case "ACCEPTED" -> ActivityState.PENDING;
            case "RUNNING" -> ActivityState.IN_PROGRESS;
            case "RESULT_UNKNOWN" -> ActivityState.RESULT_UNKNOWN;
            case "FAILED" -> ActivityState.FAILED;
            default -> null;
        };
        if (state == null) return null;
        return new ExplorerActivity(
                row.receiptId(), SourceKind.COMMAND_RECEIPT, Module.SYS, state,
                sourceRevision, row.updatedAt(), node.target());
    }
}
