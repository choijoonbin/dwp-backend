package com.dwp.services.auth.repository.projection;

/**
 * Native query 결과: percentile_cont(0.5/0.95/0.99) 1행.
 * SQL alias: "p50Ms", "p95Ms", "p99Ms"
 */
public interface LatencyPercentilesView {

    Double getP50Ms();

    Double getP95Ms();

    Double getP99Ms();
}
