# 2026-11-17 DSL-WORKPC

## 目标
归档 CI 首次运行两个真实问题的原因与修复，便于以后追溯。

## 改动（文件 → 说明）
- `docs/ci-cd-plan.md` → 新增 §9「首次运行问题记录」：追溯索引（提交 / run ID）、两个问题的现象、根因、修复、验证与预防，以及经验总结。
- `README.md` → CI 章节增加问题记录入口。
- `docs/agent-memory/PROGRESS.md` → CI 条目补充 §9 引用。

## 决策（为什么）
- 详细技术复盘放在长期方案文档，会话记录只做索引：方案文档随仓库演进，便于新设备或以后的自己直接检索。
- 保留提交号与 Actions run ID，形成可回查的证据链，而不是只写结论。

## 未完成 / 下一步
- 无。CD 仍按 `docs/ci-cd-plan.md` 第 4/5 节，待换机并稳定手动部署后再做。

## 阻塞
- 无。

## 追加：进度同步与 M2 开工确认（同日）

### 目标
同步当前开发计划进度，确认 M2 何时可以开工。

### 改动（文件 → 说明）
- `docs/superpowers/specs/职点（JobPilot）需求文档.md` → 状态字段由「M1 进行中」改为「M1 已完成并验收」，迭代历程脉络表下追加状态同步记录（无需求变化，版本号不变）。
- `docs/agent-memory/PROGRESS.md` → 「当前状态」补充本次进度同步结论；「下一步」明确 M2 开工第一步为契约扩展。

### 结论
- M1 已收口且用户验收通过（2026-11-16），CI 三 job 全绿（PR #1 已合并），阻塞为零。
- **M2 随时可以开工**，唯一前置动作是按契约先行补 M2 契约（JD / 匹配 / 模拟面试 / Offer / 简历对比 / 场景级绑定）。
- 遗留非阻塞项：开源前补 LICENSE 并同步契约 `info.license`；CD 待换机稳定手动部署后再做；换个人电脑主机流程见 README 多设备章节。

### 阻塞
- 无。

## 追加：M2 第一步——第一批契约扩展（同日）

### 目标
按契约先行硬约束启动 M2，本批覆盖场景级模型绑定 + F2 岗位库 + F7 简历对比（用户确认分两批）。

### 改动（文件 → 说明）
- `contracts/openapi.yaml` → 版本 `1.5.0 → 1.6.0`；新增 12 路径 / 19 端点 / 34 schema：
  - 场景绑定：`GET /api/llm/scenes`、`PUT|DELETE /api/llm/scenes/{scene}`；`LlmScene` 一次定义 7 个场景（M2 只 `JOB_MATCH` 生效，其余预留）。
  - 公司：`GET|POST /api/companies`（含 keyword 联想）、`PUT|DELETE /api/companies/{companyId}`；删除被引用时 40904。
  - 岗位库：JD 导入（multipart 文件/文本）→ 异步解析；列表（status/city/keyword 筛选）、详情轮询、修正、单条/批量删除、重试解析；匹配 `POST|GET /api/job-matches`（`(resumeId, jobPositionId)` 唯一，重复即重跑）+ 轮询；`GET /api/job-market/insights` 实时统计。
  - 简历对比：`POST /api/resume-comparisons` 同步返回，本地 diff + LLM 总结，失败降级、模型不一致标注。
  - 新增错误码 40903/40904/40905；未实现路径标 `x-implemented: false`（12 处）。
- `backend/src/test/java/com/jobpilot/contract/ContractCoverageTest.java` → 由「硬断言 26 端点 + 双向一致」改为「契约总量 45 / 待实现 19 / 已实现双向一致」，按 `x-implemented` 自动区分。
- `frontend/src/api/schema.d.ts` → 契约重新生成（构建内自动执行）。
- `README.md` → 契约覆盖范围更新为 M1 + M2 第一批，补充 `x-implemented` 机制说明。
- `docs/agent-memory/PROGRESS.md` → 状态、下一步、关键决策同步。

