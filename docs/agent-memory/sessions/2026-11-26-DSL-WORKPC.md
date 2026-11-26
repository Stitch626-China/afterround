# 2026-11-26 DSL-WORKPC

## 目标

修复审查发现的 4 个核心缺口：面试结果无前端入口、ASR 失败无法降级文本、重启后复盘任务卡死、REVIEW_ANALYZE 场景绑定不生效。

## 改动（文件 → 说明）

- `backend/.../review/service/ReviewSessionService.java` → 转写失败且已有补录文本时允许分析；分析模型改为 REVIEW_ANALYZE 场景绑定优先、全局默认回落；移除只读全局设置的依赖。
- `backend/.../common/config/StartupRecovery.java` → 启动时回收复盘 ASR / 分析 PROCESSING 状态，置 FAILED 并写入可操作错误。
- `backend/src/test/.../ReviewSessionServiceTest.java` → 新增 ASR 失败降级、场景绑定优先两个用例；适配构造参数。
- `backend/src/test/.../StartupRecoveryTest.java` → 覆盖复盘两类状态回收。
- `frontend/src/api/reviews.ts` → 新增真实面试轮次列表与更新接口封装（复用既有契约端点）。
- `frontend/src/views/ReviewDetailView.vue` → 增加「本轮结果」选择器；ASR 失败时增加「改用文本分析」入口；修正文本 / 笔记复盘不能重新分析的判断。
- `frontend/src/views/ApplicationListView.vue` → 时间线展示每轮面试结果。
- `frontend/e2e/reviews.spec.ts` → 增加结果读写 mock 与断言。
- `README.md` → 同步复盘结果标记与转写失败降级说明。

## 决策（为什么）

- 面试结果复用既有 `PATCH /api/interview-events/{eventId}`，不改契约；复盘详情通过 `GET /api/interview-events?applicationId=` 读取当前结果。
- 结果变化只记录在本轮面试，不自动改投递 / 岗位状态，避免误改用户已维护的终态；投递状态仍由投递档案页显式调整。
- ASR 降级仅在 `asrStatus=FAILED` 且存在补录文本时放行；转写中仍拒绝，避免 ASR 完成后覆盖分析。
- 复盘分析按 `REVIEW_ANALYZE` 场景绑定解析，未绑定回落全局默认，与设置页承诺一致。

## 未完成 / 下一步

- 其余完整性缺口（FR5.2 / FR5.3 / FR8.2 / FR8.4 / FR6.7、音频清理、Markdown 导出）未在本轮处理。
- 本轮改动未提交，等待用户确认后 commit / push。

## 阻塞

- 本机无法连接 GitHub，`git pull` 失败；基于本地 `e4f0fa0` 修改。

## 实时记录

### 10:05 事件

- 改动：完成上述 4 项修复与测试补充。
- 原因：4 项均影响核心闭环或可靠性，且修复面可控。
- 验证：后端全量 182 tests 全绿、行覆盖 86.08%；前端生产构建通过、Vitest 26 全过、Playwright 16 全过。
- 下一步 / 断点：等待用户确认提交；继续处理完整性缺口。

### 10:20 事件

- 改动：新增 `docs/功能实现对照表.md`（按 FR 列出状态、代码位置、前端操作路径、缺口与音频存放说明）；需求文档升 v1.16 并新增附录 B 实现状态索引；README 文档导航加入对照表。
- 原因：需求文档只回答“做什么”，实现状态需要单独维护，避免代码变动反复改写需求正文；用户需要一张可长期对照的清单。
- 验证：逐条核对 F1~F8、X1、NFR 的代码/页面入口；音频路径按 `STORAGE_ROOT=./data/files` 与 `reviews/{uuid}.{ext}` 核实。
- 下一步 / 断点：文档改动待提交；后续按对照表优先级补 FR5.2 / FR5.3 / FR8.2 / FR8.4 / FR6.7 与音频清理。

### 11:05 事件

