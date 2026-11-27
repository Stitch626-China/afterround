# 2026-11-27 DSL-WORKPC

## 目标

抽检另一台主机产出的 27 份 LLM 评测语料，在此基础上实现评测执行器、断言引擎与基线。

## 改动（文件 → 说明）

- `backend/src/test/resources/llm-eval/cases/review-analyze/003`、`004` → 真实转写中的绝对时间（毕业时间、比赛时间）替换为 `[时间]`，降低准标识符风险。
- `backend/src/test/resources/llm-eval/cases/resume-analysis/*/expect.json`（10 份）→ 移除 `maxItems` 对 strengths / weaknesses / suggestions 的 5 条限制；prompt 只约束 careerDirections 3-5 个，原断言超出契约。
- 新增 `backend/src/test/java/com/jobpilot/eval/EvalCase.java` → 单份语料模型。
- 新增 `backend/src/test/java/com/jobpilot/eval/EvalCorpus.java` → 目录扫描与结构 / 字段 / 词表 / 隐私校验；默认测试直接执行。
- 新增 `backend/src/test/java/com/jobpilot/eval/Expectations.java` → 路径解析、L1/L2 断言、L3 稳定性。
- 新增 `backend/src/test/java/com/jobpilot/eval/LlmEvalTest.java` → `@Tag("llm-eval")` 真实模型执行器、报告、基线读写。
- 新增 `ExpectationsTest` / `EvalCorpusTest` → 13 个单元测试；真实语料合规检查纳入默认测试。
- `backend/pom.xml` → surefire 默认排除 `llm-eval`，通过 `-Dsurefire.excludedGroups=__none__ -Dgroups=llm-eval` 覆盖。
- `backend/src/main/java/com/jobpilot/common/llm/StructuredOutputInvoker.java` → 重试错误反馈由 system 角色改为 user 角色；严格网关不接受末尾 system 消息。
- `backend/src/main/java/com/jobpilot/common/llm/OpenAiCompatibleClient.java` → 新增可配置超时构造器，生产默认仍 120s。
- `docs/LLM评测集语料规范.md` 升 v1.2 → 补执行器运行方式、环境变量、输出与判定、成本控制；README 测试章节补评测入口。

## 决策（为什么）

- 语料校验不调用模型，放进默认 `mvn test`，保证 CI 与无密钥环境也能守住语料质量。
- 评测使用 8192 token / 300s 超时，是为了测 prompt 质量，不是复现生产 NFR7 的 4096 token 与 120s 上限；生产限制另行评估。
- 语料断言失败不通过修改语义来「让模型过」；引用非原文、失分标签缺失等先作为真实模型行为记录。
- 公网 DeepSeek 批量评测偶发挂起，基线改用内网 `datangsoft` / `Qwen3.8-27B`。

## 未完成 / 下一步

- 全量基线生成中（27 份 × 1 次，内网 Qwen）；完成后复核报告与基线，处理 3 个简历引用断言失败与 1 个复盘 lossTag 失败。
- 复核后运行后端全量测试（含 JaCoCo 门禁），恢复验收数据库容器为停止状态。
- 提交并推送评测执行器、语料修正、文档与基线。

## 阻塞

- 公网 DeepSeek 在批量长文本调用时出现长时间无响应；已切换内网网关。

## 实时记录

### 12:10 事件

- 改动：完成 27 份语料结构抽检（场景 / 命名 / meta / expect / 词表 / 断言键 / BOM / 隐私正则），0 个结构问题；修复 3 处真实转写绝对时间。
- 原因：公开仓库语料需先过隐私与口径检查，再实现执行器。
- 验证：Node 抽检脚本 0 问题；`mustMentionAny` 关键词在输入中可达。
- 下一步 / 断点：写语料校验与断言引擎测试。

### 12:30 事件

- 改动：按 TDD 完成 `EvalCase` / `EvalCorpus` / `Expectations`；新增 13 个测试并全部通过；`LlmEvalTest` 与报告 / 基线读写完成；pom 增加默认排除。
- 原因：默认测试不调用模型，真实调用显式触发。
- 验证：`mvn -o -Djacoco.skip=true -Dtest=ExpectationsTest,EvalCorpusTest test` 通过；`test-compile` 通过。
- 下一步 / 断点：小样本真实调用，修正语料断言偏差。

