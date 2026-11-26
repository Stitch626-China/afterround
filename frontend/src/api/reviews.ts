/**
 * 真实面试复盘接口（F4 / F5）：复盘会话、转写、结构化分析与改进动作。
 * 类型来自 contracts/openapi.yaml 生成的 schema，禁止手写数据结构。
 */
import type { components } from './schema'
import { del, get, patch, post, postForm, put } from './http'

export type ReviewSessionListItem = components['schemas']['ReviewSessionListItem']
export type ReviewSessionDetail = components['schemas']['ReviewSessionDetail']
export type ReviewSessionStatus = components['schemas']['ReviewSessionStatus']
export type ReviewStatus = components['schemas']['ReviewStatus']
export type ReviewStageStatus = components['schemas']['ReviewStageStatus']
export type ReviewInputType = components['schemas']['ReviewInputType']
export type ReviewAnalysis = components['schemas']['ReviewAnalysis']
export type ReviewQuestion = components['schemas']['ReviewQuestion']
export type ReviewTag = components['schemas']['ReviewTag']
export type ReviewSummary = components['schemas']['ReviewSummary']
export type ImprovementActionDraft = components['schemas']['ImprovementActionDraft']
export type ReviewAnalysisUpdateRequest = components['schemas']['ReviewAnalysisUpdateRequest']
export type UpdateReviewSessionRequest = components['schemas']['UpdateReviewSessionRequest']
export type ImprovementAction = components['schemas']['ImprovementAction']
export type ImprovementActionStatus = components['schemas']['ImprovementActionStatus']
export type CreateImprovementActionRequest = components['schemas']['CreateImprovementActionRequest']
export type UpdateImprovementActionRequest = components['schemas']['UpdateImprovementActionRequest']
export type ReviewTaxonomy = components['schemas']['ReviewTaxonomy']
export type InterviewRoundType = components['schemas']['InterviewRoundType']
export type InterviewFormat = components['schemas']['InterviewFormat']

export type ReviewSessionPage = NonNullable<
  components['schemas']['ResultReviewSessionPage']['data']
> & {
  items: ReviewSessionListItem[]
}

export type InterviewEventListItem = components['schemas']['InterviewEventListItem']
export type InterviewResult = components['schemas']['InterviewResult']
export type UpdateInterviewEventRequest = components['schemas']['UpdateInterviewEventRequest']
export type InterviewEventPage = NonNullable<
  components['schemas']['ResultInterviewEventPage']['data']
> & {
  items: InterviewEventListItem[]
}

export type ImprovementActionPage = NonNullable<
  components['schemas']['ResultImprovementActionPage']['data']
> & {
  items: ImprovementAction[]
}

export interface ReviewFilters {
  page?: number
  size?: number
  status?: ReviewStatus | ''
  applicationId?: number
  /** 全文检索：转写 / 笔记 / 公司名 / 岗位名（FR5.8）。 */
  keyword?: string
}

export interface InterviewEventFilters {
  page?: number
  size?: number
  applicationId?: number
  result?: InterviewResult
  roundType?: InterviewRoundType
}

export interface ImprovementActionFilters {
  page?: number
  size?: number
  status?: ImprovementActionStatus | ''
  sourceReviewId?: number
}

export interface CreateReviewInput {
  audio?: File | null
  transcript?: string
  notes?: string
  applicationId?: number | null
  jobPositionId?: number | null
  /** 快速新建：已有公司 ID（与 companyName 二选一） */
  companyId?: number | null
  /** 快速新建：公司名称，后端按精确同名复用或新建 */
  companyName?: string
  /** 快速新建：岗位名称 */
  jobTitle?: string
  /** 可选背景：公司介绍 */
  companyIntro?: string
  /** 可选背景：岗位 JD 文本 */
  jdText?: string
  /** 本次面试使用的简历版本 */
  resumeId?: number | null
  roundNo: number
  roundType: InterviewRoundType
  interviewerRole?: string
  format?: InterviewFormat | null
  interviewTime?: string | null
  idempotencyKey?: string
}

function query(params: Record<string, string | number | null | undefined>): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value))
    }
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

export function listReviewSessions(filters: ReviewFilters = {}): Promise<ReviewSessionPage> {
  return get<ReviewSessionPage>(`/review-sessions${query({ ...filters })}`)
}

/** 真实面试轮次列表：复盘详情用它读取本轮结果（契约暂无单条 GET）。 */
export function listInterviewEvents(filters: InterviewEventFilters = {}): Promise<InterviewEventPage> {
  return get<InterviewEventPage>(`/interview-events${query({ ...filters })}`)
}

