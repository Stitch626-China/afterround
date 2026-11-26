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