### 12:45 事件

- 改动：小样本发现简历 `maxItems` 断言超出 prompt 约束，已修正 10 份 expect；`StructuredOutputInvoker` 重试末尾 system 消息导致严格网关 400，改为 user 角色并新增回归测试。
- 原因：评测暴露真实兼容性缺陷；system 只能位于消息开头。
- 验证：新增测试先失败后通过；`StructuredOutputInvokerTest` 4 个测试全绿；内网 Qwen 探测 HTTP 200 / 563ms。
- 下一步 / 断点：内网 Qwen 全量基线生成中（8192 token / 300s 超时 / repeat=1）。

### 13:30 事件

- 改动：首版基线生成完成（27 份全量，内网 `Qwen3.8-27B`，8192 token / 300s / repeat=1），写入 `backend/src/test/resources/llm-eval/baselines/`；修正复盘负样本 008 的 `maxItems.questions` 与过严的 `shallow-depth` 禁项；执行器 L2 判定改为规范要求的整轮断言通过率 95%，`quotesMustComeFromInput` 失败详情带首条原文。
- 原因：抽检发现 008 断言超出 prompt 契约；原实现「任一 L2 失败即整轮失败」与规范「L2 通过率 ≥95%」不一致。
- 验证：全量 27 份结果——L2 断言 45 项、失败 2 项（95.6%，达标）；L1 硬失败 1 项 `resume-analysis/002`（引用用省略号拼接两段原文）；三份基线 10 / 8 / 9 个 case、无 null、模型均为 `Qwen3.8-27B`；后端 **229 tests 全绿**（JaCoCo 门禁通过）。验收库容器 `jobpilot-verify-pg` 已停止。
- 下一步 / 断点：提交并推送；`resume-analysis/002` 属 prompt 约束不足，后续补强引用规则后重建基线；`review-analyze/003`、`004` 的 L2 标签未命中在阈值内，作为波动观察。

### 17:35 事件

- 改动：简历 Prompt 升 `resume-analysis-v3`，引用规则补「必须是原文中连续出现的一段，不得用省略号拼接多个片段」；全量 27 份重建基线。修复复盘基线分数对齐：由数组下标改为 `questionIndex` + 维度名（`q1.score` / `q1.depth`），并在 `Expectations.stability` 同步按 `questionIndex` 对齐；新增回归测试先失败后通过。
- 原因：`resume-analysis/002` 持续用省略号拼接两段原文；旧基线按题目数组下标比较，而复盘每次抽取的题目数量/顺序不同，产生 20~50 分假漂移。
- 验证：27 份全量 L1 全部通过；L2 断言 45 项失败 2 项（95.6%，达标，003 `knowledge-gap`、004 `no-evidence` 未命中）；新基线复盘键为 `qN.score` / `qN.<dimension>`，无下标残留、无 null。不依赖 Docker 的 230 个单元测试全绿（23 个集成测试跳过）。
- 阻塞：Docker Desktop 升级 29.9.3 后 Testcontainers 1.21 探测失败；`~/.testcontainers.properties` 固定旧 `docker_engine` 策略，已征得用户同意修改，但被路径白名单拒绝。JaCoCo 门禁需在修复 Docker 探测后补跑。
- 下一步 / 断点：提交推送；修复 Docker/Testcontainers 环境（删除用户级固定策略或开启 TCP 2375）后补跑 `mvn test` 与覆盖率。

### 18:10 事件

- 改动：完成 P1 后端 Task 1~4。契约升 1.22.0，新增提示词工作台 8 端点与 schema（总数 101）；新增 `V11__llm_prompt_versions.sql`；新增 `PromptCatalog`（12 组）、`PromptValidator`、`PromptRegistry`（DB ACTIVE 优先 + classpath 兜底 + 精确失效）、`PromptService`、`PromptController` 与 DTO；新增 `PromptServiceTest`、`PromptApiIntegrationTest`；移除 8 个提示词端点的 `x-implemented: false`；开发对照表前两行改为已完成。
- 原因：提示词查看/草稿/发布/回滚后端闭环，发布只热更新、不自动触发评测。
- 验证：`mvn -f backend/pom.xml clean test`（Docker 可用、非沙箱）253 tests、0 failures、0 skipped、JaCoCo 全部达标；`PromptApiIntegrationTest` 覆盖列表/内置兜底/草稿/发布/版本历史/回滚/无效草稿 400/丢弃幂等/404。Docker 探测以 `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` 解决，无需改用户级配置。
- 下一步 / 断点：Task 5 让 8 个 PromptFactory 接入 `PromptRegistry` 并记录动态版本；之后做 P1 前端工作台。

