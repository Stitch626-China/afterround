# 2026-11-05 DSL-WORKPC

## 目标

讨论并落定新增需求 F5「求职洞察与规律分析」：面试录音复盘沉淀为文档、按公司/岗位/轮次多层聚合、跨次规律识别。

## 改动（文件 → 说明）

- `docs/superpowers/specs/职点（JobPilot）需求文档.md` → 升至 v1.5：
  - 新增 **§4.6 F5 求职洞察与规律分析（P1 / M3）**，FR5.1~FR5.8
  - F4 扩展 FR4.5 为「关联公司 / 岗位 / 简历」
  - §4.1 功能总览新增 F5 行；原 §4.6 X1 顺延为 **§4.7**（§2 交叉引用同步）
  - §7 新增 `companies`、`interview_events`；`job_positions` 加 `company_id` + `status`；`review_analyses` 加 `topics` / `loss_tags`
  - §6.7 补充 F5 复用评估骨架（SQL 预聚合 → 一次 LLM 总结 → 失败回退纯统计）
  - §1.3 对比表与 §13 明确「不做 RAG」及替代方案
  - §11 M2 要求预留 `companies` 与 `company_id`；M3 纳入 F5
  - §12 新增风险：洞察失真、ASR 误差传导
- `docs/agent-memory/PROGRESS.md` → 更新状态三块与关键决策（7 条新增）

## 决策（为什么）

- **优先"找规律"（B 场景）**：单次复盘价值有限，跨次规律才暴露真实短板；"查具体某场"由复盘文档本身承担，不单独立项。
- **不做 RAG**：几十至数百场面试约 15 万字，远小于模型上下文窗口，向量检索无收益且增加组件与维护成本。替代方案 = 结构化字段聚合 + PostgreSQL 全文检索 + 分层摘要 + 批量 LLM 总结。
- **文档格式 Markdown，不放图片**：MD 通用（Obsidian/Notion/语雀/飞书）、可 diff、不污染全文索引；"好看"由 Web 端渲染承担，HTML 导出仅作可选二次转换。评分可视化属于 UI，不烙进归档文档。
- **三层强结构**（公司 → 岗位 → 轮次），不做无限树：查询与展示清晰，避免文件系统式过度设计。
- **公司归一化必须人工确认**：自动合并出错会导致层级与统计全错，代价过高。采用联想检索 + 用户点选复用 + `name` UNIQUE 防同名，另留手动「合并公司」补救。
- **`job_positions` 与 F2 JD 库合并**：用 `status`（SAVED/APPLIED/INTERVIEWING/CLOSED）管生命周期，避免同一份 JD 录两遍。
- **聚合的前提是结构化**：纯文本复盘无法统计，必须在 `review_analyses` 落 `topics`（知识点）与 `loss_tags`（失分类型）；标签走受控词表 + 用户可修正。

## 未完成 / 下一步

1. M1-a：建 `backend/` `frontend/` `contracts/` 三目录 + Spring Boot 骨架 + Docker Compose + Flyway
2. 写 `contracts/openapi.yaml` 全量契约，评审后才实现
3. M2 建 F2 表时预留 `companies` 与 `job_positions.company_id`（否则 M3 需回填数据）

## 阻塞

- 无

## 备注

- 仓库有一批未提交改动（README、需求文档、PROGRESS、sessions），等用户确认后再 commit + push

---

## 追加 · 需求文档「变更记录」重写为「迭代历程」

### 目标

用户指出：变更记录只记技术层面，非技术读者看不出需求演进。要求改成能一眼看懂"多了什么 / 变了什么 / 砍了什么"的产品语言叙述。

### 改动

- `docs/superpowers/specs/职点（JobPilot）需求文档.md` → 原「变更记录」表格整体重写为「**迭代历程**」：
  - 顶部维护规则增加"以产品语言为主"的硬要求
  - 新增「一眼看懂的迭代脉络」速览表（版本 / 日期 / 一句话）
  - v1.0~v1.5 每个版本独立小节，结构统一为：**多了什么 / 变了什么 / 砍了什么 / 附：技术实现要点**
  - 末尾保留产品命名沿革说明
- `README.md` → 文档维护规则同步为「迭代历程 + 产品语言」
- `AGENTS.md` → 项目硬约束同步
- `docs/agent-memory/PROGRESS.md` → 关键决策追加一条

### 决策（为什么）

- 表格化只适合"技术 diff"，无法承载"需求演进故事"；改为分版本叙述，读者按版本读即可理解产品如何长出来。
- 技术细节不删除，但降为每节末尾的附注，兼顾开发与产品两类读者。

### 未完成 / 下一步

- 同前：M1-a 骨架与契约 → `contracts/openapi.yaml`

### 阻塞

- 无

---

## 追加 · 完成 M1 全量接口契约

### 目标

按契约先行约定，落地 `contracts/openapi.yaml` 作为前后端唯一接口真实源。

### 改动

- `contracts/openapi.yaml`（新增）→ M1 全量契约：**25 个接口 / 45 个 schema / 156 个 $ref**
  - 模型配置（12）：Provider CRUD、连通测试、上游模型列举、Model 增删改、全局默认读写
  - 对话会话（7）：会话 CRUD、两级切换（409）、历史消息、SSE 流式发送
  - 简历（6）：上传（文件/文本，含 413/415）、分页列表、详情、删除、触发分析、分析状态轮询
  - 统一 `Result<T>`（code/message/data/traceId）、`PageResult<T>`、业务错误码约定
- `redocly.yaml`（新增，仓库根）→ 关闭 `no-server-example.com`（本地部署）与 `operation-4xx-response`（无鉴权纯查询接口），理由写在注释
- `README.md` → 新增「契约怎么用」命令行说明
- `AGENTS.md` → 硬约束新增「先改契约再实现 + 提交前 redocly lint 必须 0 error 0 warning」
- `docs/agent-memory/PROGRESS.md` → 状态与下一步更新

### 校验结果（实测）

- `python yaml.safe_load` 解析通过
- 自写脚本检查：156 refs **0 broken**、25 operations、operationId 唯一、responses 与 tags 齐全
- `npx @redocly/cli lint contracts/openapi.yaml` → **valid，0 error / 0 warning**

### 决策（为什么）

- **OpenAPI 3.1**：需去掉 3.0 的 `nullable`，改用 `type: [string, "null"]`；`allOf + nullable` 改为 `oneOf + type: "null"`。首轮 18 个 error 全部由此而来。
- **SSE 以 `ChatStreamEvent` 作为响应 schema**：虽非严格表达 SSE 分帧文本，但能让 `openapi-typescript` 生成前端可用的强类型事件对象，收益大于形式纯度。
- **删除未被引用的 `Conflict` 响应组件**：各端点 409 的业务码不同，已在各自 description 内注明，保留通用组件反而误导。
- **连通测试回显 `upstreamModel`**：用于识别网关模型别名（历史实测三个模型名指向同一后端）。

### 未完成 / 下一步

- M1-a 剩余：`backend/` 工程骨架（Spring Boot 3.5.x + Java 21 + Docker Compose + Flyway + 统一响应/异常/traceId）、`frontend/` 骨架 + 由契约生成 TS 类型

### 阻塞

- 无

### 备注

- 未提交改动等待用户确认后再 commit + push
