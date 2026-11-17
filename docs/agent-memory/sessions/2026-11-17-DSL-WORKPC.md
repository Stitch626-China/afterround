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
