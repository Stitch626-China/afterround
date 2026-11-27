import { describe, expect, it } from 'vitest'
import type { PromptDetail } from '@/api/prompts'
import {
  extractPlaceholders,
  hasUnsavedChanges,
  lineDiff,
  mergeValidation,
  shortTime,
  validatePrompt,
  versionLabel,
} from './prompt'

const rule = {
  placeholders: ['resumeText', 'marketDirections'],
  requiredFields: ['technicalDepth', 'suggestions'],
  outputMode: 'JSON' as const,
}

describe('prompt utils', () => {
  it('提取并去重占位符，忽略非法名称', () => {
    expect(extractPlaceholders('{{resumeText}} {{ marketDirections }} {{resumeText}} {{bad-name}}'))
      .toEqual(['resumeText'])
  })

  it('校验占位符、JSON 字段与未知占位符', () => {
    const result = validatePrompt(rule, '输出 technicalDepth 与 suggestions', '{{resumeText}} {{extra}}')
    expect(result.valid).toBe(false)
    expect(result.errors).toContain('缺少占位符 {{marketDirections}}')
    expect(result.warnings).toContain('未知占位符 {{extra}}（不会被替换）')
  })

  it('TEXT 模式不检查字段名', () => {
    expect(validatePrompt({ ...rule, outputMode: 'TEXT' }, '用中文总结', '{{resumeText}} {{marketDirections}}'))
      .toEqual({ valid: true, errors: [], warnings: [] })
  })

  it('合并校验结果时去重且任一无效则整体无效', () => {
    const merged = mergeValidation(
      { valid: false, errors: ['a', 'a'], warnings: ['w'] },
      { valid: true, errors: [], warnings: ['w', 'v'] },
    )
    expect(merged.valid).toBe(false)
    expect(merged.errors).toEqual(['a'])
    expect(merged.warnings).toEqual(['w', 'v'])
  })

  it('内置与 DB 版本标签可区分', () => {
    expect(versionLabel({ versionNo: 0, source: 'BUILTIN' }, 'resume-analysis-v3'))
      .toBe('内置默认（resume-analysis-v3）')
    expect(versionLabel({ versionNo: 2, source: 'DB' })).toBe('DB v2')
    expect(versionLabel(null)).toBe('未配置')
  })

  it('行级 diff 标记新增、删除与不变行', () => {
    const rows = lineDiff('a\nb\nc', 'a\nB\nc')
    expect(rows.map((row) => row.type)).toEqual(['same', 'remove', 'add', 'same'])
    expect(rows[1]).toMatchObject({ text: 'b', leftNo: 2 })
    expect(rows[2]).toMatchObject({ text: 'B', rightNo: 2 })
  })

  it('脏状态同时比较正文与备注', () => {
    const detail = {
      active: { systemContent: 's', userContent: 'u' },
      draft: null,
    } as unknown as PromptDetail
    expect(hasUnsavedChanges(detail, 's', 'u', '')).toBe(false)
    expect(hasUnsavedChanges(detail, 's2', 'u', '')).toBe(true)
    expect(hasUnsavedChanges(detail, 's', 'u', '备注')).toBe(true)
  })

  it('短时间格式去掉秒与时区', () => {
    expect(shortTime('2026-11-27T10:20:30.123+08:00')).toBe('2026-11-27 10:20')
    expect(shortTime(null)).toBe('—')
  })
})
