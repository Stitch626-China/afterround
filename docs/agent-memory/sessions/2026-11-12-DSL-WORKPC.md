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

---

## 2026-11-12 M1-d 报告页视觉样板

### 目标与需求确认
- 按需求 §6.10「先只做分析报告页一屏作为视觉样板，评审通过后再推其余页面」
- 风格：专业、克制、可信；不用紫色渐变/发光/糖果卡片
- 五维评分用横向条形图（不用雷达图）；空状态给行动指引不给插画
- 本页需覆盖：总分、五维、基本信息、优势/短板（带原句）、原句 vs 优化句、3-5 个岗位方向

### 架构与实现
- `api/resumes.ts`：接口函数与类型全部来自契约生成的 `schema.d.ts`，不手写数据结构
- `components/report/ScoreBar.vue`：单维横条（低/中/高三档语义色 + 权重）
- `components/report/ReportContent.vue`：纯展示组件，真实页与预览共用
- `views/ResumeReportView.vue`：数据加载与状态机（加载 / PENDING / PROCESSING 轮询 2s / FAILED 重试 / COMPLETED 报告），支持重新分析
- `views/ReportPreviewView.vue` + 仅 DEV 注册的 `/preview/report`：不接后端即可评审视觉
- 路由新增 `/resumes/:id/report`

### 验证
- `npm run build`：gen:api + vue-tsc 类型检查 + vite 构建全部通过
- 浏览器实测 `/preview/report`：标题/四类章节/5 条评分条/总分 76 全部正确，条形宽度与分值比例一致，控制台 0 错误
- 截图：`C:\Users\Leno\AppData\Local\Temp\trae\screenshots\preview-report-top.png` 等

### 待评审 / 下一步
- 用户评审样板观感与信息层级
- 通过后实现：简历列表/上传（含去重提示、状态轮询、删除确认）、模型设置（Provider/Model CRUD + 连通测试 + 全局默认）、对话（会话列表 + 两级切换 + SSE 流式）
- 前端测试：Vitest + Playwright 关键链路

## 阻塞

- **待补推**：本地提交 `c1d800a`（feat: 实现 M1-c 简历中心与异步分析）尚未推送成功。
  代理 `127.0.0.1:7890` 未启动，直连 GitHub 报 `Recv failure: Connection was reset`。
  下次开工第一件事：启动代理后 `git push`（或 `git -c http.proxy=http://127.0.0.1:7890 push`）。
- 已补推完成（2026-11-12）：`6cb2ba5..231252d` 已成功推送到 origin/main。

---

## 2026-11-12 需求 v1.6：薪资可见可改 + Offer 决策规划

### 需求确认（用户原话 → 结论）
- 报告基本信息新增「当前月薪 / 期望月薪」：LLM 从简历提取，用户可改；**用户值优先，重分析不覆盖，清空回落 LLM 值**
- 新增 Offer 决策板块：多 Offer 同维度对比（薪资 / 五险一金基数与比例 / 双休 / 福利补贴 / 上下班时间 / 底薪与绩效占比 / 年终奖 / 季度奖 / 公司前景与成长空间），保留 / 淘汰分组、可反悔
- 对比一致性：**固定字段 schema + 缺失统一「未填写」+ 前端固定行渲染**，不建 Skill
- 对比上限 4 个；前景分析默认基于用户粘贴的文本/图片，联网搜索为开关（默认关闭）
- 归属：薪资归 M1（当前实现）；**Offer 决策记为 M2 F6，仅落需求与契约规划，不现在实现**

