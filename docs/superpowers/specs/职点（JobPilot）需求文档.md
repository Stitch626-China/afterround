# 职点（JobPilot）需求文档

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.2 |
| 日期 | 2026-10-14 |
| 状态 | 需求已确认（可用于 M1 开工） |
| 技术底座 | Spring Boot 3 + PostgreSQL + Docker Compose |

---

## 变更记录

> **维护规则**：本文件每次修改必须在下方表格追加一行（版本 / 日期 / 变更内容 / 影响章节），正文改动与变更记录必须同步，禁止只改正文不记录。这是回溯与追踪的唯一依据。

| 版本 | 日期 | 变更内容 | 影响章节 |
|---|---|---|---|
| v1.2 | 2026-10-14 | 补全 6 项阻断性缺口：新增 `llm_call_logs` 表；状态三件套扩充为四件套（+ `retry_count`）；明确五维评分与匹配分权重；补齐答题流程规则（追问链、跳过计分、交卷条件）；明确无简历时的出题配额；新增简历/JD/面试/复盘的删除需求（FR1.8 / FR2.6 / FR3.9 / FR4.7）。修正 5 项一致性问题：F1 里程碑拆分轨一/轨二、FRX.1 明确 M1 交付范围、风险表同步手写客户端与成本/磁盘风险、`job_match_results` 补状态与 Provider 字段、明确 `user_id` 单用户固定为 1。新增完善项：NFR7 资源上限与清理、列表统一分页、简历迭代约定（FR1.9）、复盘可重复分析（FR4.3）。新增本变更记录章节 | §4.1 / 4.2 / 4.3 / 4.4 / 4.5 / 4.6 / 5 / 6.2 / 6.3 / 7 / 8 / 12 / 13 |
| v1.1 | 2026-08-28 | 收敛 4 个关键决策点：文件存储→本地磁盘（存储层接口隔离）、模型网关→手写 WebClient OpenAI 兼容客户端、前端→Vue3 极简先行、岗位方向→双轨（JD 图谱延后 M2）；文档状态由「待评审」置为「需求已确认」 | §4.2 / 6.1 / 6.2 / 5 / 11 / 附录A |
| v1.0 | 2026-08-26 | 需求探索完成，首版（智求职 / JobPilot） | 全文 |

> 命名沿革：产品名于 2026-10-14 由「智求职（JobPilot）」更名为「**职点（JobPilot）**」，英文名 `JobPilot` 保留不变，文档同步重命名。

---

## 1. 项目背景与目标

### 1.1 背景

当前求职竞争激烈、面试难度持续提升，求职者在"简历投递 → 面试 → 复盘"各环节缺乏系统性的能力提升工具。市面上的开源面试平台（如 interview-guide）聚焦"模拟面试练习"，缺少"真实面试复盘"与"岗位行情分析"的闭环能力。

### 1.2 产品目标

构建一个智能求职辅助平台，围绕求职全流程闭环：

**简历分析 → 岗位匹配 → 模拟面试 → 真实面试复盘**

一句话目标：让每一次求职动作（改简历、选岗位、练面试、复盘）都有 AI 深度参与和数据沉淀。

### 1.3 差异化定位（对比 interview-guide）

| 能力 | interview-guide | 本产品 |
|---|---|---|
| 简历分析 | ✅ | ✅（+ 岗位方向推荐） |
| 模拟面试（文字） | ✅ | ✅ |
| 实时语音面试 | ✅ | 后期（P2+） |
| 真实面试录音复盘 | ❌ | ✅（P2，核心差异） |
| 岗位 JD 库 / 匹配评分 / 行情分析 | ❌ | ✅（P1，核心差异） |
| 会话内切换模型 | ❌（仅全局默认切换） | ✅ |
| RAG 知识库 / 题库 / 面试日历 | ✅ | 暂不做 |

> 合规说明：interview-guide 为 AGPL-3.0 协议。本项目**只借鉴其架构设计思想，所有代码自研**，不复制 `reference/` 目录下任何代码。

## 2. 产品定位与发展路线

