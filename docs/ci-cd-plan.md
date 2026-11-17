# CI/CD 方案（CI 已实现，CD 规划中）

> 状态：2026-11-16 完成适配核对；同日已落地 CI（`.github/workflows/ci.yml` + Playwright channel 环境变量化），CD 尚未实现。
> 目标：用 GitHub Actions 为个人自用版补齐「每次提交自动验证 + 打 tag 出镜像」的最小闭环，先学习再逐步自动化。

## 1. 适用范围

- **CI**：契约 lint、后端测试 + 覆盖率门禁、前端构建 + 单测 + E2E。
- **CD**：后端镜像推送到 GHCR（GitHub Container Registry）；家庭主机拉取更新。
- **暂不包含**：前端镜像与自动部署（当前无前端 Dockerfile，继续手工 `npm run build` + Nginx）、真实 LLM 联调、多环境灰度。

## 2. 与本仓库的适配核对

结论：整体可行，需先做 3 个前置改动（第 5 节）。

| 检查项 | 现状 | 对 CI/CD 的影响 |
|---|---|---|
| 构建工具 | 无 Maven Wrapper；Runner 自带 Maven | `setup-java` 后直接 `mvn`；可选后续生成 `mvnw` |
| 后端测试 | surefire 一次跑完单元 + 集成测试，集成测试用 Testcontainers 起 `postgres:16-alpine` | GitHub `ubuntu-latest` 自带 Docker，可用；`docker-java.properties` 固定 API `1.44`，要求 Docker ≥ 25（Runner 满足） |
| 覆盖率门禁 | JaCoCo `check` 绑定在 `test` 阶段（总行 ≥85%、5 个关键类 ≥95%） | `mvn test` 即自动门禁，无需额外命令 |
| 测试密钥 | `src/test/resources/application.properties` 提供测试专用密钥 | CI 不需要 `APP_SECRET_KEY` Secret |
| 契约校验 | 根 `redocly.yaml` + 前端 devDependency `@redocly/cli` | 可独立 job：`npm ci` + `npm run lint:contract` |
| 前端依赖 | `frontend/package-lock.json` 存在 | 可用 `npm ci`，需 Node 20 |
| E2E | `playwright.config.ts` 硬编码 `channel: 'msedge'`，webServer 起 5174 | **Ubuntu Runner 没有 Edge**：需改为环境变量可配置，CI 安装 chromium |
| 后端镜像 | `backend/Dockerfile` 多阶段构建，镜像内 `-DskipTests` | 镜像构建与 CI 测试可分离，不重复跑测试 |
| GHCR 命名 | GitHub owner 为 `Stitch626-China`（含大写） | **容器镜像名必须全小写**，不能直接用 `${{ github.repository_owner }}`，需写死 `ghcr.io/stitch626-china/...` |
| Compose | `app` 服务只有 `build:`，没有 `image:` | 主机拉镜像更新前需给 `app` 补 `image:`（保留 build 供本地） |

## 3. CI 设计

触发：`push` 到 `main` + 所有 `pull_request`；同一分支并发时取消旧任务（`concurrency.cancel-in-progress`）。

| Job | 运行环境 | 步骤 |
|---|---|---|
| `contract` | ubuntu + Node 20 | `npm ci` → `npm run lint:contract`（在 `frontend/`） |
| `backend` | ubuntu + JDK 21 | `mvn -B -f backend/pom.xml test`（含 Testcontainers 与覆盖率门禁）；缓存 `~/.m2` |
| `frontend` | ubuntu + Node 20 | `npm ci` → `npm test` → `npm run build` → `npx playwright install --with-deps chromium` → `PLAYWRIGHT_CHANNEL=chromium npm run test:e2e`；缓存 npm |

- 单元测试、构建、E2E 放在同一个 `frontend` job 里，复用 `node_modules`，减少重复安装。
- 可选产物：`backend/target/site/jacoco` 与 `frontend/playwright-report` 用 `actions/upload-artifact` 保留，失败时便于排查。
- 耗时预估：首次 5~8 分钟（拉依赖 + Docker 镜像），缓存命中后 3~5 分钟。Private 仓库每月 2000 分钟免费额度足够；公开仓库标准 Runner 免费。

## 4. CD 设计

