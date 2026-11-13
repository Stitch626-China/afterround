# 2026-11-13 DSL-WORKPC

## 目标
修复 2026-11-12 M1 收口复审发现的 5 个阻断项与其他重要隐患，补齐回归测试与质量门禁，重新完成 M1 收口。

## 改动（文件 → 说明）
- `backend/.../chat/service/ChatSessionService.java` → 切换模型改为 `status = IDLE` 条件更新，失败再读最新状态区分 PROCESSING / LOCKED，消除与发送抢占的竞态。
- `backend/.../chat/service/ChatMessageService.java` → 流末尾 `doFinally` 释放 PROCESSING（客户端取消也生效）；历史消息复制为可变列表并丢弃截断产生的半轮 ASSISTANT。
- `backend/.../resume/service/ResumeAnalysisService.java` → 队列拒绝回滚 PENDING；异步任务透传 MDC traceId；坏帧/建连失败按失败落库。
- `backend/.../resume/service/ResumeService.java` → 文件删除失败不再吞错删库，保留记录并提示重试。
- `backend/.../common/config/StartupRecovery.java`（新增）→ 启动回收遗留 PROCESSING：会话回 IDLE、简历回 PENDING 并写原因。
- `backend/.../common/llm/OpenAiCompatibleClient.java` → 增加 `max_tokens`（NFR7）；SSE 非法 JSON / error 帧抛 `LlmException`，避免残缺回答按成功落库。
- `backend/.../infrastructure/llm/ProviderClientRegistry.java`、`application.yml`、`.env.example` → 新增 `LLM_MAX_TOKENS`（默认 4096）。
- `backend/pom.xml` → JaCoCo `check` 绑定 test 阶段：总行覆盖 ≥85%，关键类（网关客户端 / 结构化输出 / 简历分析 / 会话消息 / 会话切换）≥95%。
- `backend/src/test/...` → 新增 `LambdaCacheSupport`、`StartupRecoveryTest`、`ChatSessionServiceTest`、`ChatMessageServiceTest`、`ResumeAnalysisServiceTest`、`ResumeServiceTest`、`LlmModelServiceTest`、`LlmProviderServiceTest`、`ContractSchemaTest`；`OpenAiCompatibleClientTest` 增加 max_tokens 断言。
- `backend/src/test/.../contract/ContractSchemaTest.java`（新增）→ 用反射比对契约 schema 与 DTO 字段，防止契约/实现漂移。
- `frontend/vite.config.ts` → Vitest 只收集 `src/**/*.{test,spec}.ts`，修复 `npm test` 误加载 Playwright。
- `frontend/src/api/chat.ts`、`ChatView.vue` → SSE 支持 AbortSignal；切会话/删除/离页取消；跨会话事件丢弃；流式错误在刷新后保留。
- `frontend/src/views/ResumeListView.vue`、`ResumeReportView.vue` → PENDING 显示「开始分析」；仅 PROCESSING 轮询；离页 disposed 防继续轮询。
- `frontend/src/components/settings/ModelSettings.vue` → 模型行内编辑展示名；上游模型一键添加到本地。
- `frontend/e2e/app.spec.ts`、`e2e/main-flows.spec.ts`（新增）→ 覆盖列表→报告→改薪资、上传→待分析、SSE 流式回答、设置新增服务商。
- `README.md` → 补充 PENDING 手动分析、`LLM_MAX_TOKENS`、启动恢复、覆盖率门禁与契约 schema 测试说明。

## 决策（为什么）
- 切换守卫用条件更新而非整行回写：状态检查与写入必须原子，否则会覆盖并发抢占的 PROCESSING。
- SSE 坏帧直接报错：静默丢弃会让残缺回答被当成成功落库，错误必须显式暴露给用户与日志。
- 启动恢复无条件回收 PROCESSING：单机单用户场景下不存在跨进程合法 PROCESSING，重启后残留必为中断任务。
- JaCoCo 门禁绑定 `test` 阶段：让 `mvn test` 本身即是验收命令；局部调试用 `-Djacoco.skip=true`，避免分母不足误报。
- 契约 schema 校验不引入新依赖：用反射 + 现有 SnakeYAML 比对 DTO 字段，轻量且能覆盖主要漂移风险。

## 验证
- `mvn clean test`：86 tests，0 failures / 0 errors / 0 skipped；JaCoCo 总行覆盖 91.27%，关键类 95.7%~100%，门禁通过。
- `npm test`：9 tests 通过；`npm run build`：通过。
- `npx playwright test`：6 tests 全部通过（沙箱内命令退出码 1 为本机 Edge 写入系统目录被拦截，非断言失败；沙箱外重跑曾获 3 passed / exit 0）。
- `npx @redocly/cli lint contracts/openapi.yaml`：0 error / 0 warning。

## 未完成 / 下一步
- M1 收口复审已通过，可启动 M2：先补 JD / 匹配 / 模拟面试 / Offer / 简历对比契约。
- 遗留优化（M2 再做）：历史消息从「按条数截断」升级为「完整轮次 + token 预算 + 摘要」。

## 阻塞
- 无。

---

## 2026-11-13 二次复验（修复提交 98ef7a4）

### 目标
独立复验 `b89d2ee..98ef7a4` 是否真正修复 M1 收口复审发现的问题，重新取得测试/构建/契约证据，不沿用上一会话结论。

