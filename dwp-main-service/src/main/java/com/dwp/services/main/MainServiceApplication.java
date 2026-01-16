package com.dwp.services.main;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * DWP Main Service
 * 
 * - 사용자 정보 및 공통 메타데이터 관리
 * - AI 에이전트 작업(AgentTask) 관리
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "com.dwp.services.main")
@EnableAsync  // 비동기 작업 활성화 (AI 에이전트 장기 실행 작업용)
public class MainServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MainServiceApplication.class, args);
    }
}