- **阶段一（当前）**：个人自用版。免登录、单用户、本地 Docker Compose 部署；所有数据表预留 `user_id` 字段。
- **阶段二**：基于自用反馈迭代，完善体验与稳定性。
- **阶段三（可选）**：产品化。补登录/注册、API Key 加密落库、接口限流、多租户数据隔离、云部署。

> 架构原则：单用户阶段不实现多租户功能，但数据模型与接口形态（无状态 REST、可加鉴权头）不堵死产品化路径。

## 3. 用户画像与核心场景

个人版用户即开发者本人（典型画像：1-3 年经验的 Java 后端求职者）。四个核心场景：

- **场景 1 改简历**：上传简历，获得评分、短板分析、优化建议和适合的岗位方向。
- **场景 2 选岗位**：收集目标 JD 导入系统，查看匹配评分与技能差距，了解市场行情。
- **场景 3 练面试**：选定方向或关联 JD，AI 面试官出题、追问、打分点评；中途不满意可切换模型。
- **场景 4 复盘**：真实面试后上传录音，获得转写稿 + 回答质量分析 + 改进建议。

## 4. 功能需求

### 4.1 功能总览与优先级

| 编号 | 功能 | 优先级 | 里程碑 |
|---|---|---|---|
| X1 | 模型网关（多模型管理 + 会话内切换） | P0 | M1（全局切换）/ M2（会话级） |
| F1 | 简历中心（上传/解析/分析/岗位方向推荐） | P0 | M1（轨一）/ M2（轨二） |
| F2 | 岗位库与行情分析 | P1 | M2 |
| F3 | 模拟面试（文字结构化模式） | P1 | M2 |
| F4 | 面试复盘（录音 ASR + 分析） | P2 | M3 |

### 4.2 F1 简历中心（P0）

**上传与解析**

- FR1.1 支持上传 PDF / DOCX / DOC / TXT，单文件 ≤ 5MB。
- FR1.2 Apache Tika 解析 + 文本清洗（去控制字符 / 图片引用 / 多余空行，保留段落结构）。
- FR1.3 SHA-256 内容哈希去重：数据库 UNIQUE 约束，重复上传直接返回已有记录。
- FR1.4 原始文件存**本地磁盘**（存储层接口隔离 `FileStorage`，默认 `LocalFileStorage`，产品化阶段再平滑替换为 MinIO），解析文本落库。

**AI 分析**

- FR1.5 异步分析（状态机：待分析 / 分析中 / 已完成 / 失败；失败自动重试 ≤ 3 次）。
- FR1.6 结构化分析报告：
  - 基本信息提取（姓名 / 工作年限 / 技术方向）；
  - 五维评分（0-100，加权总分）：技术深度 30% / 项目质量 25% / 工程能力 20% / 表达结构 15% / 岗位匹配潜力 10%；权重与评分标准在 prompt 的 system 模板中显式声明（§6.4），调整无需改代码；
  - 优势清单 / 短板清单（带简历原句引用）；
  - 逐条优化建议：原句 vs 优化句对照。
- FR1.7 岗位方向推荐（双轨）：**轨一（M1）** 基于简历推荐 3-5 个岗位方向，每个方向含匹配理由、技能差距清单、面试准备重点（高频考点）；**轨二（M2，随 F2）** 基于已导入 JD 库统计出的方向图谱（岗位名/技能/薪资分布）作为候选池，与市场真实在招方向对齐，让 F2 行情分析反哺 F1 推荐。
- FR1.8 简历删除：单条删除（二次确认），级联删除其分析结果与关联的岗位匹配结果，同时清理本地磁盘上的原始文件。
- FR1.9 迭代约定：同一份简历修改后重新上传视为**新记录**（内容不同则 file_hash 不同），列表按上传时间倒序展示；暂不做版本对比（延后）。

### 4.3 F2 岗位库与行情分析（P1）