触发：推送 `v*` tag，或 `workflow_dispatch` 手动触发。

1. 构建并推送镜像：`ghcr.io/stitch626-china/jobpilot-backend:<tag>` 与 `:latest`；使用 `GITHUB_TOKEN`，workflow 声明 `permissions: packages: write`。
2. 主机更新（先手动，稳定后再自动）：
   - 部署前备份：`pg_dump` + 拷贝 `STORAGE_ROOT`；
   - `docker compose pull app && docker compose up -d app`；
   - 健康检查 `GET /actuator/health`；
   - 回滚：切回上一个 tag 的镜像。**Flyway 是前向迁移，不能靠回滚镜像撤销**，必要时从备份恢复。
3. 自动部署两种方式，任选其一：
   - **A. 自托管 Runner**：主机安装 GitHub Runner，仅响应 tag 事件。Private 仓库安全可控；若仓库公开，必须限制触发条件，避免不可信代码在主机执行。
   - **B. SSH 远程部署**：GitHub Runner 通过 Tailscale + SSH 登录主机执行更新命令，密钥放 GitHub Secrets。
4. 安全约束：`APP_SECRET_KEY` 只存在主机 `.env`；主机只在内网 / Tailscale 可达，项目无鉴权，禁止公网暴露。

## 5. 前置改动清单（实施时再做）

1. 新增 `.github/workflows/ci.yml`（第 3 节）。
2. `frontend/playwright.config.ts`：`channel: process.env.PLAYWRIGHT_CHANNEL ?? 'msedge'`，本机仍用 Edge，CI 用 Chromium。
3. 新增 `.github/workflows/image.yml`（第 4 节）。
4. `docker-compose.yml`：`app` 增加 `image: ghcr.io/stitch626-china/jobpilot-backend:${IMAGE_TAG:-latest}`，保留 `build:` 供本地。
5. 可选：`mvn wrapper:wrapper` 固定 Maven 版本；仓库公开后在 README 加 CI 状态徽章。

## 6. 明确不做

- CI 不调用真实 LLM、不依赖内网网关；E2E 继续接口全 mock。
- 不把 `.env` / `APP_SECRET_KEY` 放进 CI 或镜像。
- 不是每次 `main` 提交都自动上线，个人项目保留手动确认。
- 暂不构建前端镜像（等前端部署方式稳定后再定）。

## 7. 学习路径

1. **只上 CI**：让 `contract` / `backend` / `frontend` 三个 job 在 PR 上稳定变绿，理解失败日志定位方式。
2. **手动出镜像**：`workflow_dispatch` 跑一次 `image`，确认 GHCR 里能拉到镜像，本地 `docker run` 验证。
3. **手动部署主机**：按第 4 节流程更新一次，验证备份、Flyway 迁移、健康检查与回滚。
4. **再自动化 CD**：确认稳定后，把 tag 触发接上自托管 Runner 或 SSH 部署。

## 8. 待定项

- 仓库公开时机与 LICENSE（暂定开发完成后再定）。
- 自动部署选 A（自托管 Runner）还是 B（Tailscale + SSH）。
- 是否引入 Codecov、Dependabot、镜像漏洞扫描等增强项（后置）。

## 9. 首次运行问题记录（2026-11-16）

首次真实运行 CI 暴露了两个本地 Windows 无法复现的问题。两者都已修复并回归，记录在此便于追溯。

### 9.1 追溯索引

| 阶段 | 提交 / 运行 | 结果 |
|---|---|---|
| 首次 PR 运行 | PR #1 / run `43830839548` | 契约 job 失败，后端、前端 job 成功 |
| 修复提交 | `c0ce5f5`（fix: 修复 CI 契约 job 的依赖与配置路径） | — |
| 修复后重跑 | run `43831265961` | 三个 job 全绿 |
| 文档提交 | `e32a2d6` | run `43831626862` 全绿 |
| 合并 | `590c813`（Merge PR #1） | main 运行 `43831982032` 全绿 |

### 9.2 问题一：`Cannot find module 'yaml'`

**现象**

契约 job 在 `npm run lint:contract` 步骤退出码 1：

