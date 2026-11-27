# 提示词管理与场景级基线回归 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在「面后」提供全部 LLM 提示词的查看、草稿、发布、回滚；发布后可选跑评测，满意后把运行设为基线，并与历史运行做 4 维对比。

**Architecture:** 提示词采用「代码内置默认 + DB 版本覆盖」：`PromptCatalog` 维护 12 组元数据，`PromptRegistry` 负责 classpath 兜底、DB 生效版本与热更新；评测执行器从 test scope 迁入运行时，语料随包发布，运行与基线落 PostgreSQL；基线以「场景 × 指纹」为单位，提示词版本是被测变量、不参与可比性判定。前端新增 `/llm-workbench` 全屏工作台（侧栏底部入口），承载模型与绑定、提示词、评测运行、基线对比、调用统计。

**Tech Stack:** Java 21 + Spring Boot 3.5 + MyBatis-Plus + Flyway + PostgreSQL 16 + Vue3/TS/Vite + OpenAPI 3.1 + JUnit5/Mockito/Testcontainers + Vitest/Playwright。

**Spec:** `docs/superpowers/specs/面后（AfterRound）需求文档.md`（本期新增 F9 章节，并补 v1.18 迭代历程）；本计划「需求基线」章节为 2026-11-27 已确认口径。

---

## 一页看懂（先读这个）

**一句话**：提示词是菜谱，版本是菜谱的某一版，草稿是还没上架的改法；运行是按当前菜谱 + 当前模型做一次菜并打分；基线是你认可的那次打分，贴个「参考」标签；「设为基线」只是贴标签，不重新做菜。

| 术语 | 一句话解释 |
|---|---|
| 提示词 | 12 组 system/user 模板，如简历分析、JD 解析、复盘分析 |
| 版本 | 一次「发布」后的不可变内容；历史版本可查看、可回滚 |
| 草稿 | 还没发布的修改；不影响线上，也不参与评测 |
| 发布 | 把草稿变成当前生效版本；立即热更新，不触发评测 |
| 运行 | 按当前生效提示词 + 当前模型，跑一遍语料并打分 |
| 基线 | 被你「设为基线」的那次运行；有 label，每个场景一个 current |
| 设为基线 | 给已完成的运行贴标签，不重跑；每个场景独立，可部分成功 |
| 全量 / 单场景 | 3 个场景 27 例 / 1 个场景 10·9·8 例 |
| 可比 | 两次运行的模型/参数/语料/引擎一致；提示词不同不影响（它正是被测变量） |
| 过期 | 当前配置和当前基线不一致，只是提示，不阻塞 |

**三条用户路径**

1. **只改提示词**：改 → 发布 → 直接用，不跑评测。
2. **想验证**：跑一次 → 看结果 → 满意「设为基线」；不满意继续改。
3. **换模型**：模型 Tab 保存 → 可选跑一次 → 满意「设为基线」。

**功能 ↔ 接口 ↔ 表 ↔ 实现**

| 你看到的功能 | 接口 | 实现 | 数据表 | 解决什么问题 |
|---|---|---|---|---|
| 查看提示词 | `GET /api/llm/prompts`、`GET /api/llm/prompts/{key}` | `PromptService` / `PromptRegistry` | `llm_prompt_versions` | 知道有哪些提示词、当前用哪版 |
| 改草稿/发布/回滚 | `PUT .../draft`、`POST .../publish`、`POST .../versions/{n}/activate` | `PromptService` | `llm_prompt_versions` | 安全地改提示词并可回退 |
| 改模型/绑定 | 现有 `/api/llm/global`、`/api/llm/scenes/{scene}` | `LlmGlobalSettingService` / `LlmSceneService` | 现有 LLM 配置表 | 换模型，不触发评测 |
| 跑评测 | `POST /api/llm/eval/runs`、`GET .../runs`、`GET .../runs/{id}`、`POST .../runs/{id}/cancel` | `EvalRunService` / `EvalRunner` | `llm_eval_runs` / `llm_eval_run_cases` | 按当前配置跑一遍语料并留结果 |
| 设为基线 | `POST /api/llm/eval/baselines` | `EvalBaselineService` | `llm_eval_baselines` | 把满意的那次运行标记为参考 |
| 基线列表/设为当前 | `GET .../baselines`、`POST .../baselines/{id}/activate` | `EvalBaselineService` | `llm_eval_baselines` | 管理参考版本 |
| 运行对比 | `GET /api/llm/eval/runs/compare` | `EvalCompareService` | runs + cases | 看质量/回归/成本/耗时变好还是变坏 |
| 调用统计 | 现有 `GET /api/llm/usage` | `LlmUsageService` | `llm_call_logs` | 看 token 成本 |

**运行状态**

```
PENDING → PROCESSING → COMPLETED
                   ↘ FAILED / CANCELLED

COMPLETED + 全量 + 无 ERROR → 可「设为基线」
```

> 基线不是运行的对立面：**基线 = 被贴了标签的运行**。所以「设为基线」不需要重新跑。

---

## 全局约束

- 契约先行：先改 `contracts/openapi.yaml` 并通过 `npx @redocly/cli lint contracts/openapi.yaml`（0 error / 0 warning），再写后端与前端；前端类型只能由契约生成。
- 后端必须 JDK 21：`$env:JAVA_HOME="D:\Java\21"; mvn -f backend/pom.xml test`。
- 单用户 `user_id = 1`；列表分页默认 20、时间倒序；删除硬删除 + 级联清理。
- 异步任务状态四件套：`status` + CHECK + `error` + `retry_count`；自动重试 ≤3 次。
- 所有 LLM 调用落 `llm_call_logs`；评测调用使用 `EVAL_` 前缀场景，便于成本归因。
- 提示词/基线存 DB；classpath 的 `.st` 与 `baselines/*.json` 只做内置默认与首次导入。
- 不做 RAG、不做提示词 A/B、不做语料前端编辑、不做金额成本。
- 不强制「发布前必须评测通过」；有语料场景只提示，不阻断。
- 需求文档每次修改必须补迭代历程（产品语言）；README 同步说明；收工按 `AGENTS.md` 更新 `PROGRESS.md` 与会话记录。
- 本计划只描述方案，未修改任何业务代码。

## 复审修订（2026-11-27）

- 对比对象改为**运行 vs 运行**；基线是「被标记为参考的运行」，回归运行可直接与历史基线比较。
- **取消 `mode=BASELINE`**：所有评测都是「运行」；满意后在结果页点「设为基线」，不重跑。契约总数改为 17 端点 / 110。
- 顶部新增「一页看懂」与「开发对照表」，开发过程中同步更新功能 ↔ 接口 ↔ 表 ↔ 实现。
- 多场景重建基线返回逐场景结果；新增基线 label 与「设为当前基线」。
- CLI `EVAL_WRITE_BASELINE` 改为写 `target/llm-eval/baselines`，不再写 classpath/源码目录；产品基线只走 DB。
- 异步运行增加：事务 `afterCommit`、单活跃运行锁、连续 ERROR 熔断、启动/取消后的剩余用例清理。
- 提示词编辑增加：版本 diff、恢复内置默认、未保存离开保护、激活历史版本与草稿的边界规则。
- 命名与口径：`failedCases` → `errorCases`；`EvalCompareCase.change` → `changes[]`；场景差异指标标「不适用」；「基线已过期」≠「不可比」。
- P3 轻量纳入：重跑 ERROR 用例（复用创建运行接口）、前端导出提示词 JSON；运行/基线保留策略与重试 token 明细留后续。

## 需求基线（2026-11-27 已确认）

1. **提示词范围**：12 组 system/user 全部可查看、编辑、发布、回滚；不是只做简历。
2. **生效方式**：草案 + 手动发布；发布后热更新；历史版本保留，可回滚。
3. **可编辑范围**：system/user 全文可编辑；保存校验占位符，发布校验该场景必需的 JSON 字段仍在。
4. **重建范围**：按影响面自动识别 + 用户可调整；只改简历就只跑简历 10 例、只比简历基线。
5. **比较维度**：4 维——质量门禁（L1/L2 通过率）、回归变化（修复/新增失败/分数漂移/标签变化）、成本（tokens）、耗时（平均/P95）；稳定性 L3 作为可选第 5 维，默认关闭。
6. **基线语义**：基线保留历史、不覆盖；「当前基线」只是标记；任意两次同场景基线可比较；不同场景不做横向平均。
7. **可比性**：场景、模型/Provider、生成参数、语料 hash、断言引擎版本一致才可比较；提示词版本只记录展示，不参与可比性。
8. **无语料提示词**：可编辑、可发布，但「设为基线」置灰并提示「暂无评测语料」。
9. **基线触发**：基线始终由用户主动触发；发布提示词、改全局默认模型、改场景绑定都不自动跑评测，也不阻塞使用。
10. **信息架构**：侧栏底部新增「LLM 工作台」独立入口 → `/llm-workbench`；全屏 5 个 Tab：模型与绑定 / 提示词 / 评测运行 / 基线对比 / 调用统计。
11. **模型与统计**：模型与绑定在工作台内联修改，与设置抽屉同一 API；调用统计从设置抽屉迁入工作台第 5 个 Tab，只读。
12. **过期提示**：配置与基线不一致时只显示「基线已过期」徽标，不弹窗、不自动跑；「全量基线」= 选中全部 3 个场景跑一次。
13. **概念区分**：基线新鲜度是建议（当前配置 vs 当前基线），基线可比性是硬约束（两次运行是否同场景/模型/参数/语料/引擎）。
14. **对比对象**：运行 vs 运行；基线只是被标记的 run；回归运行可直接与历史基线（其 `runId`）比较。
15. **误操作防护**：提示词版本 diff、恢复内置默认、未保存离开保护；模型/绑定修改需显式保存，全局默认二次确认。
16. **场景能力**：简历/JD/复盘的可比指标不同；不适用维度显示「不适用」，不伪造 0 或 1.0。
17. **设为基线**：运行完成后，满意才点「设为基线」；只贴标签，不重跑；每个场景独立，可部分成功。

## 术语与口径

- **运行**：一次手动评测；**基线**：被「设为基线」的那次运行（带 label 与 `is_current`）。
- **设为基线**：给已完成的运行贴标签，不重跑；每个场景独立，可部分成功。
- **当前基线**：每个场景 `is_current=true` 的基线；「设为当前」只换标记，不重跑。
- **全量基线**：选中 3 个场景跑一次（27 例）再「设为基线」，不是独立数据类型。
- **质量失败**：case `status=FAIL`；**执行错误**：`status=ERROR`；两者分开计数。
- **L1**：硬性检查，按用例（任一 L1 断言失败即该用例失败）；**L2**：质量断言，按断言条目；**L3**：稳定性复跑。通过率为 0~1 小数。
- **基线已过期**：当前配置与当前基线不一致，只是提示；**不可比**：两次运行模型/参数/语料/引擎不一致，是硬约束。提示词不同不影响可比性（它是被测变量）。

## 现状与差距

| 项 | 现状 | 差距 |
|---|---|---|
| 提示词 | 24 个 `.st` 在 `backend/src/main/resources/prompts/`，8 个 `PromptFactory` 启动时读 classpath | 只读；仅 4 个工厂有版本号；无 DB 版本、无草稿/发布/回滚 |
| 提示词版本记录 | 简历/复盘分析表有 `prompt_version`，值来自硬编码常量 | 其余功能不记录；DB 发布后需动态版本号 |
| 评测语料 | 27 份在 `backend/src/test/resources/llm-eval/cases/` | 运行时不可见，需迁到 main resources 并用 classpath 加载 |
| 评测执行器 | `LlmEvalTest` 在 test scope，单一 `EVAL_MODEL`，只支持全量 | 需迁入运行时、支持场景子集、按场景解析模型、异步/进度/取消 |
| 基线 | 3 个 JSON 在 test resources，写入即覆盖，`EVAL_ONLY` 不对比 | 需 DB 版本化、历史保留、指纹、4 维对比 |
| 前端 | 设置抽屉 4 个 Tab（模型服务/场景模型/外观/调用统计） | 无 LLM 工作台、无提示词编辑/评测/基线对比；调用统计需迁入工作台 |
| 契约 | 93 端点，1.21.1 | 本期新增 17 端点，分三个阶段升版本 |

**工作区提醒**：当前 `git status` 有 3 个未提交改动（`ResumePromptFactory` 升 v3、`resume-analysis-system.st` 引用约束、`功能实现对照表`）。开工前先确认并提交/暂存；v3 是「只改简历提示词」的现成验证样例。

## 影响面规则

> **以下规则只在用户主动选择重建时适用。** 不重建不影响发布、改模型或正常使用，也不会自动触发评测。

