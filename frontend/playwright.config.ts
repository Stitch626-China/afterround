import { defineConfig } from '@playwright/test'

/**
 * E2E：自包含（接口全部 mock），使用本机 Edge，无需下载浏览器。
 * 运行：npm run test:e2e
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  use: {
    baseURL: 'http://localhost:5174',
    channel: 'msedge',
    headless: true,
  },
  webServer: {
    command: 'npm run dev -- --port 5174',
    url: 'http://localhost:5174',
    reuseExistingServer: false,
    timeout: 60_000,
  },
})
