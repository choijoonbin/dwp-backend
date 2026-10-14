package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerReceiptAuthoritySource.CurrentExplorerReceiptAuthority;

@Component
final class HrisExplorerOwnerEvidenceAdapter {

    static final String SNAPSHOT_ATTRIBUTE =
            HrisExplorerOwnerEvidenceAdapter.class.getName() + ".authoritySnapshot";
    private static final String PURPOSE = "HRIS_APP_DISCOVERY";
    private static final java.util.Set<String> EXPLORER_ACTIONS = java.util.Set.of(
            JdbcHrisExplorerRepository.PREFERENCES_ACTION,
            JdbcHrisExplorerRepository.RECENT_ACTION);

    private final ObjectProvider<HrisExplorerAuthoritySource> authoritySources;
    private final ObjectProvider<HrisExplorerReceiptAuthoritySource>
            receiptAuthoritySources;
    private final HrisExplorerRepository repository;
    private final Clock clock;

    @Autowired
    HrisExplorerOwnerEvidenceAdapter(
            ObjectProvider<HrisExplorerAuthoritySource> authoritySources,
            ObjectProvider<HrisExplorerReceiptAuthoritySource> receiptAuthoritySources,
            HrisExplorerRepository repository) {
        this(authoritySources, receiptAuthoritySources,
                repository, Clock.systemUTC());
    }

    HrisExplorerOwnerEvidenceAdapter(
            ObjectProvider<HrisExplorerAuthoritySource> authoritySources,
            ObjectProvider<HrisExplorerReceiptAuthoritySource> receiptAuthoritySources,
            HrisExplorerRepository repository,
            Clock clock) {
        this.authoritySources = authoritySources;
        this.receiptAuthoritySources = receiptAuthoritySources;
        this.repository = repository;
        this.clock = clock;
    }

    Optional<ResolvedEvidence> resolve(
            AuthorityRequest request, UUID receiptId) {
        Optional<ResolvedEvidence> requestEvidence = resolveRequest(request);
        if (requestEvidence.isEmpty() || receiptId == null) return requestEvidence;
        return resolveReceipt(request, receiptId, requestEvidence.get());
    }

    Optional<ResolvedEvidence> resolveRequest(AuthorityRequest request) {
        List<HrisExplorerAuthoritySource> sources = authoritySources.orderedStream().toList();
        if (sources.size() != 1) return Optional.empty();
        Optional<AuthoritySnapshot> loaded = sources.getFirst().resolve(request);
        if (loaded.isEmpty()) return Optional.empty();
        AuthoritySnapshot source = loaded.get();
        if (!validCore(source, request)) return Optional.empty();

        Map<String, HrisExplorerDtos.ExplorerAuthorization> admitted = new LinkedHashMap<>();
        source.allowedRouteDecisions().forEach((routeKey, decision) -> {
            HrisExplorerCatalog.CatalogNode node = HrisExplorerCatalog.node(routeKey);
            if (node != null && HrisExplorerV43RouteAdapter.exactDecision(routeKey, decision)
                    && (node.target().kind() != HrisExplorerDtos.TargetKind.DWP_CONTROL
                    || HrisExplorerV43RouteAdapter.OPERATIONAL_DWP_CONTROL_ROUTE_KEY
                    .equals(routeKey))) {
                admitted.put(routeKey, decision);
            }
        });
        AuthoritySnapshot snapshot = new AuthoritySnapshot(
                source.tenantId(), source.subjectPrincipalPublicId(),
                source.currentDecisionRevision(), source.contextKey(),
                source.contextScopeKey(), source.purposeCode(),
                source.populationScopeDigest(), source.fieldPolicyRevision(),
                source.authorizationRevision(), source.entitlementRevision(),
                source.projectionRevision(), source.freshUntil(), Map.copyOf(admitted));
        HcmPlatformPepRegistry.ExplorerOwnerEvidence owner = owner(
                snapshot, admitted, null);
        return Optional.of(new ResolvedEvidence(snapshot, owner));
    }

    Optional<ResolvedEvidence> resolveReceipt(
            AuthorityRequest request,
            UUID receiptId,
            ResolvedEvidence requestEvidence) {
        if (receiptId == null || requestEvidence == null
                || !validCore(requestEvidence.snapshot(), request)) {
            return Optional.empty();
        }
        Optional<HrisExplorerRepository.ReceiptAuthority> receipt =
                repository.findReceiptAuthority(
                        request.tenantId(), request.subjectPrincipalPublicId(), receiptId);
        List<HrisExplorerReceiptAuthoritySource> originSources =
                receiptAuthoritySources.orderedStream().toList();
        if (receipt.isEmpty() || originSources.size() != 1) {
            return Optional.empty();
        }
        Optional<CurrentExplorerReceiptAuthority> currentOrigin =
                originSources.getFirst().resolveExplorerOrigin(
                        request, receiptId, receipt.get());
        if (currentOrigin.isEmpty() || !validReceiptContext(
                receipt.get(), requestEvidence.snapshot(), currentOrigin.get())) {
            return Optional.empty();
        }
        HcmPlatformPepRegistry.ExplorerReceiptEvidence receiptEvidence =
                new HcmPlatformPepRegistry.ExplorerReceiptEvidence(
                        receipt.get().subjectPrincipalPublicId().toString(),
                        receipt.get().originatingAction(),
                        requestEvidence.snapshot().contextKey(),
                        requestEvidence.snapshot().contextScopeKey());
        Map<String, HrisExplorerDtos.ExplorerAuthorization> admitted =
                requestEvidence.snapshot().allowedRouteDecisions();
        return Optional.of(new ResolvedEvidence(
                requestEvidence.snapshot(), owner(
                requestEvidence.snapshot(), admitted, receiptEvidence)));
    }

