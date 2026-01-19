package com.dwp.services.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * 일별 페이지뷰 집계 엔티티 (sys_page_view_daily_stats)
 */
@Entity
@Table(name = "sys_page_view_daily_stats", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "stat_date", "page_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PageViewDailyStat extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "page_view_daily_stat_id")
    private Long pageViewDailyStatId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    @Column(name = "page_key", nullable = false, length = 255)
    private String pageKey;

    @Column(name = "pv_count", nullable = false)
    @Builder.Default
    private Long pvCount = 0L;

    @Column(name = "uv_count", nullable = false)
    @Builder.Default
    private Long uvCount = 0L;

    @Column(name = "unique_session_count", nullable = false)
    @Builder.Default
    private Long uniqueSessionCount = 0L;
}
