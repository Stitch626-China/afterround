# 2026-11-18 DSL-WORKPC

## 目标

补强 M2 第一批的验证缺口：给新增的 19 个端点补 HTTP 层集成测试，并覆盖「异步重试打满置 FAILED」路径。

## 改动（文件 → 说明）

- `backend/src/test/java/com/jobpilot/modules/M2Batch1ApiIntegrationTest.java`（新增，3 个用例）
  - `fullPipeline`：导入 JD → 轮询异步解析 → 公司自动关联与联想检索 → 场景绑定绑定/解绑 → 简历分析 → 发起匹配并轮询 → 行情洞察 → 简历对比 → 删除岗位（详情 404、匹配级联删除、公司解除引用后可删）。
  - `validationsAndGuards`：空导入 400、非法文件 415、公司重名 409、被引用公司删除 409、匹配资源不存在 404、对比同 id 400、对比分析未完成 409、匹配执行中重复发起 409、失败后重跑恢复。
  - `parseFailsAfterRetries`：上游持续 401 时 JD 解析重试 3 次后置 FAILED，`parseError` 含 `HTTP 401`。
- `JobPositionServiceTest`：新增 `parseMarksFailedAfterRetriesExhausted`。
- `JobMatchServiceTest`：新增 `matchMarksFailedAfterRetriesExhausted`。
- `README.md`：补充「测试分层（123 个用例）」与真实覆盖率。
- `docs/agent-memory/PROGRESS.md`：更新测试数字与验证结论。

## 决策（为什么）

- 集成测试复用 M1b/M1c 的「真实 PostgreSQL（Testcontainers）+ 本地假 LLM 上游」模式，不引入新依赖；假上游按各 system prompt 的唯一措辞分流返回，避免为测试新增生产代码。
- JD 解析/匹配的重试上限用「单测验证调用次数 + 集成测试验证真实落库状态」双层覆盖：单测快、集成测试证明状态机在真实 DB 上正确。
- 集成测试清理顺序为 `job_match_results → job_positions → companies`，满足外键 RESTRICT/CASCADE 约束。

## 未完成 / 下一步

- M2 前端页面与真实模型联调。
- 前端 M2 E2E（页面开发后补）。

## 阻塞

无。

## 实时记录

### 11:22 补齐 M2 HTTP 集成测试与重试失败路径
- 改动：新增 `M2Batch1ApiIntegrationTest`（3 用例）；`JobPositionServiceTest` 8→9、`JobMatchServiceTest` 4→5；README / PROGRESS 同步测试说明与数字。
- 原因：此前 M2 只有 Service 单测与契约反射测试，HTTP 参数绑定、multipart、真实 DB 状态、执行中 409、上游 401 重试均未验证。
- 验证：`mvn test` 全量 **123 tests 全绿**，JaCoCo 门禁通过（行覆盖 **90.32%**、分支 **73.77%**）；M2 集成测试真实 PostgreSQL 16 + 假上游全部通过。
- 过程中的问题：
  1. 假上游最初用「匹配度」分流，误命中简历分析 Prompt（第 26 行「按匹配度从高到低」），导致简历分析拿到匹配 JSON 后结构化失败；改为「岗位 JD 的匹配度」等唯一措辞后修复。
  2. 公司联想查询的中文关键字被 WebTestClient 二次编码，改为 `queryParam` 由框架编码。
  3. 对比未完成用例中 target 传固定 `1` 与首条简历 id 冲突，返回 400；改为 `resumeId + 9999`。
  4. 匹配失败单测未给返回实体设置 `resumeId/jobPositionId`，worker 提前判定资源已删除；补齐字段后通过。
- 下一步 / 断点：M2 前端开发。

### 11:26 进度检查与对比前置条件用例复核
- 改动：`M2Batch1ApiIntegrationTest.validationsAndGuards` 的 40907 用例改为「先新建第二份已完成简历，再把首份置 PENDING」，替代 `targetResumeId = resumeId + 9999`。
- 原因：`ResumeComparisonService.compare` 先对 base 调 `side()`，base 未完成时即刻抛 40907，`resumeId + 9999` 虽能通过，但「目标简历根本不存在」的分支被掩盖；改用两份真实存在的简历，断言才真正覆盖「两份都存在、其中一份未完成」。
- 验证：契约 lint 0 error / 0 warning；前端 `npm run build`（含 `gen:api`）+ Vitest 9 通过；后端全量 **123 tests 全绿**、JaCoCo 门禁通过；`M2Batch1ApiIntegrationTest` 3 用例在真实 PostgreSQL 16 + 假上游下通过。
- 下一步 / 断点：M2 前端页面（岗位库 / 公司 / 匹配 / 行情 / 简历对比 / 场景绑定）与真实链路联调。