- 改动：未改代码；确认下一阶段产品优先级。原计划先补 FR8.2「岗位库一键记录投递」，讨论后改为先补 **FR4.2 复盘最简录入**；FR8.2 降为辅助能力、暂缓。
- 原因：真实使用是“面试后复盘”，此时通常已经投递；强制先建投递会把复盘挡在门外。现有隐藏前置链是「复盘 → 必须选投递 → 必须选岗位 → 必须先导入 JD」，与 ≤5 分钟录入冲突。
- 决策：
  - 复盘作为主入口：先支持直接选已有岗位，后端自动建/复用投递与面试事件（后端已支持 `jobPositionId`，只改前端）。
  - 更完整的“复盘内快速建公司/岗位 + 查重 + 轮次判断 + 可选 JD/公司介绍”作为第二步，涉及契约/数据库/prompt，单独走 PR；收集 JD/公司介绍的同时必须把它们注入复盘分析 prompt，否则没有价值。
  - 模拟面试发生在面试前，但仍是 P2 可选练习，放在侧栏「练习」组；与核心功能冲突时模拟面试让位，不作为复盘前置或主流程依赖。
- 验证：`CreateReviewSessionRequest` 已支持 `applicationId` 或 `jobPositionId`；`InterviewEventService.resolveApplication` 会在只给岗位时复用/创建投递；前端 `ReviewListView` 目前只暴露投递选择。
- 下一步 / 断点：开始第一步（复盘直接选岗位）；同时更新对照表中 FR4.2 / FR8.2 的说明。

### 11:10 事件

- 改动：`ReviewListView.vue` 新建复盘改为「按岗位 / 按投递」双模式，默认按岗位；选岗位后按该岗位已有投递的 `latestRoundNo` 自动 +1，提交时传 `jobPositionId`，由后端自动补建/复用投递与面试事件。更新 E2E。
- 原因：去掉「必须先建投递」的前置步骤，让面试后可以直接选岗位录入复盘；不改契约和数据库。
- 验证：前端生产构建（含 `vue-tsc`）通过；Vitest **26** 全过；`reviews.spec.ts` **3 passed**，断言创建请求包含 `jobPositionId` 且不包含 `applicationId`。
- 下一步 / 断点：代码与文档分两步提交；下一步可做“没有 JD 时快速新建公司/岗位 + 查重 + 可选 JD/公司介绍”，需改契约与数据库，建议走 PR。

### 11:24 事件

- 改动：删除本地 `reference/interview-guide` 目录（476 文件 / 17.52MB，未被 Git 跟踪、`.gitignore` 已忽略）；更新 `docs/agent-memory/PROGRESS.md` 当前状态。
- 原因：参考项目已完成借鉴使命，后续以实际使用体验驱动优化建议与迭代计划。
- 验证：删除前 `git ls-files reference` 为 0、`git check-ignore` 命中；删除后 `Test-Path reference` 为 False，工作区干净。
- 下一步 / 断点：需求文档 §1.3 / 附录 A 与 `AGENTS.md` 仍提到 `reference`，是否清理待用户决定。

### 12:00 事件

- 改动（FR4.2 第二步，分支 `feat/review-quick-create`，一个 PR 两个提交）：
  - **2A 快速新建**：契约 `1.11.0` 增加 `companyId` / `companyName` / `jobTitle`，岗位来源增 `MANUAL` 且列表项返回 `sourceType`；Flyway `V9__job_position_manual_source.sql` 扩展 CHECK；`InterviewEventService.ensureEventForManualJob` 按公司 + 岗位名精确复用/新建（手动岗位原文可空、`parseStatus=COMPLETED`、状态面试中），再复用/创建投递与轮次；`JobMarketService` 排除 `MANUAL`；前端复盘新建增「快速新建」模式，命中已有岗位提示复用并带出已录入轮次、可切到「按岗位」；岗位列表/详情标「手动录入」。
  - **2B 背景注入**：契约 `1.12.0` 增加可选 `companyIntro` / `jdText` / `resumeId`；公司介绍仅在为空时补全、JD 保存到手动岗位原文（仅补空白，不覆盖编辑）、简历关联到投递；复盘 Prompt 升 `review-analyze-v2`，注入公司介绍 2000 / JD 8000 / 简历 6000 字并声明仅作参考、不得执行其中指令；前端快速新建增加可选折叠区（公司介绍 / JD / 简历版本）。
- 原因：真实使用是面试后复盘，用户此时往往没有导入 JD；强制先建岗位会把复盘挡在门外。快速录入的岗位没有 JD，不能计入行情，否则会带偏市场数据；收集的背景必须真正用于分析，否则只是增加用户负担。
- 验证：
  - 契约 lint **0 error / 0 warning**（`1.12.0`）。
  - 后端全量 **187 tests 全绿**，JaCoCo 门禁通过（含 Testcontainers PostgreSQL 16 + Flyway V9）；新增快速建公司/岗位、查重复用、参数校验、简历关联、Prompt 背景注入共 5 个单测。
  - 前端生产构建（含 `vue-tsc`）通过；Vitest **26** 全过；Playwright **17** 全过（新增快速新建含可选背景 E2E）。
