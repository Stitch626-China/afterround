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