### 复验结论
- 原 5 项阻断项均确认修复：PENDING 手动「开始分析」且仅 PROCESSING 轮询；切换模型改条件原子更新（仅写绑定字段 + 检查影响行数 + 失败后二次读状态）；队列拒绝回滚 PENDING、启动回收遗留 PROCESSING；`max_tokens` 在统一 payload 注入（普通 / JSON / SSE 全生效，默认 4096）；文件删除失败保留 DB 记录并提示重试。
- 其余确认修复：SSE 取消经 `doFinally` 释放；坏 JSON / error 帧显式抛错；历史截断丢弃开头 ASSISTANT；异步任务透传 traceId；模型展示名行内编辑 + 上游模型添加；前端 AbortSignal + 跨会话事件丢弃；Vitest 只收集 `src/**`；E2E 扩至 6 个；JaCoCo 门禁绑定 test。

### 新鲜验证证据
- `mvn clean test`（JDK 21）：86 tests，0 failures / 0 errors / 0 skipped；JaCoCo “All coverage checks have been met”，总行覆盖 **91.27%**（1129/1237），关键类 95% 门禁通过；BUILD SUCCESS。
- `npm test`：3 文件 / 9 tests 通过，退出码 0（不再误收集 Playwright）。
- `npm run build`：通过。
- `npx playwright test`：6 tests 全部通过（10.8s）；进程退出码 1 因沙箱拦截 Edge 写 Microsoft 系统目录，属环境限制，非断言失败。
- `npx @redocly/cli lint`：契约有效，0 error / 0 warning。

### 仍未闭环（非阻断）
- **Provider `enabled` 热更新失效**：`LlmProviderService.update` 仅在 baseUrl/apiKey 变化时 invalidate，`ProviderClientRegistry.get` 命中缓存后不再校验 enabled；禁用后旧客户端仍可继续调用，且 `LlmProviderServiceTest` 断言 `never().invalidate`。违反「保存即生效 / CRUD 后精确失效」。
- MVC SSE 仍走默认 `SimpleAsyncTaskExecutor`（本次日志仍打印 “not suitable for production use under load”），未见 `configureAsyncSupport` 或 `spring.mvc.async.request-timeout`。
- 流式响应无 `[DONE]` 正常结束时仍按成功保存残缺回答；客户端取消路径不写 `llm_call_logs`。
- 成功 / 异常路径与 `doFinally` 重复 release（当前幂等，建议只保留 `doFinally`）。
- 上游错误体仅截断 300 字，仍原样进入 SSE error 与 `analyze_error`，未脱敏。
- `ContractSchemaTest` 只比字段名（不比类型 / required / 枚举）；`ContractCoverageTest` 只 `containsAll`，不拒绝额外端点。
- 前端：聊天快速切换无请求世代守卫；删除末页唯一会话后停留在空页；报告页触发分析后离页仍会排一次轮询，初始加载可能用旧 PENDING 覆盖 PROCESSING；上传结果为 PENDING 时提示仍称“正在自动分析”；`chat.ts` / `resumes.ts` 仍手写分页类型。
- E2E 的 SSE 用例一次性返回完整流，无法证明增量渲染。

### 判定
阻断项已清零，功能主链路可验收；但“完全收口”不成立，建议先修 Provider enabled 缓存与 MVC 异步执行器，其余按非阻断遗留排期。

### 复验后修复（补记，2026-11-13）
- `LlmProviderService.update`：`enabled` 变化也置 `changedConnection=true` 并 `registry.invalidate`，禁用立即生效；`LlmProviderServiceTest` 改为断言「仅改 label 不失效、启停/连接变化失效」，5 tests 通过。
- 新增 `WebMvcConfig`：`configureAsyncSupport` 绑定独立有界 `mvcTaskExecutor`（core 4 / max 16 / queue 200）+ 5 分钟默认超时，替代默认 `SimpleAsyncTaskExecutor`。
- 验证：`mvn "-Djacoco.skip=true" test "-Dtest=*Test,!M1bApiIntegrationTest,!M1cResumeApiIntegrationTest"` → **74 tests，0 失败 / 0 跳过**；`LlmProviderServiceTest` 5 tests 通过。
- 环境阻塞：本机 Docker Desktop 未运行（`dockerDesktopLinuxEngine` 管道不存在），启动 Docker 属沙箱外操作被拒；`JobPilotApplicationTests` / M1b / M1c 共 12 个集成测试在无 Docker 时**未按 README 所述自动跳过，而是直接失败**（`AbstractIntegrationTest` 的类级 `@EnabledIf` 未被 JUnit 继承到子类）。全量 86 tests 与覆盖率门禁需 Docker 启动后补跑。

### 遗留清理与最终回归（2026-11-13）
- 后端：流式缺少 `[DONE]` 视为异常结束；上游错误原文只进日志、对外统一脱敏；成功/异常先释放再发事件，`doFinally` 仅在 CANCEL 时释放并写 `llm_call_logs`；契约端点双向严格相等、schema 检查不允许静默跳过；集成测试类补显式 `@EnabledIf`，Docker 不可用真正跳过；Provider 删除引用检查含 LOCKED；移除未用 import。
- 前端：ChatView 增加会话世代 + 列表序号守卫，快速切换/删除/离页的迟到响应不再回写；删除末页会话自动回退页码；ResumeReportView 增加加载序号与 disposed 守卫，避免旧 PENDING 覆盖 PROCESSING 与离页后轮询；上传 PENDING 提示改为「点击开始分析」；分页类型改为契约派生；SSE E2E 延迟返回并断言「生成中」。
- 最终验证（Docker 29.6.1 已启动）：后端 `mvn clean test` → **88 tests，0 失败 / 0 错误 / 0 跳过**，总行覆盖 **91.49%**，JaCoCo 全部门禁通过，无 `SimpleAsyncTaskExecutor` 警告；前端 `npm test` 9 通过、`npm run build` 通过、`npx playwright test` 6 passed（沙箱致退出码 1）；契约 lint 0 error / 0 warning。
- 结论：原阻断项、复验发现的 Provider/MVC 问题及其余非阻断遗留均已处理，M1 可收口并启动 M2。
