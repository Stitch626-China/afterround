import { expect, test } from '@playwright/test'

/**
 * 主链路 E2E（接口全 mock）：上传简历 → 列表待分析入口；SSE 流式对话；设置新增 Provider。
 */

const CORS = { 'access-control-allow-origin': '*' }

test('上传简历后留在列表并显示待分析入口', async ({ page }) => {
  let uploaded = false
  const item = {
    id: 1,
    fileName: 'resume.pdf',
    fileType: 'PDF',
    fileSize: 1024,
    analyzeStatus: 'PENDING',
    overallScore: null,
    createdAt: '2026-11-13T10:00:00Z',
    updatedAt: '2026-11-13T10:00:00Z',
  }

  await page.route(/\/api\/resumes(\?|$)/, (route) => {
    if (route.request().method() === 'POST') {
      uploaded = true
      return route.fulfill({ json: { code: 0, message: 'ok', data: { resume: item, duplicated: false } } })
    }
    return route.fulfill({
      json: {
        code: 0,
        message: 'ok',
        data: { items: uploaded ? [item] : [], total: uploaded ? 1 : 0, page: 1, size: 20 },
      },
    })
  })
  await page.route('**/api/llm/providers', (route) => route.fulfill({ json: { code: 0, message: 'ok', data: [] } }))

  await page.goto('/resumes')
  await page.locator('input[type=file]').setInputFiles({
    name: 'resume.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('%PDF-1.4'),
  })

  await expect(page.getByText('已上传，点击「开始分析」生成报告；也可先在设置中配置默认模型。')).toBeVisible()
  await expect(page.getByText('resume.pdf')).toBeVisible()
  await expect(page.getByText('待分析')).toBeVisible()
  await expect(page.getByRole('button', { name: '开始分析' })).toBeVisible()
})

test('模型对话页完成一次 SSE 流式回答', async ({ page }) => {
  const session = {
    id: 1,
    title: '测试会话',
    currentProviderKey: 'fake',
    currentModel: 'm',
    status: 'IDLE',
    createdAt: '2026-11-13T10:00:00Z',
    updatedAt: '2026-11-13T10:00:00Z',
  }
  const provider = {
    providerKey: 'fake',
    label: '假上游',
    baseUrl: 'http://fake:3000/v1',
    apiKeyMasked: 'sk-****fake',
    enabled: true,
    sortOrder: 10,
    models: [{ modelName: 'm', label: 'M', enabled: true, isDefault: true, sortOrder: 10 }],
  }
  let sent = false
  const messages = [
    { id: 1, role: 'USER', content: '你好', llmProvider: null, llmModel: null, traceId: null, createdAt: '2026-11-13T10:00:00Z' },
    {
      id: 2,
      role: 'ASSISTANT',
      content: '你好，这是流式回答。',
      llmProvider: 'fake',
      llmModel: 'm',
      traceId: 't-1',
      createdAt: '2026-11-13T10:00:01Z',
    },
  ]

  await page.route('**/api/llm/providers', (route) => route.fulfill({ json: { code: 0, message: 'ok', data: [provider] } }))
  await page.route(/\/api\/chat\/sessions\?page=/, (route) => route.fulfill({
    json: { code: 0, message: 'ok', data: { items: [session], total: 1, page: 1, size: 20 } },
  }))
  await page.route(/\/api\/chat\/sessions\/1$/, (route) => route.fulfill({
    json: { code: 0, message: 'ok', data: session },
  }))
  await page.route(/\/api\/chat\/sessions\/1\/messages$/, async (route) => {
    if (route.request().method() === 'POST') {
      sent = true
      // 延迟返回，先断言页面进入“生成中”再校验最终回答
      await new Promise((resolve) => setTimeout(resolve, 600))
      return route.fulfill({
        status: 200,
        headers: { ...CORS, 'content-type': 'text/event-stream' },
        body: 'data: {"type":"delta","content":"你好，这是流式回答。"}\n\n'
          + 'data: {"type":"done","messageId":2,"providerKey":"fake","model":"m"}\n\n',
      })
    }
    return route.fulfill({ json: { code: 0, message: 'ok', data: sent ? messages : [] } })
  })

  await page.goto('/chat')
  await expect(page.getByText('测试会话').first()).toBeVisible()
  await page.getByPlaceholder('输入问题，Enter 发送，Shift + Enter 换行').fill('你好')
  await page.getByRole('button', { name: '发送' }).click()

  await expect(page.locator('.badge').filter({ hasText: '生成中' })).toBeVisible()
  await expect(page.getByText('你好，这是流式回答。').first()).toBeVisible()
  await expect(page.getByText('fake · m').first()).toBeVisible()
  await expect(page.getByText('空闲')).toBeVisible()
})

