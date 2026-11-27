import { expect, test } from '@playwright/test'

/**
 * LLM 工作台 E2E（接口全 mock）：
 * 侧栏入口 → 模型保存确认 → 提示词 diff/恢复内置/未保存拦截/发布/历史 → 调用统计只读。
 */
const envelope = (data: unknown) => ({ code: 0, message: 'ok', data })

const provider = {
  providerKey: 'stub',
  label: '内网网关',
  baseUrl: 'https://example.com/v1',
  apiKeyMasked: 'sk-****test',
  enabled: true,
  sortOrder: 1,
  models: [
    { modelName: 'Qwen3.8-27B', label: 'Qwen3.8-27B', enabled: true, isDefault: true, sortOrder: 1 },
    { modelName: 'AltModel', label: 'AltModel', enabled: true, isDefault: false, sortOrder: 2 },
  ],
}

const resumeSystem = '输出 JSON，字段 technicalDepth。{{resumeText}} {{marketDirections}}'
const resumeUser = '简历：{{resumeText}}\n方向：{{marketDirections}}'

const resumeSummary = {
  key: 'resume-analysis',
  name: '简历分析',
  category: '档案',
  description: '评估简历并生成五维报告',
  outputMode: 'JSON',
  modelScope: 'SCENE',
  scene: 'RESUME_ANALYZE',
  evalScene: 'resume-analysis',
  hasEvalCorpus: true,
  affectedFunctions: ['简历分析报告'],
  placeholders: ['resumeText', 'marketDirections'],
  requiredFields: ['technicalDepth'],
  activeVersion: { versionNo: 0, source: 'BUILTIN', status: 'ACTIVE', contentHash: 'hash-0' },
  builtinVersion: 'resume-analysis-v3',
  hasDraft: false,
  updatedAt: null,
}

const reviewSummary = {
  key: 'review-analyze',
  name: '复盘分析',
  category: '复盘',
  description: '从转写还原问答与行动',
  outputMode: 'TEXT',
  modelScope: 'SCENE',
  scene: 'REVIEW_ANALYZE',
  evalScene: 'review-analyze',
  hasEvalCorpus: true,
  affectedFunctions: ['面试复盘分析'],
  placeholders: ['text'],
  requiredFields: [],
  activeVersion: { versionNo: 0, source: 'BUILTIN', status: 'ACTIVE', contentHash: 'hash-r0' },
  builtinVersion: 'review-analyze-v2',
  hasDraft: false,
  updatedAt: null,
}

const detailOf = (summary: typeof resumeSummary, system: string, user: string) => ({
  ...summary,
  active: { ...summary.activeVersion, systemContent: system, userContent: user },
  builtIn: { ...summary.activeVersion, systemContent: system, userContent: user },
  draft: null,
  validation: { valid: true, errors: [], warnings: [] },
})

const sceneLabels: [string, string][] = [
  ['RESUME_ANALYZE', '简历分析'],
  ['CHAT', '模型对话'],
  ['JOB_MATCH', '岗位匹配'],
  ['INTERVIEW_QUESTION', '面试出题'],
  ['INTERVIEW_EVALUATE', '面试评估'],
  ['OFFER_ANALYZE', 'Offer 分析'],
  ['REVIEW_ANALYZE', '复盘分析'],
]

const emptyUsage = {
  days: 30,
  generatedAt: '2026-11-27T10:00:00Z',
  summary: {
    calls: 0,
    successCalls: 0,
    successRate: null,
    promptTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
    avgDurationMs: 0,
  },
  scenes: [],
  models: [],
  daily: [],
}