### 18:14 事件

- 改动：8 个 `PromptFactory`（简历/简历对比/JD 解析+OCR/岗位匹配/复盘/洞察/面试三组/Offer 两组）全部改为调用 `PromptRegistry.template(key)`，保留无参构造供测试与评测走内置模板；新增 `version()` / `parseVersion()` / `ocrVersion()` / `questionVersion()` / `followUpVersion()` / `evaluateVersion()` / `extractVersion()` / `analysisVersion()`；`ResumeAnalysisService`、`ReviewSessionService` 记录版本改为动态值；新增 `PromptFactoryTest` 8 个测试。
- 原因：发布提示词后无需重启即可生效；分析结果记录的版本必须与实际调用模板一致，否则对比会误判。
- 验证：`mvn -f backend/pom.xml clean test`（Docker）261 tests、0 failures、0 skipped、JaCoCo 全部达标；`PromptFactoryTest` 覆盖 8 个工厂的 DB 模板、占位符替换与版本标签。
- 下一步 / 断点：Task 6 前端 LLM 工作台（入口、模型与绑定、提示词编辑/发布/历史/diff、调用统计迁移）。

### 18:22 事件

- 改动：新增侧栏底部「LLM 工作台」入口与 `/llm-workbench` 路由；工作台 3 个 Tab：模型与绑定（全局 + 7 场景 + 3 个评测场景生效模型，内联编辑、手动保存、全局二次确认）、提示词（12 组列表、system/user 编辑、即时校验、diff/恢复内置、草稿/发布/回滚、版本历史、未保存三选一拦截）、调用统计（迁移 `UsageSettings`）；设置抽屉移除调用统计并保留迁移链接；新增 `api/prompts.ts`、`utils/prompt.ts` 及 8 个单测、`llm-workbench.spec.ts`。
- 原因：提示词与模型变更必须由用户主动保存/发布，且不自动触发评测；调用统计与评测实验分开入口。
- 验证：`npm run build` 通过；`npm run test` 36 tests 全过；`npm run test:e2e` 21 tests 全过（含新工作台用例与设置抽屉三 Tab 回归）。
- 下一步 / 断点：Task 7 P1 文档、README、需求文档 F9 与 PROGRESS 收口；随后进入 P2 评测运行器。

### 18:27 事件

- 改动：P1 收尾文档。需求文档升 v1.19，新增 F9「LLM 工作台」与迭代历程（产品语言：多了什么 / 变了什么 / 砍了什么）；README 核心能力新增工作台与调用统计入口、测试指标更新为 261 / 36 / 21 / 101 端点；功能实现对照表新增 F9（FR9.1~9.8）并迁移 NFR6 入口；PROGRESS 更新当前状态、阻塞（Docker 已用 `DOCKER_HOST` 解决）、下一步（P2 评测运行器）与 3 条关键决策；实施计划开发对照表 P1 行全部标为已完成。
- 原因：统一「运行 = 评测、基线 = 贴标签的运行、发布不自动评测」的术语与入口，避免文档、实现和验收口径脱节。
- 验证：契约 lint 0/0；后端 `clean test` 261 tests、0 failures、0 skipped、JaCoCo 全部门禁通过；前端 `npm run build` 通过、Vitest 36、Playwright 21 全过。
- 下一步 / 断点：P1 完成，按计划进入 P2 Task 8：契约 1.23.0 评测 5 端点。

### 18:35 事件

