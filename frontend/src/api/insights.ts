/**
 * 跨场洞察接口（F5）：类型来自 contracts/openapi.yaml 生成的 schema，禁止手写数据结构。
 */
import type { components } from './schema'
import { get, post } from './http'

export type InsightsOverview = components['schemas']['InsightsOverview']
export type InsightSample = components['schemas']['InsightSample']
export type InsightSummary = components['schemas']['InsightSummary']
export type InsightTopicOutcome = components['schemas']['InsightTopicOutcome']
export type InsightTagCount = components['schemas']['InsightTagCount']
export type InsightRoundPassRate = components['schemas']['InsightRoundPassRate']
export type InsightCompanyProfile = components['schemas']['InsightCompanyProfile']
export type InsightQuestion = components['schemas']['InsightQuestion']
export type InsightFunnel = components['schemas']['InsightFunnel']
export type InsightResumeFunnel = components['schemas']['InsightResumeFunnel']
export type InsightImprovements = components['schemas']['InsightImprovements']
export type InsightTrend = components['schemas']['InsightTrend']
export type InsightEventItem = components['schemas']['InsightEventItem']
export type InsightsSummaryResponse = components['schemas']['InsightsSummaryResponse']

export type InsightEventPage = NonNullable<
  components['schemas']['ResultInsightEventPage']['data']
> & {
  items: InsightEventItem[]
}

export interface InsightFilters {
  days?: number
  companyId?: number | null
  jobPositionId?: number | null
}

export interface InsightEventFilters extends InsightFilters {
  page?: number
  size?: number
  result?: string
  roundType?: string
  topic?: string
}

export function getInsights(filters: InsightFilters = {}): Promise<InsightsOverview> {
  const search = new URLSearchParams()
  if (filters.days) {
    search.set('days', String(filters.days))
  }
  if (filters.companyId) {
    search.set('companyId', String(filters.companyId))
  }
  if (filters.jobPositionId) {
    search.set('jobPositionId', String(filters.jobPositionId))
  }
  const query = search.toString()
  return get<InsightsOverview>(`/insights${query ? `?${query}` : ''}`)
}

export function listInsightInterviewEvents(
  filters: InsightEventFilters = {},
): Promise<InsightEventPage> {
  const search = new URLSearchParams()
  if (filters.page) {
    search.set('page', String(filters.page))
  }
  if (filters.size) {
    search.set('size', String(filters.size))
  }
  if (filters.days) {
    search.set('days', String(filters.days))
  }
  if (filters.companyId) {
    search.set('companyId', String(filters.companyId))
  }
  if (filters.jobPositionId) {
    search.set('jobPositionId', String(filters.jobPositionId))
  }
  if (filters.result) {
    search.set('result', filters.result)
  }
  if (filters.roundType) {
    search.set('roundType', filters.roundType)
  }
  if (filters.topic) {
    search.set('topic', filters.topic)
  }
  const query = search.toString()
  return get<InsightEventPage>(`/insights/interview-events${query ? `?${query}` : ''}`)
}

/** AI 总结（FR5.8 可选）：失败时后端返回 degraded=true 并回退本地规则总结。 */
export function summarizeInsights(filters: InsightFilters = {}): Promise<InsightsSummaryResponse> {
  return post<InsightsSummaryResponse>('/insights/summary', {
    days: filters.days ?? 90,
    companyId: filters.companyId ?? null,
    jobPositionId: filters.jobPositionId ?? null,
  })
}
