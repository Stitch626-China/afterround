# 2026-11-19 DSL-WORKPC

## 目标

对 M2 第一批做真实链路联调：临时 PostgreSQL + 本地假 LLM 上游 + 真后端 + 浏览器，验证「导入 JD → 解析 → 公司自动关联 → 简历分析 → 匹配 → 行情 → 对比 → 状态/备注」。

## 改动（文件 → 说明）

- 本次无产品代码改动；仅更新 `docs/agent-memory/PROGRESS.md` 并新增本会话记录。
- 环境：临时 PostgreSQL 16（Docker，端口 5433）、本地假 OpenAI 上游（临时目录脚本，不入库）、后端以环境变量指向临时库、前端 Vite 5173。

## 决策（为什么）

- 联调使用**假 LLM 上游**而非内网真实模型：可返回确定性 JSON，验证的是「浏览器 → 真后端 → 真数据库」链路，不把模型波动混入联调结论。
- 临时库与临时容器用完即删，不污染本机 PostgreSQL 与仓库；测试数据不留存。
- Docker Desktop 起初未运行，联调前先启动；结束后删除容器，Docker Desktop 保持运行。

## 未完成 / 下一步

1. 按契约 `1.8.0` 实现 M2 第二批 F3 模拟面试 / F6 Offer 决策（Flyway V5、新 DTO、集成测试与前端）。
2. E2E 仍为接口全 mock；真实链路已验证一次，后续涉及真实行为变更时优先补契约/集成测试。

## 阻塞

无。HOMEPC 本机无 Docker/PostgreSQL 的限制仍在，集成测试需在 DSL-WORKPC 或 CI 执行。

## 实时记录

### 10:50 M2 第一批真实链路联调通过
- 改动：启动临时 PostgreSQL 16（5433）、本地假 OpenAI 上游（3001）、后端（8080，Flyway V1→V4）、前端 Vite（5173）；通过 HTTP 与浏览器完成全链路核对，结束后停止进程并删除容器。
- 原因：M2 第一批此前只有接口 mock E2E 与后端集成测试，缺少「真前端 → 真后端 → 真 PG」的联调证据。
- 验证：
  - JD 导入 → 解析 `COMPLETED`，标题「Java 后端开发工程师」、公司「字节跳动」自动关联、技能四项正确。
  - 两份简历上传 → 分析均 `COMPLETED`，总分 74。
  - 岗位匹配 `COMPLETED`，总分 76，三维 82 / 78 / 60（权重 50/30/20）。
  - 行情洞察：样本 1，技能 Top 含 Java 等 6 项，薪资桶 20-30K 为 1。
  - 简历对比：五维差异齐全，`degraded=false`，点评正常返回。
  - 状态/备注：`OFFERED` + 「二面已约」保存成功，详情与列表一致；关键字「字节」可搜到岗位与公司。
  - 浏览器：岗位列表显示「录用」「备注：二面已约」；行情 Tab 显示「样本 1 份」与 Java 技能条；详情页备注为「二面已约」、匹配总分 76 与三维条正确；均无红色报错横幅。
  - 清理：5173 / 8080 / 3001 / 5433 已无监听，`jobpilot-it-pg` 容器已删除，git 工作区干净。
- 下一步 / 断点：M2 第二批后端实现（F3 / F6）。

### 14:44 M2 第二批后端完成（F3 模拟面试 / F6 Offer 决策）
- 改动：
  - Flyway `V5__m2_batch2.sql`：`interview_sessions`（出题 / 评估双状态机 + 四件套）、`interview_questions`（主问题 / 追问树、唯一题号）、`offers`（分组、JSONB 福利与分析）。
  - F3：方向 Skill 资源（`java-backend` / `ai-agent` 的 `SKILL.md` + `skill.meta.yml`）与加载器；8 个 DTO + 3 个 Prompt 模板；`InterviewService`（异步出题、追问插入与题号顺延、作答 / 跳过、交卷、评估、两级切换、重试、删除）与两个控制器。
  - F6：Offer 实体 / Mapper + 10 个 DTO + 2 个 Prompt；`OfferService`（CRUD、保留组 ≤4、显式 null 清空、固定 12 行对比、文本 / 图片提取、前景分析持久化）与控制器。
  - `ChatMessage` / `OpenAiCompatibleClient` 支持 OpenAI 视觉消息（图片提取）；`LlmSceneService.optionalResolve` 支持「评估场景显式绑定优先、其次会话模型」；启动恢复扩展面试出题 / 评估；`@MapperScan` 补 interview / offer 包。
  - 契约：移除 14 处 `x-implemented: false`（19 端点）与未使用的 `ResultOffer` schema；`ContractCoverageTest` / `ContractSchemaTest` 纳入新控制器与 18 个新 DTO。
  - 测试：新增 `M2Batch2ApiIntegrationTest` 4 个用例（真实 PG + 假上游），覆盖面试全链路 / 守卫 / 失败重试与 Offer 全链路。