test('LLM 工作台：模型保存确认、提示词编辑/拦截/发布、调用统计只读', async ({ page }) => {
  const dialogs: string[] = []
  page.on('dialog', async (dialog) => {
    dialogs.push(dialog.message())
    await dialog.accept()
  })

  let globalSetting = { defaultProviderKey: 'stub', defaultModel: 'Qwen3.8-27B' }
  let resumeDetail = detailOf(resumeSummary, resumeSystem, resumeUser)
  const reviewDetail = detailOf(reviewSummary, '复盘 system：{{text}}', '转写：{{text}}')
  let versions: Record<string, unknown>[] = []
  const sceneBindings = sceneLabels.map(([scene, label]) => ({
    scene,
    label,
    providerKey: null,
    model: null,
    inherited: true,
    effectiveProviderKey: 'stub',
    effectiveModel: 'Qwen3.8-27B',
  }))

  const toSummary = (detail: typeof resumeDetail) => ({
    ...detail,
    activeVersion: detail.activeVersion,
    hasDraft: detail.hasDraft,
  })

  await page.route('**/api/llm/providers', (route) => route.fulfill({ json: envelope([provider]) }))
  await page.route(/\/api\/llm\/global$/, (route) => {
    if (route.request().method() === 'PUT') {
      globalSetting = route.request().postDataJSON()
    }
    route.fulfill({ json: envelope(globalSetting) })
  })
  await page.route(/\/api\/llm\/scenes(\/.*)?$/, (route) => {
    const scene = route.request().url().split('/').pop() ?? ''
    const binding = sceneBindings.find((item) => item.scene === scene)
    if (route.request().method() === 'DELETE') {
      if (binding) {
        binding.providerKey = null
        binding.model = null
        binding.inherited = true
      }
      return route.fulfill({ json: envelope(binding) })
    }
    if (route.request().method() === 'PUT') {
      const body = route.request().postDataJSON() as { providerKey: string; model: string }
      if (binding) {
        binding.providerKey = body.providerKey
        binding.model = body.model
        binding.inherited = false
        binding.effectiveProviderKey = body.providerKey
        binding.effectiveModel = body.model
      }
      return route.fulfill({ json: envelope(binding) })
    }
    return route.fulfill({ json: envelope(sceneBindings) })
  })
  await page.route('**/api/llm/usage**', (route) => route.fulfill({ json: envelope(emptyUsage) }))

  await page.route(/\/api\/llm\/prompts(\/.*)?$/, (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    if (path === '/api/llm/prompts') {
      return route.fulfill({ json: envelope([toSummary(resumeDetail), toSummary(reviewDetail)]) })
    }
    if (path.endsWith('/resume-analysis') && method === 'GET') {
      return route.fulfill({ json: envelope(resumeDetail) })
    }
    if (path.endsWith('/review-analyze') && method === 'GET') {
      return route.fulfill({ json: envelope(reviewDetail) })
    }
    if (path.endsWith('/resume-analysis/draft') && method === 'PUT') {
      const body = route.request().postDataJSON() as { systemContent: string; userContent: string; note?: string }
      resumeDetail = {
        ...resumeDetail,
        hasDraft: true,
        draft: {
          versionNo: 1,
          source: 'DB',
          status: 'DRAFT',
          contentHash: 'hash-1',
          note: body.note ?? null,
          systemContent: body.systemContent,
          userContent: body.userContent,
        },
        validation: { valid: true, errors: [], warnings: ['存在未发布草稿；发布后才会生效'] },
      }
      return route.fulfill({ json: envelope(resumeDetail) })
    }
    if (path.endsWith('/resume-analysis/publish') && method === 'POST') {
      const draft = resumeDetail.draft
      if (!draft) {
        return route.fulfill({ status: 400, json: { code: 40001, message: '没有可发布的草稿' } })
      }
      resumeDetail = {
        ...resumeDetail,
        activeVersion: { ...draft, status: 'ACTIVE', versionNo: 1 },
        active: draft,
        draft: null,
        hasDraft: false,
        validation: { valid: true, errors: [], warnings: [] },
      }
      versions = [{
        versionNo: 1,
        source: 'DB',
        status: 'ACTIVE',
        contentHash: 'hash-1',
        note: '测试发布',
        createdAt: '2026-11-27T10:00:00Z',
        publishedAt: '2026-11-27T10:00:00Z',
      }]
      return route.fulfill({ json: envelope(resumeDetail) })
    }
    if (path.endsWith('/resume-analysis/versions') && method === 'GET') {
      return route.fulfill({
        json: envelope({ items: versions, total: versions.length, page: 1, size: 20 }),
      })
    }
    if (path.endsWith('/resume-analysis/versions/1') && method === 'GET') {
      return route.fulfill({ json: envelope({ ...versions[0], systemContent: resumeSystem, userContent: resumeUser }) })
    }
    if (path.endsWith('/resume-analysis/versions/1/activate') && method === 'POST') {
      return route.fulfill({ json: envelope(resumeDetail) })
    }
    return route.fulfill({ status: 404, json: { code: 40401, message: 'not mocked' } })
  })

  await page.goto('/llm-workbench')
  await expect(page.getByRole('heading', { name: 'LLM 工作台' })).toBeVisible()
  await expect(page.getByRole('button', { name: '模型与绑定' })).toBeVisible()

  // 模型与绑定：改全局模型 → 保存前二次确认
  await expect(page.getByRole('heading', { name: '全局默认' })).toBeVisible()
  await page.getByLabel('全局模型').selectOption('AltModel')
  await page.getByRole('button', { name: '保存修改' }).click()
  await expect(page.getByText('模型配置已保存', { exact: false })).toBeVisible()
  expect(dialogs.some((message) => message.includes('全局默认'))).toBeTruthy()

  // 提示词：恢复内置、编辑、diff、未保存拦截
  await page.getByRole('button', { name: '提示词' }).click()
  await page.getByRole('button', { name: /简历分析/ }).click()
  await expect(page.getByRole('heading', { name: '简历分析' })).toBeVisible()
  await page.getByRole('button', { name: '恢复内置默认' }).click()
  await expect(page.getByText('已填入编辑器', { exact: false })).toBeVisible()

  const systemEditor = page.getByLabel('system 模板')
  await systemEditor.fill(`${await systemEditor.inputValue()}\n补充约束`)
  await expect(page.getByText('未保存', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '与当前生效对比' }).click()
  await expect(page.getByText('编辑中').first()).toBeVisible()

  await page.getByRole('button', { name: /复盘分析/ }).click()
  await expect(page.getByRole('dialog', { name: '未保存修改' })).toBeVisible()
  await page.getByRole('button', { name: '取消' }).click()
  await expect(page.getByRole('heading', { name: '简历分析' })).toBeVisible()
  await page.getByRole('button', { name: /复盘分析/ }).click()
  await page.getByRole('button', { name: '放弃修改' }).click()
  await expect(page.getByRole('heading', { name: '复盘分析' })).toBeVisible()

  // 回到简历分析，发布后查看版本历史
  await page.getByRole('button', { name: /简历分析/ }).click()
  await expect(page.getByRole('heading', { name: '简历分析' })).toBeVisible()
  await systemEditor.fill(`${await systemEditor.inputValue()}\n再次调整`)
  await page.getByRole('button', { name: '发布' }).click()
  await expect(page.getByText('发布成功', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: '版本历史' }).click()
  await expect(page.getByText('生效中', { exact: false })).toBeVisible()

  // 调用统计：只读空态
  await page.getByRole('button', { name: '调用统计' }).click()
  await expect(page.getByText('近 30 天还没有模型调用记录', { exact: false })).toBeVisible()
})