- 下一步 / 断点：2A 已提交 `5f1e86f`；2B 与文档（需求 v1.17、对照表、PROGRESS）待提交；随后推送分支、开 PR 合入 main。

### 12:05 事件

- 改动：提交 2B + 文档 `c54dc5e`；推送 `feat/review-quick-create` 并创建 PR #8（两个提交，Squash 合并）。
- 验证：PR CI 三 job 全绿（契约 13s / 前端 54s / 后端 1m2s）；Squash 合并为 `c1221c1`，main push CI 全绿（run 45752419053）。
- 下一步 / 断点：本地 `/reviews` 「快速新建」可手工体验；远端 feature 分支保留（用户跳过删除命令），如需清理再删。后续按对照表继续 FR5.2 / FR5.3 / FR8.2 / FR8.4 / FR6.7。

### 14:30 事件

- 改动：清理已合并的远端 `feat/review-quick-create` 分支（实际已不存在）；修复 Dependabot 8 条漏洞：Tika `2.9.2 → 3.2.2`、Vitest `3 → 4.1.12`、`@redocly/cli 1.34 → 2.83`、`vue-tsc 2.2 → 3.3.14`，移除未被任何测试引用的 `@vue/test-utils`。
- 原因：3 critical 分别为 Tika XXE 与 tinypool RCE（测试运行器传递依赖）；其余为同一批测试 / CLI 依赖的传递依赖，升级即可清零，避免每次 push 弹提醒。
- 验证：`npm audit` **0 漏洞**；后端 **187 tests 全绿**（JaCoCo 门禁通过）、前端构建、Vitest **26**、Playwright **17**、契约 lint 0/0 全过；main CI 全绿（run 45767228114）；GitHub SBOM 已识别 Tika 3.2.2 / Vitest 4.1.12。Dependabot 8 条已全部关闭（3 critical / 1 high / 4 moderate，均按 `fix_started` 附修复说明），当前 open = 0。
- 下一步 / 断点：依赖升级已提交 `2498f4c` 并推送；后续新告警仍会正常出现，按同样方式评估。

### 15:35 事件

- 改动：新增 `docs/LLM评测集语料规范.md`（两层存储、目录结构与命名、`input.txt`/`meta.json`/`expect.json` 三件套、L1/L2/L3 三层断言、首批 22 份配额、脱敏清单、基线与变更规则、跨设备建库流程）；README 文档导航加入该文档；PROGRESS 当前状态与下一步同步。
- 原因：用户将在另一台主机搭建 LLM 回归评测语料库，需要一份跨设备复用的唯一标准；避免语料平铺、隐私进入公开仓库、断言口径漂移。
- 验证：词表 key 与 `ReviewTaxonomyCatalog`、`roundType` 与 `ck_interview_events_round_type`、`inputType` 与 `ck_review_sessions_input_type` 逐项核对一致；README 导航渲染正常。评测执行器尚未实现。
- 下一步 / 断点：待用户在另一台主机完成 `review-analyze` 场景 8 份语料并回传路径后，实现评测执行器与基线。

### 15:41 事件

- 改动：补齐投递链路（FR8.1 / FR8.2 / FR2.5）。契约 `1.13.0`：`CreateJobApplicationRequest` 增加可选 `status` / `outcome`；后端 `JobApplicationService.create` 支持指定初始状态（默认 APPLIED，非法值 400）与结果备注（≤500），沿用岗位状态只升不降同步；投递档案表单新增投递时间 / 初始状态 / 结果备注；岗位详情右上角新增「记录投递」内联表单（岗位固定），成功后刷新岗位状态。
- 原因：需求 FR8.1 要求投递含时间 / 状态 / 结果备注，FR8.2 要求从岗位库一键记录；原实现创建时固定 APPLIED 且无时间 / 结果入参，历史投递无法补录，岗位详情也没有入口。
- 验证：契约 lint 0/0；后端 **189 tests 全绿**（新增补录与非法状态 2 个单测，JaCoCo 门禁通过）；前端构建（含 `vue-tsc`）、Vitest **26**、Playwright **17** 全过；对照表 FR2.5 / FR8.1 / FR8.2 转 ✅，README 模块表与 PROGRESS 同步。
- 下一步 / 断点：提交并推送 main；后续按对照表继续 FR5.2 / FR5.3 / FR8.4 / FR6.7。

