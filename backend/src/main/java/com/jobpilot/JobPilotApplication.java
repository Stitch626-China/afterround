package com.jobpilot;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 职点（JobPilot）后端启动类。
 *
 * <p>分层约定：{@code common} 通用能力 / {@code infrastructure} 技术设施 /
 * {@code modules} 自包含业务模块（Controller → Service → Repository）。
 */
@SpringBootApplication
@EnableAsync
@MapperScan({"com.jobpilot.modules.llm.mapper", "com.jobpilot.modules.chat.mapper",
        "com.jobpilot.modules.resume.mapper", "com.jobpilot.modules.company.mapper",
        "com.jobpilot.modules.job.mapper", "com.jobpilot.modules.interview.mapper",
        "com.jobpilot.modules.offer.mapper", "com.jobpilot.modules.application.mapper",
        "com.jobpilot.modules.review.mapper"})
public class JobPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobPilotApplication.class, args);
    }
}