### 决策（为什么）
- 分两批：单次评审 30+ 端点负担过大，第二批 F3/F6 可以基于第一批实现经验再定。
- `x-implemented: false`：契约先行必然让双向一致性测试变红；在契约里声明实现状态比在测试里维护白名单更可维护，实现完成删标记即自动收紧。
- F7 不落库 + 同步：需求无历史回看要求，省一张表与级联清理；LLM 失败降级保证本地 diff 始终可用。
- companies 最小 CRUD 先行：满足 F6「不重复维护公司」与 F5「建表预留」两条硬约束；模糊合并与联想确认 UI 留到 M3。
- 行情洞察实时聚合不落快照：个人 JD 量级小，删 JD 后天然满足「按剩余重算」。
- 经验/岗位方向按原文统计、不做归一化：避免为 M2 引入词表维护，契约里已显式声明该口径。

### 验证
- `npx @redocly/cli lint contracts/openapi.yaml` → 0 error / 0 warning。
- `npm run build`（含 `gen:api` + `vue-tsc` + vite）→ 通过。
- `mvn test -Dtest=ContractCoverageTest,ContractSchemaTest` → 通过（修复测试中 `pendingOnly` 判断写反的问题）。
- 过程中遇到本机编辑工具「报告成功但未落盘」：12 处 `x-implemented` 首轮只写入 4 处，靠 Grep 复核发现并补齐。

### 未完成 / 下一步
- 第一批后端实现清单见 `PROGRESS.md`「下一步」；每完成一个模块删对应 `x-implemented` 标记。
- 第二批契约（F3 模拟面试 + F6 Offer）待第一批实现后启动。

### 阻塞
- 无。

## 追加：M2 第一批契约复验（同日）

### 目标
独立复验 M2 第一批契约是否有误或遗漏，不沿用「已完成」结论。

### 核对结果（结构与兼容性）
- 27 路径 / 45 端点 / 80 schema；12 路径 / 19 端点标 `x-implemented: false`，全部落在新增路径。
- 与 M1 契约（`1e3bb16`）对比：0 路径删除、0 schema 删除或修改，只有新增；operationId 45 个唯一、标签齐全、2xx 全部 `Result*` 包装。
- lint 0 error 0 warning；前端 `npm run build` 通过；`ContractCoverageTest` + `ContractSchemaTest` 通过；远程 main CI（`c74bdef`）为 success。

### 发现并修复的问题
1. `info.version` 仍为 `1.5.0`，与提交说明、PROGRESS 的 `1.6.0` 不一致 → 已改 `1.6.0`。
2. M2 错误码与后端已占用码冲突：40903/40904/40905 分别是 Provider/Model 已存在、资源被引用；40901 是会话切换守卫。已重排为 `40906` 公司存在、`40905` 公司被引用（复用通用码）、`40907` 分析未完成、`40908` 任务执行中，并同步新增 `ErrorCode` 枚举项。
3. `ContractCoverageTest` 注释引用了契约 `info.description` 的「实现状态标记」，但契约实际没有 → 已补说明。
4. 需求 FR7.4 要求「同一 Prompt 版本 + 同一模型」，契约原先只比较模型 → `ComparisonSide` 增加 `promptVersion`，`ComparisonSummary` 增加 `promptVersionMismatch`；V3 实现时需给 `resume_analyses` 增加对应列（M1 历史数据为 null）。

### 决策
- JD 结构化解析与简历对比总结暂不扩 `LlmScene`，统一走全局默认，并已写入契约说明；若后续需要单独绑定模型，再扩枚举并更新需求 FRX.11。
- 未修改需求文档：本批复验未改变产品需求，只修正契约元数据与技术错误码。

### 验证
- `npm run lint:contract` → 0 error / 0 warning。
- `npm run build` → 通过。
- `mvn test -Dtest=ContractCoverageTest,ContractSchemaTest -Djacoco.skip=true` → 2 tests 通过。

### 未完成 / 下一步
- 提交并推送本次复验修复，观察 main CI。
- M2 第一批实现时按契约删除对应 `x-implemented` 标记；V3 加 `prompt_version` 列。

## 追加：Prompt 机制写入需求文档（同日）

### 目标
把「让大模型按预设模板思考并稳定输出」的机制整理进需求文档，作为长期依据。