- FR2.1 JD 导入：粘贴文本或上传文件。
- FR2.2 LLM 结构化解析：岗位名 / 公司 / 城市 / 薪资区间 / 经验要求 / 学历要求 / 技能要求（分类：必备 / 加分）/ 职责摘要。
- FR2.3 简历-JD 匹配评分（异步，状态 `match_status`）：总体匹配分（0-100）+ 分项（技能匹配 50% / 经验匹配 30% / 加分项覆盖 20%）+ 差距清单 + 针对性准备建议；权重同 FR1.6 在 prompt 中显式声明。
- FR2.4 行情洞察：基于已导入 JD 集合统计——技能词频 Top N、薪资分布、经验要求分布、岗位方向分布。
- FR2.5 匹配的 JD 可一键发起模拟面试（联动 F3）。
- FR2.6 JD 删除：支持单条与批量删除（二次确认）；删除后行情洞察（FR2.4）按剩余 JD 实时重算。

### 4.4 F3 模拟面试（P1）

面试模式：**结构化面试**（预生成题目 + 逐题作答 + 智能追问），工程可控、评估质量稳定；自由对话模式作为后期增强。

- FR3.1 选择面试方向（初期 2-3 个：Java 后端、AI Agent 工程等）或关联已导入的 JD。
- FR3.2 题目生成：**关联简历时** 简历题约 60%（深挖项目）+ 方向题约 40%（知识体系）；**仅选方向（无简历）时** 全部为方向题，按知识体系分类配额出题（配额由 `skill.meta.yml` 定义）。题目含难度分布。
- FR3.3 历史去重：以知识点摘要（topicSummary）注入出题 prompt，避免重复考察。
- FR3.4 智能追问：每题可配置追问数（默认 1，设 0 则不出追问），主问题-追问树结构。追问规则：主问题作答后按配置生成追问，同一主问题链的追问串行进行，链上答完才进入下一题；追问可跳过，跳过则不生成该追问的评价。
- FR3.5 作答：文字输入（语音后期）；支持跳过。**跳过计分规则**：跳过题与未答题均记 0 分并计入总分，评估报告标注「未作答」且不生成逐题点评（避免无意义点评）。
- FR3.6 统一评估报告（交卷后异步生成）：总分、分类得分、逐题评价（得分 + 反馈 + 参考答案 + 关键点）、优势、改进建议。**交卷规则**：答完最后一题后出现「交卷」；同时提供「提前交卷」（二次确认），未作答按 FR3.5 规则处理；交卷后答案不可修改。
- FR3.7 会话内模型切换（见 X1）。
- FR3.8 面试记录列表：按方向 / 时间 / 状态筛选，支持继续未完成面试。
- FR3.9 面试会话删除：单条删除（二次确认），级联删除该会话的题目与评估结果。

### 4.5 F4 面试复盘（P2）

- FR4.1 上传面试录音（mp3 / wav / m4a，≤ 100MB 或 2 小时）。
- FR4.2 ASR 转写：适配层设计（DashScope paraformer / 讯飞 / 本地 whisper 可切换），尽量支持说话人分离。
- FR4.3 转写稿校对编辑：用户可修正转写错误后再分析；校对保存后可重复触发分析，重新分析**覆盖**原分析结果（以 `review_analyses.updated_at` 标记最后更新时间）。
- FR4.4 AI 复盘分析：
  - 面试官问题归类（技术 / 项目 / 行为 / 压力 / 反问）；
  - 每个问答：回答质量评分、问题诊断（知识缺口 / 表达混乱 / 深度不足 / 跑题）、改进建议、参考回答；
  - 整体总结：优势、高频失分点、下次面试准备清单。
- FR4.5 可关联简历上下文增强分析（可选）。
- FR4.6 复盘历史沉淀，与模拟面试记录共同构成"练习-实战-复盘"数据闭环。
- FR4.7 复盘记录删除：单条删除（二次确认），级联删除转写稿、分析结果与磁盘上的音频文件。

### 4.6 X1 模型网关（P0 横切能力）

- FRX.1 Provider 配置管理：MVP 阶段通过 `application.yml` + `.env` 静态配置多个 Provider。**M1 交付范围**：配置加载 + 连通性测试 + 全局默认模型切换（后端接口形式，不含管理页面）；Provider 列表查看与选择的页面化随 M2 完整前端提供。产品化阶段升级为页面 CRUD + API Key 加密落库。
- FRX.2 全部走 OpenAI 兼容协议（`/chat/completions`），Provider = baseUrl + apiKey + model 三要素，不写厂商 if-else。
- FRX.3 连通性一键测试。
- FRX.4 默认模型设置：全局默认 + 会话级覆盖。
- FRX.5 **会话内切换**：会话页顶部模型选择器；切换后本会话后续回答由新模型生成；历史上下文全量随请求组装（不依赖模型侧记忆）；每条消息记录实际使用的 Provider / 模型（可追溯）。
- FRX.6 流式输出（SSE）。
- FRX.7 失败处理：超时 / 报错时提示，支持一键切换备用模型重试。
- FRX.8 API Key 个人版从 `.env` 环境变量注入，不落库、不入日志。