### 改动（文件 → 说明）
- `docs/superpowers/specs/职点（JobPilot）需求文档.md` → v1.6：迭代历程新增条目；功能总览新增 F6；FR1.6 加薪资；新增 §4.8 FR6.1~FR6.6；M2 里程碑加 F6
- `contracts/openapi.yaml` → 新增 `PUT /api/resumes/{resumeId}/salary`（`UpdateSalaryRequest`）；`ResumeBasicInfo` 加 `currentMonthlySalary` / `expectedMonthlySalary`；端点 25 → **26**
- `backend/.../db/migration/V2__resume_salary.sql` → `resumes` 加 `current_salary` / `expected_salary`
- `Resume` / `UpdateSalaryRequest` / `ResumeBasicInfoResponse` / `AiResumeAnalysis.BasicInfo` / `resume-analysis-system.st` → 薪资字段与提取要求（元/月，无则 null，禁止编造）
- `ResumeService.updateSalary` + `ResumeController` PUT 端点 → 落用户值后返回最新状态
- `ResumeAnalysisService` → `mergedBasicInfo` 用户值优先 + `toReport(analysis, resume)` 统一出口
- `frontend`：`api/resumes.ts` 类型与 `updateResumeSalary`；`ReportContent.vue` 薪资展示 + 编辑（`salaryEditable` / `saving` / `save-salary`）；`ResumeReportView.vue` 保存逻辑 + 错误横幅
- `ContractCoverageTest` → 断言 26 端点；`M1cResumeApiIntegrationTest` → 新增 `salaryOverrideSurvivesReanalysis`（LLM 值 → 用户改 → 重分析不覆盖 → 清空回落 → 负数 400）
- README / PROGRESS / 本记录同步

### 验证结果
- `mvn test`：全量 49 → 新增薪资用例后 **50 tests 全部通过**；Flyway V1+V2 在 `postgres:16-alpine` 真实执行
- `npm run build`：gen:api + vue-tsc + vite 全部通过
- `npx @redocly/cli lint contracts/openapi.yaml` → valid（0 error / 0 warning）

### 问题与修复
1. **工具编辑多次回退**：`api/resumes.ts` 类型别名、`ReportContent.vue` 的 props/emit/薪资 UI、`ResumeReportView.vue` 的 import/ref/函数、后端 `toReport` 调用均出现"报告成功但实际未落盘"→ 每处改完立即 Grep/Read 验证，缺失即重写；最终以编译/测试结果为准
2. **PUT 薪资后仍返回 LLM 值 30000**：`status()` 仍调用单参 `toReport(analysis)`，`mergedBasicInfo` 未接入 → 改为 `toReport(analysis, resume)` 并在报告构造中使用 `mergedBasicInfo`
3. **npm 默认 cache 在 D 盘**（`D:\nodejs\node_cache`）被沙箱拦截 → 统一 `npm_config_cache=$env:TEMP\npm-cache-jobpilot` 后 lint/build 正常

### 未完成 / 下一步
- M1-d 其余三屏（简历列表/上传、模型设置、对话）+ Vitest/Playwright
- M2 实现 F6 前先补契约（Offer CRUD / 分组 / 对比 / 前景分析）

---

## 2026-11-12 需求 v1.7：信息架构分层 + 设置抽屉 + Light/Dark

### 需求确认（用户四点 → 决策）
1. 模型设置不占一级菜单：左下角齿轮 → **右侧抽屉**，分「模型服务 / 外观」两个 Tab。
2. 历史上传始终在列表显示；上传后**留在列表**观察进度，点击进报告，不自动跳转。
3. 简历版本对比**列入 M2 F7**（手动选择两份，不引入版本链）；M1-d 先完成收口。
4. 场景级模型绑定**列入 M2**（`llm_scene_settings`，未配置回落全局）；M1 保持全局默认。
5. 分析一致性：固定 prompt + 固定五维权重 + 结构化校验 + **固定 temperature 0.2**，LLM 不得改维度/权重/字段。

### 参考开源项目（只借鉴、不复制代码）
- `srbhr/Resume-Matcher`：上传 + JD 对比的三层评分思路；可借鉴「结果分层展示」与「Provider 可配置」。
- `JobMatch-AI` 等：单页渐进披露、分析自动运行、失败兜底与明确错误提示可借鉴；其霓虹渐变/发光风格**明确不采用**（违背本项目「专业克制」定位）。
- 通病规避：不把所有功能塞进一页、不依赖纯关键词匹配、不隐藏模型配置。

