package com.dwp.services.auth.repository.projection;

/**
 * Native query 결과: path별 p95 최대 1건.
 * SQL alias: "path", "p95Ms"
 */
public interface TopSlowView {

    String getPath();

    Double getP95Ms();
}
