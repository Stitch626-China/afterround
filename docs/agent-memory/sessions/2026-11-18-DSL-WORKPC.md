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

### 14:48 覆盖率缺口盘点与进度同步
- 改动：`PROGRESS.md` 当前状态补「测试缺口盘点」「CI 状态」，下一步新增第 5 项「测试缺口（按价值排序）」；本条会话记录。
- 原因：用户要求明确「剩余未覆盖」而不是只看门禁结论；同时把盘点结果固化为后续可执行清单。
- 盘点方式：解析 `backend/target/site/jacoco/jacoco.xml` 的 method 级 LINE/BRANCH 计数，按 missed 降序人工分类。
- 结论：
  - 整体：行覆盖 90.32%（2053/2273）、分支 73.77%；M2 业务类 `JobPositionService` 81%、`JobMatchService` 92%、`CompanyService` 85%、`LlmSceneService` 98%、`ResumeComparisonService` 97%、`JobMarketService` 95%。
  - 高价值缺口：岗位列表 `page`（15 行 / 10 分支）与 `companyIdsMatching`、`companyNames` 共 26 行 0 覆盖；批量删除 Controller 未走 HTTP；匹配 worker 的「简历/岗位已删除 / 客户端获取失败 / 记录删除终止」；`CompanyService.update` 的名称未变与并发重复键分支；JD 文件导入的 Tika/IO 异常；`GlobalExceptionHandler` 413/500。
  - 中价值：JSON 兜底异常（`skillsJson`/`skills`/`parseList`/`write`）、全局设置部分分支、Provider/Model 删除 HTTP 层。
  - 可接受不补：`LlmBootstrapRunner`（25 行，种子仅在配置环境变量时执行）、`main`、DTO、Prompt 资源缺失、`AesGcmCipher.deriveKey` 分支。
- 远程状态：本地与 `origin/main` 一致（HEAD `7571a14`）；最新 CI run 44253787088 三 job 全绿。
- 下一步 / 断点：按「下一步」第 5 项补高价值缺口，或先做 M2 前端。

### 15:10 补齐岗位列表、批量删除与匹配异常分支
- 改动：
  - `JobPositionServiceTest` 8→10：新增 `pageMapsCompanyNameAndSupportsAllFilters`（status/city/keyword 全传入、公司名映射、分页参数）与 `pageWithoutKeywordSkipsCompanyLookupAndNormalizesPaging`（keyword 空白、page/size 归一、不查公司）。
  - `JobMatchServiceTest` 5→8：新增 `runFailsWhenResumeDeletedBeforeWorker`、`runFailsWhenProviderClientUnavailable`、`runStopsWhenRecordDeletedMidway`，覆盖 worker 的三类异常/终止分支。
  - `M2Batch1ApiIntegrationTest` 3→4：新增 `listFiltersAndBatchDelete`，走真实 HTTP 验证状态筛选、城市精确匹配、分页与倒序、公司名/岗位名关键字、批量删除忽略不存在 id 与最终清空。
  - README 测试分层与 PROGRESS 数字同步。
- 原因：上一轮盘点的高价值缺口——岗位列表 26 行 0 覆盖、批量删除未走 HTTP、匹配 worker 异常分支未测。
- 验证：全量 `mvn test` **129 tests 全绿**、JaCoCo 门禁通过；行覆盖 **91.90%**（原 90.32%），分支 **76.11%**（原 73.77%）；`JobPositionService` 81%→89%、`JobMatchService` 92%→94%、`JobPositionController` 58%→92%、`JobMatchController` 100%。
- 剩余：公司并发重复键/标签兜底、JD 导入 IO/Tika 异常、`toResponse` 详情解析兜底、413/500 等中价值缺口（已写入 PROGRESS「下一步」第 5 项）。
- 下一步 / 断点：中价值缺口或 M2 前端，待选择。

### 16:05 完成 M2 第一批前端页面与 E2E
- 改动：
  - API 层：新增 `frontend/src/api/jobs.ts`（岗位/公司/匹配/行情/场景绑定，类型全部来自契约生成 schema）；`api/resumes.ts` 新增 `compareResumes` 与对比类型。
  - 页面：`JobListView`（岗位列表 + 行情双 Tab：文件/粘贴导入、keyword/状态/城市筛选、分页、批量选择与删除、行内删除确认、解析轮询 2s、状态/解析双徽章）；`components/jobs/MarketPanel.vue`（技能 Top20 / 薪资 5 桶 / 经验与方向 Top10，横向条形）；`JobDetailView`（字段修正、重试解析、发起匹配并轮询、三维得分与差距/建议、历史匹配、原始 JD 折叠）；`ComparisonView`（两份已完成简历对比：总分 delta、五维双条、优势/短板新增消除、方向 chips、LLM 点评、模型/Prompt 版本不一致提示与降级）；`components/settings/SceneSettings.vue`（7 场景绑定/解绑、继承全局徽章）+ 设置抽屉第三个 Tab；简历中心头部新增「对比简历」入口。
  - 路由与导航：`/jobs`、`/jobs/:id`、`/resumes/compare`；一级导航加「岗位库」。
  - E2E：新增 `frontend/e2e/m2-flows.spec.ts` 3 个用例（岗位库筛选+行情+批量删除、简历对比、场景绑定）。
  - README 新增「M2 第一批前端」使用说明、设置抽屉三 Tab、路线图与当前进度；PROGRESS 同步。
