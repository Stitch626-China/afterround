package com.jobpilot.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 异步执行器（§6.3）：Spring MVC 默认的 SimpleAsyncTaskExecutor 不适合生产，
 * SSE 长连接改用独立有界线程池，并放宽异步超时避免长回答被容器提前中断。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Bean("mvcTaskExecutor")
    public ThreadPoolTaskExecutor mvcTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mvc-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcTaskExecutor());
        configurer.setDefaultTimeout(300_000);
    }
}
