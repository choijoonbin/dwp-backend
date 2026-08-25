package com.dwp.gateway.productsurface;

import java.util.List;

/**
 * Projection boundary implemented by the generated authorization-bundle candidate superset.
 */
public interface ProductSurfaceCandidateCatalog {

    List<ProductSurfaceContextDtos.ProductCandidate> activeCandidates();

    /**
     * Product rollout inventory is intentionally broader than the active route-authority
     * candidates. A default-off product still needs an explicit 000 rollout in the context
     * envelope so clients can preserve its legacy surface without inventing authority state.
     * Contract-less products remain compatibility-only in 000/100. A raw 110/111 decision
     * without exact active product participation fails closed; enforcement states are never
     * projected to shadow compatibility.
     */
    default List<String> rolloutProductKeys() {
        return activeCandidates().stream()
                .map(ProductSurfaceContextDtos.ProductCandidate::productKey)
                .distinct()
                .sorted()
                .toList();
    }
}
