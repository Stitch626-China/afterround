import { expect, test } from '@playwright/test'

/**
 * M2 第一批前端 E2E（接口全 mock）：岗位库列表/筛选/批量删除、行情洞察、
 * 简历版本对比、设置抽屉的场景级模型绑定。
 */

const ok = (data: unknown) => ({ json: { code: 0, message: 'ok', data } })

const job = (id: number, title: string, status: string, parseStatus = 'COMPLETED') => ({
  id,
  title,
  companyId: 1,
  companyName: '字节跳动',
  city: '上海',
  salaryMin: 20000,
  salaryMax: 30000,
  experience: '3-5年',
  education: '本科及以上',
  status,
  parseStatus,
  retryCount: 0,
  createdAt: '2026-11-18T10:00:00Z',
  updatedAt: '2026-11-18T10:00:00Z',
})

test('岗位库：列表筛选、行情洞察与批量删除', async ({ page }) => {
  let deleted = false
  let lastQuery = ''
  let batchPayload: { ids: number[] } | null = null

  await page.route('**/api/job-positions/batch-delete', (route) => {
    batchPayload = route.request().postDataJSON() as { ids: number[] }
    deleted = true
    return route.fulfill(ok(undefined))
  })
  await page.route(/\/api\/job-positions(\?|$)/, (route) => {
    lastQuery = route.request().url()
    return route.fulfill(
      ok({
        items: deleted ? [] : [job(1, 'Java 后端开发工程师', 'SAVED'), job(2, 'Go 后端工程师', 'APPLIED')],
        total: deleted ? 0 : 2,
        page: 1,
        size: 20,
      }),
    )
  })
  await page.route('**/api/job-market/insights', (route) =>
    route.fulfill(
      ok({
        sampleSize: 2,
        generatedAt: '2026-11-18T10:00:00Z',
        skillTop: [
          { skill: 'java', count: 2 },
          { skill: 'redis', count: 1 },
        ],
        salaryDistribution: [
          { label: '<10K', count: 0 },
          { label: '10-20K', count: 0 },
          { label: '20-30K', count: 2 },
          { label: '30-50K', count: 0 },
          { label: '50K+', count: 0 },
        ],
        experienceDistribution: [{ label: '3-5年', count: 2 }],
        directionDistribution: [{ label: 'Java 后端开发工程师', count: 1 }],
      }),
    ),
  )

  await page.goto('/jobs')
  await expect(page.getByText('Java 后端开发工程师')).toBeVisible()
  await expect(page.locator('.list').getByText('已投递').first()).toBeVisible()
  await expect(page.locator('.list').getByText('20-30K').first()).toBeVisible()

  // 关键字筛选会带 query 请求
  await page.getByPlaceholder('搜索岗位名或公司').fill('Java')
  await page.getByRole('button', { name: '查询' }).click()
  await expect.poll(() => lastQuery).toContain('keyword=Java')

  // 行情 Tab
  await page.getByRole('button', { name: '行情洞察' }).click()
  await expect(page.getByText('样本 2 份', { exact: false })).toBeVisible()
  await expect(page.getByText('java').first()).toBeVisible()
  await expect(page.getByText('20-30K').first()).toBeVisible()

  // 回到列表并批量删除
  await page.getByRole('button', { name: '岗位列表' }).click()
  await page.getByLabel('选择 Java 后端开发工程师').check()
  await page.getByLabel('选择 Go 后端工程师').check()
  await page.getByRole('button', { name: '批量删除' }).click()
  await page.getByRole('button', { name: '确认删除' }).click()

  await expect.poll(() => batchPayload?.ids).toEqual([1, 2])
  await expect(page.getByText('还没有岗位')).toBeVisible()
})

