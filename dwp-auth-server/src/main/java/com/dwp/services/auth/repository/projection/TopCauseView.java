package com.dwp.services.auth.repository.projection;

/**
 * Native query 결과: 5xx path별 건수 최대 1건.
 * SQL alias: "path", "count"
 */
public interface TopCauseView {

    String getPath();

    Long getCount();
}