## 5. 非功能需求

- NFR1 性能：普通接口 P95 < 500ms（不含 LLM）；LLM 首 token < 3s（流式）。
- NFR2 可观测：结构化日志（traceId 贯穿）；LLM 调用埋点（模型 / 耗时 / token / 成败）；异步任务状态可查。
- NFR3 安全：Prompt 注入防护（用户输入加数据边界声明）；文件上传类型 / 大小校验。
- NFR4 可靠性：LLM 结构化输出失败自动重试 + 本地修复 + 降级；异步任务状态落 DB，重启不丢、可重试。
- NFR5 部署：docker-compose 一键起（PostgreSQL + 应用，无 MinIO）；`.env` 管理密钥；支持 IDE 直跑调试。
- NFR6 成本：模型调用 token 用量统计展示（帮助控制成本），数据源为 `llm_call_logs`（§7）。
- NFR7 资源上限与清理：单次 LLM 任务设置 token 上限（超限中断并明确报错）；`@Async` 使用有界线程池（§6.3）；复盘分析完成后音频文件可选择清理，避免本地磁盘无限增长。

## 6. 技术架构设计

### 6.1 总体架构与分层

```
前端 SPA（Vue3；M1 仅极简单页打通核心链路，完整 UI 放 M2）
   │ REST + SSE
后端 Spring Boot 3.x（Java 21，Maven）
 ├── common/         通用能力：模型网关、结构化输出、Prompt 模板、统一响应/异常
 ├── infrastructure/  文件解析/清洗/哈希、对象存储、ASR 适配
 └── modules/        业务模块：resume / job / interview / review / llmprovider
PostgreSQL（预留 pgvector）+ 本地文件系统（本地磁盘，存储层接口隔离）
```

分层原则：common 通用能力 / infrastructure 技术设施 / modules 自包含业务模块（Controller → Service → Repository）。

### 6.2 模型网关核心设计

- 统一接入采用 **WebClient 手写的 OpenAI 兼容客户端**（`/chat/completions`，自定义 base-url）。不使用 Spring AI，目的是便于实现"会话级 Provider 绑定 + 每条消息落 Provider 追溯 + 结构化输出修复"等横切能力；客户端接口层隔离，后续如需换框架替换成本低。
- 注册中心：启动时加载 yml 中多个 Provider 配置 → 惰性构建客户端 → 缓存。
- 会话绑定：会话表存 `current_provider_key`（字符串标识）；每次请求动态取当前绑定；消息表冗余 `llm_provider` 字段。
- 上下文组装：system prompt + 历史消息 + 本轮输入 → 目标模型；统一截断策略（保 system + 最近 N 轮 + 摘要）。
- 切换语义：切换只改会话绑定，不迁移历史；新模型接管后续回答。
- 调用埋点：每次调用落 `llm_call_logs`（traceId / 场景 / provider / model / token / 耗时 / 成败），支撑 NFR2 可观测与 NFR6 成本统计。

### 6.3 异步任务框架（简化版）

- 个人版不引入 Redis，用 Spring `@Async` + DB 状态机。
- 状态四件套：`status VARCHAR + CHECK 约束 + error VARCHAR(500) + retry_count INT`。
- 流程：落库(PENDING) → 事务提交后投递 → PROCESSING → COMPLETED / FAILED。失败自动重试（`retry_count` 递增，≤ 3 次）；超过 3 次置 FAILED 并支持**手动重试**（手动重试重置 `retry_count`）。
- 并发控制：自定义有界线程池（核心 2 / 队列 20），避免多任务并发触发厂商限流。
- 适用于：简历分析、JD 解析、岗位匹配、面试评估、录音转写、复盘分析。
- 产品化如需横向扩展，再升级 Redis Stream（借鉴模板方法抽象）。

