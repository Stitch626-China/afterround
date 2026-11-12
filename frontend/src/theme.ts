/**
 * 主题（§6.10）：light / dark，默认跟随系统；手动切换后写入 localStorage。
 * 首屏由 index.html 内联脚本提前应用，避免闪白。
 */
export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'jobpilot-theme'

/** 当前生效的主题（以 <html data-theme> 为准）。 */
export function currentTheme(): Theme {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'
}

/** 应用并记住主题。 */
export function applyTheme(theme: Theme): void {
  document.documentElement.dataset.theme = theme
  localStorage.setItem(STORAGE_KEY, theme)
}
