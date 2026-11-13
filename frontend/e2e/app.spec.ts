import { expect, test } from '@playwright/test'

/** 空列表与空模型配置，保证页面不依赖真实后端。 */
const emptyResumes = {
  code: 0,
  message: 'ok',
  data: { items: [], total: 0, page: 1, size: 20 },
}
const emptyEnvelope = { code: 0, message: 'ok', data: [] }

test.beforeEach(async ({ page }) => {
  await page.route(/\/api\/resumes(\?|$)/, (route) => route.fulfill({ json: emptyResumes }))
  await page.route('**/api/llm/providers', (route) => route.fulfill({ json: emptyEnvelope }))
  await page.route('**/api/llm/global', (route) => route.fulfill({
    json: { code: 0, message: 'ok', data: { defaultProviderKey: null, defaultModel: null } },
  }))
})

test('一级导航只含业务主线，设置抽屉与主题切换可用', async ({ page }) => {
  await page.goto('/resumes')

  await expect(page.getByRole('link', { name: '简历中心' })).toBeVisible()
  await expect(page.getByText('模拟面试')).toBeVisible()
  await expect(page.getByText('模型设置')).toHaveCount(0)

  await page.getByRole('button', { name: '设置' }).click()
  await expect(page.getByRole('dialog', { name: '设置' })).toBeVisible()
  await expect(page.getByRole('button', { name: '模型服务' })).toBeVisible()

  await page.getByRole('button', { name: '外观' }).click()
  await page.getByRole('button', { name: /Dark/ }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')

  await page.getByRole('button', { name: /Light/ }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
})

test('简历页展示上传区与空状态', async ({ page }) => {
  await page.goto('/resumes')

  await expect(page.getByText('拖入简历文件')).toBeVisible()
  await expect(page.getByText('还没有简历')).toBeVisible()
  await expect(page.locator('input[type=file]')).toHaveAttribute('accept', '.pdf,.docx,.doc,.txt')
})

const report = {
  overallScore: 76,
  dimensions: [
    { key: 'technicalDepth', label: '技术深度', score: 82, weight: 30 },
    { key: 'projectQuality', label: '项目质量', score: 75, weight: 25 },
    { key: 'engineeringAbility', label: '工程能力', score: 70, weight: 20 },
    { key: 'expressionStructure', label: '表达结构', score: 65, weight: 15 },
    { key: 'jobMatchPotential', label: '岗位匹配潜力', score: 90, weight: 10 },
  ],
  basicInfo: {
    name: '张三',
    yearsOfExperience: 3,
    techDirection: 'Java 后端',
    currentMonthlySalary: 30000,
    expectedMonthlySalary: 40000,
  },
  strengths: [{ quote: '负责订单系统重构', comment: '有主导经验' }],
  weaknesses: [{ quote: '参与需求评审', comment: '缺少量化结果' }],
  suggestions: [{
    original: '负责订单系统重构',
    improved: '主导订单系统重构，QPS 提升 3 倍',
    reason: '量化结果更有说服力',
  }],
  careerDirections: [{
    direction: 'Java 后端开发',
    matchReason: '技能匹配',
    skillGaps: ['分布式'],
    interviewFocus: ['JVM'],
  }],
  llmProvider: 'fake',
  llmModel: 'fake-model',
}

const resume = {
  id: 1,
  fileName: '张三-Java.pdf',
  fileType: 'PDF',
  fileSize: 204800,
  analyzeStatus: 'COMPLETED',
  overallScore: 76,
  createdAt: '2026-11-12T10:00:00Z',
  updatedAt: '2026-11-12T10:00:00Z',
}

test('主链路：列表 → 报告 → 修改薪资', async ({ page }) => {
  let salaryBody: unknown = null

  await page.route(/\/api\/resumes\/1(\?|$)/, (route) => route.fulfill({
    json: { code: 0, message: 'ok', data: resume },
  }))
  await page.route(/\/api\/resumes\/1\/analysis/, (route) => route.fulfill({
    json: {
      code: 0,
      message: 'ok',
      data: { resumeId: 1, status: 'COMPLETED', retryCount: 0, error: null, report },
    },
  }))
  await page.route(/\/api\/resumes\/1\/salary/, async (route) => {
    salaryBody = route.request().postDataJSON()
    await route.fulfill({
      json: {
        code: 0,
        message: 'ok',
        data: {
          resumeId: 1,
          status: 'COMPLETED',
          retryCount: 0,
          error: null,
          report: {
            ...report,
            basicInfo: { ...report.basicInfo, currentMonthlySalary: 25000, expectedMonthlySalary: 35000 },
          },
        },
      },
    })
  })
  await page.route(/\/api\/resumes(\?|$)/, (route) => route.fulfill({
    json: {
      code: 0,
      message: 'ok',
      data: { items: [resume], total: 1, page: 1, size: 20 },
    },
  }))

  await page.goto('/resumes')
  await expect(page.getByText('张三-Java.pdf')).toBeVisible()
  await expect(page.getByText('76')).toBeVisible()

  await page.getByText('张三-Java.pdf').click()
  await expect(page).toHaveURL(/\/resumes\/1\/report/)
  await expect(page.getByText('综合评分')).toBeVisible()
  await expect(page.getByText('技术深度')).toBeVisible()
  await expect(page.getByText('¥30,000/月')).toBeVisible()

  await page.getByRole('button', { name: '修改薪资' }).click()
  await page.getByLabel('当前月薪（元）').fill('25000')
  await page.getByLabel('期望月薪（元）').fill('35000')
  await page.getByRole('button', { name: '保存' }).click()

  await expect(page.getByText('¥25,000/月')).toBeVisible()
  expect(salaryBody).toEqual({ currentMonthlySalary: 25000, expectedMonthlySalary: 35000 })
})
