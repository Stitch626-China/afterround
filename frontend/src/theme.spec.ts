import { beforeEach, describe, expect, it } from 'vitest'
import { applyTheme, currentTheme } from './theme'

beforeEach(() => {
  localStorage.clear()
  delete document.documentElement.dataset.theme
})

describe('theme', () => {
  it('应用 dark 并写入 localStorage', () => {
    applyTheme('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem('jobpilot-theme')).toBe('dark')
    expect(currentTheme()).toBe('dark')
  })

  it('未设置时回落 light', () => {
    expect(currentTheme()).toBe('light')
  })
})