| 变更 | 重建范围 |
|---|---|
| 某场景的 system/user 提示词 | 只该场景 |
| 某场景的模型绑定 | 只该场景 |
| 全局默认模型 | 所有未绑定场景（如 JD 解析、简历对比、Offer 提取、JD OCR）；已绑定场景不动 |
| 断言规则 / 评测引擎版本 | 全部场景（旧基线标记「不可比」，保留历史） |
| 增删某场景语料 | 只该场景 |
| taxonomy / rubric 版本 | 只复盘分析 |
| 全局生成参数 | 使用该参数的场景 |

> 洞察总结当前复用 `REVIEW_ANALYZE` 场景绑定；模拟面试追问使用会话创建时的模型。`PromptCatalog` 必须记录真实依赖，不按名称猜。

## 架构设计

### 信息架构（2026-11-27 确认）

- 侧栏底部「LLM 工作台」独立入口，位于「设置」附近；与简历/复盘等业务主线分开。
- 全屏 `/llm-workbench`，5 个 Tab：模型与绑定 / 提示词 / 评测运行 / 基线对比 / 调用统计。
- 模型与绑定：全局默认 + 7 个场景绑定 + 3 个评测场景生效模型；内联修改，调用现有 API，与设置抽屉同源。
- 提示词：12 组查看/草稿/发布/回滚；发布不自动跑评测。
- 评测运行：全量 27 例或单场景 10/9/8 例；始终手动开始；完成后可「设为基线」。
- 基线对比：当前/历史基线 + 4 维 + case 明细；过期只显示徽标。
- 调用统计：复用现有 `UsageSettings`，只读；从设置抽屉迁入。
- 设置抽屉保留「模型服务 / 场景模型 / 外观」三个 Tab，不再保留「调用统计」。

### P1 提示词注册与编辑

- **元数据在代码**：`PromptCatalog` 维护 12 组 `PromptSpec`（key、名称、文件、占位符、必填字段、输出模式、模型作用域、评测场景、影响功能、内置版本号）。元数据不落库，避免与代码漂移。
- **内容在 DB**：`llm_prompt_versions` 存 system+user 版本；`DRAFT`/`ACTIVE`/`ARCHIVED` 三态，部分唯一索引保证每个 key 最多一个草稿、一个生效版本。
- **运行时读取**：`PromptRegistry` 先查 DB ACTIVE，查不到回退 classpath；`PromptFactory` 只做占位符替换，模板由 registry 提供；发布后精确失效缓存。
- **版本记录**：分析结果写 `prompts.version(key)`：内置为 `resume-analysis-v3`，DB 为 `resume-analysis-db1`；F7 对此字符串做等值判定。
- **校验**：占位符必须完整；JSON 提示词必填字段必须在 system 中出现（按词边界匹配）；TEXT 提示词只校验占位符与非空。草稿允许保存但返回错误；发布必须通过校验。
- **前端**：P1 落地 `/llm-workbench` 壳、侧栏底部入口、模型与绑定 Tab、提示词 Tab、调用统计 Tab；评测与基线 Tab 在 P2/P3 加入。

### P2 评测运行器产品化

- `EvalSceneCatalog` 维护 3 个评测场景：`resume-analysis` / `jd-parse` / `review-analyze`，含 promptKey、模型作用域、DTO、评分路径、语料目录。
- `EvalCorpus` 改为 classpath 加载（`classpath*:llm-eval/cases/*/*/meta.json`），`EvalCase` 不再持有 `Path`；27 份语料移入 `src/main/resources/llm-eval/cases/`。
- `EvalRunService` 创建运行时快照：提示词全文、Provider/Model、参数、语料 hash、引擎版本；单个运行最多一个并发；异步线程池独立（`evalTaskExecutor`，core=1/max=1）。
- `EvalClientFactory` 按 Provider 构建评测客户端，使用 `app.eval.max-tokens=8192`、`app.eval.timeout-seconds=300`，不影响生产 4096/120s。
- 每个用例独立记录 L1/L2/L3、scores、topics、lossTags、tokens、耗时、error；用例失败不自动重试，避免成本失控；运行级 `retry_count` 只处理 worker 级异常。
- 创建运行的事务提交后才提交异步任务（`afterCommit`），避免 worker 读不到 run/cases；单实例服务锁 + DB 部分唯一索引保证同时只有一个活跃运行。
- 连续 3 个用例因同一 Provider 错误失败时熔断，run 置 FAILED，避免跑满 27×300s。
- 启动恢复：PENDING/PROCESSING 的评测运行置 FAILED，剩余 PENDING 用例置 SKIPPED 并写原因；取消时同样把剩余 PENDING 置 SKIPPED，计数器按实际用例重算。
- **前端**：P2 增加「评测运行」Tab；任何配置变更都不自动开始运行，必须由用户点击「开始运行」。

### P3 基线版本化与对比

- `llm_eval_baselines` 每个场景保留历史；`is_current` 部分唯一索引保证每个场景一个当前基线；支持 `label` 与「设为当前」。
- 只有用户点「设为基线」、run `COMPLETED` 且 `partial=false`、该场景无用例 ERROR 时才创建基线；L1/L2 失败仍可建基线，但指标如实记录；一次「设为基线」返回逐场景 `EvalBaselineResult`。
- 基线是「被标记的 run」；对比按 `runId` 进行，回归运行可直接与历史基线比较；取消/部分重跑不可比。
- 旧 JSON 基线由 `LegacyBaselineImporter` 一次性导入：创建合成 run + case + baseline，保留状态/分数/tokens/耗时；旧数据无 L1/L2 明细，对应通过率显示「无数据」；旧基线 `promptHash` 只在版本不一致时标 `legacy`，避免 JD/复盘误报过期。
- `EvalFingerprint` 负责可比性：场景、Provider、Model、参数、语料 hash、引擎版本（复盘额外含 taxonomy/rubric）。提示词 hash 只用于影响面提示。
- 场景能力决定可比指标：简历有分数无标签、JD 无分数无标签、复盘有分数有标签；不适用维度在 `applicableDimensions` 外，前端显示「不适用」。
- `EvalCompareService` 输出 4 维 + case 明细（`changes[]`）；不可比时返回 200 且 `comparable=false`，由前端解释原因。
- **前端**：P3 增加「基线对比」Tab 与「基线已过期」徽标；徽标只做提示，不弹窗、不自动跳转、不自动运行。
- `activate` 返回被激活的 `EvalBaselineSummary`；`ResultEvalBaselineSummary` 包装。

## 实现细节约束

- 新 JSONB 字段统一复用现有 `JsonbStringTypeHandler`；实体字段用 `String` 承载 JSON，避免为驱动改依赖。
- `PromptFactory` 双构造器：Spring 注入构造器加 `@Autowired`，无参构造只供测试/CLI 走内置模板。
- 提示词内容只用 `textarea` / `<pre>` 文本渲染，禁止 `v-html`，防止提示词里的内容被当 HTML 执行。
- `.env.example` 与 `docker-compose.yml` 补 `app.eval.max-tokens/timeout-seconds` 说明；README 同步。
- `LlmUsageService` 增加 `EVAL_*` 中文标签；评测调用计入总量，过滤开关留后续。

## 表结构草案

### V11 `llm_prompt_versions.sql`

```sql
CREATE TABLE llm_prompt_versions (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        BIGINT      NOT NULL DEFAULT 1,
    prompt_key     VARCHAR(64) NOT NULL,
    version_no     INT         NOT NULL,
    system_content TEXT        NOT NULL,
    user_content   TEXT        NOT NULL,
    content_hash   VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    note           VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    CONSTRAINT uk_llm_prompt_versions_key_no UNIQUE (prompt_key, version_no),
    CONSTRAINT ck_llm_prompt_versions_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_llm_prompt_versions_version_no CHECK (version_no > 0)
);
CREATE UNIQUE INDEX uk_llm_prompt_versions_active
    ON llm_prompt_versions (prompt_key) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uk_llm_prompt_versions_draft
    ON llm_prompt_versions (prompt_key) WHERE status = 'DRAFT';
CREATE INDEX idx_llm_prompt_versions_key_created
    ON llm_prompt_versions (prompt_key, created_at DESC);
COMMENT ON TABLE llm_prompt_versions IS '提示词 system+user 版本；内置默认在 classpath，DB 仅存覆盖版本';
COMMENT ON COLUMN llm_prompt_versions.content_hash IS 'SHA-256(system + "\\n---\\n" + user)，用于影响面提示';
```

### V12 `llm_eval_runs.sql`

```sql
CREATE TABLE llm_eval_runs (
    id                 BIGSERIAL   PRIMARY KEY,
    user_id            BIGINT      NOT NULL DEFAULT 1,
    status             VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    partial            BOOLEAN     NOT NULL DEFAULT false,
    scenes             JSONB       NOT NULL,
    model_snapshot     JSONB       NOT NULL,
    prompt_snapshot    JSONB       NOT NULL,
    corpus_hash        VARCHAR(64) NOT NULL,
    engine_version     VARCHAR(32) NOT NULL,
    params             JSONB       NOT NULL,
    total_cases        INT         NOT NULL DEFAULT 0,
    completed_cases    INT         NOT NULL DEFAULT 0,
    error_cases        INT         NOT NULL DEFAULT 0,
    error              VARCHAR(500),
    retry_count        INT         NOT NULL DEFAULT 0,
    cancel_requested   BOOLEAN     NOT NULL DEFAULT false,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_llm_eval_runs_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_llm_eval_runs_counts CHECK (
        total_cases >= 0 AND completed_cases >= 0 AND error_cases >= 0)
);
CREATE INDEX idx_llm_eval_runs_created ON llm_eval_runs (created_at DESC);
CREATE INDEX idx_llm_eval_runs_status ON llm_eval_runs (status);
-- 单实例服务锁之外的 DB 兜底：同一时间只允许一个活跃运行。
-- 若目标 PG 版本不支持常量表达式索引，改为 active_slot 列 + 部分唯一索引。
CREATE UNIQUE INDEX uk_llm_eval_runs_active
    ON llm_eval_runs ((1)) WHERE status IN ('PENDING', 'PROCESSING');
COMMENT ON COLUMN llm_eval_runs.partial IS 'true 表示只重跑部分用例（ERROR 重试），不可作为基线、不参与完整对比';
COMMENT ON COLUMN llm_eval_runs.error_cases IS '执行错误（ERROR）用例数，不是质量失败数';

CREATE TABLE llm_eval_run_cases (
    id          BIGSERIAL   PRIMARY KEY,
    run_id      BIGINT      NOT NULL REFERENCES llm_eval_runs (id) ON DELETE CASCADE,
    scene       VARCHAR(32) NOT NULL,
    case_id     VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    l1_passed   BOOLEAN,
    l2_passed   BOOLEAN,
    l3_passed   BOOLEAN,
    results     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    scores      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    topics      JSONB       NOT NULL DEFAULT '[]'::jsonb,
    loss_tags   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    tokens      INT,
    elapsed_ms  BIGINT,
    error       VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_llm_eval_run_cases UNIQUE (run_id, scene, case_id),
    CONSTRAINT ck_llm_eval_run_cases_status CHECK (
        status IN ('PENDING', 'PASS', 'FAIL', 'GRACEFUL', 'ERROR', 'SKIPPED'))
);
CREATE INDEX idx_llm_eval_run_cases_run ON llm_eval_run_cases (run_id);
COMMENT ON TABLE llm_eval_run_cases IS '逐用例结果；scores/topics/loss_tags 供基线对比，不依赖报告文件';
```

### V13 `llm_eval_baselines.sql`

```sql
CREATE TABLE llm_eval_baselines (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL DEFAULT 1,
    scene           VARCHAR(32) NOT NULL,
    run_id          BIGINT      NOT NULL REFERENCES llm_eval_runs (id) ON DELETE RESTRICT,
    label           VARCHAR(100),
    provider_key    VARCHAR(64) NOT NULL,
    model           VARCHAR(128) NOT NULL,
    prompt_version  VARCHAR(64) NOT NULL,
    prompt_hash     VARCHAR(64) NOT NULL,
    corpus_hash     VARCHAR(64) NOT NULL,
    engine_version  VARCHAR(32) NOT NULL,
    params          JSONB       NOT NULL,
    metrics         JSONB       NOT NULL,
    is_current      BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_llm_eval_baselines_run_scene UNIQUE (run_id, scene)
);
CREATE UNIQUE INDEX uk_llm_eval_baselines_current
    ON llm_eval_baselines (scene) WHERE is_current;
CREATE INDEX idx_llm_eval_baselines_scene_created
    ON llm_eval_baselines (scene, created_at DESC);
COMMENT ON TABLE llm_eval_baselines IS '场景级基线；历史保留，is_current 仅标记当前基线';
COMMENT ON COLUMN llm_eval_baselines.label IS '可选人读标签，如「v3 发布前」；创建基线时写入';
COMMENT ON COLUMN llm_eval_baselines.prompt_hash IS '影响面提示用；不参与可比性判定';
```

