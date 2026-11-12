package com.jobpilot.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 有界异步线程池（§6.3）：避免多任务并发触发厂商限流。
 */
@Configuration
public class AsyncConfig {

    @Bean("jobpilotTaskExecutor")
    public ThreadPoolTaskExecutor jobpilotTaskExecutor(
            @Value("${app.async.core-pool-size:2}") int corePoolSize,
            @Value("${app.async.max-pool-size:4}") int maxPoolSize,
            @Value("${app.async.queue-capacity:20}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("jobpilot-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
