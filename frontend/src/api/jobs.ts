/**
 * 岗位库接口（F2）：JD 导入解析、匹配评分、行情洞察、公司联想、场景绑定。
 * 类型全部来自 contracts/openapi.yaml 生成的 schema，禁止手写数据结构。
 */
import type { components } from './schema'
import { ApiError, del, get, post, put } from './http'

export type JobPositionListItem = components['schemas']['JobPositionListItem']
export type JobPositionDetail = components['schemas']['JobPositionDetail']
export type JobPositionStatus = components['schemas']['JobPositionStatus']
export type UpdateJobPositionRequest = components['schemas']['UpdateJobPositionRequest']
export type JobMatch = components['schemas']['JobMatch']
export type JobMatchDetail = components['schemas']['JobMatchDetail']
export type MatchDimension = components['schemas']['MatchDimension']
export type JobMarketInsights = components['schemas']['JobMarketInsights']
export type CountItem = components['schemas']['CountItem']
export type SkillCountItem = components['schemas']['SkillCountItem']
export type Company = components['schemas']['Company']
export type UpdateCompanyRequest = components['schemas']['UpdateCompanyRequest']
export type MergeCompaniesRequest = components['schemas']['MergeCompaniesRequest']
export type SceneBinding = components['schemas']['SceneBinding']
export type LlmScene = components['schemas']['LlmScene']

/** 分页结构由契约派生，仅把 items 收紧为必填。 */
export type JobPositionPage = NonNullable<components['schemas']['ResultJobPositionPage']['data']> & {
  items: JobPositionListItem[]
}
export type JobMatchPage = NonNullable<components['schemas']['ResultJobMatchPage']['data']> & {
  items: JobMatch[]
}
export type CompanyPage = NonNullable<components['schemas']['ResultCompanyPage']['data']> & {
  items: Company[]
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

export interface JobFilters {
  page?: number
  size?: number
  status?: JobPositionStatus | ''
  city?: string
  keyword?: string
}

export function listJobPositions(filters: JobFilters = {}): Promise<JobPositionPage> {
  return get<JobPositionPage>(`/job-positions${query({
    page: filters.page,
    size: filters.size,
    status: filters.status,
    city: filters.city,
    keyword: filters.keyword,
  })}`)
}

export function getJobPosition(id: number): Promise<JobPositionDetail> {
  return get<JobPositionDetail>(`/job-positions/${id}`)
}

export function updateJobPosition(id: number, payload: UpdateJobPositionRequest): Promise<JobPositionListItem> {
  return put<JobPositionListItem>(`/job-positions/${id}`, payload)
}

export function deleteJobPosition(id: number): Promise<void> {
  return del<void>(`/job-positions/${id}`)
}

export function batchDeleteJobPositions(ids: number[]): Promise<void> {
  return post<void>('/job-positions/batch-delete', { ids })
}

export function retryJobParse(id: number): Promise<JobPositionListItem> {
  return post<JobPositionListItem>(`/job-positions/${id}/parse`)
}

/** 导入 JD：file / text / images 三选一；截图由后端视觉识别为文本。 */
export async function importJobPosition(input: {
  file?: File
  text?: string
  images?: File[]
}): Promise<JobPositionListItem> {
  const form = new FormData()
  if (input.file) {
    form.append('file', input.file)
  }
  if (input.text) {
    form.append('text', input.text)
  }
  input.images?.forEach((image) => form.append('images', image))
  const response = await fetch('/api/job-positions', { method: 'POST', body: form })
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
      envelope?.message ?? `导入失败（HTTP ${response.status}）`,
    )
  }
  return envelope.data as JobPositionListItem
}

export interface MatchFilters {
  page?: number
  size?: number
  resumeId?: number
  jobPositionId?: number
}

export function listJobMatches(filters: MatchFilters = {}): Promise<JobMatchPage> {
  return get<JobMatchPage>(`/job-matches${query({ ...filters })}`)
}

export function createJobMatch(resumeId: number, jobPositionId: number): Promise<JobMatch> {
  return post<JobMatch>('/job-matches', { resumeId, jobPositionId })
}

export function getJobMatch(id: number): Promise<JobMatch> {
  return get<JobMatch>(`/job-matches/${id}`)
}

export function getMarketInsights(): Promise<JobMarketInsights> {
  return get<JobMarketInsights>('/job-market/insights')
}

export function listCompanies(keyword = '', size = 100): Promise<CompanyPage> {
  return get<CompanyPage>(`/companies${query({ keyword, size })}`)
}

export function updateCompany(id: number, payload: UpdateCompanyRequest): Promise<Company> {
  return put<Company>(`/companies/${id}`, payload)
}

export function mergeCompanies(payload: MergeCompaniesRequest): Promise<Company> {
  return post<Company>('/companies/merge', payload)
}

export function listSceneBindings(): Promise<SceneBinding[]> {
  return get<SceneBinding[]>('/llm/scenes')
}

export function bindSceneModel(scene: LlmScene, providerKey: string, model: string): Promise<SceneBinding> {
  return put<SceneBinding>(`/llm/scenes/${scene}`, { providerKey, model })
}

export function unbindSceneModel(scene: LlmScene): Promise<SceneBinding> {
  return del<SceneBinding>(`/llm/scenes/${scene}`)
}