### 改动（文件 → 说明）
- `docs/superpowers/specs/职点（JobPilot）需求文档.md` → 版本 v1.7 → v1.8；迭代脉络补 v1.7 / v1.8 两行；新增 v1.8 记录（多了什么 / 变了什么 / 砍了什么 / 技术附注）；重写 §6.4 Prompt 工程规范与 §6.5 结构化输出规范。
- `docs/agent-memory/PROGRESS.md` → 已完成区补充需求文档 v1.8 条目。

### 关键内容
- 模板 system / user 分工、数据边界防注入、固定维度与权重；
- JSON 模式 + 固定 temperature 0.2 + max_tokens、本地 JSON 修复、带错误反馈重试、AI 中间 DTO 与实体分离、本地计分、列表兜底、失败降级、llm_call_logs；
- 如实记录当前简化：未引入 StringTemplate 引擎、JSON 字段手写、未转义引号不本地修复；
- 明确场景绑定仅覆盖 `LlmScene` 7 个场景，JD 解析与简历对比总结走全局默认；
- 记录内外两层重试叠加的最坏调用次数风险。

### 验证
- 文档结构检查：v1.8 位于迭代历程最前，v1.7 / v1.8 脉络行齐全，§6.4 / §6.5 内容完整。
- 本次仅改文档，未改代码与契约，无需重跑构建。

### 未完成 / 下一步
- 无。后续实现 M2 场景时按 §6.4 / §6.5 执行，并补「模板字段与 DTO 一致性测试」。

## 追加：项目全记录与 AI 协作方法论（同日）

### 目标
建立一份长期维护的总记录，覆盖设计、实现、开发过程、AI 协作、问题发现与解决，用于沉淀方法论并作为 Agent 开发岗面试材料。

### 改动（文件 → 说明）
- `docs/AI-COLLABORATION.md`（新增，547 行）→ 十个章节：结论、项目全景、时间线、架构与流程、20 条 ADR、实现地图、AI 协作实录、12 个问题案例、方法论、面试包装、维护约定与证据索引。
- `README.md` → 文档索引新增本记录；顺带修正 CI/CD 方案行的「暂未实现」为「CI 已实现、CD 规划中」。
- `PROGRESS.md` → 已完成区新增本记录条目。

### 决策（为什么）
- 采用单文件而非多文件：面试前可一处通读，日常维护只追加章节；细节仍链接到 sessions / 需求 / 契约 / CI 文档，避免重复。
- 明确记录 AI 协作的坑（编辑假成功、并行覆盖、本地与 CI 差异），而不是只写成功经验；对方法论更有价值。
- 面试部分如实标注「不是多智能体系统」，只映射可迁移能力，避免过度包装。

### 验证
- 检查章节结构与行数；确认未写入密钥、token、个人隐私；未改代码与契约，无需重跑构建。

### 未完成 / 下一步
- 按维护约定在里程碑与重大问题时追加；M2 实现后补「第二批契约」「面试模块」章节。

## 实时记录

### 17:09 将实时记录写入仓库级 AI 契约
- 改动：`AGENTS.md` 新增「实时记录」章节（触发条件、追加格式、与 push 的关系）；`README.md` 的「AI 协作与跨设备同步」补充一条；`docs/AI-COLLABORATION.md` 维护约定补充实时记录节奏。
- 原因：用户要求后续任务与记录实时留痕；AGENTS.md 已是跨设备生效的项目规则，写在那里避免多份规则漂移。
- 验证：文档结构检查通过；本次仅改规则文档，未改代码与契约，无需重跑构建。
- 下一步 / 断点：无；后续每个可验证闭环按新规则在当天会话文件追加 HH:mm 记录。

### 17:14 全记录文档改为中文文件名
- 改动：`docs/AI-COLLABORATION.md` → `docs/项目全记录与AI协作方法论.md`；README 文档索引与 PROGRESS 引用同步。
- 原因：英文文件名不易识别，中文名与文档标题一致。
- 验证：`git status` 显示 rename；活文档引用已更新；历史会话记录按只追加规则保留旧路径，以本条为准。
- 下一步 / 断点：无。