## 契约变更清单

### P1：契约 1.22.0（8 端点）

| 方法 | 路径 | operationId | 说明 |
|---|---|---|---|
| GET | `/api/llm/prompts` | `listPrompts` | 12 组提示词摘要 |
| GET | `/api/llm/prompts/{promptKey}` | `getPrompt` | 生效版本 + 草稿 + 校验 + 影响信息 |
| PUT | `/api/llm/prompts/{promptKey}/draft` | `savePromptDraft` | 保存草稿，返回校验结果 |
| DELETE | `/api/llm/prompts/{promptKey}/draft` | `discardPromptDraft` | 丢弃草稿 |
| POST | `/api/llm/prompts/{promptKey}/publish` | `publishPrompt` | 发布草稿，热更新 |
| GET | `/api/llm/prompts/{promptKey}/versions` | `listPromptVersions` | 版本历史分页 |
| GET | `/api/llm/prompts/{promptKey}/versions/{versionNo}` | `getPromptVersion` | 版本详情 |
| POST | `/api/llm/prompts/{promptKey}/versions/{versionNo}/activate` | `activatePromptVersion` | 回滚到历史版本 |

新增 schema：`PromptSummary`、`PromptDetail`、`PromptVersionSummary`、`PromptVersionDetail`、`PromptValidation`、`SavePromptDraftRequest`、`PublishPromptRequest`；结果包装 `ResultPromptList`、`ResultPromptDetail`、`ResultPromptVersionPage`、`ResultPromptVersionDetail`。

关键字段：

```yaml
PromptSummary:
  type: object
  required: [key, name, category, description, outputMode, modelScope, hasEvalCorpus,
             affectedFunctions, placeholders, requiredFields, activeVersion, builtinVersion, hasDraft]
  properties:
    key: { type: string }
    name: { type: string }
    builtinVersion: { type: string, description: 代码内置版本号，如 resume-analysis-v3 }
    category: { type: string }
    description: { type: string }
    outputMode: { type: string, enum: [JSON, TEXT] }
    modelScope: { type: string, enum: [SCENE, GLOBAL, SESSION] }
    scene: { type: [string, "null"] }
    evalScene: { type: [string, "null"] }
    hasEvalCorpus: { type: boolean }
    affectedFunctions: { type: array, items: { type: string } }
    placeholders: { type: array, items: { type: string } }
    requiredFields: { type: array, items: { type: string } }
    activeVersion: { $ref: '#/components/schemas/PromptVersionSummary' }
    hasDraft: { type: boolean }
    updatedAt: { type: [string, "null"], format: date-time }
PromptVersionSummary:
  type: object
  required: [versionNo, source, status, contentHash]
  properties:
    versionNo: { type: integer }
    source: { type: string, enum: [BUILTIN, DB] }
    status: { type: string, enum: [DRAFT, ACTIVE, ARCHIVED] }
    contentHash: { type: string }
    note: { type: [string, "null"] }
    createdAt: { type: [string, "null"], format: date-time }
    publishedAt: { type: [string, "null"], format: date-time }
PromptDetail:
  allOf:
    - $ref: '#/components/schemas/PromptSummary'
    - type: object
      required: [active, builtIn, validation]
      properties:
        active: { $ref: '#/components/schemas/PromptVersionDetail' }
        builtIn: { $ref: '#/components/schemas/PromptVersionDetail', description: 代码内置默认，用于 diff / 恢复内置 }
        draft: { oneOf: [{ $ref: '#/components/schemas/PromptVersionDetail' }, { type: 'null' }] }
        validation: { $ref: '#/components/schemas/PromptValidation' }
```

行为约定：
- `versions` 只返回 `ACTIVE` / `ARCHIVED`；`DRAFT` 只在 `PromptDetail.draft`，不混入历史列表。
- `active.source=BUILTIN` 时 `versionNo=0`，前端显示「内置默认（vN）」，不显示 `v0`；`builtinVersion` 用于检测升级。
- 保存/发布内容与当前生效版本完全相同 → `validation.warnings` 提示，不阻断。
- 激活历史版本时已有草稿 → 保留草稿并返回 warning「草稿仍基于旧版本」；激活 `DRAFT` → 400。
- 丢弃不存在的草稿 → 幂等 200。
- 并发保存/发布冲突 → `40920`。

错误码：提示词 key/版本不存在 → `40401`；草稿缺失、发布校验失败、场景不支持 → `40001`；并发版本冲突 → `40920`。`info.version` 升 `1.22.0`；`ContractCoverageTest` 总数 `93 → 101`，新路径先标 `x-implemented: false`。

### P2：契约 1.23.0（5 端点）

| 方法 | 路径 | operationId | 说明 |
|---|---|---|---|
| GET | `/api/llm/eval/scenes` | `listEvalScenes` | 3 个场景、case 数、当前模型、当前基线、重建原因 |
| POST | `/api/llm/eval/runs` | `createEvalRun` | 创建异步评测运行 |
| GET | `/api/llm/eval/runs` | `listEvalRuns` | 分页列表 |
| GET | `/api/llm/eval/runs/{runId}` | `getEvalRun` | 运行详情 + 逐用例结果 |
| POST | `/api/llm/eval/runs/{runId}/cancel` | `cancelEvalRun` | 请求取消 |

新增 schema：`EvalSceneSummary`、`EvalRunSummary`、`EvalRunDetail`、`EvalCaseResult`、`EvalAssertionResult`、`EvalMetrics`、`EvalSceneModel`、`EvalScenePrompt`、`CreateEvalRunRequest`、`EvalBaselineSummary`（P2 先定义，供场景摘要引用；P3 复用）；结果包装 `ResultEvalSceneList`、`ResultEvalRunPage`、`ResultEvalRunDetail`。

关键字段：

```yaml
CreateEvalRunRequest:
  type: object
  required: [scenes]
  properties:
    scenes:
      type: array
      minItems: 1
      items: { type: string, enum: [resume-analysis, jd-parse, review-analyze] }
    repeat: { type: integer, minimum: 1, maximum: 2, default: 1 }
EvalMetrics:
  type: object
  properties:
    l1Total: { type: [integer, "null"] }
    l1Failed: { type: [integer, "null"] }
    l1PassRate: { type: [number, "null"] }
    l2Total: { type: [integer, "null"] }
    l2Failed: { type: [integer, "null"] }
    l2PassRate: { type: [number, "null"] }
    totalTokens: { type: [integer, "null"] }
    avgTokens: { type: [number, "null"] }
    avgElapsedMs: { type: [number, "null"] }
    p95ElapsedMs: { type: [number, "null"] }
EvalCaseResult:
  type: object
  required: [scene, caseId, status, results]
  properties:
    scene: { type: string }
    caseId: { type: string }
    status: { type: string, enum: [PENDING, PASS, FAIL, GRACEFUL, ERROR, SKIPPED] }
    l1Passed: { type: [boolean, "null"] }
    l2Passed: { type: [boolean, "null"] }
    l3Passed: { type: [boolean, "null"] }
    results: { type: array, items: { $ref: '#/components/schemas/EvalAssertionResult' } }
    scores: { type: object, additionalProperties: { type: number } }
    tokens: { type: [integer, "null"] }
    elapsedMs: { type: [integer, "null"] }
    error: { type: [string, "null"] }
EvalRunSummary:
  type: object
  required: [id, status, scenes, totalCases, completedCases, errorCases]
  properties:
    id: { type: integer, format: int64 }
    status: { type: string, enum: [PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED] }
    scenes: { type: array, items: { type: string } }
    totalCases: { type: integer }
    completedCases: { type: integer }
    errorCases: { type: integer, description: 执行错误（ERROR）用例数，不含质量失败 }
    error: { type: [string, "null"] }
    createdAt: { type: string, format: date-time }
    startedAt: { type: [string, "null"], format: date-time }
    finishedAt: { type: [string, "null"], format: date-time }
EvalSceneModel:
  type: object
  required: [scene, providerKey, model]
  properties:
    scene: { type: string }
    providerKey: { type: string }
    model: { type: string }
EvalScenePrompt:
  type: object
  required: [scene, promptKey, promptVersion, promptHash]
  properties:
    scene: { type: string }
    promptKey: { type: string }
    promptVersion: { type: string }
    promptHash: { type: string }
EvalSceneSummary:
  type: object
  required: [scene, name, caseCount, corpusHash, promptKey, promptVersion, modelScope, rebuildReasons]
  properties:
    scene: { type: string, enum: [resume-analysis, jd-parse, review-analyze] }
    name: { type: string }
    caseCount: { type: integer }
    corpusHash: { type: string }
    promptKey: { type: string }
    promptVersion: { type: string }
    modelScope: { type: string, enum: [SCENE, GLOBAL] }
    providerKey: { type: [string, "null"] }
    model: { type: [string, "null"] }
    currentBaseline: { oneOf: [{ $ref: '#/components/schemas/EvalBaselineSummary' }, { type: 'null' }] }
    lastRun: { oneOf: [{ $ref: '#/components/schemas/EvalRunSummary' }, { type: 'null' }] }
    rebuildReasons: { type: array, items: { type: string } }
EvalRunDetail:
  allOf:
    - $ref: '#/components/schemas/EvalRunSummary'
    - type: object
      required: [cases, models, prompts]
      properties:
        cases: { type: array, items: { $ref: '#/components/schemas/EvalCaseResult' } }
        models: { type: array, items: { $ref: '#/components/schemas/EvalSceneModel' } }
        prompts: { type: array, items: { $ref: '#/components/schemas/EvalScenePrompt' } }
        metrics: { oneOf: [{ $ref: '#/components/schemas/EvalMetrics' }, { type: 'null' }] }
```

P2 的评测只有「运行」，没有 mode；运行完成不等于基线。P3 增加独立的「设为基线」动作，不满意就不设，不需要重跑。`errorCases` 统计执行错误（ERROR），质量失败看 `FAIL` 与 L1/L2 指标。指标口径：L1 按用例（任一 L1 断言失败即该用例 L1 失败），L2 按断言条目（与现有 95% 门禁一致），ERROR 计入 L1/L2 失败；`l1PassRate` / `l2PassRate` 为 0~1 小数。`EvalRunDetail.models/prompts` 是启动时快照，中途改模型或提示词不影响本次运行；场景未配置模型时前端禁用勾选并给「去配置」入口。

错误码新增：`40918` 已有评测任务执行中；`40919` 评测已结束，不可取消。`info.version` 升 `1.23.0`；`ContractCoverageTest` 总数 `101 → 106`。

### P3：契约 1.24.0（4 端点）

| 方法 | 路径 | operationId | 说明 |
|---|---|---|---|
| GET | `/api/llm/eval/baselines` | `listEvalBaselines` | 按场景返回当前 + 历史基线 |
| POST | `/api/llm/eval/baselines` | `createEvalBaseline` | 把一次已完成的运行「设为基线」，不重跑 |
| POST | `/api/llm/eval/baselines/{baselineId}/activate` | `activateEvalBaseline` | 将历史基线设为当前 |
| GET | `/api/llm/eval/runs/compare` | `compareEvalRuns` | 两次运行 4 维对比（基线只是被标记的 run） |

复用 `EvalBaselineSummary`；新增 `EvalBaselineResult`、`CreateEvalBaselineRequest`、`EvalRunCompare`、`EvalRunCompareSide`、`EvalCompareQuality`、`EvalCompareRegression`、`EvalCompareCost`、`EvalCompareLatency`、`EvalCompareCase`、`PromptImpactSuggestion`；同时扩展 `PromptDetail`（`suggestedEvalScenes`）、`EvalRunSummary`（`partial`）、`EvalRunDetail`（`baselines`）、`CreateEvalRunRequest`（`sourceRunId`、`retryErrorsOnly`）。`PromptImpactSuggestion` 字段：`scene`、`caseCount`、`reasons[]`、`currentBaselineId`（nullable）。结果包装 `ResultEvalBaselineList`、`ResultEvalBaselineResultList`、`ResultEvalBaselineSummary`（activate 返回）、`ResultEvalRunCompare`。`info.version` 升 `1.24.0`；`ContractCoverageTest` 总数 `106 → 110`。

