package com.dwp.services.synapsex.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Phase B: Detect 배치 설정
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "detect.batch")
public class DetectBatchConfig {

    /** 스케줄 활성화 (기본 false - 수동 트리거만) */
    private boolean enabled = false;

    /** 15분마다 실행 (cron) */
    private String cron = "0 */15 * * * *";

    /** interval_minutes (cron 파싱 대신 명시, 기본 15) */
    private Integer intervalMinutes = 15;

    /** 대상 tenant ID 목록 (비어있으면 tenant 1만) */
    private List<Long> tenantIds = List.of(1L);
}