- 原因：按契约 `1.8.0` 落地 M2 第二批后端，使 64 个契约端点全部有实现；面试与 Offer 的异步、状态机、结构化输出复用既有统一机制。
- 验证：`mvn test` **133 tests 全绿**，行覆盖 89.43%、分支 68.60%，JaCoCo 门禁通过；`redocly lint` **0 error / 0 warning**；`npm run build`（含契约类型重新生成）通过。
- 下一步 / 断点：M2 第二批前端页面（模拟面试 / Offer 决策）；随后真实链路联调。

### 16:15 M2 第二批后端独立复验通过
- 改动：无代码改动，仅追加本记录。
- 原因：用户要求独立检查并验证第二批后端交付，不沿用「已完成」结论。
- 验证：
  - 契约 `1.8.0`：41 路径 / 64 端点 / 115 schema，`x-implemented: false` 无残留；`redocly lint` **0 error / 0 warning**。
  - 后端全量 **133 tests 全绿**，JaCoCo 门禁通过；Docker 29.9.3 可用，真实 PostgreSQL 16 集成测试未被跳过。
  - 前端 `npm run build`（80 modules，含契约类型重新生成）+ Vitest **9 passed**；页面仍只有第一批功能（第二批前端未做，符合进度）。
  - `V5__m2_batch2.sql`：`interview_sessions` 出题/评估双状态机各带 error + retry_count；`interview_questions` 追问树 CHECK 一致、`(session_id, question_index)` 唯一；`offers` 分组 CHECK 与薪资校验。
  - 业务规则抽查：追问数默认 1、跳过置 `SKIPPED` 且计 0 分、`SUBMITTED/COMPLETED` 禁改禁切、执行中 409、保留组上限 4、固定 12 维对比与「未填写」、联网开关默认关闭。
  - Skill：`java-backend` / `ai-agent` 各含 `SKILL.md` + `skill.meta.yml`，方向列表由资源加载。
  - CI：`e5df181`（M2 第二批后端）运行 **success**。
- 差异：`PROGRESS.md` 记 schema 116，实测 115（移除未使用 `ResultOffer` 后的计数口径差异，非缺陷）。
- 下一步 / 断点：M2 第二批前端（模拟面试 / Offer 决策）→ 真实链路联调。

### 17:47 M2 第二批前端完成 + 真实链路联调通过
- 改动：
  - 新增 `frontend/src/api/interview.ts` / `offers.ts`、`utils/interview.ts` / `offer.ts`；页面 `InterviewListView.vue` / `InterviewRoomView.vue` / `OfferView.vue`；路由 `/interviews`、`/interviews/:id`、`/offers`，导航加「模拟面试 / Offer 决策」。
  - 面试房间：创建后轮询出题、逐题作答 / 跳过、追问实时插入、会话级 Provider + Model 切换、交卷轮询评估、总分 / 分类条 / 逐题点评、失败重试。
  - Offer：列表 / 对比双 Tab、文本图片提取回填、保留淘汰分组、固定 12 行矩阵、行内前景分析。
  - 测试：新增 Vitest 10 个（纯函数）与 Playwright 2 个（接口 mock），Vitest 总 19、E2E 总 11。
- 原因：按契约 `1.8.0` 补齐 M2 第二批前端，并用真实链路验证前后端与 V5 迁移。
- 验证：
  - `npm run build` 通过（含契约类型生成）。
  - `npm run test` 19 全过；`npm run test:e2e` 11 全过。
  - 真实链路：临时 PostgreSQL 16（5433）+ 本地假上游（3001）+ 真后端（8080）+ Playwright 浏览器；面试创建 → 出题 → 作答 → 追问 → 跳过 → 交卷 → 评估 69 分；Offer 新增 → 前景分析「值得接受」→ 对比矩阵；临时容器、脚本与进程已清理。
- 下一步 / 断点：进入 M3（F4 面试复盘 / F5 求职洞察）。
