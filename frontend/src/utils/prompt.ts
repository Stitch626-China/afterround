/**
 * 提示词工作台的纯函数：占位符、校验、版本标签、行级 diff 与脏状态。
 * 与后端 PromptValidator 保持同一口径，前端只做即时提示，最终以保存/发布响应为准。
 */
import type { PromptDetail } from '@/api/prompts'

export interface PromptRule {
  placeholders: string[]
  requiredFields: string[]
  outputMode: 'JSON' | 'TEXT'
}

export interface ValidationMessages {
  valid: boolean
  errors: string[]
  warnings: string[]
}

export interface DiffRow {
  type: 'same' | 'add' | 'remove'
  text: string
  leftNo?: number
  rightNo?: number
}

const PLACEHOLDER = /\{\{([a-zA-Z0-9_]+)\}\}/g
const MAX_LENGTH = 20_000

/** 按出现顺序去重提取 `{{name}}`。 */
export function extractPlaceholders(text: string): string[] {
  const names = new Set<string>()
  for (const match of text.matchAll(PLACEHOLDER)) {
    names.add(match[1])
  }
  return [...names]
}

/** 合并多次校验结果：errors/warnings 去重，任一无效则整体无效。 */
export function mergeValidation(...results: ValidationMessages[]): ValidationMessages {
  const errors = new Set<string>()
  const warnings = new Set<string>()
  results.forEach((result) => {
    result.errors.forEach((error) => errors.add(error))
    result.warnings.forEach((warning) => warnings.add(warning))
  })
  return { valid: errors.size === 0, errors: [...errors], warnings: [...warnings] }
}

/** 与后端 PromptValidator 同口径的即时校验。 */
export function validatePrompt(rule: PromptRule, system: string, user: string): ValidationMessages {
  const errors: string[] = []
  const warnings: string[] = []
  if (!system.trim()) {
    errors.push('system 模板不能为空')
  }
  if (!user.trim()) {
    errors.push('user 模板不能为空')
  }
  if (system.length > MAX_LENGTH || user.length > MAX_LENGTH) {
    errors.push(`system/user 单段不能超过 ${MAX_LENGTH} 字符`)
  }

  const present = new Set(extractPlaceholders(`${system}\n${user}`))
  rule.placeholders.forEach((name) => {
    if (!present.has(name)) {
      errors.push(`缺少占位符 {{${name}}}`)
    }
  })
  present.forEach((name) => {
    if (!rule.placeholders.includes(name)) {
      warnings.push(`未知占位符 {{${name}}}（不会被替换）`)
    }
  })

  if (rule.outputMode === 'JSON') {
    const tokens = new Set(system.split(/[^A-Za-z0-9_]+/))
    rule.requiredFields.forEach((field) => {
      if (!tokens.has(field)) {
        errors.push(`system 模板缺少必需字段 ${field}`)
      }
    })
  }
  return { valid: errors.length === 0, errors, warnings }
}

/** 版本标签：内置展示版本号，DB 覆盖展示 vN。 */
export function versionLabel(
  version: { versionNo: number; source: 'BUILTIN' | 'DB' } | null | undefined,
  builtinVersion?: string | null,
): string {
  if (!version) {
    return '未配置'
  }
  if (version.source === 'BUILTIN') {
    return builtinVersion ? `内置默认（${builtinVersion}）` : '内置默认'
  }
  return `DB v${version.versionNo}`
}

/** 编辑器内容是否与「草稿优先、否则生效版本」不同；note 一并比较。 */
export function hasUnsavedChanges(
  detail: PromptDetail | null,
  system: string,
  user: string,
  note: string,
): boolean {
  if (!detail) {
    return false
  }
  const base = detail.draft ?? detail.active
  return system !== base.systemContent || user !== base.userContent || note !== (detail.draft?.note ?? '')
}

/** 行级 LCS diff；用于「当前生效 vs 编辑中」的并排对比。 */
export function lineDiff(left: string, right: string): DiffRow[] {
  const a = left.split('\n')
  const b = right.split('\n')
  const dp: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0))
  for (let i = a.length - 1; i >= 0; i -= 1) {
    for (let j = b.length - 1; j >= 0; j -= 1) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }

  const rows: DiffRow[] = []
  let i = 0
  let j = 0
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) {
      rows.push({ type: 'same', text: a[i], leftNo: i + 1, rightNo: j + 1 })
      i += 1
      j += 1
    } else if (j < b.length && (i === a.length || dp[i][j + 1] > dp[i + 1][j])) {
      rows.push({ type: 'add', text: b[j], rightNo: j + 1 })
      j += 1
    } else {
      rows.push({ type: 'remove', text: a[i], leftNo: i + 1 })
      i += 1
    }
  }
  return rows
}

/** 时间展示：后端返回 ISO 字符串，空值用短横线。 */
export function shortTime(value: string | null | undefined): string {
  if (!value) {
    return '—'
  }
  return value.replace('T', ' ').replace(/\..+$/, '').slice(0, 16)
}