```text
Error: Cannot find module 'yaml'
Require stack:
- /home/runner/work/jobpilot/jobpilot/frontend/node_modules/redoc/bundles/redoc.lib.js
- /home/runner/work/jobpilot/jobpilot/frontend/node_modules/@redocly/cli/lib/commands/build-docs/index.js
- /home/runner/work/jobpilot/jobpilot/frontend/node_modules/@redocly/cli/lib/index.js
...
Node.js v20.20.2
```

**根因**

- `@redocly/cli`（锁文件实际安装 `1.34.24`）的入口会加载 `build-docs` 模块，其依赖的 `redoc`（`2.5.3`）在运行时 `require('yaml')`；
- 但 `yaml` 没有出现在修复前的 `frontend/package-lock.json` 里；
- 本机 Windows 的 `node_modules` 存在历史安装残留的 `yaml`（未被锁文件记录），`require` 能解析到，所以本地一直正常；
- CI 的 Linux Runner 用 `npm ci`，严格按锁文件从零安装，没有这个"隐形依赖"，因此报错。

这说明：**依赖提升（hoisting）会让本地通过、干净环境失败；锁文件才是依赖事实。**

**修复**

```bash
cd frontend
npm install --save-dev yaml
```

结果：`package.json` 增加 `"yaml": "^2.9.1"`（实际安装 `2.9.1`），锁文件同步；`c0ce5f5` 提交。

**验证**

- 本地 `cd frontend && npm run lint:contract` 正常；
- CI 重跑 `43831265961` 契约 job 成功；
- 后端与前端 job 在首次运行中本就已通过，说明问题仅限契约工具链。

**预防**

- CI 用到的 CLI 依赖一律显式声明，不依赖传递依赖是否被提升；
- 新增 CI 工具后，最好像 CI 一样在干净目录执行一次 `npm ci`（可临时删 `node_modules`），不要只信本机已装好的环境。

### 9.3 问题二：`lint:contract` 没有加载根目录 `redocly.yaml`

**现象**

修好 `yaml` 后在本地按 CI 的方式（工作目录 `frontend/`）执行 `npm run lint:contract`，契约出现 3 条本应被关闭的警告：

- `no-server-example.com`：`servers` 使用 `http://localhost:8080`；
- `operation-4xx-response`：`GET /api/llm/providers` 缺少 4xx；
- `operation-4xx-response`：`GET /api/llm/global` 缺少 4xx。

而根目录 `redocly.yaml` 已明确关闭这两条规则（本项目单用户、无鉴权，且 server 就是本地地址）。

**根因**

- redocly 默认从**当前工作目录**查找配置文件；npm script 的 cwd 是 `frontend/`，根目录的 `redocly.yaml` 在上一级，不在查找范围内，于是回退到内置 recommended 规则；
- 之前本地验证是在仓库根目录执行 `npx @redocly/cli lint contracts/openapi.yaml`，能自动找到根配置，所以问题被掩盖；
- CI 中 `working-directory: frontend` 与本地 script 的 cwd 一致，同样会丢配置。

**影响**

redocly 默认对 warning 返回退出码 0，CI 可能"绿着"带 3 条 warning，违背项目「0 error / 0 warning」的硬约束，且规则开关实际失效。

**修复**

```diff
- "lint:contract": "redocly lint ../contracts/openapi.yaml"
+ "lint:contract": "redocly lint ../contracts/openapi.yaml --config ../redocly.yaml"
```

**验证**

- 本地 `npm run lint:contract`：`validated ... Your API description is valid`，0 error 0 warning；
- CI 重跑 `43831265961` 契约 job 成功。

**预防**

- 共享配置不要依赖 cwd 自动发现，npm script 中显式传 `--config`；
- 本地复现必须与 CI 使用相同的命令和工作目录，最好两者都调用同一个 npm script，避免"本地手工命令通过、CI 脚本失败"。

### 9.4 经验总结

1. CI 的第一价值不是重复本地测试，而是暴露**干净环境与本地残留环境的差异**。
2. `npm ci` 严格按锁文件安装；本地能跑不代表依赖声明完整。
3. 配置文件查找与工作目录强相关，脚本中应显式指定路径。
4. 排查顺序：看 job 日志定位失败 step → 在本地用**同一命令、同一 cwd** 复现 → 修复后保留提交与运行 ID 作为追溯链。