### 改动（文件 → 说明）
- 需求文档 v1.7：迭代历程新增条目；功能总览补 F6 / F7；§4.7 增 FRX.11 场景级模型；新增 §4.8 F6、§4.9 F7；§6.10 增信息架构与 Light/Dark token 表；§11 M1-d / M2 更新
- 前端主题：`styles/tokens.css` 增 dark 变量与 `--success/warning/danger-soft`；`theme.ts`；`index.html` 首屏前应用主题
- 布局：`App.vue` 一级导航只留简历中心 + M2/M3 占位；左下角齿轮；`SettingsDrawer.vue`
- 设置：`ModelSettings.vue`（Provider/Model CRUD、全局默认、连通测试、上游列举）、`AppearanceSettings.vue`（Light/Dark）、`api/llm.ts`
- 简历：`ResumeListView.vue` 拖拽/选择/粘贴上传、状态轮询、分页、行内删除确认、点击进报告；移除 `/llm` 路由与 `LlmSettingsView.vue`
- 请求降级：`api/http.ts` 与 `uploadResume` 对非 JSON / 非 2xx 给友好提示
- 后端：`OpenAiCompatibleClient` JSON 调用固定 `temperature=0.2` + 单测断言

### 验证结果
- `npm run build`：gen:api + vue-tsc + vite 通过
- `mvn test`：**50 tests 全部通过**
- 浏览器实测（外部 Chrome）：导航仅简历中心 + 占位、齿轮抽屉右侧 480px、Light/Dark 背景切换正确、控制台 0 error
- 实测发现后端未启动时页面暴露 JSON 解析错误 → 已改为友好提示（HTTP 状态 / 连接失败）

### 未完成 / 下一步
- 模型对话页（会话列表 + 两级切换 + SSE）+ 前端 Vitest/Playwright
- 真实后端联调（上传 → 分析 → 报告 → 薪资编辑）

---

## 2026-11-12 M1-d 收尾：模型对话页 + 前端测试

### 目标
完成 M1 最后一块：模型对话页（会话列表、Provider + Model 两级切换、SSE 流式），并补齐前端 Vitest / Playwright。

### 改动（文件 → 说明）
- `api/chat.ts`：会话 CRUD / 切换 / 历史 / SSE 发送；`api/sse.ts`：`extractSseEvents` 纯函数（跨 chunk、CRLF、末尾无空行）
- `ChatView.vue`：双栏布局（会话列表 + 对话区）、新建/删除、两级选择器与「切换」按钮、流式草稿、生成中徽标、消息级 provider/model 追溯
- 测试：`sse.spec.ts`（3）、`http.spec.ts`（4）、`theme.spec.ts`（2）共 **9 个 Vitest**；`e2e/app.spec.ts`（2 个 Playwright，接口全 mock、本机 Edge）与 `playwright.config.ts`
- `.gitignore` 增加 `test-results/`、`playwright-report/`；README / PROGRESS 同步

### 验证结果
- `npm test`：9 tests 通过；`npm run test:e2e`：2 tests 通过；`npm run build` 通过
- 真实链路联调（临时 PostgreSQL 5433 + 本机假 OpenAI 上游）：
  - 新建会话 → 两级选择器显示 Fake Local / Fake Model → 发送「你好」→ 流式返回「你好，这是流式回答。」→ 状态回「空闲」→ 控制台 0 error
  - 联调结束后已停止后端 / 假上游 / 临时数据库并删除临时文件

### 问题与修复
1. **后端连不上 PostgreSQL（密码认证失败）**：本机 5432 同时被本地 PostgreSQL 与 Docker 占用，`localhost:5432` 命中的不是容器 → 临时用 `docker run -p 5433:5432`，显式 `DB_URL=...:5433`；验证完清理
2. **Playwright mock 误伤 Vite 模块请求**：`/\/api\/resumes/` 同时匹配 `/src/api/resumes.ts`，导致页面白屏 → 收紧为 `/\/api\/resumes(\?|$)/`
3. **生成期间徽标仍显示空闲**：发送前只在流结束后刷新状态 → `send()` 本地置 `PROCESSING`，结束后由 `refreshActive()` 回落
4. 内置浏览器 viewport 为 0x0，改用外部 Chrome 完成真实交互验证

