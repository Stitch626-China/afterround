# 2026-11-28 DSL-WORKPC

## 目标

对 P1（提示词工作台）、P2（评测运行器）、P3（基线对比）做全链路审计：契约、数据库、后端、前端、测试、文档逐层核对，修复偏差 / 错误 / 漏洞 / 歧义 / 误解，并给出结论。

## 改动（文件 → 说明）

- `backend/.../eval/service/EvalRunner.java` → 取消终态改条件更新并保留取消优先；负样本上游故障不再当优雅降级；每次逻辑调用单独落 `llm_call_logs`（含输入 / 输出 token）；`finish` 重算计数。
- `backend/.../eval/service/EvalRunService.java` → worker 重试改为最多 3 次（总执行 4 次）；`fail` 先判活跃态并条件更新，避免覆盖已取消；`cancel` 加事务。
- `backend/.../eval/service/EvalRunRecovery.java` → 重启恢复后按实际用例重算 `completed_cases` / `error_cases`。
- `backend/.../llm/service/LlmProviderService.java` → Provider 更新 / 删除时同步失效评测客户端缓存。
- `backend/.../eval/service/LegacyBaselineImporter.java` → 旧基线运行改用组合语料 hash，与正常运行可比；promptHash 仅在旧版本与内置一致时写当前内容 hash。
- `backend/.../eval/service/EvalBaselineService.java` → 基线列表统一分页（`page` / `size`，当前基线在前）；运行语料与当前不一致时拒绝设为基线。
- `backend/.../eval/core/EvalCorpus.java` → 隐私正则覆盖 `jd.txt` / `company-intro.txt` / `resume.txt`；`inputType` 只接受 TEXT / NOTES。
- `backend/.../eval/core/EvalSceneCatalog.java` → 语料指纹纳入可选背景文件。
- `backend/.../prompt/service/PromptService.java` → 发布 / 回滚缓存立即失效并再挂提交后失效，关闭并发回填旧 ACTIVE 的窗口。
- `backend/.../prompt/entity/LlmPromptVersion.java`、`backend/.../eval/entity/LlmEvalBaseline.java` → 空备注 / 空标签可真正清空。
- `contracts/openapi.yaml` → 1.25.0：基线列表分页并改为 `ResultEvalBaselinePage`；补充 `EvalCompareQuality` 左右语义与 `EvalRunCompareSide` 多场景展示口径。
- 前端 → `listEvalBaselines` 改分页、基线列表「加载更多」；`canBaseline` 按干净场景判断；版本注释同步 1.25.0；E2E mock 改分页响应。
- 测试 → `EvalRunnerTest` 补取消 / Provider 故障 / 调用日志断言；`EvalRunServiceTest` 补 3 次重试；`EvalBaselineServiceTest` 补语料变化拒绝；`EvalCorpusTest` 补可选背景文件隐私扫描；`EvalApiIntegrationTest` 恢复计数；`LlmProviderServiceTest` 补缓存失效。
- 文档 → 需求 v1.21 FR9.14 / 迭代段、README、功能实现对照表统一为「有 ERROR 的场景跳过」；语料规范补可选文件隐私扫描、语料指纹范围、基线分页；计划文档补审计修订说明。

## 决策（为什么）

- 基线列表必须分页：历史基线永久保留，违反 AGENTS「列表统一 page/size」硬约束。
- 语料指纹必须包含可选背景文件：这些文件会进入模型输入，遗漏会让语料变更被误判为「可比」。
- 语料变化后禁止把旧运行设为基线：否则基线 hash 写成当前值，影响面会漏报语料变化。
- 旧 JSON 导入运行的语料 hash 改为组合 hash：与正常运行的 `llm_run.corpus_hash` 表示一致，否则首次「基线 vs 新运行」会误报「语料不同」。
- 取消优先且条件更新：用户取消是明确意图，不能被最后一个用例的 `COMPLETED` 覆盖。
- 提示词缓存先失效 + 提交后再失效：单次失效无法覆盖「提交前读到旧 ACTIVE 并回填」的并发窗口。

## 验证

- `npx @redocly/cli lint contracts/openapi.yaml` → valid（0 error / 0 warning）。
- `mvn -f backend/pom.xml clean test` → **294 tests，0 failures / 0 errors / 0 skipped，BUILD SUCCESS**，JaCoCo 门禁通过。
- `npm run build`（含契约类型生成 + vue-tsc）→ 通过；`npm run test` → **42 tests 全过**。
- `npx playwright test --list` → 24 tests / 8 files（仅静态枚举，未启动浏览器）。