export function updateInterviewEvent(
  id: number,
  payload: UpdateInterviewEventRequest,
): Promise<InterviewEventListItem> {
  return patch<InterviewEventListItem>(`/interview-events/${id}`, payload)
}

/** 删除面试轮次：存在关联复盘时后端返回 409，需先删除复盘。 */
export function deleteInterviewEvent(id: number): Promise<void> {
  return del<void>(`/interview-events/${id}`)
}

/** 创建复盘：音频与文本 / 笔记互斥；multipart 由浏览器自动设置 boundary。 */
export function createReviewSession(input: CreateReviewInput): Promise<ReviewSessionListItem> {
  const form = new FormData()
  if (input.audio) {
    form.append('audio', input.audio)
  }
  if (input.transcript) {
    form.append('transcript', input.transcript)
  }
  if (input.notes) {
    form.append('notes', input.notes)
  }
  if (input.applicationId) {
    form.append('applicationId', String(input.applicationId))
  }
  if (input.jobPositionId) {
    form.append('jobPositionId', String(input.jobPositionId))
  }
  if (input.companyId) {
    form.append('companyId', String(input.companyId))
  }
  if (input.companyName) {
    form.append('companyName', input.companyName)
  }
  if (input.jobTitle) {
    form.append('jobTitle', input.jobTitle)
  }
  if (input.companyIntro) {
    form.append('companyIntro', input.companyIntro)
  }
  if (input.jdText) {
    form.append('jdText', input.jdText)
  }
  if (input.resumeId) {
    form.append('resumeId', String(input.resumeId))
  }
  form.append('roundNo', String(input.roundNo))
  form.append('roundType', input.roundType)
  if (input.interviewerRole) {
    form.append('interviewerRole', input.interviewerRole)
  }
  if (input.format) {
    form.append('format', input.format)
  }
  if (input.interviewTime) {
    form.append('interviewTime', input.interviewTime)
  }
  if (input.idempotencyKey) {
    form.append('idempotencyKey', input.idempotencyKey)
  }
  return postForm<ReviewSessionListItem>('/review-sessions', form)
}

export function getReviewSession(id: number): Promise<ReviewSessionDetail> {
  return get<ReviewSessionDetail>(`/review-sessions/${id}`)
}

export function updateReviewSession(
  id: number,
  payload: UpdateReviewSessionRequest,
): Promise<ReviewSessionDetail> {
  return patch<ReviewSessionDetail>(`/review-sessions/${id}`, payload)
}

export function deleteReviewSession(id: number): Promise<void> {
  return del<void>(`/review-sessions/${id}`)
}

/** 清理复盘音频（NFR7）：只删磁盘音频与元信息，转写与分析保留。 */
export function clearReviewAudio(id: number): Promise<void> {
  return del<void>(`/review-sessions/${id}/audio`)
}

export function getReviewSessionStatus(id: number): Promise<ReviewSessionStatus> {
  return get<ReviewSessionStatus>(`/review-sessions/${id}/status`)
}

export function analyzeReviewSession(id: number): Promise<ReviewSessionListItem> {
  return post<ReviewSessionListItem>(`/review-sessions/${id}/analyze`)
}

export function transcribeReviewSession(id: number): Promise<ReviewSessionListItem> {
  return post<ReviewSessionListItem>(`/review-sessions/${id}/transcribe`)
}

export function updateReviewAnalysis(
  id: number,
  payload: ReviewAnalysisUpdateRequest,
): Promise<ReviewSessionDetail> {
  return put<ReviewSessionDetail>(`/review-sessions/${id}/analysis`, payload)
}

export function listImprovementActions(
  filters: ImprovementActionFilters = {},
): Promise<ImprovementActionPage> {
  return get<ImprovementActionPage>(`/improvement-actions${query({ ...filters })}`)
}

export function createImprovementAction(
  payload: CreateImprovementActionRequest,
): Promise<ImprovementAction> {
  return post<ImprovementAction>('/improvement-actions', payload)
}

export function updateImprovementAction(
  id: number,
  payload: UpdateImprovementActionRequest,
): Promise<ImprovementAction> {
  return patch<ImprovementAction>(`/improvement-actions/${id}`, payload)
}

export function deleteImprovementAction(id: number): Promise<void> {
  return del<void>(`/improvement-actions/${id}`)
}

export function getReviewTaxonomy(): Promise<ReviewTaxonomy> {
  return get<ReviewTaxonomy>('/review-taxonomy')
}
