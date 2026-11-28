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

/**
 * 评测运行 E2E（接口全 mock）：
 * 未配置模型场景禁用 → 勾选简历 → 二次确认开始 → 进度 60% → 取消确认后继续 →
 * 完成详情展示 L1/L2、模型/提示词快照与逐用例结果。
 */
test('评测运行：模型缺失禁用、手动开始、进度、取消确认与完成详情', async ({ page }) => {
  const dialogs: string[] = []
  page.on('dialog', async (dialog) => {
    dialogs.push(dialog.message())
    await dialog.accept()
  })

  const evalScenes = [
    {
      scene: 'resume-analysis',
      name: '简历分析',
      caseCount: 10,
      corpusHash: 'hash-resume',
      promptKey: 'resume-analysis',
      promptVersion: 'resume-analysis-v3',
      modelScope: 'SCENE',
      providerKey: 'stub',
      model: 'Qwen3.8-27B',
      currentBaseline: null,
      lastRun: null,
      rebuildReasons: [],
    },
    {
      scene: 'jd-parse',
      name: 'JD 解析',
      caseCount: 9,
      corpusHash: 'hash-jd',
      promptKey: 'job-position-parse',
      promptVersion: 'jd-parse-v1',
      modelScope: 'GLOBAL',
      providerKey: null,
      model: null,
      currentBaseline: null,
      lastRun: null,
      rebuildReasons: [],
    },
    {
      scene: 'review-analyze',
      name: '复盘分析',
      caseCount: 8,
      corpusHash: 'hash-review',
      promptKey: 'review-analyze',
      promptVersion: 'review-analyze-v2',
      modelScope: 'SCENE',
      providerKey: 'stub',
      model: 'Qwen3.8-27B',
      currentBaseline: null,
      lastRun: null,
      rebuildReasons: [],
    },
  ]

  const baseRun = {
    id: 7,
    status: 'PENDING',
    scenes: ['resume-analysis'],
    totalCases: 10,
    completedCases: 0,
    errorCases: 0,
    error: null,
    createdAt: '2026-11-27T10:00:00Z',
    startedAt: null,
    finishedAt: null,
  }

  const cases = [
    { scene: 'resume-analysis', caseId: '001-backend-java-ai', status: 'PASS', l1Passed: true, l2Passed: true, l3Passed: null, results: [], scores: {}, tokens: 3000, elapsedMs: 12000, error: null },
    { scene: 'resume-analysis', caseId: '002-ai-rag-platform', status: 'FAIL', l1Passed: false, l2Passed: false, l3Passed: null, results: [], scores: {}, tokens: 2800, elapsedMs: 15000, error: 'L1:quotesMustComeFromInput' },
    { scene: 'resume-analysis', caseId: '003-newgrad-java-intern', status: 'ERROR', l1Passed: false, l2Passed: false, l3Passed: null, results: [], scores: {}, tokens: null, elapsedMs: 30000, error: '上游服务返回 HTTP 502' },
  ]

  const completedRun = {
    ...baseRun,
    status: 'COMPLETED',
    completedCases: 10,
    errorCases: 1,
    startedAt: '2026-11-27T10:00:01Z',
    finishedAt: '2026-11-27T10:03:00Z',
    cases,
    models: [{ scene: 'resume-analysis', providerKey: 'stub', model: 'Qwen3.8-27B' }],
    prompts: [{ scene: 'resume-analysis', promptKey: 'resume-analysis', promptVersion: 'resume-analysis-v3', promptHash: 'hash' }],
    metrics: {
      l1Total: 10,
      l1Failed: 1,
      l1PassRate: 0.9,
      l2Total: 45,
      l2Failed: 2,
      l2PassRate: 0.9556,
      totalTokens: 30000,
      avgTokens: 3000,
      avgElapsedMs: 12000,
      p95ElapsedMs: 25000,
    },
  }

  const processingRun = {
    ...baseRun,
    status: 'PROCESSING',
    completedCases: 6,
    startedAt: '2026-11-27T10:00:01Z',
    cases: [],
    models: [{ scene: 'resume-analysis', providerKey: 'stub', model: 'Qwen3.8-27B' }],
    prompts: [{ scene: 'resume-analysis', promptKey: 'resume-analysis', promptVersion: 'resume-analysis-v3', promptHash: 'hash' }],
    metrics: null,
  }

  let created = false
  let polls = 0
  let runs: unknown[] = []

  await page.route('**/api/llm/providers', (route) => route.fulfill({ json: envelope([provider]) }))
  await page.route(/\/api\/llm\/global$/, (route) =>
    route.fulfill({ json: envelope({ defaultProviderKey: 'stub', defaultModel: 'Qwen3.8-27B' }) }))
  await page.route(/\/api\/llm\/scenes(\/.*)?$/, (route) =>
    route.fulfill({ json: envelope(sceneLabels.map(([scene, label]) => ({
      scene,
      label,
      providerKey: null,
      model: null,
      inherited: true,
      effectiveProviderKey: 'stub',
      effectiveModel: 'Qwen3.8-27B',
    }))) }))
  await page.route('**/api/llm/usage**', (route) => route.fulfill({ json: envelope(emptyUsage) }))
  await page.route(/\/api\/llm\/prompts(\/.*)?$/, (route) => route.fulfill({
    json: envelope([toSummaryOf(detailOf(resumeSummary, resumeSystem, resumeUser)), toSummaryOf(detailOf(reviewSummary, '复盘', '转写'))]),
  }))

  await page.route('**/api/llm/eval/runs**', (route) => {
    const url = new URL(route.request().url())
    const method = route.request().method()
    if (url.pathname === '/api/llm/eval/runs' && method === 'POST') {
      created = true
      runs = [baseRun]
      return route.fulfill({ json: envelope(baseRun) })
    }
    if (url.pathname === '/api/llm/eval/runs' && method === 'GET') {
      return route.fulfill({ json: envelope({ items: runs, total: runs.length, page: 1, size: 20 }) })
    }
    if (url.pathname.endsWith('/cancel')) {
      return route.fulfill({ json: envelope({ ...processingRun, status: 'CANCELLED' }) })
    }
    if (url.pathname.endsWith('/7')) {
      polls += 1
      const run = polls === 1 ? processingRun : completedRun
      runs = [run]
      return route.fulfill({ json: envelope(run) })
    }
    return route.fulfill({ status: 404, json: { code: 40401, message: 'not mocked' } })
  })
  await page.route('**/api/llm/eval/scenes', (route) => route.fulfill({ json: envelope(evalScenes) }))

  await page.goto('/llm-workbench')
  await page.getByRole('button', { name: '评测运行' }).click()

  await expect(page.getByRole('heading', { name: '选择场景' })).toBeVisible()
  await expect(page.getByLabel('选择JD 解析')).toBeDisabled()
  await expect(page.getByText('未配置模型').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '去配置' }).first()).toBeVisible()

  await page.getByLabel('选择简历分析').check()
  await expect(page.getByText('1 个场景 · 10 例')).toBeVisible()
  await page.getByRole('button', { name: '开始运行' }).click()
  await page.getByRole('dialog', { name: '开始评测' }).getByRole('button', { name: '开始运行' }).click()
  await expect.poll(() => created).toBe(true)

  await expect(page.getByText('6 / 10 例 · 60%')).toBeVisible({ timeout: 8000 })
  await page.getByRole('button', { name: '取消运行' }).click()
  await expect(page.getByRole('dialog', { name: '取消评测' })).toBeVisible()
  await page.getByRole('button', { name: '再想想' }).click()

  await expect(page.getByText('已完成', { exact: false }).first()).toBeVisible({ timeout: 10000 })
  await expect(page.getByText('L1 通过率')).toBeVisible()
  await expect(page.getByText('90.0%')).toBeVisible()
  await expect(page.getByText('模型快照')).toBeVisible()
  await expect(page.getByText('001-backend-java-ai')).toBeVisible()
  await expect(page.getByText('002-ai-rag-platform')).toBeVisible()
  await expect(page.getByRole('button', { name: '设为基线' })).toBeDisabled()
})

