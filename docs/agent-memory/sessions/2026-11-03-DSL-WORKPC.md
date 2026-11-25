# 2026-11-03 DSL-WORKPC

## 目标

开工前把剩余需求决策全部收敛：确认 M1 范围、技术选型、LLM 网关实际能力，并落到需求文档。

## 改动（文件 → 说明）

- `docs/superpowers/specs/职点（JobPilot）需求文档.md` → 升至 v1.4：
  - v1.3：Provider 与 Model 解耦（`Provider = baseUrl + apiKey`，1 个 Provider 挂 N 个 model）；会话内切换改为 Provider + Model 两级；新增执行中禁止切换（PROCESSING → 409，终态锁定）；新增 §7 字段 `current_model` / `llm_model`
  - v1.4：Provider/Model 改为**数据库驱动热更新**，新增 `llm_providers` / `llm_models` / `llm_global_setting` 三表，API Key AES-GCM 加密落库并掩码；新增 M1 会话载体 `chat_sessions` / `chat_messages`；新增 §6.8 技术选型决策、§6.9 契约与唯一真实源、§6.10 前端设计规范；测试策略升级为分层覆盖承诺（TDD）；M1 拆 a/b/c/d 四阶段
- `README.md` → 目录结构改为 `contracts/` `backend/` `frontend/` 三工程；技术底座、里程碑、当前进度同步 v1.4
- `docs/agent-memory/PROGRESS.md` → 更新状态三块与关键决策表

## 决策（为什么）

详见 PROGRESS.md 决策表。要点：

- **Provider 落库热更新**：用户当前用公司内网 new-api 网关，后续会切 GPT / DeepSeek，改 yml 不可接受；原 M3 的「API Key 加密落库」因此提前到 M1。
- **M1 引入极简会话载体**：M1 原本没有会话实体，"会话内切换"无处落地。`chat_sessions` 仅作能力验证载体（验证两级切换、409 守卫、消息级 provider/model 追溯、SSE），不做业务包装，M2 面试会话复用。
- **不用 LLM 框架**：Spring AI 是配置驱动，与 DB 驱动热更新冲突；Spring AI Alibaba 绑 DashScope；AgentScope 是 Agent 编排；LangChain4j 是唯一值得考虑的，保留为备选。真正复杂度（热更新、结构化加固、分层降级）框架都不提供。
- **PostgreSQL 保持**：JSONB 使用密度高（分析报告 / 关键点 / 问答分析），MySQL 会退化。已知代价：国内 Java 岗 MySQL 更常见，需面试时能解释选型。
- **单 Maven 模块**：一人开发拆多模块只会增加 `install` 往返成本。

## 实测结论（LLM 网关）

- 网关为内网 new-api（one-api 分支）地址，**已脱敏**
- 当前仅 `Qwen3.8-27B` 一个模型；早期三个别名（27B / NVFP4 / W8A8-DFlash2）**全部路由到同一后端** `Qwen3.8-27B-W8A8-DFlash2`，输出与延迟无差异 → 属模型别名
- `/v1/models` ✅、`response_format: json_object` ✅、SSE 流式 ✅（30 tok / 801ms）、UTF-8 正常
- 吞吐约 15 tok/s；首 token < 1s，满足 NFR1
- **指令遵循偏弱**：要求「最多 3 个 skills」返回 5 个 → 结构化校验 + 带错重试是刚需

## 未完成 / 下一步

1. M1-a：建 `backend/` `frontend/` `contracts/` 三目录 + Spring Boot 骨架 + Docker Compose + Flyway + 统一响应/异常/traceId
2. 写 `contracts/openapi.yaml` 全量契约，评审通过后才实现
3. M1-b：模型网关与 Provider 管理

## 阻塞

- 无（JDK 21 已装 `D:\Java\21`；用户自行在编辑器指定，不改系统 `JAVA_HOME`，命令行构建时单次覆盖即可）

## 安全备注

- LLM API Key 曾出现在对话中，需写入 `.env`（已 gitignore），不落库、不入日志；建议开发完成后轮换
