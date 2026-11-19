import { expect, test } from '@playwright/test'

/**
 * M2 第二批前端 E2E（接口全 mock）：模拟面试全流程与 Offer 决策主链路。
 */

const ok = (data: unknown) => ({ json: { code: 0, message: 'ok', data } })

test('模拟面试：创建 → 出题轮询 → 切换模型 → 作答 / 跳过 → 交卷 → 评估报告', async ({ page }) => {
  const now = '2026-11-19T10:00:00Z'
  let stage = 0
  let detailCalls = 0
  let answered = false
  let skipped = false
  let submitted = false
  let evaluationCompleted = false
  let currentModel = 'm'
  let createPayload: Record<string, unknown> | null = null
  let switchPayload: { providerKey: string; model: string } | null = null
  let answerPayload: { answer: string } | null = null
  let skipCalled = false
  let submitCalled = false

  const session = () => ({
    id: 1,
    direction: 'java-backend',
    directionLabel: 'Java 后端',
    resumeId: null,
    resumeFileName: null,
    jobPositionId: null,
    jobPositionTitle: null,
    companyName: null,
    status: submitted ? 'SUBMITTED' : stage === 0 ? 'PREPARING' : 'IN_PROGRESS',
    evaluateStatus: evaluationCompleted ? 'COMPLETED' : submitted ? 'PROCESSING' : 'PENDING',
    questionCount: 8,
    followUpCount: 1,
    answeredCount: (answered ? 1 : 0) + (skipped ? 1 : 0),
    currentProviderKey: 'stub',
    currentModel,
    createdAt: now,
    updatedAt: now,
  })

  const questions = () => {
    if (stage === 0) {
      return []
    }
    return [
      {
        questionIndex: 1,
        parentIndex: null,
        type: 'MAIN',
        content: '请说明 JDK、JVM 与 JRE 的区别。',
        topicSummary: 'Java 基础',
        difficulty: 'EASY',
        answerStatus: answered ? 'ANSWERED' : 'UNANSWERED',
        answer: answered ? 'JDK 是开发工具包，JRE 是运行环境，JVM 负责执行字节码。' : null,
        score: evaluationCompleted ? 80 : null,
        feedback: evaluationCompleted ? '回答覆盖了核心区别。' : null,
        referenceAnswer: evaluationCompleted ? '参考：JDK ⊃ JRE ⊃ JVM。' : null,
        keyPoints: ['JDK 包含 JRE', 'JVM 执行字节码'],
        llmProvider: 'stub',
        llmModel: currentModel,
      },
      {
        questionIndex: 2,
        parentIndex: null,
        type: 'MAIN',
        content: 'Redis 缓存一致性怎么保证？',
        topicSummary: '缓存一致性',
        difficulty: 'MEDIUM',
        answerStatus: skipped ? 'SKIPPED' : 'UNANSWERED',
        answer: null,
        score: evaluationCompleted ? 0 : null,
        feedback: null,
        referenceAnswer: null,
        keyPoints: ['延迟双删', '最终一致'],
        llmProvider: 'stub',
        llmModel: currentModel,
      },
    ]
  }

  await page.route('**/api/interview/directions', (route) =>
    route.fulfill(
      ok([
        { key: 'java-backend', label: 'Java 后端', description: 'Java 基础与框架' },
        { key: 'ai-agent', label: 'AI Agent 工程', description: 'Agent 与工具调用' },
      ]),
    ),
  )
  await page.route(/\/api\/resumes\?page=/, (route) =>
    route.fulfill(ok({ items: [], total: 0, page: 1, size: 100 })),
  )
  await page.route(/\/api\/job-positions\?/, (route) =>
    route.fulfill(ok({ items: [], total: 0, page: 1, size: 100 })),
  )
  await page.route('**/api/llm/providers', (route) =>
    route.fulfill(
      ok([
        {
          providerKey: 'stub',
          label: '假上游',
          baseUrl: 'http://fake:3000/v1',
          apiKeyMasked: 'sk-****fake',
          enabled: true,
          sortOrder: 10,
          models: [
            { modelName: 'm', label: '模型 M', enabled: true, isDefault: true, sortOrder: 10 },
            { modelName: 'n', label: '模型 N', enabled: true, isDefault: false, sortOrder: 20 },
          ],
        },
      ]),
    ),
  )
  await page.route(/\/api\/interview-sessions(\?|$)/, (route) => {
    if (route.request().method() === 'POST') {
      createPayload = route.request().postDataJSON() as Record<string, unknown>
      stage = 0
      return route.fulfill(ok(session()))
    }
    return route.fulfill(ok({ items: [], total: 0, page: 1, size: 20 }))
  })
  await page.route('**/api/interview-sessions/1/questions/1/answer', (route) => {
    answerPayload = route.request().postDataJSON() as { answer: string }
    answered = true
    return route.fulfill(ok(questions()[0]))
  })
  await page.route('**/api/interview-sessions/1/questions/2/skip', (route) => {
    skipCalled = true
    skipped = true
    return route.fulfill(ok(questions()[1]))
  })
  await page.route('**/api/interview-sessions/1/submit', (route) => {
    submitCalled = true
    submitted = true
    stage = 2
    return route.fulfill(ok(session()))
  })
  await page.route('**/api/interview-sessions/1/model', (route) => {
    switchPayload = route.request().postDataJSON() as { providerKey: string; model: string }
    currentModel = switchPayload.model
    return route.fulfill(ok(session()))
  })
  await page.route('**/api/interview-sessions/1/evaluation', (route) => {
    evaluationCompleted = true
    return route.fulfill(
      ok({
        status: 'COMPLETED',
        error: null,
        retryCount: 0,
        overallScore: 76,
        categories: [
          { key: 'java-basics', label: 'Java 基础', score: 80 },
          { key: 'concurrency-jvm', label: '并发与 JVM', score: 70 },
        ],
        strengths: ['基础概念清晰'],
        improvements: ['补充线上问题处理经验'],
        summary: '整体达到中级后端水平。',
        llmProvider: 'stub',
        llmModel: currentModel,
        evaluatedAt: now,
      }),
    )
  })
  await page.route('**/api/interview-sessions/1', (route) => {
    const first = detailCalls === 0
    detailCalls += 1
    if (!first) {
      stage = Math.max(stage, 1)
    }
    const base = session()
    if (evaluationCompleted) {
      base.status = 'COMPLETED'
    }
    return route.fulfill(ok({
      ...base,
      error: null,
      retryCount: 0,
      evaluateError: null,
      evaluateRetryCount: 0,
      questions: first ? [] : questions(),
    }))
  })

  await page.goto('/interviews')
  await expect(page.getByText('还没有模拟面试')).toBeVisible()
  await page.locator('.page-head').getByRole('button', { name: '新建面试' }).click()
  await page.getByRole('button', { name: '开始面试' }).click()

  await page.waitForURL('**/interviews/1')
  await expect(page.getByText('正在生成题目…')).toBeVisible()
  await expect(page.getByText('请说明 JDK、JVM 与 JRE 的区别。')).toBeVisible()
  await expect.poll(() => createPayload?.direction).toBe('java-backend')

  // 会话内两级切换
  await page.locator('.model-switch select').nth(1).selectOption('n')
  await page.getByRole('button', { name: '切换模型' }).click()
  await expect.poll(() => switchPayload).toEqual({ providerKey: 'stub', model: 'n' })
  await expect(page.getByText('会话模型已切换，只影响后续出题、追问与评估')).toBeVisible()

  // 作答第 1 题
  await page.locator('.answer-box').fill('JDK 是开发工具包，JRE 是运行环境，JVM 负责执行字节码。')
  await page.getByRole('button', { name: '提交作答' }).click()
  await expect.poll(() => answerPayload?.answer).toContain('JVM')
  await expect(page.getByText('Redis 缓存一致性怎么保证？')).toBeVisible()

  // 跳过第 2 题
  await page.getByRole('button', { name: '跳过本题' }).click()
  await page.getByRole('button', { name: '确认跳过' }).click()
  await expect.poll(() => skipCalled).toBe(true)
  await expect(page.getByText('所有题目都已处理')).toBeVisible()

  // 交卷 → 评估报告
  await page.getByRole('button', { name: '交卷并评估' }).click()
  await page.getByRole('button', { name: '确认交卷' }).click()
  await expect.poll(() => submitCalled).toBe(true)
  await expect(page.getByText('评估完成')).toBeVisible()
  await expect(page.getByText('76')).toBeVisible()
  await expect(page.getByText('整体达到中级后端水平。')).toBeVisible()
  await expect(page.getByText('基础概念清晰')).toBeVisible()
  await expect(page.getByText('80 分')).toBeVisible()
})

