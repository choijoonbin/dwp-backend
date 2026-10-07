package com.dwp.gateway.productsurface;

/** Trusted request tracing metadata shared by rollout evaluation and receipt clients. */
public interface FeatureRolloutRequestMetadata {
    String correlationId();

    String traceParent();

    String traceState();
}
