/**
 * 跨场洞察接口（F5）：类型来自 contracts/openapi.yaml 生成的 schema，禁止手写数据结构。
 */
import type { components } from './schema'
import { get } from './http'

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

export interface InsightFilters {
  days?: number
  companyId?: number | null
  jobPositionId?: number | null
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
