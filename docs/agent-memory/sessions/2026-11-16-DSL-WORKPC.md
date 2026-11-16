# 2026-11-16 DSL-WORKPC

## 目标
补齐 README 的环境依赖、配置项、启动方式与常见问题，让外部用户 clone 后可以按文档跑起来，为公开仓库开源做准备。

## 改动（文件 → 说明）
- `README.md` → 新增「环境依赖」「配置说明」「快速开始（本地开发）」「Docker Compose 部署」「常见问题」；补充 `.env` 配置表、`APP_SECRET_KEY` 生成方式、Windows/macOS/Linux 启动命令、Nginx SSE 反向代理示例；修正未配置模型时的入口为「开始分析」。
- `frontend/package.json` → 新增 `predev` 自动执行 `gen:api`，保证新克隆仓库没有 `schema.d.ts` 时 `npm run dev` 也能直接启动。
- `.env.example` → 补充 `LLM_MAX_HISTORY_MESSAGES=50`，与 README 配置表一致。

## 决策（为什么）
- 不引入 Spring Boot 自动读取 `.env`：`.env` 与 properties 语法存在差异，且工作目录不同路径不一致；改为文档明确要求先导入环境变量，行为更可预期。
- Docker Compose 维持「数据库 + 后端」：前端继续用 Vite 开发或静态托管，README 如实说明并给出 Nginx 配置，避免宣称不存在的一键全栈能力。
- `predev` 放在前端脚本而不是文档里让人手工跑：开源用户最容易漏掉契约类型生成，脚本兜底成本最低。

## 验证
- `npm run gen:api` 通过（openapi-typescript 7.13.0）。
- README 代码块围栏成对（24 个）。
- 本轮同时启动了完整环境供验收：前端 `http://localhost:5173`、后端 `http://localhost:8080`（临时 PostgreSQL 容器 `jobpilot-pg-dev`，端口 5433）；健康检查 UP，前端代理返回正常。

## 未完成 / 下一步
- 公开仓库前需补充 `LICENSE`（当前仓库没有许可证文件），并同步修改 `contracts/openapi.yaml` 中 `info.license` 的「私有项目（未授权）」描述。
- 如需「一条命令起全栈」，可后续增加前端 Dockerfile + Nginx 反代服务到 Compose。

## 阻塞
- 无。

## 追加：模型配置引导体验改进（同日）

### 目标
解决「新增服务商后连通测试提示没有模型」「简历分析提示没有可用模型但不知道去哪设置」两类卡点，补齐使用提示。

### 改动（文件 → 说明）
- `frontend/src/composables/useSettings.ts`（新增）→ 共享 `settingsOpen` / `settingsTab` / `openModelSettings()`，业务页可直接打开设置抽屉。
- `frontend/src/App.vue`、`components/settings/SettingsDrawer.vue` → 改用共享状态，去掉各自的本地 tab 状态。
- `frontend/src/components/settings/ModelSettings.vue` → 创建服务商后自动拉取上游模型：唯一模型自动添加并设全局默认；多模型显示勾选面板（默认勾选第一个，支持全选/清空/批量添加）；连通测试前若无模型先拉取引导；模型全停用时提示先启用；全局默认栏增加说明与「用 X 设为默认」；手动添加模型且无全局默认时自动设默认。
- `frontend/src/views/ResumeListView.vue`、`ResumeReportView.vue` → 模型相关错误/提示旁增加「打开模型设置」按钮；FAILED 状态提供入口；空状态补充设置路径说明。
- `backend/.../ResumeAnalysisService.java`、`LlmQueryService.java` → 无默认模型/无启用模型的错误文案改为明确操作指引。
- `frontend/e2e/main-flows.spec.ts` → 设置流程重写为「新增服务商 → 自动拉取 → 勾选添加 → 自动全局默认」完整断言。

### 决策（为什么）
- 自动拉取放在前端创建成功后触发，而不是后端创建时隐式拉取：创建接口保持幂等、可离线完成；拉取失败不影响服务商保存，只给手动填模型兜底提示。
- 唯一模型才自动设全局默认，多模型让用户勾选：避免把不可用/非预期模型设为全局默认影响简历分析。
- 设置入口用 composable 共享而非路由跳转：保持当前页面上下文，配置完可直接在原页面重试。

### 验证
- `npm run build`（含 `gen:api` + `vue-tsc`）通过。
- 全量 E2E 6 passed（沙箱 Edge 写系统目录导致退出码 1，断言全过）。
- 后端 `mvn clean test` BUILD SUCCESS：88 tests，JaCoCo 总行覆盖 ≥85%、关键类 ≥95% 门禁通过。
- 已重启完整环境供验收：前端 5173、后端 8080（PostgreSQL 5433，Flyway V1/V2 已是最新）。

### 未完成 / 下一步
- 无新增待办；公开开源前仍需确定 `LICENSE` 并同步契约 `info.license` 字段。
