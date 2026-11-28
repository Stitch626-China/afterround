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
- 测试 → `EvalRunnerTest` 补取消 / Provider 故障 / 调用日志断言；`EvalRunServiceTest` 补 3 次重试；`EvalBaselineServiceTest` 补语料变化拒绝；`EvalApiIntegrationTest` 恢复计数；`LlmProviderServiceTest` 补缓存失效。
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
- `mvn -f backend/pom.xml clean test` → **293 tests，0 failures / 0 errors / 0 skipped，BUILD SUCCESS**，JaCoCo 门禁通过。
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

### 13:25 审计修复与回归完成
- 改动：见上「改动」清单，覆盖取消竞态、重试上限、恢复计数、Provider 缓存、基线分页与语料一致性、语料指纹 / 隐私扫描、提示词缓存提交后失效及文档同步。
- 原因：P1-P3 全链路审计发现确定性缺陷与契约 / 文档口径不一致。
- 验证：契约 lint 0/0；后端 293 tests 全绿、JaCoCo 通过；前端构建 + Vitest 42；Playwright 24 例静态枚举。
- 下一步 / 断点：本机 E2E 与真实模型手工验收；E2E 基线列表断言缺口待补。
