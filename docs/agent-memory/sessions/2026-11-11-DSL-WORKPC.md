# 2026-11-11 DSL-WORKPC

## 目标
重新验证 Flyway V1 迁移（上次因 Docker 网络与 Testcontainers 兼容性问题未完成）。

## 改动（文件 → 说明）
- `docs/agent-memory/PROGRESS.md` → 「当前状态 / 阻塞 / 下一步」：迁移验证由阻塞改为已完成；阻塞更新为 Testcontainers 与 Docker 29 不兼容
- 本次无代码改动（曾试改 `backend/pom.xml` 升级 Testcontainers 2.0.5，因坐标改名与 Spring Boot 3.5.0 不兼容已回退，工作区与 HEAD 一致）

## 验证记录（真实 PostgreSQL）
- 环境：本机 PostgreSQL 18.3，临时隔离实例（端口 55433，数据目录 `%TEMP%\jobpilot-pg-verify`），未接触现有 5432 实例
- `psql -v ON_ERROR_STOP=1 -f V1__init.sql`：零错误，8 张表 + 11 个命名约束全部建立
- 约束实测：`ck_llm_global_setting_single_row`（id=2 被拒）、`ck_chat_sessions_status`、`ck_chat_messages_role`、`ck_resumes_analyze_status`、`uk_resumes_file_hash` 重复被拒、`uk_llm_models_provider_model`、FK `ON DELETE CASCADE`（删 provider 后 model 归零）
- 默认值实测：`user_id=1`、`analyze_status=PENDING`、`retry_count=0`
- Flyway 链路：空库启动应用，`Migrating schema "public" to version "1 - init"` → `Successfully applied 1 migration`，`flyway_schema_history` 为 `version=1, success=t`，业务表 8 张
- 提示（非错误）：Flyway 当前版本官方支持到 PG 17，PG 18 打印升级建议；生产/compose 目标为 PostgreSQL 16，无此问题

## 决策（为什么）
- 用本机 PG 隔离实例验证而非等待 Docker：Docker 镜像已能拉取，但 Testcontainers 1.21.0 的 docker-java 3.4 默认 API 1.32 被 Docker 29 最低 API 1.44 拒绝（400），集成测试仍会跳过
- 不升级 Testcontainers 2.x：2.x 模块坐标改为 `testcontainers-*`，Spring Boot 3.5.0 依赖管理无法解析，且包结构兼容性未验证；迁移已被真实 PG 覆盖，升级留待 Spring Boot 升级时一并处理

## 未完成 / 下一步
- 临时 PG 实例的停止与目录删除命令被用户跳过，若 55433 仍在监听，可手动执行：`D:\PostgreSQL\18\bin\pg_ctl -D "$env:TEMP\jobpilot-pg-verify\data" -m fast stop` 后删除 `%TEMP%\jobpilot-pg-verify`
- 进入 M1-b：模型网关与 Provider 管理

## 阻塞
- 无（Testcontainers 集成测试在本机仍自动跳过，属环境限制，不影响迁移验证结论）

---

## 2026-11-11 补充：Testcontainers × Docker 29 兼容处理 + 临时实例清停

> 本节结论覆盖上文「未完成 / 阻塞」中关于 Testcontainers 仍跳过的描述。

### 改动（文件 → 说明）
- 新增 `backend/src/test/resources/docker-java.properties` → 固定 `api.version=1.44`

### 决策（为什么）
- Docker 29 起最低 API 1.44，docker-java 3.4（Testcontainers 1.21）默认 1.32 被 400 拒绝（testcontainers-java#11210 / #11212）
- 用 docker-java 配置文件是官方推荐的最小 workaround：零依赖变更、零生产代码改动
- 不升 Testcontainers 2.x：模块坐标改名为 `testcontainers-*`，Spring Boot 3.5.0 依赖管理不兼容；待 Spring Boot 原生支持 2.x 后删除该配置文件

### 验证结果
- `mvn test -Dtest=JobPilotApplicationTests`：Tests run 3 / Failures 0 / Errors 0 / **Skipped 0**
- 容器内 Flyway：`Migrating schema "public" to version "1 - init"` → `Successfully applied 1 migration`

### 临时实例清停（用户要求重新执行）
- `pg_ctl -m fast -w stop` 成功，`%TEMP%\jobpilot-pg-verify` 已删除，55433 无监听

### 未完成 / 下一步
- M1-b 开工

---

## 2026-11-11 M1-b 后端实现

### 目标
实现模型网关与 Provider/会话管理（契约「模型配置 12 + 对话会话 7」共 19 个端点）。

### 改动（文件 → 说明）
- `common/llm/*`：`ChatMessage` / `ChatResult` / `StreamChunk` / `LlmClient` / `OpenAiCompatibleClient` / `JsonOutputParser` / `StructuredOutputInvoker`
  - 手写 WebClient：chat、SSE 流式、`/models`、`response_format=json_object`
  - JSON 去围栏 + 括号配对截取 + 尾随逗号修复；解析失败附错误反馈重试 ≤ 3 次
- `infrastructure/security/AesGcmCipher`：AES-GCM（随机 IV，Base64 密文）+ 展示掩码；密钥缺失启动失败
- `infrastructure/llm/ProviderClientRegistry`：按 providerKey 缓存客户端，CRUD 后精确失效；`LlmCallLogger` 落 `llm_call_logs`
- `modules/llm/*`：Provider/Model/Global CRUD、连通测试（回显上游真实 model）、上游模型列举与本地回退、启动种子
- `modules/chat/*`：会话分页/创建/详情/删除、Provider+Model 两级切换、PROCESSING/LOCKED 409 守卫、消息历史、SSE 流式发送与追溯
- `common/config/MybatisPlusConfig`：分页插件；`pom.xml` 新增 `mybatis-plus-jsqlparser`（3.5.9 分页插件独立模块）
- `JobPilotApplication`：显式 `@MapperScan` 两个模块的 mapper 包
- `application.yml`：`app.llm.bootstrap.*`、`app.llm.max-history-messages`
- 测试：`AesGcmCipherTest`、`JsonOutputParserTest`、`StructuredOutputInvokerTest`、`OpenAiCompatibleClientTest`、`ProviderClientRegistryTest`、`ContractCoverageTest`、`M1bApiIntegrationTest`（真实 PostgreSQL + 本地假上游 + SSE）

### 决策（为什么）
- SSE 用 Spring MVC 返回 `Flux<ServerSentEvent>`：复用已有 WebFlux，守卫异常在返回 Flux 前抛出，仍走统一 JSON 错误响应
- 并发守卫用条件 `UPDATE ... WHERE status='IDLE'` 原子抢占 PROCESSING，避免两次请求同时通过检查
- 不新增契约外接口：FRX.7 的「同步到本地」由客户端对选中的上游模型逐个调用 POST 创建模型完成
- `LlmCallLogger` 内部吞掉写日志异常，埋点失败不影响主流程

### 验证
- `mvn test`：**38 tests，0 failures / 0 errors / 0 skipped**
- 端到端覆盖：Provider/Model CRUD、重复键 40903/40904、连通测试成功与 401 失败、上游模型列举、全局默认、SSE delta/done、消息 provider/model 追溯、PROCESSING → 40901、LOCKED → 40902、删除后 404
- 契约覆盖率：M1-b 19 个端点与 `contracts/openapi.yaml` 完全匹配

### 未完成 / 下一步
- M1-c 简历中心（上传 / Tika / 去重 / 异步分析 / 五维报告 / 方向推荐）
- M1-d 前端四屏，模型设置页与对话页接本模块接口