- 改动：契约升 1.23.0，新增评测运行 5 端点（`GET /api/llm/eval/scenes`、`POST/GET /api/llm/eval/runs`、`GET /runs/{runId}`、`POST /runs/{runId}/cancel`）与 12 个 schema（`EvalMetrics`、`EvalCaseResult`、`EvalRunSummary/Detail`、`EvalSceneSummary`、`EvalBaselineSummary`、`CreateEvalRunRequest` 及结果包装），路径暂标 `x-implemented: false`；`ErrorCode` 新增 `EVAL_RUN_IN_PROGRESS(40918)`、`EVAL_RUN_FINISHED(40919)`；`ContractCoverageTest` 总数 101 → 106。
- 原因：P2 评测运行器先落契约，后端与前端按契约实现；运行始终由用户主动触发，运行不等于基线。
- 验证：`npx @redocly/cli lint contracts/openapi.yaml` 0 error / 0 warning；`ContractCoverageTest`、`ContractSchemaTest` 均通过（待实现端点不计入双向全等）。
- 下一步 / 断点：Task 9 评测核心迁入运行时、27 份语料与 3 份基线迁至 main resources 并改为 classpath 加载。

### 18:38 事件

- 改动：评测核心从 test scope 迁入 `com.jobpilot.modules.eval.core`：`EvalCase`（附属文件改为 `Map<String,String>`）、`EvalCorpus`（新增 `ResourcePatternResolver` 加载 `classpath*:llm-eval/cases/*/*/meta.json`，保留 `Path` 校验给临时语料）、`Expectations`；新增 `EvalSnapshot`、`EvalMetrics`、`EvalMetricsCalculator`（L1 按用例 / L2 按断言 / p95）、`EvalFingerprint`、`EvalEngine(VERSION=llm-eval-v1)`。27 份语料与 3 份基线迁至 `src/main/resources/llm-eval/`；基线补 `promptVersion`（简历 v2 / JD v1 / 复盘 v2）、`engineVersion`、`params`。`LlmEvalTest` 改 classpath 加载；`EVAL_WRITE_BASELINE` 只写 `target/llm-eval/baselines`（可用 `EVAL_BASELINE_OUT` 覆盖），不再写源码目录。
- 原因：语料要随包发布，评测执行器要在运行时被 `EvalRunService` 复用；CLI 写基线不能污染仓库。
- 验证：`EvalCorpusTest` classpath 27 份通过（classpath 多根重复时按 场景/id 去重）；新增 `EvalMetricsCalculatorTest`（通过率、tokens、P95 最近秩）；后端 `clean test` **264 tests、0 skipped、JaCoCo 全部门禁通过**。
- 下一步 / 断点：Task 10 V12 + `EvalRunService` 异步执行；真实模型 CLI 冒烟需用户主动触发，未自动执行。

### 18:50 事件

- 改动：新增 `V12__llm_eval_runs.sql`（运行 + 逐用例；状态 CHECK、活跃运行唯一索引、部分唯一索引防并发）；`LlmEvalRun/LlmEvalRunCase` 实体与 mapper；`EvalSceneCatalog`（3 场景：简历 10 / JD 9 / 复盘 8，含语料哈希）；`EvalClientFactory`（评测专用 8192 token / 300s）；`EvalRunService`（校验 + 快照 + 事务 afterCommit 调度 + 分页/详情/取消）；`EvalRunner`（逐用例断言、token 埋点、连续 3 次上游故障熔断、取消/失败清理剩余用例）；`EvalRunRecovery`（启动把 PENDING/PROCESSING 置 FAILED、剩余用例 SKIPPED）；`EvalController` 5 端点；`AsyncConfig` 增加 `evalTaskExecutor`；`LlmUsageService` 补 `EVAL_*` 中文标签；`application.yml` 补 `app.eval` 与评测线程池；契约移除 5 个 `x-implemented: false`，`ContractCoverageTest` 纳入 `EvalController`。
- 原因：评测运行产品化，始终由用户主动触发；模型与提示词用启动快照，运行中改配置不影响本次；运行完成不等于基线。
- 验证：`EvalRunServiceTest` 4 个、`EvalRunnerTest` 2 个（含熔断 9 次调用）、`EvalApiIntegrationTest` 3 个（真实 PG + 假上游，10 例跑完 COMPLETED、tokens 3000、40918/40919、启动恢复）全过；后端 `clean test` **273 tests、0 skipped、JaCoCo 全部门禁通过**；契约 lint 0/0。
- 下一步 / 断点：Task 11 前端评测运行面板（scenes 卡片、手动开始、轮询、取消、详情）。

