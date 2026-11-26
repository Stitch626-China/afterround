import { describe, expect, it } from 'vitest'
import type { ImprovementAction, InterviewEventListItem } from '@/api/reviews'
import {
  ACTION_STATUS_TEXT,
  INPUT_TYPE_TEXT,
  REVIEW_STATUS_TEXT,
  ROUND_TYPE_TEXT,
  buildAnalysisPayload,
  emptyDraft,
  isProcessing,
  parseTagList,
  pendingVerifyActions,
} from './review'

describe('review utils', () => {
  it('标签输入去空去重并保持顺序', () => {
    expect(parseTagList('jvm, 并发，jvm mysql\nredis')).toEqual(['jvm', '并发', 'mysql', 'redis'])
    expect(parseTagList('  ')).toEqual([])
  })

  it('处理中状态包含上传、转写与分析', () => {
    expect(isProcessing('UPLOADED')).toBe(true)
    expect(isProcessing('TRANSCRIBING')).toBe(true)
    expect(isProcessing('ANALYZING')).toBe(true)
    expect(isProcessing('READY')).toBe(false)
    expect(isProcessing('FAILED')).toBe(false)
  })

  it('确认 payload：问题重编号、标签清洗、行动截断', () => {
    const payload = buildAnalysisPayload({
      questions: [
        { questionIndex: 9, question: '  介绍 JVM GC  ', score: 70 },
        { questionIndex: 9, question: '讲讲线程池', score: null },
      ],
      topics: [
        { tag: ' jvm ', label: 'JVM', confidence: 0.9, evidence: ' 证据 ' },
        { tag: '  ', label: null, confidence: null, evidence: null },
        { tag: 'concurrency', label: null, confidence: null, evidence: null },
      ],
      lossTags: [{ tag: 'depth', label: '深度不足', confidence: null, evidence: null }],
      summary: {
        overall: ' 整体尚可 ',
        strengths: [' 表达清晰 ', ''],
        improvements: ['深度不足'],
        nextPreparation: ['补 JVM'],
      },
      actions: [
        { action: ' 补 JVM GC ', category: ' 知识点 ', reason: ' ' },
        { action: '练习表达', category: null, reason: null },
        { action: '', category: null, reason: null },
        { action: '第四条', category: null, reason: null },
      ],
      confirmed: true,
    })

    expect(payload.questions.map((item) => item.questionIndex)).toEqual([1, 2])
    expect(payload.questions[0].question).toBe('介绍 JVM GC')
    expect(payload.topics).toEqual([
      { tag: 'jvm', label: 'JVM', confidence: 0.9, evidence: '证据' },
      { tag: 'concurrency', label: null, confidence: null, evidence: null },
    ])
    expect(payload.summary.overall).toBe('整体尚可')
    expect(payload.summary.strengths).toEqual(['表达清晰'])
    expect(payload.actions).toHaveLength(3)
    expect(payload.actions[0]).toEqual({ action: '补 JVM GC', category: '知识点', reason: null })
    expect(payload.confirmed).toBe(true)
  })

  it('空草稿与状态文案', () => {
    expect(emptyDraft()).toEqual({ action: '', category: null, reason: null })
    expect(ROUND_TYPE_TEXT.TECH).toBe('技术面')
    expect(INPUT_TYPE_TEXT.AUDIO).toBe('录音')
    expect(REVIEW_STATUS_TEXT.READY).toBe('已就绪')
    expect(ACTION_STATUS_TEXT.VERIFIED).toBe('已验证')
  })

  it('下一轮面试后只提示创建更早且未验证的行动', () => {
    const actions = [
      { id: 1, action: 'a', status: 'TODO', createdAt: '2026-11-01T00:00:00Z' },
      { id: 2, action: 'b', status: 'IN_PROGRESS', createdAt: '2026-11-03T00:00:00Z' },
      { id: 3, action: 'c', status: 'VERIFIED', createdAt: '2026-11-01T00:00:00Z' },
      { id: 4, action: 'd', status: 'TODO', createdAt: '2026-11-05T00:00:00Z' },
      { id: 5, action: 'e', status: 'DROPPED', createdAt: '2026-11-01T00:00:00Z' },
    ] as ImprovementAction[]
    const events = [
      { interviewTime: '2026-11-02T00:00:00Z' },
      { interviewTime: '2026-11-04T00:00:00Z' },
    ] as InterviewEventListItem[]

    expect(pendingVerifyActions(actions, events).map((action) => action.id)).toEqual([1, 2])
    expect(pendingVerifyActions(actions, [])).toEqual([])
  })
})