function toSummaryOf(detail: ReturnType<typeof detailOf>) {
  return { ...detail, activeVersion: detail.activeVersion, hasDraft: detail.hasDraft }
}

/**
 * 基线与对比 E2E（接口全 mock）：
 * 发布后「基线已过期」→ 去评测只预选受影响场景 → 设为基线（不重跑）→
 * 基线对比四维指标与用例变化 → 设为当前只切换标记。
 */
test('基线对比：过期徽标、去评测预选、设为基线与四维对比', async ({ page }) => {
  const resumeDetail = {
    ...detailOf(resumeSummary, resumeSystem, resumeUser),
    suggestedEvalScenes: [{
      scene: 'resume-analysis',
      caseCount: 10,
      reasons: ['提示词已更新'],
      currentBaselineId: 3,
    }],
  }
  const runSummary = (id: number, partial = false) => ({
    id,
    status: 'COMPLETED',
    scenes: ['resume-analysis'],
    totalCases: 10,
    completedCases: 10,
    errorCases: 0,
    partial,
    error: null,
    createdAt: '2026-11-27T10:00:00Z',
    startedAt: '2026-11-27T10:00:01Z',
    finishedAt: '2026-11-27T10:03:00Z',
  })
  const runDetail = {
    ...runSummary(8),
    cases: [],
    models: [{ scene: 'resume-analysis', providerKey: 'stub', model: 'Qwen3.8-27B' }],
    prompts: [{ scene: 'resume-analysis', promptKey: 'resume-analysis', promptVersion: 'resume-analysis-v3', promptHash: 'hash' }],
    metrics: { l1Total: 10, l1Failed: 1, l1PassRate: 0.9, l2Total: 45, l2Failed: 2, l2PassRate: 0.9556, totalTokens: 31000, avgTokens: 3100, avgElapsedMs: 11000, p95ElapsedMs: 24000 },
  }
  const baseline = (id: number, runId: number, label: string, current: boolean) => ({
    id,
    scene: 'resume-analysis',
    runId,
    label,
    providerKey: 'stub',
    model: 'Qwen3.8-27B',
    promptVersion: 'resume-analysis-v2',
    promptHash: 'old',
    corpusHash: 'hash-resume',
    engineVersion: 'llm-eval-v1',
    params: {},
    metrics: { l1Total: 10, l1Failed: 2, l1PassRate: 0.8, l2Total: 45, l2Failed: 5, l2PassRate: 0.8889, totalTokens: 30000, avgTokens: 3000, avgElapsedMs: 12000, p95ElapsedMs: 25000 },
    current,
    createdAt: '2026-11-27T10:00:00Z',
  })
  let baselines = [baseline(3, 7, 'v3 基线', true), baseline(4, 8, '复测', false)]
  const evalScenes = [{
    scene: 'resume-analysis',
    name: '简历分析',
    caseCount: 10,
    corpusHash: 'hash-resume',
    promptKey: 'resume-analysis',
    promptVersion: 'resume-analysis-v3',
    modelScope: 'SCENE',
    providerKey: 'stub',
    model: 'Qwen3.8-27B',
    currentBaseline: baselines[0],
    lastRun: runSummary(8),
    rebuildReasons: ['提示词已更新'],
  }]
  const compareData = {
    comparable: true,
    reasons: [],
    warnings: [],
    applicableDimensions: ['QUALITY', 'SCORE_DRIFT', 'COST', 'LATENCY'],
    scene: 'resume-analysis',
    left: {
      runId: 7,
      scene: 'resume-analysis',
      providerKey: 'stub',
      model: 'Qwen3.8-27B',
      promptVersion: 'resume-analysis-v2',
      promptHash: 'old',
      corpusHash: 'hash-resume',
      engineVersion: 'llm-eval-v1',
      params: {},
      metrics: baselines[0].metrics,
      baselineId: 3,
      baselineLabel: 'v3 基线',
    },
    right: {
      runId: 8,
      scene: 'resume-analysis',
      providerKey: 'stub',
      model: 'Qwen3.8-27B',
      promptVersion: 'resume-analysis-v3',
      promptHash: 'new',
      corpusHash: 'hash-resume',
      engineVersion: 'llm-eval-v1',
      params: {},
      metrics: runDetail.metrics,
      baselineId: null,
      baselineLabel: null,
    },
    quality: { l1PassRate: 0.8, l2PassRate: 0.8889, l1Delta: 0.1, l2Delta: 0.0667, l1Failed: 1, l2Failed: 2 },
    regression: { fixedCases: 1, newFailedCases: 0, scoreDriftCases: 0, tagChangedCases: 0 },
    cost: { leftTokens: 30000, rightTokens: 31000, delta: 1000, leftAvgTokens: 3000, rightAvgTokens: 3100 },
    latency: { leftAvgMs: 12000, rightAvgMs: 11000, avgDelta: -1000, leftP95Ms: 25000, rightP95Ms: 24000, p95Delta: -1000 },
    cases: [{
      caseId: '001-backend-java-ai',
      leftStatus: 'FAIL',
      rightStatus: 'PASS',
      changes: ['FIXED'],
      scoreDrift: 5,
      tagJaccard: null,
      details: 'FAIL → PASS，已修复',
    }],
  }

  await page.route('**/api/llm/providers', (route) => route.fulfill({ json: envelope([provider]) }))
  await page.route(/\/api\/llm\/global$/, (route) =>
    route.fulfill({ json: envelope({ defaultProviderKey: 'stub', defaultModel: 'Qwen3.8-27B' }) }))
  await page.route(/\/api\/llm\/scenes(\/.*)?$/, (route) => route.fulfill({ json: envelope([]) }))
  await page.route('**/api/llm/usage**', (route) => route.fulfill({ json: envelope(emptyUsage) }))
  await page.route(/\/api\/llm\/prompts(\/.*)?$/, (route) => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/llm/prompts') {
      return route.fulfill({ json: envelope([toSummaryOf(resumeDetail)]) })
    }
    return route.fulfill({ json: envelope(resumeDetail) })
  })
  await page.route('**/api/llm/eval/scenes', (route) => route.fulfill({ json: envelope(evalScenes) }))
  await page.route('**/api/llm/eval/runs**', (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/llm/eval/runs') {
      return route.fulfill({ json: envelope({ items: [runSummary(8), runSummary(7)], total: 2, page: 1, size: 50 }) })
    }
    if (url.pathname.endsWith('/8')) {
      return route.fulfill({ json: envelope(runDetail) })
    }
    if (url.pathname.endsWith('/7')) {
      return route.fulfill({ json: envelope({ ...runDetail, id: 7 }) })
    }
    return route.fulfill({ json: envelope(runDetail) })
  })
  await page.route(/\/api\/llm\/eval\/runs\/compare.*/, (route) => route.fulfill({ json: envelope(compareData) }))
  await page.route('**/api/llm/eval/baselines**', (route) => {
    const url = new URL(route.request().url())
    if (route.request().method() === 'POST' && url.pathname.endsWith('/activate')) {
      const id = Number(url.pathname.split('/').at(-2))
      baselines = baselines.map((item) => ({ ...item, current: item.id === id }))
      return route.fulfill({ json: envelope(baselines.find((item) => item.id === id)) })
    }
    if (route.request().method() === 'POST') {
      const payload = route.request().postDataJSON() as { runId?: number; label?: string | null }
      const target = baselines.find((item) => item.scene === 'resume-analysis' && item.runId === payload.runId)
      const baselineId = target?.id ?? 5
      if (target) {
        baselines = baselines.map((item) => item.id === target.id
          ? { ...item, label: payload.label || '', current: true }
          : { ...item, current: false })
      } else {
        baselines = [
          ...baselines.map((item) => ({ ...item, current: false })),
          baseline(5, payload.runId ?? 8, payload.label || '', true),
        ]
      }
      return route.fulfill({ json: envelope([{ scene: 'resume-analysis', created: true, baselineId, reason: null }]) })
    }
    return route.fulfill({ json: envelope({ items: baselines, total: baselines.length, page: 1, size: 20 }) })
  })

  await page.goto('/llm-workbench?tab=prompts')
  await expect(page.getByText('基线已过期', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '去评测' }).click()
  await expect(page.getByRole('heading', { name: '选择场景' })).toBeVisible()
  await expect(page.getByLabel('选择简历分析')).toBeChecked()
  await expect(page.getByText('1 个场景 · 10 例')).toBeVisible()

  await page.locator('tr.clickable').nth(1).click()
  await page.getByRole('button', { name: '设为基线' }).click()
  await page.getByLabel('基线备注').fill('v3 复测')
  await page.getByRole('dialog', { name: '设为基线' }).getByRole('button', { name: '设为基线' }).click()
  await expect(page.getByText('已设为基线', { exact: false })).toBeVisible()

  await page.getByRole('button', { name: '基线对比' }).click()
  await expect(page.getByRole('heading', { name: '选择对比' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'v3 复测', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '开始对比' }).click()
  await expect(page.getByText('修复 1')).toBeVisible()
  await expect(page.getByText('80.0% → 90.0%')).toBeVisible()
  await expect(page.getByText('001-backend-java-ai')).toBeVisible()
  await expect(page.getByText('FAIL → PASS，已修复')).toBeVisible()
  await page.getByRole('button', { name: '设为当前' }).last().click()
  await expect(page.getByText('已将「复测」设为当前基线')).toBeVisible()
})