```yaml
EvalBaselineSummary:
  type: object
  required: [id, scene, runId, label, providerKey, model, promptVersion, promptHash,
             corpusHash, engineVersion, params, metrics, current, createdAt]
  properties:
    id: { type: integer, format: int64 }
    scene: { type: string }
    runId: { type: integer, format: int64 }
    label: { type: [string, "null"], maxLength: 100 }
    providerKey: { type: string }
    model: { type: string }
    promptVersion: { type: string }
    promptHash: { type: string }
    corpusHash: { type: string }
    engineVersion: { type: string }
    params: { type: object, additionalProperties: true }
    metrics: { $ref: '#/components/schemas/EvalMetrics' }
    current: { type: boolean }
    createdAt: { type: string, format: date-time }
EvalBaselineResult:
  type: object
  required: [scene, created]
  properties:
    scene: { type: string }
    created: { type: boolean }
    baselineId: { type: [integer, "null"], format: int64 }
    reason: { type: [string, "null"] }
EvalRunCompareSide:
  type: object
  required: [runId, scene, providerKey, model, promptVersion, promptHash,
             corpusHash, engineVersion, params, metrics]
  properties:
    runId: { type: integer, format: int64 }
    scene: { type: string }
    providerKey: { type: string }
    model: { type: string }
    promptVersion: { type: string }
    promptHash: { type: string }
    corpusHash: { type: string }
    engineVersion: { type: string }
    params: { type: object, additionalProperties: true }
    metrics: { $ref: '#/components/schemas/EvalMetrics' }
    baselineId: { type: [integer, "null"], format: int64 }
    baselineLabel: { type: [string, "null"] }
EvalRunCompare:
  type: object
  required: [comparable, reasons, warnings, applicableDimensions, scene, left, right]
  properties:
    comparable: { type: boolean }
    reasons: { type: array, items: { type: string } }
    warnings: { type: array, items: { type: string } }
    applicableDimensions:
      type: array
      items: { type: string, enum: [QUALITY, SCORE_DRIFT, TAG_CHANGE, COST, LATENCY] }
    scene: { type: string }
    left: { $ref: '#/components/schemas/EvalRunCompareSide' }
    right: { $ref: '#/components/schemas/EvalRunCompareSide' }
    quality: { oneOf: [{ $ref: '#/components/schemas/EvalCompareQuality' }, { type: 'null' }] }
    regression: { oneOf: [{ $ref: '#/components/schemas/EvalCompareRegression' }, { type: 'null' }] }
    cost: { oneOf: [{ $ref: '#/components/schemas/EvalCompareCost' }, { type: 'null' }] }
    latency: { oneOf: [{ $ref: '#/components/schemas/EvalCompareLatency' }, { type: 'null' }] }
CreateEvalRunRequest (P3 扩展):
  type: object
  properties:
    scenes: { type: array, minItems: 1, items: { type: string } }
    repeat: { type: integer, minimum: 1, maximum: 2, default: 1 }
    sourceRunId: { type: [integer, "null"], format: int64 }
    retryErrorsOnly: { type: boolean, default: false }
CreateEvalBaselineRequest:
  type: object
  required: [runId]
  properties:
    runId: { type: integer, format: int64 }
    label: { type: [string, "null"], maxLength: 100 }
EvalCompareCase:
  type: object
  required: [caseId, leftStatus, rightStatus, changes]
  properties:
    caseId: { type: string }
    leftStatus: { type: string }
    rightStatus: { type: string }
    changes:
      type: array
      items:
        type: string
        enum: [FIXED, REGRESSED, SCORE_DRIFT, TAG_CHANGED, UNCHANGED]
    scoreDrift: { type: [number, "null"] }
    tagJaccard: { type: [number, "null"] }
    details: { type: [string, "null"] }
EvalRunSummary (P3 扩展):
  type: object
  properties:
    partial: { type: boolean }
EvalRunDetail (P3 扩展):
  type: object
  properties:
    baselines: { type: array, items: { $ref: '#/components/schemas/EvalBaselineResult' } }
```

P3 行为约定：
- 对比对象是 **run vs run**；基线只是被标记的 run。回归运行可直接与历史基线（其 `runId`）比较。
- 只有 `COMPLETED` 且 `partial=false` 的运行可参与完整对比；取消、失败、部分重跑返回 `comparable=false`。
- 可比性检查：场景、Provider、Model、参数、语料 hash、引擎版本；提示词版本/hash 只展示（它是被测变量）。
- 场景能力决定 `applicableDimensions`：简历有分数、无标签；JD 无分数、无标签；复盘有分数、有标签。不适用维度前端显示「不适用」。
- 「设为基线」是独立动作：`POST /api/llm/eval/baselines` 传 `runId + label`，**不重跑**；只接受 `COMPLETED`、`partial=false`、该场景无 ERROR 的运行；返回逐场景 `EvalBaselineResult`，只有 `created=true` 的场景写基线。
- `sourceRunId + retryErrorsOnly=true` 创建部分重跑（`partial=true`），只跑原运行中 ERROR 的用例；源运行必须 `COMPLETED` 且有 ERROR，否则 400；部分重跑不能设为基线、不能参与完整对比；`scenes` 由源运行推导，与 `sourceRunId` 二选一。
- P3 将 `CreateEvalRunRequest.required` 改为空：普通运行必须给 `scenes`，重跑必须给 `sourceRunId + retryErrorsOnly=true`；服务端做互斥校验，不满足返回 400。
- 基线 `activate` 只切换 `is_current`，不重跑、不改历史；返回被激活的 `EvalBaselineSummary`。
- 不可比也返回 200，`comparable=false` + `reasons`；基线/运行不存在返回 `40401`。

## 文件结构

**后端新增**

```
backend/src/main/java/com/jobpilot/modules/prompt/
  catalog/PromptSpec.java            # 元数据 record
  catalog/PromptCatalog.java         # 12 组配置
  catalog/PromptOutputMode.java      # JSON / TEXT
  catalog/PromptModelScope.java      # SCENE / GLOBAL / SESSION
  service/PromptValidator.java       # 占位符 + 必填字段校验
  service/PromptRegistry.java        # DB ACTIVE → classpath 兜底 + 缓存
  service/PromptService.java         # 草稿/发布/回滚/版本
  entity/LlmPromptVersion.java
  mapper/LlmPromptVersionMapper.java
  controller/PromptController.java
  dto/*.java

backend/src/main/java/com/jobpilot/modules/eval/
  catalog/EvalSceneSpec.java
  catalog/EvalSceneCatalog.java
  core/EvalCase.java                 # 由 test 迁入，去掉 Path
  core/EvalCorpus.java               # classpath 加载 + 校验
  core/Expectations.java             # 由 test 迁入
  core/EvalSnapshot.java             # scores/topics/lossTags
  core/EvalMetricsCalculator.java
  core/EvalFingerprint.java
  core/EvalEngine.java               # ENGINE_VERSION
  service/EvalRunService.java
  service/EvalRunner.java
  service/EvalRunRecovery.java       # 启动回收 PENDING/PROCESSING
  service/EvalClientFactory.java
  service/EvalBaselineService.java
  service/EvalCompareService.java
  service/EvalImpactAnalyzer.java
  service/LegacyBaselineImporter.java
  entity/LlmEvalRun.java
  entity/LlmEvalRunCase.java
  entity/LlmEvalBaseline.java
  mapper/*.java
  controller/EvalController.java
  dto/*.java
```

**迁移与资源**

- `backend/src/main/resources/db/migration/V11__llm_prompt_versions.sql`
- `backend/src/main/resources/db/migration/V12__llm_eval_runs.sql`
- `backend/src/main/resources/db/migration/V13__llm_eval_baselines.sql`
- 移动：`backend/src/test/resources/llm-eval/cases/**` → `backend/src/main/resources/llm-eval/cases/**`
- 移动：`backend/src/test/resources/llm-eval/baselines/*.json` → `backend/src/main/resources/llm-eval/baselines/*.json`
- `backend/src/main/resources/application.yml` 增加 `app.eval.*`

**后端修改**

- `common/config/AsyncConfig.java` 增加 `evalTaskExecutor`
- `common/api/ErrorCode.java` 增加 `40918/40919`
- 8 个 `PromptFactory`：模板改由 `PromptRegistry` 提供；补 `version()`；保留无参构造供测试/CLI 走内置模板
- `ResumeAnalysisService` / `ReviewSessionService`：`VERSION` 常量改为 `prompts.version(key)`
- `ContractCoverageTest` / `ContractSchemaTest`：端点总数与新 schema
- `LlmEvalTest`：改为调用迁入后的 `EvalCorpus/Expectations`，baseline 文件改 classpath 读取

**前端新增/修改**

```
frontend/src/api/prompts.ts
frontend/src/api/eval.ts
frontend/src/views/LlmWorkbenchView.vue
frontend/src/components/workbench/ModelBindingPanel.vue
frontend/src/components/workbench/PromptList.vue
frontend/src/components/workbench/PromptEditor.vue
frontend/src/components/workbench/PromptVersionHistory.vue
frontend/src/components/workbench/PromptDiffView.vue
frontend/src/components/workbench/EvalRunPanel.vue
frontend/src/components/workbench/BaselineComparePanel.vue
frontend/src/components/workbench/UsagePanel.vue        # 复用 UsageSettings
frontend/src/utils/prompt.ts
frontend/src/utils/eval.ts
frontend/src/utils/prompt.spec.ts
frontend/src/utils/eval.spec.ts
frontend/e2e/llm-workbench.spec.ts
```

修改：`App.vue`（侧栏底部「LLM 工作台」入口）、`router/index.ts`（`/llm-workbench`）、`composables/useSettings.ts`（`SettingsTab` 移除 `usage`）、`components/settings/SettingsDrawer.vue`（移除调用统计 Tab，保留模型服务/场景模型/外观）。

## 开发对照表（每个 Task 完成后更新）

> 规则：每个 Task commit 前更新对应行（实现类 / 验证 / 状态）；Phase 结束时在会话记录贴一次表；接口或表变更先改本表再改代码。这样随时能看到「功能 → 接口 → 表 → 类 → 怎么验证」。

| 功能 | 接口 | 实现类 | 数据表 | 验证 | 状态 |
|---|---|---|---|---|---|
| 提示词列表/详情 | `GET /api/llm/prompts`、`GET /api/llm/prompts/{key}` | `PromptService` / `PromptRegistry` | `llm_prompt_versions` | `PromptApiIntegrationTest` + `llm-workbench.spec.ts` | 已完成 |
| 草稿/发布/回滚 | `PUT .../draft`、`POST .../publish`、`POST .../versions/{n}/activate` | `PromptService` | `llm_prompt_versions` | `PromptServiceTest` + `PromptApiIntegrationTest` + E2E | 已完成 |
| 运行时提示词生效 | 无（内部调用） | 8 个 `PromptFactory` + `PromptRegistry` | `llm_prompt_versions` | `PromptFactoryTest` + 业务服务测试 | 已完成 |
| 模型与绑定 | 现有 `/api/llm/global`、`/api/llm/scenes/{scene}` | `LlmGlobalSettingService` / `LlmSceneService` | 现有 LLM 配置表 | 现有测试 + `llm-workbench.spec.ts` | 已完成 |
| 评测运行 | `POST/GET /api/llm/eval/runs`、`GET /{id}`、`POST /{id}/cancel` | `EvalRunService` / `EvalRunner` | `llm_eval_runs` / `llm_eval_run_cases` | `EvalRunServiceTest` + `EvalRunnerTest` + `EvalApiIntegrationTest` | 后端已完成，前端待 Task 11 |
| 设为基线 | `POST /api/llm/eval/baselines` | `EvalBaselineService` | `llm_eval_baselines` | `EvalBaselineServiceTest` + E2E | 待实现 |
| 基线列表/设为当前 | `GET /api/llm/eval/baselines`、`POST .../{id}/activate` | `EvalBaselineService` | `llm_eval_baselines` | `EvalBaselineServiceTest` + E2E | 待实现 |
| 运行对比 | `GET /api/llm/eval/runs/compare` | `EvalCompareService` | runs + cases | `EvalCompareServiceTest` + E2E | 待实现 |
| 调用统计 | 现有 `GET /api/llm/usage` | `LlmUsageService` | `llm_call_logs` | 现有测试 + `llm-workbench.spec.ts` | 已完成 |
| 重跑失败用例 | `POST /api/llm/eval/runs`（`sourceRunId` + `retryErrorsOnly`） | `EvalRunService` | runs/cases | `EvalRunServiceTest` | 待实现 |
| 提示词导出 | 前端聚合 `listPrompts + getPrompt` | `PromptList.vue` | 无 | E2E | 待实现 |

## 任务分解

### Phase P1：LLM 工作台壳 + 提示词注册与编辑

#### Task 0：开工收口（不写业务代码）

**Files:** 无新增；处理当前工作区 3 个未提交改动。