### 19:20 事件

- 改动：前端新增 `api/eval.ts`（5 端点、类型由契约生成）、`utils/eval.ts` + 5 个单测、`EvalRunPanel.vue`；工作台增加「评测运行」Tab（场景卡片、全量 27 例、repeat 1/2、二次确认、3 秒轮询、进度、取消确认、metrics / 模型与提示词快照 / 逐用例表、历史运行；「设为基线」置灰待 P3）；未配置模型场景禁用勾选并给「去配置」；`playwright.config.ts` 固定 `outputDir` 与 list reporter；E2E 追加评测用例。
- 原因：评测必须由用户主动触发，页面只做展示与控制；运行中改配置不影响启动快照。
- 验证：`npm run build` 通过；Vitest **41** 全过；Playwright 22 个用例已编写，执行被沙箱策略拒绝（Edge 用户目录 / npm 缓存越界），未获得本次 22 全过证据，待本机 `npm run test:e2e`。
- 下一步 / 断点：Task 12 P2 文档与回归，然后进入 P3 基线版本化。

### 19:22 事件

- 改动：P2 收口文档。需求文档升 v1.20：新增 v1.20 迭代历程与 F9「评测运行」FR9.9~9.13（运行范围、手动确认、进度取消、结果口径、EVAL_* 成本归因）；README 增加评测运行能力 / 语料新路径 / CLI 基线写 target；`ContractSchemaTest` 纳入 9 个评测 DTO 并验证字段一致；PROGRESS 更新状态、阻塞、下一步与 3 条决策；实施计划 Task 8~12 全部勾选（E2E 标注待本机）。
- 原因：让「运行 = 评测、运行完成 ≠ 基线、发布不自动评测」的口径在需求、README 与验收表一致。
- 验证：契约 lint 0/0（106 端点）；后端全量 273 tests、JaCoCo 通过；`ContractSchemaTest` 单独通过；前端构建 + Vitest 41 通过。
- 下一步 / 断点：P3 Task 13：契约 1.24.0 + V13 + 基线服务（设为基线不重跑）。

### 19:28 事件

- 改动：契约升 1.24.0，新增 4 个端点（基线列表 / 设为基线 / 设为当前 / 运行对比；对比按计划暂标 `x-implemented: false`），新增 `EvalBaselineResult`、`CreateEvalBaselineRequest`、`EvalRunCompare*`、`PromptImpactSuggestion` 等 schema，并扩展 `PromptDetail.suggestedEvalScenes`、`EvalRunSummary.partial`、`EvalRunDetail.baselines`、`CreateEvalRunRequest.sourceRunId/retryErrorsOnly`；新增 V13 `llm_eval_baselines`（历史保留、每场景一个当前基线、run 删除 RESTRICT）；新增 `LlmEvalBaseline` 实体 / mapper、`EvalBaselineService`（设为基线复用运行结果、逐场景跳过 ERROR、activate 只换标记）、`LegacyBaselineImporter`（启动且基线表与 COMPLETED 运行均为空时导入 3 个 JSON，简历 v2→legacy、JD/复盘写当前 hash）；`EvalController` 增加 3 个基线端点；`ContractCoverageTest` 总数 110；`EvalRunService` 详情补 `partial` 与 `baselines`。
- 原因：基线是「被贴标签的运行」，设为基线不重跑；历史 JSON 只做首次导入，产品基线只走 DB；`is_current` 只做标记，历史不覆盖。
- 验证：`ContractSchemaTest` 通过（新增 12 个 DTO 字段对齐）；`EvalBaselineServiceTest` 4 个 + `LegacyBaselineImporterTest` 1 个通过（3 场景 / 27 例 / legacy hash 规则 / 幂等）；后端 `clean test` **278 tests、0 skipped、JaCoCo 全部门禁通过**；契约 lint 0/0。
- 下一步 / 断点：Task 14 影响面分析 + run vs run 4 维对比（含部分重跑），移除 compare 的待实现标记。
