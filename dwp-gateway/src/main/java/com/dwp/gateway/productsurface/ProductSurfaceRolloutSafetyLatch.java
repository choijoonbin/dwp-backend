package com.dwp.gateway.productsurface;

import reactor.core.publisher.Mono;

/**
 * Durable, tenant-scoped safety latch for the shared Product Surface rollout axes.
 *
 * <p>The latch is deliberately independent of the short-lived evaluation cache. A caller can
 * therefore recover the last authoritative S/E state after a process restart or Provider
 * evaluation failure without turning an already-approved enforcement decision off.
 */
public interface ProductSurfaceRolloutSafetyLatch {

    Mono<LoadResult> load(long authTenantId);

    Mono<ApprovalResult> approve(
            long authTenantId,
            FeatureRolloutDecisionCache.FlagDecision shadow,
            FeatureRolloutDecisionCache.FlagDecision enforcement);

    enum LoadStatus {
        FOUND,
        MISSING,
        CORRUPT,
        UNAVAILABLE
    }

    enum ApprovalStatus {
        CREATED,
        UPDATED,
        UNCHANGED,
        OUT_OF_ORDER,
        REVISION_CONFLICT,
        INVALID_DECISION,
        CORRUPT,
        UNAVAILABLE
    }

    record Snapshot(
            boolean contextShadow,
            String shadowOpaqueRevision,
            boolean capabilityEnforcement,
            String enforcementOpaqueRevision) {
    }

    record LoadResult(LoadStatus status, Snapshot snapshot) {

        public LoadResult {
            if (status == null) throw new IllegalArgumentException("A load status is required");
            if ((status == LoadStatus.FOUND) != (snapshot != null)) {
                throw new IllegalArgumentException(
                        "Only a found latch result can contain a snapshot");
            }
        }

        public boolean found() {
            return status == LoadStatus.FOUND;
        }
    }

    record ApprovalResult(ApprovalStatus status, Snapshot snapshot) {

        public ApprovalResult {
            if (status == null) {
                throw new IllegalArgumentException("An approval status is required");
            }
            boolean requiresSnapshot = switch (status) {
                case CREATED, UPDATED, UNCHANGED, OUT_OF_ORDER -> true;
                default -> false;
            };
            if (requiresSnapshot != (snapshot != null)) {
                throw new IllegalArgumentException(
                        "The approval result has an invalid snapshot shape");
            }
        }

        public boolean applied() {
            return status == ApprovalStatus.CREATED || status == ApprovalStatus.UPDATED;
        }

        public boolean hasStoredSnapshot() {
            return snapshot != null;
        }
    }
}
