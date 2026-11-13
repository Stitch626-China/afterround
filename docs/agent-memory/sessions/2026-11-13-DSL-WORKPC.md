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