test('简历对比：展示分数变化、五维差异与版本不一致提示', async ({ page }) => {
  const resume = (id: number, fileName: string, score: number) => ({
    id,
    fileName,
    fileType: 'PDF',
    fileSize: 1024,
    analyzeStatus: 'COMPLETED',
    overallScore: score,
    createdAt: '2026-11-18T10:00:00Z',
    updatedAt: '2026-11-18T10:00:00Z',
  })
  const side = (id: number, fileName: string, score: number, promptVersion: string | null) => ({
    resumeId: id,
    fileName,
    overallScore: score,
    analyzedAt: '2026-11-18T10:00:00Z',
    llmProvider: 'stub',
    llmModel: 'm',
    promptVersion,
  })

  await page.route(/\/api\/resumes\?page=/, (route) =>
    route.fulfill(ok({ items: [resume(1, 'resume-v1.pdf', 70), resume(2, 'resume-v2.pdf', 80)], total: 2, page: 1, size: 100 })),
  )
  await page.route('**/api/resume-comparisons', (route) =>
    route.fulfill(
      ok({
        base: side(1, 'resume-v1.pdf', 70, null),
        target: side(2, 'resume-v2.pdf', 80, 'resume-analysis-v1'),
        overallScoreDelta: 10,
        dimensionDeltas: [
          { key: 'technicalDepth', label: '技术深度', baseScore: 60, targetScore: 75, delta: 15 },
          { key: 'projectQuality', label: '项目质量', baseScore: 70, targetScore: 80, delta: 10 },
          { key: 'engineeringAbility', label: '工程能力', baseScore: 70, targetScore: 75, delta: 5 },
          { key: 'expressionStructure', label: '表达结构', baseScore: 70, targetScore: 70, delta: 0 },
          { key: 'jobMatchPotential', label: '岗位匹配潜力', baseScore: 70, targetScore: 80, delta: 10 },
        ],
        strengthsAdded: [{ quote: '主导订单重构', comment: '有主导经验' }],
        strengthsRemoved: [],
        weaknessesAdded: [],
        weaknessesRemoved: [{ quote: '缺少量化结果', comment: '已补' }],
        directionsAdded: ['Go 后端'],
        directionsRemoved: ['Java 后端'],
        summary: {
          content: '新版简历在技术深度上有明显提升。',
          modelMismatch: false,
          promptVersionMismatch: true,
          degraded: false,
          degradeReason: null,
        },
      }),
    ),
  )

  await page.goto('/resumes/compare')
  await expect(page.locator('.picker select').first()).toHaveValue('1')
  await page.getByRole('button', { name: '开始对比' }).click()

  await expect(page.getByText('+10').first()).toBeVisible()
  await expect(page.locator('.dims').getByText('技术深度')).toBeVisible()
  await expect(page.getByText('+15')).toBeVisible()
  await expect(page.getByText('Prompt 版本不同')).toBeVisible()
  await expect(page.getByText('新版简历在技术深度上有明显提升。')).toBeVisible()
  await expect(page.getByText('主导订单重构')).toBeVisible()
  await expect(page.getByText('Go 后端')).toBeVisible()
})

test('设置抽屉：场景级模型绑定与恢复全局', async ({ page }) => {
  const scenes = [
    { scene: 'RESUME_ANALYZE', label: '简历分析', inherited: true, effectiveProviderKey: 'stub', effectiveModel: 'm' },
    { scene: 'CHAT', label: '模型对话', inherited: true, effectiveProviderKey: 'stub', effectiveModel: 'm' },
    { scene: 'JOB_MATCH', label: '岗位匹配', inherited: true, effectiveProviderKey: 'stub', effectiveModel: 'm' },
    { scene: 'INTERVIEW_QUESTION', label: '模拟面试出题', inherited: true, effectiveProviderKey: 'stub', effectiveModel: 'm' },
    { scene: 'INTERVIEW_EVALUATE', label: '模拟面试评估', inherited: true, effectiveProviderKey: 'stub', effectiveModel: 'm' },
    { scene: 'OFFER_ANALYZE', label: 'Offer 分析', inherited: true, effectiveProviderKey: 'stub', effectiveModel: 'm' },
    { scene: 'REVIEW_ANALYZE', label: '复盘分析', inherited: true, effectiveProviderKey: 'stub', effectiveModel: 'm' },
  ]
  let bound = false
  let putPayload: { providerKey: string; model: string } | null = null

  await page.route(/\/api\/resumes\?page=/, (route) =>
    route.fulfill(ok({ items: [], total: 0, page: 1, size: 20 })),
  )
  await page.route('**/api/llm/scenes', (route) => {
    if (route.request().method() === 'PUT') {
      return route.fulfill(ok({ ...scenes[0], inherited: false, providerKey: 'stub', model: 'm' }))
    }
    const data = scenes.map((scene) =>
      bound && scene.scene === 'RESUME_ANALYZE'
        ? { ...scene, inherited: false, providerKey: 'stub', model: 'm', updatedAt: '2026-11-18T10:00:00Z' }
        : scene,
    )
    return route.fulfill(ok(data))
  })
  await page.route('**/api/llm/scenes/*', (route) => {
    putPayload = route.request().postDataJSON() as { providerKey: string; model: string }
    bound = true
    return route.fulfill(ok({ ...scenes[0], inherited: false, providerKey: 'stub', model: 'm' }))
  })
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

  await page.goto('/resumes')
  await page.getByRole('button', { name: '设置', exact: true }).click()
  await page.getByRole('button', { name: '场景模型' }).click()

  const resumeScene = page.locator('.row').filter({ hasText: '简历分析' })
  await expect(resumeScene.getByText('继承全局')).toBeVisible()

  await resumeScene.getByRole('button', { name: '绑定模型' }).click()
  await resumeScene.locator('.editor select').first().selectOption('stub')
  await expect(resumeScene.locator('.editor select').nth(1)).toHaveValue('m')
  await resumeScene.getByRole('button', { name: '保存' }).click()

  await expect.poll(() => putPayload).toEqual({ providerKey: 'stub', model: 'm' })
  await expect(resumeScene.getByText('已绑定')).toBeVisible()
  await expect(page.getByText('场景绑定已保存，下一次调用立即生效')).toBeVisible()
})
