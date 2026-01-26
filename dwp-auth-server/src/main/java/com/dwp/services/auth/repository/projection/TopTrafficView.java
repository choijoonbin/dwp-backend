package com.dwp.services.auth.repository.projection;

/**
 * Native query 결과: path별 요청 수 최대 1건.
 * SQL alias: "path", "requestCount"
 */
public interface TopTrafficView {

    String getPath();

    Long getRequestCount();
}