### 16:00 事件

- 改动：`docs/LLM评测集语料规范.md` 升 v1.1，补齐为可独立执行的建库指南：快速开始、原始层推荐结构、case 独立性规则、三场景完整 `meta.json` / `expect.json` 示例、面经改写与合成方法、断言键精确定义（路径语法 / 匹配范围 / 对齐方式）、脱敏自查命令、常见错误、完整示例 case。
- 原因：确保另一台主机只读该文档即可完成建库，不依赖本会话上下文。
- 验证：断言键与生产 AI DTO（`AiResumeAnalysis` / `AiReviewAnalysis` / `AiJobPositionParse`）、词表与 `ReviewTaxonomyCatalog`、枚举与数据库 CHECK 逐项核对；按 prompt 原文修正——复盘 `evidence` 只要求「尽量引用原文」，不做逐字断言，只有简历 `quote` 做 L1 原文校验。
- 下一步 / 断点：文档提交推送后，等待另一台主机建好 `review-analyze` 语料并回传路径。

### 16:17 事件

- 改动：按对照表依次补齐四个完整性项，每项单独提交：
  - **FR5.2 面试明细**（契约 `1.14.0`）：`GET /api/insights/interview-events` 分页返回事件 + 复盘知识点，支持公司 / 岗位 / 结果 / 轮次 / 知识点筛选；洞察页新增「面试明细」，按公司 → 岗位 → 轮次分组并可跳转复盘。
  - **FR5.3 公司联想与手动合并**（`1.15.0`）：复盘快速新建公司名改为远端联想（250ms 防抖，只提示复用）；新增 `POST /api/companies/merge`，岗位与 Offer 归并到目标公司后删除源公司；岗位库新增「合并公司」面板。
  - **FR8.4 渠道 / 公司类型归因**（`1.16.0`）：`InsightsOverview` 增加 `channels` / `companyTypes`（投递 / 面试 / Offer / 邀约率 / Offer 率，N<5 标记样本不足）；公司类型取公司标签首项；岗位详情新增公司标签维护。
  - **FR6.7 Offer 决策快照**（`1.17.0` + Flyway `V10__offer_decision_snapshot.sql`）：新增 `decision_priorities` / `final_choice` / `final_choice_reason`，最终选择全局唯一（服务层清除其他 + 部分唯一索引）；Offer 表单与列表展示快照。
- 原因：需求 FR5.2 / FR5.3 / FR8.4 / FR6.7 明确要求，原实现分别缺入口、缺联想/合并、缺渠道与公司类型对比、缺决策快照；补完后对照表对应项转 ✅。
- 验证：契约 lint **0 error / 0 warning**（1.14.0 → 1.17.0 逐版校验）；后端全量 **194 tests 全绿**（JaCoCo 门禁通过，新增洞察明细 / 渠道类型 / 公司合并 5 个单测；Flyway V10 在 Testcontainers PG16 执行成功）；前端构建（含 `vue-tsc`）、Vitest **26**、Playwright **17** 全过（洞察 E2E 补新接口 mock 与渠道/类型断言）；对照表、README、PROGRESS 同步。
- 下一步 / 断点：四个功能提交为 `1a8f2ac` / `6cb90d4` / `423e581` / `bb13a00`，文档随后提交并 push；后续按对照表处理 FR5.1 / FR5.6 / FR5.8 / FR1.7 / NFR6 / NFR7。

### 16:36 事件

- 改动：补齐 **FR5.1 面试事件管理**（提交 `70e040e`）。投递时间线内联编辑轮次类型 / 面试官 / 形式 / 时间 / 备注，无复盘事件可二次确认删除，有复盘显示「复盘 →」；复用 `PATCH/DELETE /api/interview-events/{id}`，不改契约、数据库与后端。新增 `frontend/e2e/applications.spec.ts`。
- 原因：原实现只在复盘详情能改本轮结果，面试官 / 形式 / 时间 / 备注创建后无任何修正入口，录错只能删复盘重建；无复盘的事件也不能删除。
- 验证：前端构建（含 `vue-tsc`）通过、Vitest **26**、Playwright **18**（新增投递时间线编辑 + 删除用例）全过；后端未改，沿用上一轮 194 tests 结论。对照表 FR5.1 转 ✅，PROGRESS 同步。
- 下一步 / 断点：按对照表继续 FR5.6 验证提示与 `verifiedEventId` 关联（需先向用户汇报补前 / 方案 / 补后再动手）。