### 6.4 Prompt 工程规范

- 模板外置 `.st` 文件（StringTemplate），system / user 成对。
- system 管人格 / 规则 / 评分标准（分值显式声明），user 管数据注入。
- 用户输入（JD / 简历文本）加数据边界声明，防提示注入。

### 6.5 结构化输出规范（稳定性基石）

- JSON schema 由 DTO 运行时生成拼入 prompt（不硬编码在模板）。
- 失败重试：附加严格 JSON 指令 + 上次错误信息（让模型知道错在哪）。
- 本地兜底：常见 JSON 瑕疵（如未转义引号）字符级修复。
- 领域模型与 AI 中间 DTO 分离，不被输出格式绑架。

### 6.6 Skill 出题体系（简化版）

- 方向配置三层：`SKILL.md`（面试官人格）+ `skill.meta.yml`（分类配额）+ `references/`（参考知识，可选）。
- 初期 2-3 个方向：Java 后端、AI Agent 工程（贴合自身求职方向）。
- 历史去重用 topicSummary 摘要注入 prompt。

### 6.7 统一评估引擎

- 分批评估 → 本地合并 → 二次 LLM 汇总 → 多层降级（批次失败按缺题兜底并标注；汇总失败本地拼接）。
- 模拟面试评估、面试复盘分析共用同一评估骨架。

## 7. 数据模型（概要）

所有表统一预留 `user_id BIGINT` + `created_at` / `updated_at`。**单用户阶段 `user_id` 固定为 `1`**，由后端统一注入，前端不传该字段。

| 表 | 关键字段与设计 |
|---|---|
| resumes | file_hash UNIQUE、storage_key、resume_text、analyze_status / error / retry_count |
| resume_analyses | resume_id FK、overall_score、五维分数各自成列、strengths / suggestions / career_directions JSONB、llm_provider |
| job_positions | title、company、city、salary_min / max、experience、raw_text、structured JSONB |
| job_match_results | resume_id、job_position_id、match_status / error / retry_count、overall_score、detail JSONB、llm_provider |
| interview_sessions | direction、resume_id、job_position_id、status、evaluate_status / error / retry_count、current_provider_key、question_snapshot JSONB |
| interview_questions | session_id、question_index（UNIQUE 组合）、parent_index（追问树）、type（主问题/追问）、content、topic_summary、answer、answer_status（已答 / 跳过 / 未答）、score、feedback、reference_answer、key_points JSONB、llm_provider |
| review_sessions | audio storage_key、duration、asr_status / error / retry_count、analyze_status / error / retry_count |
| review_transcripts | review_session_id、speaker、text、start / end_time |
| review_analyses | review_session_id、qa_analysis JSONB、summary JSONB、llm_provider |
| llm_call_logs | trace_id、scene（resume_analyze / jd_parse / job_match / interview_question / interview_evaluate / review_asr / review_analyze / connectivity_test）、provider_key、model、prompt_tokens、completion_tokens、total_tokens、duration_ms、success、error、created_at |

> MVP 阶段 Provider 为 yml 静态配置，`llm_provider_config` / `llm_global_setting` 表推迟到产品化阶段（M3）。

## 8. 接口设计原则

- RESTful + 统一 `Result<T>` 包装 + 全局异常处理。
- 列表接口统一分页（`page` / `size`，默认 20 条），按时间倒序。
- SSE 用于所有流式 LLM 输出。
- 异步任务统一模式：POST 创建（返回任务 id）→ GET 轮询状态 → 完成取结果。
- OpenAPI 文档自动生成。

## 9. 错误处理与降级策略

| 场景 | 策略 |
|---|---|
| LLM 结构化输出失败 | 带错误反馈重试 → 本地 JSON 修复 → 降级提示 |
| 模型调用超时 / 网络错误 | 前端提示 + 一键切换备用模型重试 |
| 评估批次失败 | 缺题按 0 分兜底并标注；汇总失败本地拼接 |
| 简历解析失败 | 明确报错 + 建议粘贴纯文本回退 |
| ASR 转写失败 | 状态标记失败可重试；支持手动粘贴转写稿回退 |
| 异步任务中断 | 状态落 DB 不丢；重启后可重试 |

