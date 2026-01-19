package com.dwp.services.auth.repository;

import com.dwp.services.auth.entity.PageViewDailyStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PageViewDailyStatRepository extends JpaRepository<PageViewDailyStat, Long> {
    Optional<PageViewDailyStat> findByTenantIdAndStatDateAndPageKey(Long tenantId, LocalDate statDate, String pageKey);
    
    /**
     * 테넌트 및 날짜 범위별 조회
     */
    @Query("SELECT s FROM PageViewDailyStat s " +
           "WHERE s.tenantId = :tenantId " +
           "AND s.statDate >= :from " +
           "AND s.statDate <= :to " +
           "ORDER BY s.statDate ASC")
    List<PageViewDailyStat> findByTenantIdAndStatDateBetween(
            @Param("tenantId") Long tenantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