## 未完成 / 下一步

1. 本机执行 `cd frontend && npm run test:e2e`（沙箱拒绝 Edge 用户目录，未获授权）。
2. 真实模型手工验收（需用户主动触发，未自动跑评测）。
3. E2E「设为基线」mock 未把新基线写回列表，测试未断言「基线列表出现新 label」，属测试覆盖缺口。
4. 非阻塞遗留：`EvalFingerprint` 未被使用（与 `EvalCompareService` 可比性逻辑重复）；历史 JSON 导入的合成运行会出现在评测历史与场景「上次运行」中，属产品展示取舍。

## 阻塞

- Playwright 真实执行仍被本机沙箱 / Edge 用户目录权限拒绝；未绕过。
- 开工时 `git pull --rebase --autostash` 被安全策略拒绝（未授权改写历史），工作区当时干净，本次未拉取远端。

## 实时记录

### 18:26 部分重跑语料指纹收口
- 改动：`EvalRunService.createPartial` 的场景列表与 `corpusHash` 统一取实际 ERROR 场景的组合值（此前 hash 复制源运行全场景值）。
- 原因：partial 运行再次「重跑 ERROR」时，语料守卫拿全场景 hash 与当前单场景组合值比较，会误判语料不一致并拒绝。
- 验证：后端全量 **298 tests，0 failures / 0 errors / 0 skipped，BUILD SUCCESS**，JaCoCo 全部门禁通过。
- 下一步 / 断点：提交推送；剩余仅真实模型手工验收（需用户主动触发）。

### 18:20 第二轮审计与修复
- 改动：发布说明超长改走 400（`PublishPromptRequest` 补 `@Valid`）；场景「上次运行」逐场景查询；部分重跑要求源语料等于当前且只展示实际重跑场景；影响面纳入 `repeat`；JD 对比不统计分数 / 标签漂移；case id 跨场景唯一校验；评测可选背景文件按生产 2000/8000/6000 字截断；熔断错误截断 500；提示词版本历史支持加载更多；契约 / 需求 / README / 语料规范同步。
- 原因：继续审计发现契约校验缺失、最近运行展示截断、部分重跑语料混用、影响面漏报 repeat、JD 维度泄漏等确定性缺陷。
- 验证：契约 lint 0/0；后端 **298 tests 全绿、0 skipped、JaCoCo 通过**；前端构建 + Vitest **42**；Playwright **24 passed**（沙箱外实跑）。
- 下一步 / 断点：仅剩真实模型手工验收，需用户主动触发。

### 18:05 E2E 实跑与旧评测数据核查
- 改动：修复 `llm-workbench.spec.ts` 的 mock query 匹配（runs / baselines 带分页参数后原正则不命中）、补基线列表新 label 断言、修正场景标签空格、进度文本 strict mode、无基线时手动选择左运行；`设为基线` mock 改为按 runId 更新已有基线并写回列表。
- 原因：P3 的 E2E 从未真实执行，暴露 4 处 mock / 断言过期问题。
- 验证：沙箱外执行 `npm run test:e2e`，**24 passed**（25.4s）。旧数据核查：本机 5432 为系统 PostgreSQL（项目账号认证失败，历史约定不触碰）；验收容器 `jobpilot-verify-pg`（5433）最高迁移未到 V11，三张评测表不存在，无旧 P3 数据可清；容器已恢复停止状态。
- 下一步 / 断点：继续审计 P1-P3 剩余代码、契约、迁移与前端。

### 13:25 审计修复与回归完成
- 改动：见上「改动」清单，覆盖取消竞态、重试上限、恢复计数、Provider 缓存、基线分页与语料一致性、语料指纹 / 隐私扫描、提示词缓存提交后失效及文档同步。
- 原因：P1-P3 全链路审计发现确定性缺陷与契约 / 文档口径不一致。
- 验证：契约 lint 0/0；后端 294 tests 全绿、JaCoCo 通过；前端构建 + Vitest 42；Playwright 24 例静态枚举。
- 下一步 / 断点：本机 E2E 与真实模型手工验收；E2E 基线列表断言缺口待补。
