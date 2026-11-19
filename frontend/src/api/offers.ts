/**
 * Offer 决策接口（F6）：CRUD、提取、固定维度对比与前景分析。
 * 类型全部来自 contracts/openapi.yaml 生成的 schema，禁止手写数据结构。
 */
import type { components } from './schema'
import { ApiError, del, get, post, put } from './http'
import { query } from './query'

export type Offer = components['schemas']['Offer']
export type OfferDetail = components['schemas']['OfferDetail']
export type OfferGroup = components['schemas']['OfferGroup']
export type OfferAnalysis = components['schemas']['OfferAnalysis']
export type OfferComparison = components['schemas']['OfferComparison']
export type OfferExtraction = components['schemas']['OfferExtraction']
export type CreateOfferRequest = components['schemas']['CreateOfferRequest']
export type UpdateOfferRequest = components['schemas']['UpdateOfferRequest']

/** 分页结构由契约派生，仅把 items 收紧为必填。 */
export type OfferPage = NonNullable<components['schemas']['ResultOfferPage']['data']> & {
  items: Offer[]
}

export interface OfferFilters {
  page?: number
  size?: number
  group?: OfferGroup | ''
}

export function listOffers(filters: OfferFilters = {}): Promise<OfferPage> {
  return get<OfferPage>(`/offers${query({ ...filters })}`)
}

export function getOffer(id: number): Promise<OfferDetail> {
  return get<OfferDetail>(`/offers/${id}`)
}

export function createOffer(payload: CreateOfferRequest): Promise<OfferDetail> {
  return post<OfferDetail>('/offers', payload)
}

export function updateOffer(id: number, payload: UpdateOfferRequest): Promise<OfferDetail> {
  return put<OfferDetail>(`/offers/${id}`, payload)
}

export function deleteOffer(id: number): Promise<void> {
  return del<void>(`/offers/${id}`)
}

export function analyzeOffer(id: number, webSearch = false): Promise<OfferAnalysis> {
  return post<OfferAnalysis>(`/offers/${id}/analyze`, { webSearch })
}

export function getOfferComparison(): Promise<OfferComparison> {
  return get<OfferComparison>('/offers/comparison')
}

/** 从文本或图片提取 Offer 草稿（不落库）。 */
export async function extractOffer(input: { text?: string; file?: File }): Promise<OfferExtraction> {
  const form = new FormData()
  if (input.text) {
    form.append('text', input.text)
  }
  if (input.file) {
    form.append('image', input.file)
  }
  const response = await fetch('/api/offers/extract', { method: 'POST', body: form })
  const text = await response.text()
  let envelope: { code: number; message?: string; data?: unknown } | null = null
  try {
    envelope = text ? JSON.parse(text) : null
  } catch {
    envelope = null
  }
  if (!envelope || envelope.code !== 0) {
    throw new ApiError(
      envelope?.code ?? response.status,
      envelope?.message ?? `提取失败（HTTP ${response.status}）`,
    )
  }
  return envelope.data as OfferExtraction
}
