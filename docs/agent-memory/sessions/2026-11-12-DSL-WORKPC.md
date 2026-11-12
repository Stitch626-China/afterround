# 2026-11-12 DSL-WORKPC

## 目标
执行 M1-c 简历中心（后端），并在开发过程中记录需求确认、任务拆解、架构分层、实现顺序与测试方案。

## 需求确认（来源：需求文档 v1.5 §4.2 + contracts/openapi.yaml）

- FR1.1 支持 PDF / DOCX / DOC / TXT，单文件 ≤ 5MB
- FR1.2 Tika 解析 + 文本清洗（控制字符 / 图片引用 / 多余空行，保留段落）
- FR1.3 SHA-256 去重：重复上传返回已有记录，`duplicated=true`，不重复触发分析
- FR1.4 原文件存本地磁盘（`FileStorage` 接口隔离），解析文本落库
- FR1.5 异步分析状态机：PENDING / PROCESSING / COMPLETED / FAILED，失败自动重试 ≤ 3
- FR1.6 五维评分（技术深度 30 / 项目质量 25 / 工程能力 20 / 表达结构 15 / 岗位匹配 10）+ 基本信息 + 优势 / 短板（带原句）+ 原句 vs 优化句
- FR1.7 轨一：推荐 3-5 个岗位方向，含匹配理由、技能差距、面试重点
- FR1.8 删除：硬删除 + 清理磁盘文件 + 级联分析结果
- FR1.9 修改后重新上传视为新记录（哈希不同），列表按上传时间倒序
- 契约 6 端点：列表 / 上传 / 详情 / 删除 / 触发分析 / 查询分析；错误码 40910（分析中）、41301（超限）、41501（类型不支持）

## 任务拆解（与 Todo 一致）

1. 基础设施：`FileStorage` 本地实现、文本清洗、JSONB TypeHandler、异步线程池
2. 简历主流程：上传 / 去重 / Tika 解析 / 分页 / 详情 / 删除
3. 异步分析：状态机 + 重试 + 五维报告 + 方向推荐 + `llm_call_logs` 埋点
4. 接口层：简历 6 端点 + 契约覆盖测试扩展到 25 端点
5. 测试：单元 + 集成（上传 → 分析 → 报告 → 删除）+ 全量构建
6. 收口：README / PROGRESS / 会话记录 + 提交

## 架构分层（包结构）

- `common/text`：`TextCleaner`（纯函数，可单测）
- `common/mybatis`：`JsonbStringTypeHandler`（JSONB ↔ String）
- `common/config`：`AsyncConfig`（有界线程池 `jobpilotTaskExecutor`）
- `infrastructure/storage`：`FileStorage` 接口 + `LocalFileStorage`（路径穿越防护）
- `modules/resume/entity|mapper`：`Resume`、`ResumeAnalysis`
- `modules/resume/dto`：列表 / 上传 / 详情 / 状态 / 报告 / 维度 / 引用 / 建议 / 方向
- `modules/resume/ai`：`AiResumeAnalysis`（LLM 输出的中间 DTO，与领域模型分离）
- `modules/resume/prompt`：`ResumePromptFactory` + `resources/prompts/*.st`（模板外置）
- `modules/resume/service`：`ResumeService`（上传/查询/删除）、`ResumeAnalysisService`（状态机/重试/报告）
- `modules/resume/controller`：`ResumeController`

## 实现顺序

1. 文本清洗 / 文件存储 / JSONB / 线程池（无业务依赖，先测）
2. 实体与 Mapper（复用 V1 迁移，无新迁移）
3. 上传与解析主链路（先保证可落库、可去重、可删除）
4. 异步分析与报告（依赖 M1-b 的网关、全局默认、埋点）
5. 控制器与契约覆盖
6. 集成测试与全量回归

## 测试方案

- 单元：`TextCleanerTest`（换行/控制字符/图片引用/空行压缩）、`LocalFileStorageTest`（读写删/路径穿越）
- 集成（真实 PostgreSQL + 本地假 OpenAI 上游）：
  - 文本上传 → 自动分析 → 五维报告（总分 76 由本地计算）→ 列表/详情 → 删除后 404
  - 相同文本重复上传 → `duplicated=true` 且仅 1 条记录
  - TXT 文件上传 → 删除后分析记录清零
  - 空 body → 40001；`.exe` → 41501；PROCESSING 时触发 → 40910
  - 上游持续 401 → 重试后 FAILED、retryCount=3、error 含 HTTP 401
- 契约：`ContractCoverageTest` 由 19 端点扩展到 **25 端点全覆盖**

## 关键决策（为什么）

| 决策 | 理由 |
|---|---|
| 总分本地按权重计算 | 模型自报总分可能与五维不一致 |
| 上传后仅在已配置全局默认模型时自动触发 | 未配置模型时不制造必然失败的任务；前端仍可手动触发 |
| JSONB 用 `setObject(Types.OTHER)` 的 TypeHandler | pgjdbc `setString` 不能直接写 jsonb，且无需把驱动改 compile scope |
| Prompt 外置 `.st` + 简单占位替换 | 符合 §6.4 外置要求，不引入 StringTemplate 依赖 |
| 分析 worker 每轮检查简历是否仍存在 | 删除与异步任务并发时避免 FK 违约与无意义重试 |

## 过程中遇到的问题与修复

1. **编译失败：`org.postgresql.util` 不存在**（驱动是 runtime scope）→ 改为 `setObject(Types.OTHER)`，不加依赖
2. **WebTestClient 默认 5s 超时**：Tika 首次解析导致上传请求超时 → 测试客户端 `responseTimeout(60s)`
3. **全量测试时 M1c 连到已停止的端口**：`@Container` 随测试类停止，Spring 上下文跨类复用 → 集成测试基类改 JVM 级单例容器 + `@EnabledIf` 保留无 Docker 跳过
4. **M1c 复用 M1b 的假上游客户端**：测试直连 DB 清理绕过了缓存失效 → `setUp` 中显式 `ProviderClientRegistry.invalidate("stub"/"bad")`
5. **删除与异步分析并发导致 FK 违约** → worker 每轮与异常分支检查简历是否存在，已删除则终止

## 验证结果

- `mvn test`：**49 tests，0 failures / 0 errors / 0 skipped**
- 契约：25 个端点与 `contracts/openapi.yaml` 完全匹配
- README 已补充 M1-c 接口说明与存储配置

## 未完成 / 下一步

- M1-d 前端四屏：简历列表/上传、分析报告（视觉样板先行）、模型设置、对话页；前端 Vitest + Playwright
- 前端模型设置页/对话页接 M1-b 接口，简历页接 M1-c 接口

## 阻塞

- **待补推**：本地提交 `c1d800a`（feat: 实现 M1-c 简历中心与异步分析）尚未推送成功。
  代理 `127.0.0.1:7890` 未启动，直连 GitHub 报 `Recv failure: Connection was reset`。
  下次开工第一件事：启动代理后 `git push`（或 `git -c http.proxy=http://127.0.0.1:7890 push`）。
