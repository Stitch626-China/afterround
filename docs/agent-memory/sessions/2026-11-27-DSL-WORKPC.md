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
