package com.dwp.services.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Isolates latency-sensitive and blocking notification maintenance workloads. */
@Configuration(proxyBeanMethods = false)
public class NotificationSchedulingConfiguration {

    @Bean(name = "notificationOutboxTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler notificationOutboxTaskScheduler() {
        return scheduler("dwp-notification-outbox-");
    }

    @Bean(name = "notificationRetentionTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler notificationRetentionTaskScheduler() {
        return scheduler("dwp-notification-retention-");
    }

    @Bean(name = "notificationReconciliationTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler notificationReconciliationTaskScheduler() {
        return scheduler("dwp-notification-reconciliation-");
    }

    @Bean(name = "notificationSseTaskScheduler", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler notificationSseTaskScheduler() {
        return scheduler("dwp-notification-sse-");
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }
}