test('Offer 决策：对比矩阵、新增、文本提取与分析', async ({ page }) => {
  const now = '2026-11-19T10:00:00Z'
  const offers = [
    {
      id: 1,
      companyId: 1,
      companyName: '字节跳动',
      jobPositionId: 1,
      jobPositionTitle: 'Java 后端开发工程师',
      monthlySalary: 30000,
      baseSalary: 24000,
      performanceRatio: 20,
      socialInsuranceBase: '12000',
      socialInsuranceRatio: '10.5%',
      housingFundBase: '12000',
      housingFundRatio: '12%',
      twoDayWeekend: true,
      workHours: '10:00-19:00',
      benefits: ['餐补'],
      allowances: ['房补'],
      annualBonus: '3 个月',
      quarterlyBonus: null,
      remark: '首选',
      group: 'KEEP',
      createdAt: now,
      updatedAt: now,
    },
    {
      id: 2,
      companyId: 2,
      companyName: '腾讯',
      jobPositionId: null,
      jobPositionTitle: 'Go 后端工程师',
      monthlySalary: 28000,
      baseSalary: null,
      performanceRatio: null,
      socialInsuranceBase: null,
      socialInsuranceRatio: null,
      housingFundBase: null,
      housingFundRatio: null,
      twoDayWeekend: false,
      workHours: null,
      benefits: [],
      allowances: [],
      annualBonus: null,
      quarterlyBonus: null,
      remark: null,
      group: 'DROP',
      createdAt: now,
      updatedAt: now,
    },
  ]
  const analysis = {
    tendency: 'ACCEPT',
    reasons: ['薪资高于同城同级别', '技术栈匹配'],
    prospect: '业务稳定，后端团队仍在扩张。',
    risks: ['绩效占比需要确认考核口径'],
    sources: [],
    basedOnProvidedMaterials: true,
    llmProvider: 'stub',
    llmModel: 'm',
    analyzedAt: now,
  }
  let createPayload: Record<string, unknown> | null = null
  let analyzePayload: { webSearch: boolean } | null = null
  let movePayload: Record<string, unknown> | null = null
  let analyzed = false

  const rows = [
    ['monthlySalary', '月薪', ['30000 元/月', '28000 元/月']],
    ['baseSalary', '底薪', ['24000 元/月', '未填写']],
    ['performanceRatio', '绩效占比', ['20%', '未填写']],
    ['socialInsurance', '五险一金', ['基数 12000 / 比例 10.5%', '未填写']],
    ['housingFund', '公积金', ['基数 12000 / 比例 12%', '未填写']],
    ['twoDayWeekend', '双休', ['双休', '非双休']],
    ['workHours', '上下班时间', ['10:00-19:00', '未填写']],
    ['benefits', '福利', ['餐补', '未填写']],
    ['allowances', '各项补贴', ['房补', '未填写']],
    ['annualBonus', '年终奖', ['3 个月', '未填写']],
    ['quarterlyBonus', '季度奖', ['未填写', '未填写']],
    ['remark', '备注', ['首选', '未填写']],
  ] as const

  await page.route('**/api/companies?*', (route) =>
    route.fulfill(ok({ items: [{ id: 1, name: '字节跳动', tags: [], jobPositionCount: 1 }], total: 1, page: 1, size: 100 })),
  )
  await page.route(/\/api\/job-positions\?/, (route) =>
    route.fulfill(
      ok({
        items: [
          {
            id: 1,
            title: 'Java 后端开发工程师',
            companyId: 1,
            companyName: '字节跳动',
            status: 'SAVED',
            parseStatus: 'COMPLETED',
            retryCount: 0,
            createdAt: now,
            updatedAt: now,
          },
        ],
        total: 1,
        page: 1,
        size: 100,
      }),
    ),
  )
  await page.route('**/api/offers/comparison', (route) =>
    route.fulfill(
      ok({
        columns: [
          { offerId: 1, companyName: '字节跳动', jobPositionTitle: 'Java 后端开发工程师' },
          { offerId: 2, companyName: '腾讯', jobPositionTitle: 'Go 后端工程师' },
        ],
        rows: rows.map(([key, label, values]) => ({ key, label, values })),
      }),
    ),
  )
  await page.route('**/api/offers/extract', (route) =>
    route.fulfill(
      ok({
        companyName: '字节跳动',
        jobPositionTitle: 'Java 后端开发工程师',
        monthlySalary: 30000,
        twoDayWeekend: true,
        benefits: ['餐补'],
      }),
    ),
  )
  await page.route('**/api/offers/1/analyze', (route) => {
    analyzePayload = route.request().postDataJSON() as { webSearch: boolean }
    analyzed = true
    return route.fulfill(ok(analysis))
  })
  await page.route(/\/api\/offers\/1$/, (route) => {
    if (route.request().method() === 'PUT') {
      movePayload = route.request().postDataJSON() as Record<string, unknown>
      offers[0] = { ...offers[0], ...(movePayload as object) } as (typeof offers)[number]
      return route.fulfill(ok({ ...offers[0], analysis: analyzed ? analysis : null }))
    }
    return route.fulfill(ok({ ...offers[0], analysis: analyzed ? analysis : null }))
  })
  await page.route(/\/api\/offers(\?|$)/, (route) => {
    if (route.request().method() === 'POST') {
      createPayload = route.request().postDataJSON() as Record<string, unknown>
      const created = {
        ...offers[0],
        id: 3,
        monthlySalary: createPayload.monthlySalary as number,
        group: (createPayload.group as string) ?? 'KEEP',
        remark: null,
      }
      offers.push(created)
      return route.fulfill(ok({ ...created, analysis: null }))
    }
    const keep = route.request().url().includes('group=KEEP')
    const items = keep ? offers.filter((item) => item.group === 'KEEP') : offers
    return route.fulfill(ok({ items, total: items.length, page: 1, size: 20 }))
  })

  await page.goto('/offers')
  await expect(page.locator('.offer-row')).toHaveCount(2)
  await expect(page.getByText('字节跳动 · Java 后端开发工程师')).toBeVisible()

  // 固定行对比
  await page.getByRole('button', { name: '保留组对比' }).click()
  await expect(page.getByText('对比维度')).toBeVisible()
  await expect(page.getByText('30000 元/月')).toBeVisible()
  await expect(page.getByText('未填写').first()).toBeVisible()

  // 新增 Offer
  await page.getByRole('button', { name: 'Offer 列表' }).click()
  await page.locator('.page-head').getByRole('button', { name: '新增 Offer' }).click()
  await page.locator('.form-grid label').filter({ hasText: '公司' }).locator('select').selectOption('1')
  await page.locator('.form-grid label').filter({ hasText: '月薪' }).locator('input').fill('35000')
  await page.getByRole('button', { name: '保存 Offer' }).click()
  await expect.poll(() => createPayload?.monthlySalary).toBe(35000)
  await expect(page.getByText('Offer 已保存')).toBeVisible()

  // 文本提取回填
  await page.locator('.page-head').getByRole('button', { name: '新增 Offer' }).click()
  await page.locator('.extract textarea').fill('字节跳动 Java 后端 30K 双休')
  await page.getByRole('button', { name: '提取字段' }).click()
  await expect(page.locator('.form-grid label').filter({ hasText: '月薪' }).locator('input')).toHaveValue('30000')
  await expect(page.locator('.form-grid label').filter({ hasText: '公司' }).locator('select')).toHaveValue('1')
  await page.getByRole('button', { name: '取消' }).last().click()

  // 前景分析
  await page.locator('.offer-row').filter({ hasText: '字节跳动' }).first()
    .getByRole('button', { name: '分析' }).click()
  await expect(page.getByText('尚未分析。默认只基于你提供的公司与 Offer 资料，不编造外部事实。')).toBeVisible()
  await page.getByRole('button', { name: '生成前景分析' }).click()
  await expect.poll(() => analyzePayload?.webSearch).toBe(false)
  await expect(page.getByText('值得接受')).toBeVisible()
  await expect(page.getByText('业务稳定，后端团队仍在扩张。')).toBeVisible()

  // 移入淘汰组
  await page.locator('.offer-row').filter({ hasText: '字节跳动' }).first()
    .getByRole('button', { name: '移入淘汰' }).click()
  await expect.poll(() => movePayload?.group).toBe('DROP')
})
