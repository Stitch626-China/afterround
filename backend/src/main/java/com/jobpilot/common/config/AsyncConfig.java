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
        return executor(corePoolSize, maxPoolSize, queueCapacity, "jobpilot-async-");
    }

    /**
     * ASR 专用线程池（FR4.8）：转写是长耗时任务，与简历 / JD / 匹配等普通异步任务隔离，
     * 避免一段长录音把普通任务队列堵住。
     */
    @Bean("asrTaskExecutor")
    public ThreadPoolTaskExecutor asrTaskExecutor(
            @Value("${app.async.asr-core-pool-size:1}") int corePoolSize,
            @Value("${app.async.asr-max-pool-size:2}") int maxPoolSize,
            @Value("${app.async.asr-queue-capacity:5}") int queueCapacity) {
        return executor(corePoolSize, maxPoolSize, queueCapacity, "jobpilot-asr-");
    }

    private static ThreadPoolTaskExecutor executor(int corePoolSize, int maxPoolSize, int queueCapacity,
                                                   String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
