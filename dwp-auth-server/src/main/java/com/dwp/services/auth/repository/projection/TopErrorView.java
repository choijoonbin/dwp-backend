package com.dwp.services.auth.repository.projection;

/**
 * Native query 결과: (path, status_code) 별 건수 최대 1건.
 * SQL alias: "path", "statusCode", "count"
 */
public interface TopErrorView {

    String getPath();

    Integer getStatusCode();

    Long getCount();
}
