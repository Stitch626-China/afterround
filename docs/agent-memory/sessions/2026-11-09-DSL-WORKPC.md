# 2026-11-09 DSL-WORKPC

## 目标

完成 M1-a：搭建 `backend/` 与 `frontend/` 工程骨架，落地统一响应/异常/traceId、Flyway 初始迁移、Docker Compose，并由契约生成前端类型。

## 改动（文件 → 说明）

**后端 `backend/`（新增）**

- `pom.xml` → Spring Boot 3.5.0 + Java 21；MyBatis-Plus 3.5.9、Flyway（含 postgresql 模块）、Tika 2.9.2、WebFlux（WebClient）、springdoc 2.8.6、actuator、Testcontainers、JaCoCo
- `JobPilotApplication.java` → 启动类，`@EnableAsync` + `@MapperScan("com.jobpilot.modules.**.mapper")`
- `common/api/` → `Result<T>`（code/message/data/traceId）、`PageResult<T>`（含分页规范化）、`ErrorCode`（码段与契约一致）
- `common/web/TraceIdFilter.java` → 每请求 traceId，写入 MDC + 响应头 `X-Trace-Id`，结束清理
- `common/exception/` → `BizException` + `GlobalExceptionHandler`（业务异常 / 校验失败 / 缺参 / 上传超限 / 兜底 500）
- `resources/application.yml` → 数据源、Flyway、multipart 5MB、MyBatis-Plus、springdoc、actuator、app 配置、日志 pattern 带 traceId
- `resources/db/migration/V1__init.sql` → 8 张表：llm_providers / llm_models / llm_global_setting / llm_call_logs / chat_sessions / chat_messages / resumes / resume_analyses（含 CHECK 状态约束、UNIQUE、索引、注释）
- `Dockerfile` → 多阶段构建，非 root 运行
- 测试：`ResultTest`（5）、`TraceIdFilterTest`（4）、`JobPilotApplicationTests`（3，Testcontainers，无 Docker 自动跳过）

**前端 `frontend/`（新增）**

- `package.json` / `vite.config.ts`（/api 代理、vitest jsdom）/ `tsconfig.*` / `index.html`
- `src/styles/tokens.css` → §6.10 全套 design tokens
- `src/api/http.ts` → 基于契约 `Result<T>` 的请求封装，含 `ApiError`（带 code/traceId）
- `src/App.vue` → 侧栏 + 顶栏布局；`src/components/EmptyState.vue` → 行动指引型空状态
- `src/views/` → 简历中心 / 模型设置 / 模型对话三屏占位

**根目录**

- `docker-compose.yml` → postgres（healthcheck）+ app（depends_on service_healthy、env_file、双数据卷）
- `.env.example` → 数据库密码、APP_SECRET_KEY、存储根、线程池、种子模型配置
- `.gitignore` → 补 backend/target、backend/data、frontend/node_modules、frontend/dist、frontend/src/api/schema.d.ts
- `README.md` → 新增「快速开始」「构建与测试」两节含命令与集成测试说明

## 验证结果（实测）

| 项 | 结果 |
|---|---|
| Maven 依赖解析 | ✅ 全部版本可解析 |
| `mvn compile` | ✅ BUILD SUCCESS |
| `mvn test` | ✅ 12 tests，9 通过，3 跳过（集成测试无 Docker/镜像） |
| 前端 `npm install` | ✅ 378 包（Node 24.14 略低于要求的 24.15，仅 warning） |
| `npm run gen:api` | ✅ 生成 `src/api/schema.d.ts` |
| `npm run build` | ✅ 42 模块，dist 产出（index 95.55 kB / gzip 37.49 kB） |

## 决策（为什么）

- **`Result` 用 Java record**：不可变、语义清晰，避免 Lombok 与其混用。
- **traceId 走 Servlet Filter + MDC**：比在 Controller 手动传更可靠，日志 pattern 直接可用；`Result` 从 MDC 取值，业务代码零侵入。
- **集成测试 `disabledWithoutDocker = true`**：Docker 不可用时自动跳过而非失败，保证单元测试在任何环境可跑；代价是迁移 SQL 可能长期未被验证，已写入 README 与 PROGRESS 阻塞项。
- **前端 `build` 前置 `gen:api`**：保证类型永远由当前契约生成，杜绝类型过期。
- **`vite.config.ts` 用 `vitest/config` 的 defineConfig**：否则 `test` 字段类型报错。
- **compose 必须 healthcheck + service_healthy**：否则 PG 未就绪时应用必然启动失败。
- **未引入 AsyncConfig**：线程池参数已在 yml 定义，但 M1-a 无异步任务，待 M1-b/c 落地时再建，避免死代码。

## 未完成 / 下一步

- **Flyway V1 迁移未在真实 PG 验证**（见阻塞）
- M1-b 模型网关与 Provider 管理
- M1-c 简历中心
- M1-d 前端四屏与测试收口

## 阻塞

- **Docker 镜像拉取被网络阻断**：`docker pull postgres:16-alpine` 无进展（GitHub 代理 127.0.0.1:7890 亦未启动）
- **Testcontainers 1.21.0 与本机 Docker 29.6.1（API 1.55）不兼容**：报 `Could not find a valid Docker environment`，响应体 ServerVersion 为空；已尝试 `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` 仍失败。README 已记录绕过命令
- 曾尝试用本机 PostgreSQL 18 起隔离临时实例（端口 55433 + 临时目录）验证迁移，用户跳过该操作，未执行

## 备注

- 本机环境：JDK 21 在 `D:\Java\21`（JAVA_HOME 指向 17，命令行单次覆盖）；Maven 3.9.9；Node 24.14；Docker 29.6.1
- 未提交改动等待用户确认后再 commit + push（`git pull` 因代理未启动失败）