### 未完成 / 下一步
- M1 已全部完成；下一步启动 M2（先补 JD / 匹配 / 模拟面试 / Offer / 简历对比契约）

---

## 2026-11-12 M1 收口复审

### 目标
依据需求 v1.7 与 HEAD `816067e` 最新代码独立确认 M1 是否真正收口，并排查实现、测试与部署隐患；本次只审计和记录，不改业务代码。

### 新鲜验证
- `mvn test`：50 tests，0 failures / 0 errors / 0 skipped；Testcontainers 真实启动 PostgreSQL 16.15，Flyway V1/V2 成功执行。
- `npx @redocly/cli lint contracts/openapi.yaml`：通过；`npm run build`：契约类型生成、vue-tsc 与 Vite 构建通过。
- `npx vitest run src`：9 tests 通过；但标准 `npm test` 失败，因为 Vitest 配置未排除 `e2e/app.spec.ts`，误加载 Playwright。
- `npm run test:e2e`：2 个用例断言均通过，但本机 Edge 的沙箱外系统写入被拦截，命令最终退出码为 1；沙箱外重跑未获授权，因此不能记为干净的全量通过。
- 后端测试输出另有 Spring MVC 使用 SimpleAsyncTaskExecutor 的生产警告，以及 JaCoCo 插桩 JSQLParser 的 MethodTooLarge 异常；现有报告总行覆盖率 82.01%，未达需求阈值。

### 审计结论
**M1 暂不能全部收口。** 后端与前端已有完整骨架和主要能力，但存在可复现的主链路阻断、并发与可靠性缺陷，且测试门禁不足以证明需求全部满足。

### 阻断项
1. 无默认模型时上传保持 PENDING，前端没有「开始分析」入口，反而永久轮询并禁用报告页按钮，与 README 的手动重试说明不符。
2. ChatSessionService 切换模型先读后整行更新，会和 ChatMessageService 的 PROCESSING 原子抢占竞态，可能执行中换模型或错误释放状态。
3. SSE 客户端取消不会进入 onErrorResume；异步队列拒绝和进程重启也没有状态回收，可能造成会话/简历永久 PROCESSING。
4. OpenAiCompatibleClient 未设置 max_tokens，不满足 NFR7 单次任务 token 硬上限。
5. 标准前端测试命令失败；Playwright 仅覆盖导航、主题和空列表，未覆盖上传→报告、薪资、设置 CRUD、完整 SSE 等主流程。

### 其他重要隐患
- 前端 SSE 未绑定会话所有权且不可 Abort，慢流期间切换/删除会话可能串写消息；错误会在 finally 刷新时被清空，409 后本地状态不刷新。
- 列表与报告轮询在在途请求完成后可绕过卸载清 timer，造成离页轮询。
- 模型设置只能新增/启停/删除，不能编辑 label 等字段；上游模型只展示，没有同步到本地。
- 契约测试仅检查 26 个方法+路径且允许额外端点，不校验请求/响应 schema；JaCoCo 只有 report 没有 check，关键类覆盖率未达 95%。
- 文件删除异常被吞掉后继续删库，可能永久遗留磁盘文件；上游错误体与异步 traceId/内部重试埋点也存在安全与可观测缺口。
- 历史消息按 50 条而非完整轮次+摘要+token 预算截断；坏 SSE JSON 帧被静默忽略后仍可能按成功落 assistant 消息。

### 改动（文件 → 说明）
- `docs/agent-memory/PROGRESS.md` → 撤回当前「已收口」状态，记录阻断项与修复优先级。
- `docs/agent-memory/sessions/2026-11-12-DSL-WORKPC.md` → 追加本次审计证据；未改生产代码、契约和需求文档。

### 未完成 / 下一步
- 先修复阻断项并补回归，再完善契约/覆盖率门禁与主流程 E2E；重新全量验收通过前不启动 M2。