- [ ] `git pull --rebase --autostash`，确认无冲突。
- [ ] 检查 `ResumePromptFactory.java`、`resume-analysis-system.st`、`功能实现对照表.md` 的 v3 改动；确认后提交 `fix(prompt): 简历引用必须是连续原文（resume-analysis-v3）`。
- [ ] 跑基线：`$env:JAVA_HOME="D:\Java\21"; mvn -f backend/pom.xml test`，确认 229 tests 全绿。
- [ ] 记录当前 `git log` 与测试结果到会话记录。

#### Task 1：契约 1.22.0（提示词 8 端点）

**Files:**
- Modify: `contracts/openapi.yaml`
- Modify: `backend/src/test/java/com/jobpilot/contract/ContractCoverageTest.java`
- Test: `backend/src/test/java/com/jobpilot/contract/ContractSchemaTest.java`

**Interfaces:**
- 端点与 schema 见「契约变更清单 P1」；所有新路径先标 `x-implemented: false`。

- [ ] **Step 1: 写契约**：增加 8 个路径、8 个业务 schema、4 个 Result 包装；`info.version` 改 `1.22.0`；`info.description` 增加「1.22.0 新增提示词工作台」与错误码说明。
- [ ] **Step 2: 跑 lint**

```powershell
$env:npm_config_cache="$env:TEMP\npm-cache-afterround"; npx @redocly/cli lint contracts/openapi.yaml
```

Expected: `0 error / 0 warning`。

- [ ] **Step 3: 更新契约测试**：`ContractCoverageTest` 总数断言改 101；`ContractSchemaTest` 增加 `PromptDetail` 等 schema 的 DTO 字段对齐（实现后再补全）。
- [ ] **Step 4: 跑契约测试**

```powershell
$env:JAVA_HOME="D:\Java\21"; mvn -f backend/pom.xml -Dtest=ContractCoverageTest,ContractSchemaTest test
```

Expected: 通过（新端点全部 `x-implemented: false`，实际控制器尚未新增）。

- [ ] **Step 5: Commit** `feat(contract): 定义提示词工作台 8 端点（1.22.0）`。

#### Task 2：V11 + PromptCatalog（12 组元数据）

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__llm_prompt_versions.sql`
- Create: `backend/src/main/java/com/jobpilot/modules/prompt/catalog/PromptSpec.java`
- Create: `.../catalog/PromptCatalog.java`
- Create: `.../catalog/PromptOutputMode.java`
- Create: `.../catalog/PromptModelScope.java`
- Create: `.../entity/LlmPromptVersion.java`
- Create: `.../mapper/LlmPromptVersionMapper.java`
- Test: `backend/src/test/java/com/jobpilot/modules/prompt/PromptCatalogTest.java`

**Interfaces:**
- `PromptSpec(String key, String name, String category, String description, String systemFile, String userFile, List<String> placeholders, List<String> requiredFields, PromptOutputMode outputMode, PromptModelScope modelScope, LlmScene scene, String evalScene, List<String> affectedFunctions, String builtinVersion)`
- `PromptCatalog.all(): List<PromptSpec>`；`get(String key): PromptSpec`（不存在抛 `BizException(40401)`）。
- `LlmPromptVersion` 字段与 V11 一一对应。

**12 组配置（必须全部落地）：**

| key | 名称 | 占位符 | 必填 JSON 字段 | 输出 | 模型作用域 | 评测 |
|---|---|---|---|---|---|---|
| resume-analysis | 简历分析 | resumeText, marketDirections | technicalDepth, projectQuality, engineeringAbility, expressionStructure, jobMatchPotential, basicInfo, strengths, weaknesses, suggestions, careerDirections | JSON | RESUME_ANALYZE | resume-analysis |
| resume-comparison | 简历对比 | base, target, diff | summary | JSON | GLOBAL | 无 |
| job-position-parse | JD 解析 | jdText | title, companyName, city, salaryMin, salaryMax, experience, education, mustHaveSkills, niceToHaveSkills, responsibility | JSON | GLOBAL | jd-parse |
| job-position-ocr | JD 截图 OCR | 无 | 无 | TEXT | GLOBAL | 无 |
| job-match | 岗位匹配 | resumeText, jdText | skillMatch, experienceMatch, bonusCoverage, gaps, preparationAdvice | JSON | JOB_MATCH | 无 |
| review-analyze | 复盘分析 | companyName, jobTitle, roundType, interviewerRole, companyIntro, jdText, resumeText, text | questions, topics, lossTags, summary, actions | JSON | REVIEW_ANALYZE | review-analyze |
| insights-summary | 洞察总结 | days, data | 无 | TEXT | REVIEW_ANALYZE | 无 |
| interview-question | 模拟面试出题 | persona, questionCount, followUpCount, sourceRule, categories, resumeSection, jdSection, historyTopics | questions | JSON | INTERVIEW_QUESTION | 无 |
| interview-followup | 模拟面试追问 | persona, followUpCount, directionLabel, mainContent, answer | questions | JSON | 会话模型 | 无 |
| interview-evaluate | 模拟面试评估 | persona, categories, directionLabel, transcript | questions, categories, strengths, improvements, summary | JSON | INTERVIEW_EVALUATE | 无 |
| offer-extract | Offer 提取 | text | companyName, jobPositionTitle, monthlySalary, baseSalary, performanceRatio, socialInsuranceBase, socialInsuranceRatio, housingFundBase, housingFundRatio, twoDayWeekend, workHours, benefits, allowances, annualBonus, quarterlyBonus, remark | JSON | GLOBAL | 无 |
| offer-analysis | Offer 分析 | searchRule, materials | tendency, reasons, prospect, risks, sources | JSON | OFFER_ANALYZE | 无 |

内置版本号：`resume-analysis-v3`、`resume-comparison-v1`、`jd-parse-v1`、`job-position-ocr-v1`、`job-match-v1`、`review-analyze-v2`、`insights-summary-v1`、`interview-question-v1`、`interview-followup-v1`、`interview-evaluate-v1`、`offer-extract-v1`、`offer-analysis-v1`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void catalogCoversAllTwelvePrompts() {
    var all = new PromptCatalog().all();
    assertThat(all).hasSize(12);
    assertThat(all).extracting(PromptSpec::key).doesNotHaveDuplicates();
}

@Test
void everyPromptFileExistsOnClasspath() {
    for (PromptSpec spec : new PromptCatalog().all()) {
        assertThat(new ClassPathResource(spec.systemFile()).exists()).isTrue();
        assertThat(new ClassPathResource(spec.userFile()).exists()).isTrue();
    }
}

@Test
void everyEvalPromptHasScene() {
    assertThat(new PromptCatalog().all().stream()
            .filter(spec -> spec.evalScene() != null)
            .map(PromptSpec::key))
            .containsExactlyInAnyOrder("resume-analysis", "jd-parse", "review-analyze");
}
```

- [ ] **Step 2: 跑测试确认失败**：`mvn -Dtest=PromptCatalogTest test`，Expected: `PromptCatalog` 不存在。
- [ ] **Step 3: 写 V11、entity、mapper、catalog**；`PromptSpec` 使用 `List.copyOf` 防可变。
- [ ] **Step 4: 跑测试**：`PromptCatalogTest` 通过。
- [ ] **Step 5: 跑 Flyway 集成测试**：`mvn -Dtest=JobPilotApplicationTests test`，Expected: V11 在 Testcontainers PostgreSQL 执行成功。
- [ ] **Step 6: Commit** `feat(prompt): 新增提示词版本表与 12 组元数据`。

#### Task 3：PromptValidator + PromptRegistry

**Files:**
- Create: `.../service/PromptValidator.java`
- Create: `.../service/PromptRegistry.java`
- Test: `backend/src/test/java/com/jobpilot/modules/prompt/PromptValidatorTest.java`
- Test: `.../PromptRegistryTest.java`

**Interfaces:**
- `PromptValidator.validate(PromptSpec spec, String system, String user): ValidationResult`；`ValidationResult(boolean valid, List<String> errors, List<String> warnings)`。
- `PromptRegistry(PromptCatalog catalog, LlmPromptVersionMapper mapper)`；`template(String key): PromptTemplate`（DB ACTIVE 优先）；`builtin(String key): PromptTemplate`（只读 classpath，供 diff/恢复）；`version(String key): String`；`invalidate(String key)`。
- `PromptTemplate(String key, String system, String user, String versionLabel, String contentHash, PromptVersionSource source)`；`PromptVersionSource` 为 `BUILTIN/DB`。
- 静态工厂 `PromptRegistry.builtinOnly()`：不查 DB，供工厂无参构造/CLI 使用。

**校验规则：**
- `system/user` 非空；单段 ≤ 20,000 字符。
- 占位符：`PromptSpec.placeholders` 每个都必须在 user 模板出现；出现未知 `{{x}}` 只告警。
- JSON 模式：`PromptSpec.requiredFields` 每个字段名必须在 system 中以词边界出现，缺失即 error。
- TEXT 模式：不做字段检查。

- [ ] **Step 1: 写失败测试**

```java
@Test
void missingPlaceholderIsInvalid() {
    PromptSpec spec = new PromptCatalog().get("resume-analysis");
    var result = new PromptValidator().validate(spec,
            "输出 JSON，字段 technicalDepth ...", "简历：{{resumeText}}");
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("marketDirections"));
}

@Test
void missingRequiredFieldBlocksPublish() {
    PromptSpec spec = new PromptCatalog().get("resume-analysis");
    var result = new PromptValidator().validate(spec, "只输出 JSON", "{{resumeText}} {{marketDirections}}");
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("technicalDepth"));
}

@Test
void textPromptSkipsFieldCheck() {
    PromptSpec spec = new PromptCatalog().get("insights-summary");
    var result = new PromptValidator().validate(spec, "用中文总结", "{{days}} 天：{{data}}");
    assertThat(result.valid()).isTrue();
}
```

- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现 Validator**：用 `Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}")` 提取占位符；字段名用 `(?<![A-Za-z0-9_])name(?![A-Za-z0-9_])`。
- [ ] **Step 4: 实现 Registry**：`template()` 先查缓存；再查 `status='ACTIVE'` 的 DB 行；无则读 classpath；`version()` 返回 DB 版本标签 `<key>-db<versionNo>` 或 `builtinVersion`；`contentHash` 用 SHA-256。
- [ ] **Step 5: 写 Registry 测试**：mock mapper 返回 ACTIVE 行，断言 DB 覆盖内置；`invalidate` 后重新读取；`builtinOnly` 不调用 mapper。
- [ ] **Step 6: 跑测试并提交** `feat(prompt): 提示词校验与运行时注册表`。

#### Task 4：PromptService + Controller + 契约实现

**Files:**
- Create: `.../service/PromptService.java`
- Create: `.../controller/PromptController.java`
- Create: `.../dto/PromptSummary.java`、`PromptDetail.java`、`PromptVersionSummary.java`、`PromptVersionDetail.java`、`PromptValidation.java`、`SavePromptDraftRequest.java`、`PublishPromptRequest.java`
- Test: `backend/src/test/java/com/jobpilot/modules/prompt/PromptServiceTest.java`
- Test: `backend/src/test/java/com/jobpilot/modules/prompt/PromptApiIntegrationTest.java`
- Modify: `backend/src/test/java/com/jobpilot/contract/ContractCoverageTest.java`（移除 P1 的 `x-implemented: false`）
- Modify: `contracts/openapi.yaml`（删除 P1 路径的 `x-implemented: false`）
- Modify: `backend/src/main/java/com/jobpilot/common/api/ErrorCode.java`（新增 `40920` 提示词版本冲突）

**Interfaces:**
- `list(): List<PromptSummary>`
- `detail(String key): PromptDetail`（`builtIn` 始终返回；`validation` 有草稿时校验草稿，否则校验 active）
- `saveDraft(String key, SavePromptDraftRequest req): PromptDetail`
- `discardDraft(String key): void`
- `publish(String key, PublishPromptRequest req): PromptDetail`
- `versions(String key, int page, int size): PageResult<PromptVersionSummary>`
- `version(String key, int versionNo): PromptVersionDetail`
- `activate(String key, int versionNo): PromptDetail`

