package com.dwp.services.platform.hrisconfiguration.explorer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;

interface HrisExplorerRepository {

    ExplorerState readAndPurge(
            long tenantId, UUID subjectPrincipalPublicId,
            Set<String> allowedRouteKeys, OffsetDateTime now);

    HrisExplorerReceipt replaceFavorites(
            CommandContext context, long expectedRevision, List<String> routeKeys,
            Set<String> allowedRouteKeys, String requestDigest, OffsetDateTime now);

    HrisExplorerReceipt recordRecent(
            CommandContext context, String routeKey, Set<String> allowedRouteKeys,
            String requestDigest, OffsetDateTime now);

    Optional<HrisExplorerReceipt> findReceipt(
            long tenantId, UUID subjectPrincipalPublicId, UUID receiptId);

    Optional<ReceiptAuthority> findReceiptAuthority(
            long tenantId, UUID subjectPrincipalPublicId, UUID receiptId);

    ReceiptActivityProjection findActiveReceiptActivities(
            long tenantId, UUID subjectPrincipalPublicId,
            Set<String> allowedRouteKeys, OffsetDateTime asOf);

    record FavoriteRow(String routeKey, int position) {
    }

    record RecentRow(String routeKey, OffsetDateTime lastVisitedAt, OffsetDateTime expiresAt) {
    }

    record ExplorerState(
            long preferenceRevision,
            List<FavoriteRow> favorites,
            List<RecentRow> recents) {

        public ExplorerState {
            favorites = List.copyOf(favorites);
            recents = List.copyOf(recents);
        }
    }

    record CommandContext(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            String authorityPopulationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision,
            String originRouteContractKey,
            String originContextKey,
            String originContextScopeKey,
            String originDecisionRevision,
            UUID correlationId,
            String idempotencyKey,
            String originatingAction) {
    }

    record ReceiptAuthority(
            UUID subjectPrincipalPublicId,
            String originatingAction,
            String populationScopeDigest,
            String authorityPopulationScopeDigest,
            long fieldPolicyRevision,
            long authorityFieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            long authorityAuthorizationRevision,
            String originRouteContractKey,
            String originContextKey,
            String originContextScopeKey,
            String originDecisionRevision) {
    }

    record ReceiptActivityRow(
            UUID receiptId,
            String status,
            String resultRef,
            String authorityPopulationScopeDigest,
            String purposeCode,
            long authorityAuthorizationRevision,
            long authorityFieldPolicyRevision,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
    }

    record ReceiptActivityProjection(
            long sourceRevision,
            OffsetDateTime generatedAt,
            List<ReceiptActivityRow> rows) {

        public ReceiptActivityProjection {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }
}