## 10. 测试策略

- 单元测试：模型网关（Mock HTTP）、结构化输出修复器、文本清洗、哈希去重、状态机流转。
- 集成测试：Testcontainers 起 PostgreSQL；关键链路（上传 → 分析 → 查询）全流程。
- LLM 相关测试全部 Mock 适配层，不依赖真实 API。
- Prompt 质量用真实模型人工评估迭代，不做自动化。

## 11. 里程碑规划

- **M1（MVP）**：工程骨架 + docker-compose + 模型网关（多 Provider 静态配置 / 连通测试 / 全局默认切换 / SSE 流式）+ 简历上传 / 解析 / AI 分析报告 / 岗位方向推荐（轨一）+ 极简单页打通"上传→报告"链路。
- **M2**：完整前端 + JD 导入 / 匹配评分 / 行情统计 + 结构化模拟面试（Skill 出题 / 追问 / 统一评估 / 会话内模型切换）。
- **M3**：面试复盘（ASR / 分析 / 报告）+ PDF 报告导出 + token 成本统计 + 产品化准备（登录 / API Key 加密落库 / 限流）。

## 12. 风险与对策

| 风险 | 对策 |
|---|---|
| LLM 输出不稳定 | 结构化输出收口 + 多层降级（§6.5） |
| 复杂排版简历解析质量差 | Tika 坐标排序 + 清洗规则；失败回退纯文本粘贴 |
| ASR 成本 / 质量 | 适配层可切换厂商；转写稿人工校对后再分析 |
| 各模型上下文长度差异 | 统一截断策略（§6.2） |
| AGPL 代码污染 | 只借鉴设计思想，所有代码自研，不复制 reference/ 下任何代码 |
| 手写 OpenAI 客户端自研成本 | 接口层隔离；M1 只做必要能力（chat / SSE 流式 / 结构化输出），跑通后再扩展 |
| LLM 成本失控 | `llm_call_logs` 埋点 + 单任务 token 上限（NFR7） |
| 本地磁盘无限增长 | 音频文件可选清理 + 上传大小硬限制（FR4.1） |
| 单人开发进度风险 | 严格里程碑，每期只做核心闭环 |

## 13. 明确不做（Out of Scope）

- 招聘网站爬虫（法律 / 封号风险，改为手动导入 JD）。
- MVP 阶段登录 / 多租户（数据层预留 user_id 即可）。
- 实时语音面试（P2 之后探索 WebRTC）。
- RAG 知识库 / 题库系统（复杂度高，参考知识用文件注入即可）。
- 简历多版本对比 / diff（FR1.9 仅做列表区分，不做版本对比）。
- 移动端。

## 附录 A：借鉴自 interview-guide 的设计模式

来源：本地克隆 `reference/interview-guide` 源码分析（仅借鉴思想，不复制代码）。

1. OpenAI 兼容 + 配置缓存 + reload 的多模型管理（LlmProviderRegistry）。
2. 结构化输出收口：schema 动态生成 + 带错误反馈重试 + 本地 JSON 修复（StructuredOutputInvoker）。
3. 状态三件套（status + CHECK + error）异步任务状态机。
4. Skill 三层结构（SKILL.md / skill.meta.yml / references）+ topicSummary 摘要级历史去重。
5. 分批评估 + 多层降级的统一评估引擎（UnifiedEvaluationService）。
6. Prompt 外置模板 + system / user 职责分离 + 评分分值显式声明。
7. Tika 解析 + 文本清洗 + SHA-256 唯一约束去重。
8. 分析结果与主体分离的表设计（resumes / resume_analyses）。

简化决策（不照搬）：

- Redis Stream 异步 → `@Async` + DB 状态（个人版免 Redis）。
- API Key AES 加密落库 → `.env` 静态配置（产品化再升级）。
- Java 25 / Spring Boot 4.1 / Gradle / 虚拟线程 → Java 21 / Spring Boot 3.x / Maven（贴合自身技术栈）。
- 会话表"业务状态 + 评估状态"双字段设计保留；pending 消息认领等分布式特性不做（单实例部署）。
