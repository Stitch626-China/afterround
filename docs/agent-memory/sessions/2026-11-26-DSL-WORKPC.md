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
