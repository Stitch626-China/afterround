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
