import { ref } from 'vue'

/** 全局设置抽屉状态：业务页（简历中心/报告）也能直接打开模型设置。 */
export type SettingsTab = 'model' | 'scene' | 'appearance' | 'usage'

export const settingsOpen = ref(false)
export const settingsTab = ref<SettingsTab>('model')

export function openSettings(tab: SettingsTab = 'model'): void {
  settingsTab.value = tab
  settingsOpen.value = true
}

/** 简历分析缺少模型时的统一入口。 */
export function openModelSettings(): void {
  openSettings('model')
}
