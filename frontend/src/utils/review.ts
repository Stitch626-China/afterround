/**
 * 复盘页纯函数：状态文案/色调、候选枚举、分析确认 payload 清洗。
 * 所有 key 与 contracts/openapi.yaml 的枚举、受控词表保持一致。
 */
import type {
  ImprovementAction,
  ImprovementActionDraft,
  ImprovementActionStatus,
  InterviewEventListItem,
  InterviewFormat,
  InterviewRoundType,
  ReviewAnalysisUpdateRequest,
  ReviewInputType,
  ReviewQuestion,
  ReviewStatus,
  ReviewSummary,
  ReviewTag,
} from '@/api/reviews'

export const ROUND_TYPES: InterviewRoundType[] = ['HR', 'TECH', 'CROSS', 'DIRECTOR', 'FINAL', 'OTHER']
export const ROUND_TYPE_TEXT: Record<string, string> = {
  HR: 'HR 面',
  TECH: '技术面',
  CROSS: '交叉面',
  DIRECTOR: '总监面',
  FINAL: '终面',
  OTHER: '其他',
}

export const FORMATS: InterviewFormat[] = ['PHONE', 'VIDEO', 'ONSITE', 'OTHER']
export const FORMAT_TEXT: Record<string, string> = {
  PHONE: '电话',
  VIDEO: '视频',
  ONSITE: '现场',
  OTHER: '其他',
}

export const INTERVIEW_RESULT_TEXT: Record<string, string> = {
  PASS: '通过',
  FAIL: '淘汰',
  PENDING: '待定',
}

export const INPUT_TYPES: ReviewInputType[] = ['AUDIO', 'TEXT', 'NOTES']
export const INPUT_TYPE_TEXT: Record<string, string> = {
  AUDIO: '录音',
  TEXT: '转写文本',
  NOTES: '手动笔记',
}

export const REVIEW_STATUS_TEXT: Record<string, string> = {
  UPLOADED: '待分析',
  TRANSCRIBING: '转写中',
  ANALYZING: '分析中',
  READY: '已就绪',
  FAILED: '失败',
}

export const REVIEW_STATUS_TONE: Record<string, string> = {
  UPLOADED: 'muted',
  TRANSCRIBING: 'warning',
  ANALYZING: 'warning',
  READY: 'success',
  FAILED: 'danger',
}

export const ACTION_STATUSES: ImprovementActionStatus[] = ['TODO', 'IN_PROGRESS', 'VERIFIED', 'DROPPED']
export const ACTION_STATUS_TEXT: Record<string, string> = {
  TODO: '待开始',
  IN_PROGRESS: '进行中',
  VERIFIED: '已验证',
  DROPPED: '已放弃',
}
export const ACTION_STATUS_TONE: Record<string, string> = {
  TODO: 'muted',
  IN_PROGRESS: 'warning',
  VERIFIED: 'success',
  DROPPED: 'danger',
}

export function isProcessing(status: ReviewStatus): boolean {
  return status === 'TRANSCRIBING' || status === 'ANALYZING' || status === 'UPLOADED'
}

/** 标签输入框 → 去空、去重、保持顺序（用户在 UI 中以逗号分隔）。 */
export function parseTagList(text: string): string[] {
  return Array.from(
    new Set(
      text
        .split(/[,，\s]+/)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  )
}

function trimOrNull(value: string | null | undefined): string | null {
  const text = value?.trim()
  return text ? text : null
}

function cleanTags(tags: ReviewTag[]): ReviewTag[] {
  return tags
    .map((tag) => ({
      tag: tag.tag.trim(),
      label: trimOrNull(tag.label),
      confidence: tag.confidence ?? null,
      evidence: trimOrNull(tag.evidence),
    }))
    .filter((tag) => tag.tag)
}

/**
 * 确认 / 修正分析的 payload：问题重编号、标签去空、行动最多 3 条并截断文本。
 * 完整覆盖语义由后端保证（PUT /analysis）。
 */
export function buildAnalysisPayload(input: {
  questions: ReviewQuestion[]
  topics: ReviewTag[]
  lossTags: ReviewTag[]
  summary: ReviewSummary
  actions: ImprovementActionDraft[]
  confirmed: boolean
}): ReviewAnalysisUpdateRequest {
  return {
    questions: input.questions.map((question, index) => ({
      ...question,
      questionIndex: index + 1,
      question: question.question.trim(),
    })),
    topics: cleanTags(input.topics),
    lossTags: cleanTags(input.lossTags),
    summary: {
      overall: trimOrNull(input.summary.overall),
      strengths: (input.summary.strengths ?? []).map((item) => item.trim()).filter(Boolean),
      improvements: (input.summary.improvements ?? []).map((item) => item.trim()).filter(Boolean),
      nextPreparation: (input.summary.nextPreparation ?? []).map((item) => item.trim()).filter(Boolean),
    },
    actions: input.actions
      .map((action) => ({
        action: action.action.trim(),
        category: trimOrNull(action.category),
        reason: trimOrNull(action.reason),
      }))
      .filter((action) => action.action)
      .slice(0, 3),
    confirmed: input.confirmed,
  }
}

/** 行动草稿编辑态：新增一条空行动供用户补写。 */
export function emptyDraft(): ImprovementActionDraft {
  return { action: '', category: null, reason: null }
}

/** 最近一轮面试（按面试时间，忽略空 / 非法时间）；没有有效事件时返回 null。 */
export function latestInterviewEvent(
  events: InterviewEventListItem[],
): InterviewEventListItem | null {
  return events.reduce<InterviewEventListItem | null>((latest, event) => {
    const time = Date.parse(event.interviewTime)
    if (!Number.isFinite(time)) {
      return latest
    }
    return !latest || time > Date.parse(latest.interviewTime) ? event : latest
  }, null)
}

/**
 * 下一场面试后的待验证行动（FR5.6）：行动创建早于最近一轮面试，且仍在待开始 / 进行中。
 * 用于投递时间线提示「本轮已记录，旧行动可以验证了」。
 */
export function pendingVerifyActions(
  actions: ImprovementAction[],
  events: InterviewEventListItem[],
): ImprovementAction[] {
  const latest = latestInterviewEvent(events)
  if (!latest) {
    return []
  }
  const latestTime = Date.parse(latest.interviewTime)
  return actions.filter(
    (action) =>
      (action.status === 'TODO' || action.status === 'IN_PROGRESS') &&
      Date.parse(action.createdAt) < latestTime,
  )
}
