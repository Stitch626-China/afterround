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