**事务与并发：**
- 保存草稿：若已有 DRAFT 则更新；否则 `max(version_no)+1` 插入 DRAFT；同时写 `contentHash`；内容与当前 ACTIVE 相同 → `validation.warnings` 提示。
- 发布：校验 DRAFT → 当前 ACTIVE 置 ARCHIVED → DRAFT 置 ACTIVE + `published_at=now` → `registry.invalidate(key)`；内容与当前 ACTIVE 相同 → warning，不阻断。
- 回滚：只允许激活 ARCHIVED 版本；目标版本置 ACTIVE、当前 ACTIVE 置 ARCHIVED；若目标已是 ACTIVE 幂等返回；已有草稿 → 保留并返回 warning「草稿仍基于旧版本」；激活 DRAFT → 400。
- 丢弃草稿：不存在时幂等返回 200。
- 版本历史 `versions()` 只返回 ACTIVE/ARCHIVED，DRAFT 只在 `detail.draft`。
- `detail()` 返回 `builtIn`（代码内置默认），供前端 diff 与恢复内置。
- 每个方法 `@Transactional`；部分唯一索引兜底并发，唯一冲突映射 `40920`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void publishMovesDraftToActiveAndKeepsHistory() {
    var detail = service.saveDraft("resume-analysis", new SavePromptDraftRequest(
            "system v1", "{{resumeText}} {{marketDirections}} technicalDepth ...", "首版"));
    assertThat(detail.draft().status()).isEqualTo(DRAFT);

    var published = service.publish("resume-analysis", new PublishPromptRequest("发布"));
    assertThat(published.active().versionNo()).isEqualTo(1);
    assertThat(published.draft()).isNull();
    assertThat(service.versions("resume-analysis", 1, 20).items()).hasSize(1);
}

@Test
void cannotPublishWithoutDraft() {
    assertThatThrownBy(() -> service.publish("resume-analysis", new PublishPromptRequest(null)))
            .isInstanceOf(BizException.class);
}