test('设置抽屉新增服务商后自动拉取模型并设为全局默认', async ({ page }) => {
  const created = {
    providerKey: 'corp',
    label: '公司内网网关',
    baseUrl: 'http://localhost:3000/v1',
    apiKeyMasked: 'sk-****cret',
    enabled: true,
    sortOrder: 10,
    models: [],
  }
  const model = {
    modelName: 'Qwen3.8-27B',
    label: 'Qwen3.8-27B',
    enabled: true,
    isDefault: true,
    sortOrder: 10,
  }
  let hasProvider = false
  let hasModels = false
  let hasGlobal = false

  await page.route('**/api/llm/providers', async (route) => {
    if (route.request().method() === 'POST') {
      hasProvider = true
      return route.fulfill({ json: { code: 0, message: 'ok', data: created } })
    }
    return route.fulfill({
      json: {
        code: 0,
        message: 'ok',
        data: hasProvider ? [{ ...created, models: hasModels ? [model] : [] }] : [],
      },
    })
  })
  await page.route('**/api/llm/providers/corp/models', async (route) => {
    if (route.request().method() === 'POST') {
      hasModels = true
      return route.fulfill({ json: { code: 0, message: 'ok', data: model } })
    }
    return route.fulfill({
      json: { code: 0, message: 'ok', data: { source: 'UPSTREAM', models: ['Qwen3.8-27B', 'Qwen3.8-Flash'] } },
    })
  })
  await page.route('**/api/llm/global', (route) => {
    if (route.request().method() === 'PUT') {
      hasGlobal = true
    }
    return route.fulfill({
      json: {
        code: 0,
        message: 'ok',
        data: hasGlobal
          ? { defaultProviderKey: 'corp', defaultModel: 'Qwen3.8-27B' }
          : { defaultProviderKey: null, defaultModel: null },
      },
    })
  })

  await page.goto('/resumes')
  await page.getByRole('button', { name: '设置' }).click()
  await page.getByRole('button', { name: '模型服务' }).click()
  await page.getByRole('button', { name: '新增服务商' }).click()
  await page.getByPlaceholder('corp-gateway').fill('corp')
  await page.getByPlaceholder('公司内网网关').fill('公司内网网关')
  await page.getByPlaceholder('http://host:3000/v1').fill('http://localhost:3000/v1')
  await page.getByPlaceholder('sk-...').fill('sk-secret')
  await page.getByRole('button', { name: '保存' }).click()

  // 保存后自动拉取上游模型，并默认勾选第一个
  await expect(page.getByText('上游发现 2 个模型，请勾选要启用的：')).toBeVisible()
  await expect(page.getByRole('checkbox', { name: 'Qwen3.8-27B' })).toBeChecked()

  await page.getByRole('button', { name: '添加所选模型（1）' }).click()

  // 首个模型自动成为全局默认，简历分析可直接使用
  await expect(page.getByText('已添加 1 个模型，并将 Qwen3.8-27B 设为全局默认')).toBeVisible()
  await expect(page.locator('.tag.primary').filter({ hasText: '全局默认' })).toBeVisible()
  await expect(page.locator('.provider').filter({ hasText: '公司内网网关' })).toBeVisible()
})
