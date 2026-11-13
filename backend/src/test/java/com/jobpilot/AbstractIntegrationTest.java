package com.jobpilot;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 集成测试基类：JVM 级单例 PostgreSQL + Flyway；无 Docker 时整类自动跳过。
 *
 * <p>用单例而非 {@code @Container}：多个测试类会复用同一个 Spring 上下文，
 * 若容器随类结束而停止，后续测试类会连到已关闭的端口。
 */
@EnabledIf("dockerAvailable")
public abstract class AbstractIntegrationTest {

    private static final boolean DOCKER_AVAILABLE = DockerClientFactory.instance().isDockerAvailable();

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        if (DOCKER_AVAILABLE) {
            POSTGRES.start();
        }
    }

    public static boolean dockerAvailable() {
        return DOCKER_AVAILABLE;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