- 原因：用户要求直接进入 M2 前端，并明确「中等价值分支若前端联调需要补，及时记录」。
- 验证：`npm run build` 通过（vue-tsc + 契约类型生成）；Vitest **9/9**；Playwright **9/9**（原有 6 + 新增 3）。
- 联调中发现并修复的 E2E 问题（均属测试代码，不改业务）：
  1. 岗位库 `page.route(/\/api\/job-positions(\?|$)/)` 只能匹配列表路径，`/api/job-positions/batch-delete` 不被捕获，批量删除 payload 永远为空 → 为 batch-delete 单独注册 route，断言恢复。
  2. `getByText('已投递')` / `getByText('resume-v1.pdf')` 命中隐藏 `<option>` → 断言限定 `.list` 容器或直接断言 `.picker select` 的 value。
  3. 对比页 `getByText('技术深度')` 同时命中五维标签与 LLM 点评文本（strict violation）→ 限定 `.dims` 容器。
- 中等价值分支结论：本轮前端只消费既有契约，未触发公司并发重复键、JD 导入 IO/Tika 异常、JSON 兜底等后端分支，**暂不需要为此补测**；清单保留在 PROGRESS「下一步」第 5 项，待真实链路联调或第二批开发触及时再补。新增待记录项：E2E 仍为接口全 mock，真实链路（浏览器 → 真后端 → 临时 PG + 假上游）尚未跑，已写入 PROGRESS 下一步第 1 项。
- 下一步 / 断点：真实链路联调；随后 M2 第二批（F3 模拟面试 + F6 Offer 决策）契约。

### 17:40 岗位结果拆分「录用 / 淘汰」+ 岗位备注
- 改动：
  - 契约 `1.7.0`：`JobPositionStatus` 改为 `SAVED / APPLIED / INTERVIEWING / OFFERED / REJECTED`；`JobPositionListItem`、`UpdateJobPositionRequest` 新增 `remark`（≤200 字，传空字符串清空）。
  - Flyway `V4__job_position_outcome_remark.sql`：新增 `remark` 列、旧 `CLOSED` → `REJECTED`、重建状态 CHECK。
  - 后端：实体/DTO/服务透传 `remark`（trim、空串转 null、截断 200）；`UpdateJobPositionRequest.status` 正则同步。
  - 前端：列表状态徽章与筛选项改为「待跟进 / 已投递 / 面试中 / 录用 / 淘汰」；列表行内新增「备注 / 改备注」快速编辑；详情页表单新增备注与两种新状态。
  - 测试：`JobPositionServiceTest` 覆盖备注写入与清空、`OFFERED`；`M2Batch1ApiIntegrationTest` 覆盖 `OFFERED`/`REJECTED` 筛选、备注写入与清空（真实 PG）；E2E 岗位库用例覆盖备注展示与行内保存。
  - 需求文档 v1.9（新增 FR2.7 与迭代记录）、README、PROGRESS 同步。
- 原因：用户反馈「已关闭」无法区分录用与淘汰，且岗位需要记录面试轮次、淘汰环节等信息。
- 验证：后端 **129 tests 全绿**，行覆盖 **91.92%**、分支 **76.23%**，JaCoCo 门禁通过；契约 lint 0 error 0 warning；前端 build 通过、Vitest 9/9、Playwright 9/9。
- 过程中的问题：
  1. **备注无法清空**：MyBatis-Plus `updateById` 默认忽略 null 字段，`setRemark(null)` 未进入 SQL，DB 仍保留旧值。修复：`remark` 字段加 `@TableField(updateStrategy = FieldStrategy.ALWAYS)`，让 null 也写入；集成测试改为直接查库断言。
  2. **同一文件批量编辑丢改动**：多次对同一文件并发提交 Edit 时，后一次基于旧快照覆盖前一次，出现「编辑成功但内容回退」。后续同一文件的多处改动改为逐条提交，并在每步后用 Grep 验证落盘。
- 下一步 / 断点：M2 第一批真实链路联调；页面已在 5173 运行供用户查看样式。