@Test
void invalidDraftCannotBePublished() {
    service.saveDraft("resume-analysis", new SavePromptDraftRequest("system", "{{resumeText}}", null));
    assertThatThrownBy(() -> service.publish("resume-analysis", new PublishPromptRequest(null)))
            .isInstanceOf(BizException.class);
}
```

- [ ] **Step 2: 跑测试确认失败**。
- [ ] **Step 3: 实现 service**；`detail` 返回 `active`（内置时 `versionNo=0, source=BUILTIN`）与 `builtIn`（代码内置默认，供 diff/恢复）。
- [ ] **Step 4: 实现 controller**；路径、响应码严格按契约；`PromptKey` 路径变量用 `@PathVariable String promptKey`。
- [ ] **Step 5: 写 API 集成测试**：Testcontainers PostgreSQL + MockMvc，覆盖 8 端点、404、400、40920、草稿/发布/回滚/内置恢复。
- [ ] **Step 6: 跑契约测试**，移除 `x-implemented: false`，`ContractCoverageTest` 总数仍 101 且双向一致。
- [ ] **Step 7: Commit** `feat(prompt): 提示词草稿、发布、回滚与版本历史`。

#### Task 5：8 个工厂接入 Registry + 动态版本记录

**Files:**
- Modify: 8 个 `PromptFactory`
- Modify: `ResumeAnalysisService.java`、`ReviewSessionService.java`
- Test: `backend/src/test/java/com/jobpilot/modules/prompt/PromptFactoryTest.java`
- Test: 现有 `ResumeComparisonServiceTest`、`JobMatchServiceTest`、`JobPositionServiceTest` 保持通过

**Interfaces:**
- 每个工厂保留无参构造（`this(PromptRegistry.builtinOnly())`）与 `@Autowired` 构造。
- 每个工厂增加 `version()`（多组工厂分别为 `parseVersion/ocrVersion`、`questionVersion/followUpVersion/evaluateVersion`、`extractVersion/analysisVersion`）。
- 服务记录版本处改为 `prompts.version(key)`。

- [ ] **Step 1: 写失败测试**：mock `PromptRegistry` 返回 `db-v1` 模板，断言工厂 `system()` 使用 DB 内容、`version()` 返回 `resume-analysis-db1`。
- [ ] **Step 2: 改造 8 个工厂**：删除构造器里的 `ClassPathResource` 读取，改为 `registry.template(key)`；占位符 `.replace` 保持不变。
- [ ] **Step 3: 更新服务**：`ResumeAnalysisService`、`ReviewSessionService` 的 `VERSION` 引用改为工厂 `version()`。
- [ ] **Step 4: 跑全量测试**

```powershell
$env:JAVA_HOME="D:\Java\21"; mvn -f backend/pom.xml test
```

Expected: 229+ tests 全绿；无参构造的旧测试仍可运行。

- [ ] **Step 5: Commit** `refactor(prompt): 工厂接入运行时注册表并记录动态版本`。

#### Task 6：前端 LLM 工作台壳 + 模型与绑定 + 提示词 + 调用统计（P1）

**Files:**
- Create: `frontend/src/api/prompts.ts`
- Create: `frontend/src/views/LlmWorkbenchView.vue`
- Create: `frontend/src/components/workbench/ModelBindingPanel.vue`
- Create: `frontend/src/components/workbench/PromptList.vue`
- Create: `frontend/src/components/workbench/PromptEditor.vue`
- Create: `frontend/src/components/workbench/PromptVersionHistory.vue`
- Create: `frontend/src/components/workbench/PromptDiffView.vue`
- Create: `frontend/src/components/workbench/UsagePanel.vue`
- Create: `frontend/src/utils/prompt.ts`
- Modify: `frontend/src/App.vue`（侧栏底部入口）
- Modify: `frontend/src/router/index.ts`（`/llm-workbench`）
- Modify: `frontend/src/composables/useSettings.ts`（移除 `usage`）
- Modify: `frontend/src/components/settings/SettingsDrawer.vue`（移除调用统计 Tab）
- Test: `frontend/src/utils/prompt.spec.ts`
- Test: `frontend/e2e/llm-workbench.spec.ts`

**UI 行为：**
- 侧栏底部在「设置」附近增加「LLM 工作台」入口，路由 `/llm-workbench`，`ownTitle: true`；入口有 active 状态，面包屑分组显示「工具」。
- P1 三个 Tab：模型与绑定 / 提示词 / 调用统计；评测运行、基线对比在 P2/P3 加入。
- 模型与绑定：全局默认 + 7 个场景绑定 + 3 个评测场景生效模型；下拉选择后先改本地状态，**点「保存修改」才调用** `/api/llm/global`、`/api/llm/scenes/{scene}`；全局默认保存前二次确认并提示「影响所有未绑定场景」；不提供 Provider/Model CRUD（仍在设置，给跳转链接），文案标注「与设置同步」。
- 提示词：左侧 12 组列表；右侧 system/user 双编辑器、占位符提示、校验错误、保存草稿/发布/丢弃、版本历史。
- 提示词安全：与当前生效版本做行级 diff；「恢复内置默认」把 `builtIn` 内容填入草稿（不直接发布）；切换提示词/Tab/路由时若有未保存修改，弹「保存草稿 / 丢弃 / 取消」。
- 内置升级：`active.source=DB` 且 `builtinVersion` 与当前内置不一致时，显示「内置默认已升级」+「查看/恢复」。
- 发布只显示「发布成功」，不自动跑评测；内置 `versionNo=0` 显示为「内置默认（vN）」。
- 调用统计：`UsagePanel.vue` 复用 `UsageSettings.vue`；设置抽屉移除该 Tab，避免重复入口；无数据时显示空态。
- 风格：沿用 `ui.css` 与纸墨 tokens；单容器 + 行分隔线；状态用 `.status` 圆点；每屏实心按钮 ≤1。

- [ ] **Step 1: 契约类型生成**：`cd frontend; npm run gen:api`，确认 Prompt schemas 与现有 LLM schemas 都在 `api/schema.d.ts`。
- [ ] **Step 2: 写 `utils/prompt` 单测**（占位符提取、版本标签、校验消息合并、行级 diff、dirty 判断）。
- [ ] **Step 3: 实现 API client**：`listPrompts/getPrompt/saveDraft/discardDraft/publish/listVersions/getVersion/activate`；模型与统计复用现有 client。
- [ ] **Step 4: 实现工作台壳与三个 Tab、侧栏入口、路由**；更新 `SettingsDrawer` 与 `useSettings`；加 dirty 离开保护。
- [ ] **Step 5: 全局 grep `调用统计` / `usage`**，更新受影响的 E2E 与文案；设置抽屉可保留一行「调用统计已迁至 LLM 工作台」链接。
- [ ] **Step 6: 前端验证**

```powershell
cd frontend
npm run build
npm run test
npm run test:e2e
```

Expected: 构建通过；Vitest 全过；Playwright 全过（新用例：侧栏入口 → 模型保存确认 → 提示词 diff/恢复内置/未保存拦截/发布/回滚 → 调用统计只读；设置抽屉只剩 3 个 Tab）。

- [ ] **Step 7: Commit** `feat(prompt): 前端 LLM 工作台（模型/提示词/统计）`。

#### Task 7：P1 文档与回归

**Files:**
- Modify: `docs/superpowers/specs/面后（AfterRound）需求文档.md`（新增 F9 章节 + v1.18 迭代历程）
- Modify: `README.md`
- Modify: `docs/功能实现对照表.md`
- Modify: `docs/agent-memory/PROGRESS.md`、`docs/agent-memory/sessions/2026-11-27-DSL-WORKPC.md`
- Modify: `contracts/openapi.yaml`（`info.description` 覆盖范围补 1.22.0）

- [ ] 需求文档用产品语言写「多了什么/变了什么/砍了什么」，技术细节放附注。
- [ ] README 增加「LLM 工作台」能力与入口说明（模型与绑定/提示词/调用统计；发布不自动跑评测）。
- [ ] 跑完整回归：契约 lint、后端全量、前端构建/Vitest/Playwright。
- [ ] 更新 PROGRESS 当前状态与下一步；追加会话记录 `HH:mm` 条目。
- [ ] Commit `docs: LLM 工作台需求与 P1 收口`。

### Phase P2：评测运行器产品化

#### Task 8：契约 1.23.0（评测 5 端点）

- [x] 增加 5 个路径与 schema；`info.version=1.23.0`；新路径标 `x-implemented: false`。
- [x] 新增错误码 `40918`、`40919`，同步 `ErrorCode.java` 与契约 `info.description`。
- [x] `ContractCoverageTest` 总数改 106；跑 lint 与契约测试。
- [x] Commit `feat(contract): 定义评测运行 5 端点（1.23.0）`。

#### Task 9：评测核心迁入运行时 + 语料 classpath 化

**Files:**
- Move: `backend/src/test/java/com/jobpilot/eval/EvalCase.java` → `.../modules/eval/core/EvalCase.java`
- Move: `EvalCorpus.java`、`Expectations.java` 同理
- Create: `.../core/EvalSnapshot.java`、`EvalMetricsCalculator.java`、`EvalFingerprint.java`、`EvalEngine.java`
- Move: `backend/src/test/resources/llm-eval/cases/**` → `backend/src/main/resources/llm-eval/cases/**`
- Move: `backend/src/test/resources/llm-eval/baselines/*.json` → `backend/src/main/resources/llm-eval/baselines/*.json`
- Modify: `backend/src/test/java/com/jobpilot/eval/LlmEvalTest.java`
- Test: `backend/src/test/java/com/jobpilot/eval/EvalCorpusTest.java`、`ExpectationsTest.java`、`EvalMetricsCalculatorTest.java`

**Interfaces:**
- `EvalCase(String scene, String id, String input, JsonNode meta, JsonNode expect, Map<String, String> files)`；`optionalFile(name)` 从 map 取。
- `EvalCorpus.load(ResourcePatternResolver resolver): List<EvalCase>`；仍保留 `validate(...)` 供默认测试。
- `EvalSnapshot.of(String scene, JsonNode actual): Snapshot(scores, topics, lossTags)`。
- `EvalMetricsCalculator.of(List<CaseResult>): EvalMetrics`。
- `EvalFingerprint(String scene, String providerKey, String model, String corpusHash, String engineVersion, Map<String, Object> params)`。
- `EvalEngine.VERSION = "llm-eval-v1"`（断言规则不变，不升版）。

**旧基线元数据补丁：**
- 给 3 个 baseline JSON 增加 `promptVersion`、`engineVersion`、`params`：
  - `resume-analysis.json`：`promptVersion=resume-analysis-v2`（历史），`engineVersion=llm-eval-v1`，`params={maxTokens:8192,timeoutSeconds:300,repeat:1,temperature:0.2}`。
  - `jd-parse.json`：`promptVersion=jd-parse-v1`。
  - `review-analyze.json`：`promptVersion=review-analyze-v2`。
- 旧 JSON 不含 `promptHash`，导入规则：`baseline.promptVersion` 与当前内置版本一致 → 写入当前内容 hash；不一致 → 写 `legacy`。因此只有简历（v2→v3）会被建议重建，JD/复盘不会误报。

**CLI 基线写模式：**
- `LlmEvalTest` 只从 `classpath:llm-eval/baselines` 读取基线。
- `EVAL_WRITE_BASELINE=true` 时写到 `EVAL_BASELINE_OUT`（默认 `target/llm-eval/baselines`），**不写 `src/main/resources`**；打印输出路径，由开发者决定是否复制回资源目录并提交。
- 产品「设为基线」只写 DB，不碰文件。

- [x] **Step 1: 先写测试**：`EvalCorpusTest` 用 `PathMatchingResourcePatternResolver` 加载 27 份；`ExpectationsTest` 用固定 JSON 覆盖 L1/L2/L3；`EvalMetricsCalculatorTest` 覆盖 p95。
- [x] **Step 2: 迁移代码与资源**；`LlmEvalTest` 改为 `EvalCorpus.load(new PathMatchingResourcePatternResolver())`，baseline 读取 `classpath:llm-eval/baselines`，写模式改 `target/llm-eval/baselines`。
- [x] **Step 3: 跑默认测试**（不跑 llm-eval）：

```powershell
$env:JAVA_HOME="D:\Java\21"; mvn -f backend/pom.xml test
```

Expected: `EvalCorpusTest` 27 份通过；全量 229+ tests 全绿。

- [ ] **Step 4: 跑 CLI 评测冒烟**（可选，需真实模型）：确认读 classpath 基线可对比；`EVAL_WRITE_BASELINE=true` 输出到 `target/llm-eval/baselines`，源码目录保持干净。（需用户主动触发，未自动执行）
- [x] **Step 5: Commit** `refactor(eval): 评测核心迁入运行时并改为 classpath 语料`。

#### Task 10：V12 + EvalRunService + 异步执行

**Files:**
- Create: `V12__llm_eval_runs.sql`
- Create: `.../entity/LlmEvalRun.java`、`LlmEvalRunCase.java`
- Create: `.../mapper/LlmEvalRunMapper.java`、`LlmEvalRunCaseMapper.java`
- Create: `.../service/EvalClientFactory.java`、`EvalRunService.java`、`EvalRunner.java`、`EvalRunRecovery.java`
- Create: `.../controller/EvalController.java`
- Create: `.../dto/*.java`
- Modify: `AsyncConfig.java`（`evalTaskExecutor`）
- Modify: `application.yml`（`app.eval.max-tokens=8192`、`timeout-seconds=300`、`repeat=1`）
- Modify: `LlmUsageService.java`（`EVAL_*` 场景中文标签，如「评测·简历分析」）
- Test: `EvalRunServiceTest.java`、`EvalRunnerTest.java`、`EvalApiIntegrationTest.java`

**执行流程：**
1. 校验 scenes 非空且属于 3 个支持场景；校验无 PENDING/PROCESSING 运行（40918）；服务锁 + DB 部分唯一索引兜底。
2. 解析每个场景模型：`RESUME_ANALYZE`/`REVIEW_ANALYZE` 用 `sceneService.resolve`，`jd-parse` 用 `resolveGlobal`；无模型返回 400 可操作提示。
3. 快照提示词全文（`PromptRegistry.template(promptKey)`）、Provider/Model、参数、语料 hash、引擎版本。
4. 事务内插入 run + cases（PENDING）；用 `TransactionSynchronization.afterCommit` 提交异步任务；队列满则 run 置 FAILED。
5. Worker 按场景分组，构建 `EvalClientFactory` 客户端与 `StructuredOutputInvoker`；逐用例执行、评估、更新 case、更新进度；用例间检查 `cancel_requested`。
6. 连续 3 个用例因同一 Provider 错误失败 → 熔断，run 置 FAILED，剩余 PENDING 置 SKIPPED；熔断只统计上游连接/超时/5xx/429，JSON 解析失败不算 Provider 故障。用例异常记 ERROR 并继续；全部结束计算 metrics。运行完成不等于基线；用户满意后在结果页点「设为基线」（P3），不重跑。
7. 评测调用写 `llm_call_logs`，scene 为 `EVAL_RESUME_ANALYZE` / `EVAL_JD_PARSE` / `EVAL_REVIEW_ANALYZE`；`LlmUsageService` 补对应中文标签，调用统计按场景分组可区分；「含评测/仅业务」过滤开关留后续。
8. 取消：置 `cancel_requested`，当前用例结束后停止，剩余 PENDING 置 SKIPPED，run 置 CANCELLED；取消/失败的 run 不建基线。

- [x] **Step 1: 写失败测试**：`EvalRunServiceTest.createRejectsConcurrentRun`（已有 PROCESSING → 40918）、`createRejectsUnsupportedScene`（400）、`cancelFinishedRun`（40919）、`afterCommitWorkerSeesRun`、`consecutiveErrorsAbort`、`runnerWritesCaseResults`（fake client 返回固定 JSON）。
- [x] **Step 2: 实现 V12、entity、mapper、service、worker、controller**；`@Transactional` 创建 run/cases + `afterCommit` 提交任务。
- [x] **Step 3: 写 API 集成测试**：Testcontainers + 假 OpenAI 上游；POST run → 轮询 GET → COMPLETED；断言 cases、metrics、tokens、models/prompts 快照。
- [x] **Step 4: 启动恢复/取消测试**：预置 PROCESSING run，启动后变 FAILED 且剩余 case 变 SKIPPED；取消后同样清理。
- [x] **Step 5: 移除 P2 路径的 `x-implemented: false`**；`ContractCoverageTest` 总数 106 双向一致。
- [x] **Step 6: Commit** `feat(eval): 评测运行器产品化（异步、进度、取消）`。

#### Task 11：前端评测运行面板（P2）

**Files:**
- Create: `frontend/src/api/eval.ts`
- Create: `frontend/src/components/workbench/EvalRunPanel.vue`
- Modify: `frontend/src/views/LlmWorkbenchView.vue`（增加「评测运行」Tab）
- Create: `frontend/src/utils/eval.ts`
- Test: `frontend/src/utils/eval.spec.ts`
- Test: `frontend/e2e/llm-workbench.spec.ts`（追加）

**UI 行为：**
- P2 增加「评测运行」Tab；运行完成后在详情页显示「设为基线」（P3 开放），不重跑。
- 3 个场景卡片：case 数、当前 Prompt 版本、Provider/Model、当前基线时间、`rebuildReasons`；**模型为空时禁用勾选**并显示「去配置」。
- 预设「全量 27 例」与单场景 10/9/8 例；勾选后可调整；repeat 1/2；有历史运行时展示预估耗时/tokens，无历史显示「暂无预估」。
- **始终手动开始**：配置变更不触发运行；点击「开始运行」后二次确认。
- 明确提示：评测使用**当前生效版本**，草稿不参与；运行中使用启动时的模型/提示词快照，中途改配置不影响本次。
- 运行中：进度条（已完成/总数）、逐用例状态列表、取消按钮；取消二次确认并提示「当前用例完成后停止」；可离开页面，回来继续轮询。
- 完成后：跳转运行详情，展示 metrics、models/prompts 快照、质量失败（FAIL）与执行错误（ERROR）分开计数。

- [x] **Step 1: 写 `utils/eval` 单测**（进度百分比、状态文案、耗时格式化、错误/质量失败区分）。
- [x] **Step 2: 实现 API client 与面板**；轮询间隔 3 秒，终态停止。
- [x] **Step 3: E2E mock**：勾选简历 → 开始 → 进度 60% → 取消确认 → 完成 → 详情显示 L1/L2、快照与 case；另测未配置模型场景禁用。
- [x] **Step 4: 构建/Vitest/Playwright 全过；Commit** `feat(eval): 前端评测运行面板`。（构建 + Vitest 41 通过；Playwright 用例已编写，沙箱限制未执行，待本机跑）

#### Task 12：P2 文档与回归

- [x] 需求文档 F9 补「评测运行」小节；README 补使用说明。
- [x] 契约 `info.description` 补 1.23.0；`ContractSchemaTest` 补新 schema。
- [x] 全量回归；PROGRESS/会话记录；Commit `docs: 评测运行器 P2 收口`。

### Phase P3：基线版本化与对比

#### Task 13：契约 1.24.0 + V13 + 基线服务

**Files:**
- Modify: `contracts/openapi.yaml`（4 端点 + schema，1.24.0，`x-implemented: false`）
- Create: `V13__llm_eval_baselines.sql`
- Create: `.../entity/LlmEvalBaseline.java`、`mapper/LlmEvalBaselineMapper.java`
- Create: `.../service/EvalBaselineService.java`、`LegacyBaselineImporter.java`
- Create: `.../dto/EvalBaselineSummary.java`、`EvalBaselineResult.java`
- Test: `EvalBaselineServiceTest.java`、`LegacyBaselineImporterTest.java`

**规则：**
- `createFromRun(runId, label)`（`POST /api/llm/eval/baselines`）：只接受 `COMPLETED` 且 `partial=false` 的运行；按场景分组，某场景存在 ERROR → 跳过并返回 `EvalBaselineResult(scene, created=false, reason)`；否则事务内旧 `is_current=false`、插入/更新该 run 的基线 `is_current=true`；**不重跑**，直接复用 run 的结果。
- `label`：从 `CreateEvalBaselineRequest.label` 写入；为空则不填。
- `list(scene)`：当前基线在前，其余按 `created_at desc`。
- `activate(baselineId)`：只切换 `is_current`，不重跑、不改历史；不存在 404。
- 导入：启动时若 `llm_eval_baselines` 为空且没有 COMPLETED 运行，读取 3 个 JSON，创建合成 COMPLETED run + cases + baseline；旧数据无 L1/L2 明细，metrics 对应字段为 null。
- 旧基线 `promptHash`：`baseline.promptVersion == 当前内置版本` → 写当前内容 hash；否则写 `legacy`（只有简历 v2→v3 会命中）。
- 旧基线 Provider 未知：`provider_key='legacy'`；对比时 legacy 视为通配，只要求 model 相同，并在 `warnings` 中提示「历史基线 Provider 未知，按模型比较」。
- 幂等：同一 `(runId, scene)` 重复设为基线只更新 label/current，不重复插入；`run_id` 使用 `ON DELETE RESTRICT`，历史基线不随 run 删除。

- [ ] **Step 1: 写失败测试**：`setBaselineDoesNotRerun`、`createMarksOnlyOneCurrent`、`sceneWithErrorIsSkipped`、`partialRunCannotCreateBaseline`、`activateSwitchesCurrent`、`importIsIdempotent`、`legacyHashOnlyForChangedVersion`。
- [ ] **Step 2: 实现 V13、entity、mapper、service、importer**。
- [ ] **Step 3: 运行导入测试**：3 个 scene 各 1 条 current；case 数与 JSON 一致；JD/复盘不因 legacy 误报。
- [ ] **Step 4: 移除 P3 路径 `x-implemented: false`**；`ContractCoverageTest` 总数 110。
- [ ] **Step 5: Commit** `feat(eval): 基线版本化、设为基线与旧基线导入（1.24.0）`。

#### Task 14：影响面分析 + 4 维对比

**Files:**
- Create: `.../service/EvalImpactAnalyzer.java`
- Create: `.../service/EvalCompareService.java`
- Create: `.../dto/EvalRunCompare.java`、`EvalRunCompareSide.java`、`EvalBaselineResult.java`、`PromptImpactSuggestion.java` 等
- Test: `EvalImpactAnalyzerTest.java`、`EvalCompareServiceTest.java`

**影响面：**
- `suggestForPrompt(promptKey)`：找出映射场景；逐项比较当前 Prompt hash、Provider/Model、语料 hash、引擎版本、taxonomy/rubric（复盘）与当前基线；返回 `PromptImpactSuggestion(scene, caseCount, reasons, currentBaselineId)`。
- `rebuildReasons(scene)`：供 `GET /api/llm/eval/scenes` 使用。
- 无基线 → 原因「尚无基线」；无评测语料 → 空列表。

**对比：**
- 对比对象是 run vs run；基线只是被标记的 run。
- 只有 `COMPLETED` 且 `partial=false` 的运行可参与完整对比；取消/失败/部分重跑 → `comparable=false`。
- 可比性：场景相同 + Provider 相同 + Model 相同 + params 相同 + corpusHash 相同 + engineVersion 相同；否则 `comparable=false`。Provider 为 `legacy` 时视为通配，只比 model，并写入 `warnings`。
- `EvalSceneCatalog` 暴露场景能力：简历有分数无标签、JD 无分数无标签、复盘有分数有标签；据此生成 `applicableDimensions`，不适用维度不计算。
- 质量：L1/L2 总失败数与通过率、delta；旧基线无明细时为 null。
- 回归：`fixedCases`、`newFailedCases`、`scoreDriftCases`（绝对差 >10）、`tagChangedCases`（Jaccard <0.7）。
- 成本：总 tokens、平均 tokens、delta。
- 耗时：平均、P95、delta。
- case 明细：`changes[] ∈ FIXED/REGRESSED/SCORE_DRIFT/TAG_CHANGED/UNCHANGED`（一个 case 可同时有多项）。

**部分重跑：**
- `CreateEvalRunRequest.sourceRunId + retryErrorsOnly=true`：复制源 run 的 scenes/model/prompt/corpus/engine 快照，只取 `status=ERROR` 的 case；新 run `partial=true`，不建基线、不参与完整对比；前端只在有 ERROR 时显示「重跑失败用例」。

- [ ] **Step 1: 写失败测试**：仅改简历 Prompt → 只建议 resume；换模型 → 建议该场景；引擎版本变化 → 全部；对比“上次基线 run vs 本次回归 run” → 正确分类修复/失败/漂移；取消/部分运行 → 不可比；JD 不出现 tag 维度。
- [ ] **Step 2: 实现 analyzer 与 compare service**；对比入口用 `leftRunId/rightRunId`。
- [ ] **Step 3: 接入 `PromptService.detail` 的 `suggestedEvalScenes` 与 `EvalSceneSummary.rebuildReasons`。
- [ ] **Step 4: API 测试 compare 的 `comparable=false` 分支、partial/取消分支与 `404`。
- [ ] **Step 5: Commit** `feat(eval): 影响面分析与运行 4 维对比`。

#### Task 15：前端基线对比面板（P3）

**Files:**
- Create: `frontend/src/components/workbench/BaselineComparePanel.vue`
- Modify: `frontend/src/views/LlmWorkbenchView.vue`（增加「基线对比」Tab）
- Modify: `frontend/src/components/workbench/PromptEditor.vue`（发布后只显示「基线已过期」徽标 + 「去评测」链接）
- Modify: `frontend/src/components/workbench/PromptList.vue`（「导出 JSON」按钮）
- Modify: `frontend/src/api/eval.ts`（runs/compare、baselines/create/activate）
- Test: `frontend/e2e/llm-workbench.spec.ts`（追加）

**UI 行为：**
- 对比选择：左侧「上次基线（label / runId）」、右侧「本次运行」或任意两次运行；基线只是带标签的运行。
- 无基线：显示「尚无基线」，不显示「已过期」；过期徽标只用于“有基线但配置变了”，tooltip 列出原因。
- 不可比：顶部横幅列出原因（运行未完成/部分重跑/模型不同/语料不同/引擎不同），禁用指标卡片。
- 可比：4 个指标卡（质量/回归/成本/耗时），按 `applicableDimensions` 隐藏或标「不适用」；case 表筛选「新增失败/修复/分数漂移/标签变化」。
- 基线列表：显示 label、创建时间、提示词版本；「设为当前」只切换标记，不重跑。
- 运行详情：展示启动时的 models/prompts 快照；质量失败（FAIL）与执行错误（ERROR）分开；有 ERROR 时显示「重跑失败用例」（partial 运行）；运行完成且全量/无 ERROR 时显示「设为基线」（可填 label），点击后只贴标签、不重跑。
- 发布/改模型后：如果 `suggestedEvalScenes` 非空，只显示「基线已过期」徽标 + 「去评测」链接；**不弹窗、不自动跑、不自动跳转**。用户可忽略，直接继续使用。
- 「去评测」深链：`/llm-workbench?tab=eval&scenes=resume-analysis`，自动勾选受影响场景。
- 导出：提示词页「导出 JSON」在前端聚合 `listPrompts + getPrompt` 生成文件下载，不新增后端端点。

- [ ] **Step 1: 写 E2E**：发布 v3 → 出现「基线已过期」徽标（无弹窗）→ 点「去评测」→ 只勾选简历 → 手动开始 → 完成 → 选「上次基线 vs 本次」→ 看到「修复 1」与 4 张卡 → 点「设为基线」→ 基线列表出现新 label；另测「尚无基线」「不可比」「设为当前」「重跑失败用例」。
- [ ] **Step 2: 实现面板**；沿用表格/状态/横幅样式，避免布局跳动。
- [ ] **Step 3: 构建/Vitest/Playwright 全过；Commit** `feat(eval): 前端运行对比、基线标记与重跑入口`。

#### Task 16：P3 文档、回归与收尾

- [ ] 需求文档补「运行 vs 运行对比」「基线 label/设为当前」「重跑失败用例」「导出 JSON」与术语说明。
- [ ] README 补工作台操作路径、`.env.example`/`docker-compose.yml` 补 `app.eval` 配置说明。
- [ ] `docs/LLM评测集语料规范.md` 补引擎版本、基线指纹、DB 存储、旧基线导入与 CLI 写 `target/llm-eval/baselines` 说明。
- [ ] 全量回归：契约 lint、后端全量 + JaCoCo、前端构建/Vitest/Playwright。
- [ ] 手工验收（真实模型）：简历 v3 发布 → 只建议简历 → 跑一次 → 与上次基线比较 → 满意点「设为基线」（不重跑）；确认 002 从 FAIL 变 PASS 或至少记录原因。
- [ ] 更新 `PROGRESS.md` 与当日会话记录；`git status` 干净；commit + push（失败按 AGENTS 重试）。

## 验收与回归

| 验收项 | 判定 |
|---|---|
| 12 组提示词可查看 | `/api/llm/prompts` 返回 12 条，前端列表 12 行 |
| 工作台入口 | 侧栏底部「LLM 工作台」可进入 `/llm-workbench` |
| 5 个 Tab | 模型与绑定/提示词/评测运行/基线对比/调用统计均可切换 |
| 不自动跑 | 发布、改全局默认、改场景绑定均不触发评测；跳过重建不阻塞 |
| 过期提示 | 只显示「基线已过期」徽标，无弹窗、无强制跳转 |
| 同源 | 工作台与设置改同一个全局/场景绑定，刷新后一致 |
| 统计迁移 | 调用统计只在工作台出现，设置抽屉无重复 Tab |
| 编辑不影响线上 | 保存草稿后，下一次真实分析仍用 ACTIVE 版本 |
| 发布热更新 | 发布后下一次分析使用新版本，`prompt_version` 记录新标签 |
| 回滚 | 激活旧版本后，下一次分析使用旧内容 |
| 校验 | 删除占位符或必填字段 → 发布返回 400 且指出缺失项 |
| 只改简历 | 发布后建议仅 `resume-analysis`；运行只 10 例；JD/复盘基线不动 |
| 全局模型变更 | 未绑定场景全部建议重建；已绑定场景不出现 |
| 引擎版本变更 | 全部旧基线标「不可比」 |
| 4 维对比 | 质量/回归/成本/耗时均有左右值与 delta；case 可下钻 |
| 不可比 | 不同模型/语料/引擎时返回 `comparable=false` 与原因 |
| 无语料提示词 | 可发布，重建按钮置灰并提示 |
| 异步 | 运行有进度、可取消、重启后 PROCESSING 置 FAILED，剩余 case 变 SKIPPED |
| 成本 | 评测调用落 `llm_call_logs`，场景前缀 `EVAL_*` 且有中文标签 |
| 运行 vs 运行 | 本次回归可直接与历史基线（其 runId）对比，不要求先重建基线 |
| 逐场景基线 | 「设为基线」返回 `EvalBaselineResult[]`；部分成功可区分；不重跑 |
| 并发运行 | 两个标签页同时开始，只有一个成功，另一个 40918 |
| 熔断 | 连续 3 个 Provider 错误 → run FAILED，不跑满全量 |
| 取消/恢复 | 剩余 PENDING 变 SKIPPED，计数器重算，不建基线 |
| 提示词安全 | 版本 diff、恢复内置、未保存离开保护可用；内置升级有提示 |
| 模型保存 | 工作台修改需点「保存修改」；全局默认二次确认并提示影响未绑定场景 |
| 命名/口径 | `errorCases`、`changes[]`、场景不适用维度标「不适用」 |
| 基线管理 | 基线有 label；「设为当前」只换标记；无基线显示「尚无基线」 |
| 草稿边界 | 评测只跑 ACTIVE；版本历史不含 DRAFT；激活历史版本保留草稿并警告 |
| 部分重跑 | ERROR 用例可重跑（partial=true），不可建基线、不可完整对比 |
| CLI 写基线 | `EVAL_WRITE_BASELINE` 写 `target/llm-eval/baselines`，源码目录不脏 |

**固定命令：**

```powershell
# 契约
$env:npm_config_cache="$env:TEMP\npm-cache-afterround"; npx @redocly/cli lint contracts/openapi.yaml

# 后端
$env:JAVA_HOME="D:\Java\21"; mvn -f backend/pom.xml test

# 前端
cd frontend; npm run build; npm run test; npm run test:e2e

# 真实模型 CLI 评测（可选）
$env:JAVA_HOME="D:\Java\21"; mvn -o -f backend/pom.xml "-Djacoco.skip=true" "-Dsurefire.excludedGroups=__none__" "-Dgroups=llm-eval" test
```

## 风险与未决项

1. **跨设备同步**：提示词版本存本机 DB，不像代码走 git；本期前端支持导出 JSON，导入留后续。
2. **旧基线不完整**：当前 3 个 JSON 没有 L1/L2 明细，导入后质量通过率显示「无数据」；分数漂移、标签、tokens、耗时仍可对比，首次重建后完整。
3. **模型波动**：LLM 非确定性会产生分数漂移与标签变化；对比页必须展示 case 明细，阈值沿用 >10 / <0.7，避免只看聚合结论。
4. **成本与耗时**：10 例约 5-6 分钟、27 例约 15 分钟；开始前展示预估并二次确认；单次只允许一个运行。
5. **发布风险**：不强制评测门禁；任何配置变更都不自动触发评测；靠草稿/发布/回滚 + 校验兜底。若要门禁，后续加「发布前必须 L1 全过」。
6. **提示词影响面**：`PromptCatalog` 的 `affectedFunctions`/`evalScene` 是手工维护；新增调用方时必须同步，测试 `PromptCatalogTest` 只能校验结构，不能发现漏配。代码评审需检查。
7. **必填字段校验是启发式**：`PromptValidator` 只按词边界检查字段名是否出现，不能保证 JSON 结构/类型正确；发布前可先跑一次评测或至少看 diff，不能把它当 schema 校验器。
8. **`prompt_version` 兼容**：历史分析仍为 `resume-analysis-v2/v3`；DB 版本标签为 `<key>-dbN`，F7 对不等标签会提示不可比，这是预期行为。
9. **旧基线 Provider 未知**：导入的旧基线 `provider_key=legacy`，对比按 model 通配并提示；换 Provider 但同模型时也可比，结论需结合 case 明细判断。
10. **双入口模型配置**：工作台与设置抽屉都能改全局/场景绑定；必须复用同一 API 与同一状态，文案标注「与设置同步」，避免用户以为有两套配置。
11. **调用统计迁移**：从设置抽屉移除后，原有 E2E/书签可能失效；需要全局 grep「调用统计/usage」并更新测试与文案。
12. **不弹窗导致遗忘**：只用徽标提示，用户可能长期不「设为基线」；用场景卡片的 `rebuildReasons` 与「基线已过期」文案补偿，不做打断。
13. **保留策略未做**：运行/基线会长期累积；个人数据量小，本期不做删除/归档，后续按需加。
14. **重试 token 低估**：`StructuredOutputInvoker` 内部重试只记最后一次响应，评测成本可能低估；低优先，后续记录每次尝试。
15. **部分重跑不可比**：只重跑 ERROR 用例的 run 是 `partial=true`，只能看结果，不能建基线或做完整对比；要完整结论需整轮重跑。
16. **最坏单用例耗时**：`timeout=300s` 是单次调用，结构化重试最多 3 次，最坏约 15 分钟；取消只能等当前用例结束。UI 必须写明，且不要随意降低 timeout（会改变基线指纹导致不可比）。
17. **运行对比的基线回填**：历史基线导入的 `promptHash` 可能为 `legacy`；影响面只做提示，不阻断对比；对比以模型/参数/语料/引擎为准。

## 执行交接

计划已写入 `docs/superpowers/plans/2026-11-27-prompt-management-baseline-regression.md`。建议按 P1 → P2 → P3 分三批实施，每批独立可运行、可评审：

1. **P1**：LLM 工作台壳 + 模型与绑定 + 提示词 + 调用统计（评测/基线 Tab 后续加入）。
2. **P2**：评测运行器产品化 + 「评测运行」Tab（场景子集、异步、进度、结果落库）。
3. **P3**：设为基线 + 基线版本化 + 「基线对比」Tab + 过期徽标 + 运行对比 + 基线 label/设为当前 + 重跑失败用例 + 导出 JSON。

执行方式可选：

1. **Subagent-Driven（推荐）**：每个 Task 派独立子代理，任务间由主代理复核。
2. **Inline Execution**：当前会话按 Task 批量执行，每个 Phase 结束设检查点。

请先审核本计划的表结构、契约端点和任务拆分；确认后再开始 P1。
