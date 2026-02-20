package com.dwp.services.synapsex.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 비동기 처리 설정 - TaskExecutor 빈 명시적 정의
 * SynapsexServiceApplication의 @EnableAsync와 함께 사용하여 @Async 메서드의 기본 Executor 지정
 */
@Slf4j
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    @Primary
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        log.info("TaskExecutor configured: corePoolSize=5, maxPoolSize=10, queueCapacity=100");
        return executor;
    }
}