/** 无基线 / 不可比：只显示原因，不伪造指标。 */
test('基线对比：尚无基线与不可比原因', async ({ page }) => {
  const runs = [8, 7].map((id) => ({
    id,
    status: 'COMPLETED',
    scenes: ['resume-analysis'],
    totalCases: 10,
    completedCases: 10,
    errorCases: 0,
    partial: false,
    error: null,
    createdAt: '2026-11-27T10:00:00Z',
    startedAt: '2026-11-27T10:00:01Z',
    finishedAt: '2026-11-27T10:03:00Z',
  }))
  const scenes = [{
    scene: 'resume-analysis',
    name: '简历分析',
    caseCount: 10,
    corpusHash: 'hash-resume',
    promptKey: 'resume-analysis',
    promptVersion: 'resume-analysis-v3',
    modelScope: 'SCENE',
    providerKey: 'stub',
    model: 'Qwen3.8-27B',
    currentBaseline: null,
    lastRun: runs[0],
    rebuildReasons: ['尚无基线'],
  }]

  await page.route('**/api/llm/providers', (route) => route.fulfill({ json: envelope([provider]) }))
  await page.route(/\/api\/llm\/global$/, (route) =>
    route.fulfill({ json: envelope({ defaultProviderKey: 'stub', defaultModel: 'Qwen3.8-27B' }) }))
  await page.route(/\/api\/llm\/scenes(\/.*)?$/, (route) => route.fulfill({ json: envelope([]) }))
  await page.route('**/api/llm/usage**', (route) => route.fulfill({ json: envelope(emptyUsage) }))
  await page.route(/\/api\/llm\/prompts(\/.*)?$/, (route) =>
    route.fulfill({ json: envelope([toSummaryOf(detailOf(resumeSummary, resumeSystem, resumeUser))]) }))
  await page.route('**/api/llm/eval/scenes', (route) => route.fulfill({ json: envelope(scenes) }))
  await page.route('**/api/llm/eval/runs**', (route) => {
    const url = new URL(route.request().url())
    return route.fulfill({ json: envelope({ items: runs, total: runs.length, page: 1, size: 50 }) })
  })
  await page.route(/\/api\/llm\/eval\/runs\/compare.*/, (route) =>
    route.fulfill({ json: envelope({
      comparable: false,
      reasons: ['场景集合不同：["resume-analysis"] vs ["jd-parse"]'],
      warnings: [],
      applicableDimensions: [],
      scene: 'resume-analysis',
      left: { runId: 7, scene: 'resume-analysis', providerKey: 'stub', model: 'Qwen3.8-27B', promptVersion: 'v3', promptHash: 'h', corpusHash: 'c', engineVersion: 'llm-eval-v1', params: {}, metrics: {}, baselineId: null, baselineLabel: null },
      right: { runId: 8, scene: 'resume-analysis', providerKey: 'stub', model: 'Qwen3.8-27B', promptVersion: 'v3', promptHash: 'h', corpusHash: 'c', engineVersion: 'llm-eval-v1', params: {}, metrics: {}, baselineId: null, baselineLabel: null },
      quality: null,
      regression: null,
      cost: null,
      latency: null,
      cases: [],
    }) }))
  await page.route('**/api/llm/eval/baselines**', (route) =>
    route.fulfill({ json: envelope({ items: [], total: 0, page: 1, size: 20 }) }))

  await page.goto('/llm-workbench?tab=baseline')
  await expect(page.getByRole('heading', { name: '选择对比' })).toBeVisible()
  await expect(page.getByText('尚无基线', { exact: false })).toBeVisible()
  await page.getByLabel('左侧运行').selectOption('7')
  await page.getByRole('button', { name: '开始对比' }).click()
  await expect(page.getByText('不可比：场景集合不同', { exact: false })).toBeVisible()
})

