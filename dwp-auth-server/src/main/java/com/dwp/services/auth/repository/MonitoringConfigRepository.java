package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.MonitoringConfig;

import java.util.List;

/**
 * 모니터링 설정 조회 (테넌트별, 코드 키 기반)
 */
public interface MonitoringConfigRepository extends org.springframework.data.jpa.repository.JpaRepository<MonitoringConfig, Long> {

    List<MonitoringConfig> findByTenantIdOrderByMonitoringConfigId(Long tenantId);
}
