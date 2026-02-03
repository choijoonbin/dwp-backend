package com.dwp.services.synapsex.repository;

import com.dwp.services.synapsex.entity.AnalyticsKpiDaily;
import com.dwp.services.synapsex.entity.AnalyticsKpiDailyId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AnalyticsKpiDailyRepository extends JpaRepository<AnalyticsKpiDaily, AnalyticsKpiDailyId> {

    List<AnalyticsKpiDaily> findByTenantIdAndYmdBetweenOrderByMetricKeyAsc(
            Long tenantId, LocalDate from, LocalDate to);
}