    private HcmPlatformPepRegistry.ExplorerOwnerEvidence owner(
            AuthoritySnapshot snapshot,
            Map<String, HrisExplorerDtos.ExplorerAuthorization> admitted,
            HcmPlatformPepRegistry.ExplorerReceiptEvidence receiptEvidence) {
        return new HcmPlatformPepRegistry.ExplorerOwnerEvidence(
                snapshot.currentDecisionRevision(), snapshot.contextKey(),
                snapshot.contextScopeKey(), snapshot.purposeCode(),
                snapshot.subjectPrincipalPublicId().toString(),
                snapshot.fieldPolicyRevision(), snapshot.authorizationRevision(),
                admitted.values().stream()
                        .map(HrisExplorerDtos.ExplorerAuthorization::routeContractKey)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                receiptEvidence);
    }

    private boolean validReceiptContext(
            HrisExplorerRepository.ReceiptAuthority receipt,
            AuthoritySnapshot snapshot,
            CurrentExplorerReceiptAuthority current) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return snapshot.subjectPrincipalPublicId().equals(
                receipt.subjectPrincipalPublicId())
                && EXPLORER_ACTIONS.contains(receipt.originatingAction())
                && PURPOSE.equals(receipt.purposeCode())
                && expectedOriginRoute(receipt.originatingAction()).equals(
                receipt.originRouteContractKey())
                && current.tenantId() == snapshot.tenantId()
                && snapshot.subjectPrincipalPublicId().equals(
                current.subjectPrincipalPublicId())
                && receipt.originatingAction().equals(current.originatingAction())
                && current.authorityPopulationScopeDigest() != null
                && current.authorityPopulationScopeDigest().matches("^[0-9a-f]{64}$")
                && receipt.authorityPopulationScopeDigest().equals(
                current.authorityPopulationScopeDigest())
                && current.fieldPolicyRevision() > 0
                && receipt.authorityFieldPolicyRevision()
                == current.fieldPolicyRevision()
                && PURPOSE.equals(current.purposeCode())
                && receipt.purposeCode().equals(current.purposeCode())
                && current.authorizationRevision() > 0
                && receipt.authorityAuthorizationRevision()
                == current.authorizationRevision()
                && java.util.Objects.equals(receipt.originRouteContractKey(),
                current.originRouteContractKey())
                && java.util.Objects.equals(receipt.originContextKey(),
                current.originContextKey())
                && java.util.Objects.equals(receipt.originContextScopeKey(),
                current.originContextScopeKey())
                && java.util.Objects.equals(receipt.originDecisionRevision(),
                current.originDecisionRevision())
                && current.freshUntil() != null
                && current.freshUntil().isAfter(now);
    }

    private boolean validCore(
            AuthoritySnapshot snapshot, AuthorityRequest request) {
        return snapshot.tenantId() == request.tenantId()
                && request.subjectPrincipalPublicId().equals(
                snapshot.subjectPrincipalPublicId())
                && request.currentDecisionRevision().equals(
                snapshot.currentDecisionRevision())
                && request.contextKey().equals(snapshot.contextKey())
                && request.contextScopeKey().equals(snapshot.contextScopeKey())
                && PURPOSE.equals(snapshot.purposeCode())
                && snapshot.populationScopeDigest() != null
                && snapshot.populationScopeDigest().matches("^[0-9a-f]{64}$")
                && snapshot.fieldPolicyRevision() > 0
                && snapshot.authorizationRevision() > 0
                && snapshot.entitlementRevision() > 0
                && snapshot.entitlementRevision()
                <= HrisExplorerAuthoritySeals.MAX_JSON_SAFE_INTEGER
                && snapshot.projectionRevision() > 0
                && snapshot.projectionRevision()
                <= HrisExplorerAuthoritySeals.MAX_JSON_SAFE_INTEGER
                && snapshot.freshUntil() != null
                && snapshot.freshUntil().isAfter(OffsetDateTime.ofInstant(
                clock.instant(), ZoneOffset.UTC));
    }

    private String expectedOriginRoute(String action) {
        if (JdbcHrisExplorerRepository.PREFERENCES_ACTION.equals(action)) {
            return "route.hcm.utility.explorer-preferences-update.action";
        }
        if (JdbcHrisExplorerRepository.RECENT_ACTION.equals(action)) {
            return "route.hcm.utility.explorer-recent-create.action";
        }
        return "";
    }

    record ResolvedEvidence(
            AuthoritySnapshot snapshot,
            HcmPlatformPepRegistry.ExplorerOwnerEvidence ownerEvidence) {
    }
}
