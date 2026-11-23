/**
 * 投递档案接口（F8）：记录投递、状态流转、时间线与删除。
 * 类型来自 contracts/openapi.yaml 生成的 schema，禁止手写数据结构。
 */
import type { components } from './schema'
import { del, get, patch, post } from './http'

export type JobApplicationListItem = components['schemas']['JobApplicationListItem']
export type JobApplicationStatus = components['schemas']['JobApplicationStatus']
export type JobApplicationTimeline = components['schemas']['JobApplicationTimeline']
export type CreateJobApplicationRequest = components['schemas']['CreateJobApplicationRequest']
export type UpdateJobApplicationRequest = components['schemas']['UpdateJobApplicationRequest']

export type JobApplicationPage = NonNullable<components['schemas']['ResultJobApplicationPage']['data']> & {
  items: JobApplicationListItem[]
}

export interface ApplicationFilters {
  page?: number
  size?: number
  status?: JobApplicationStatus | ''
  jobPositionId?: number
  resumeId?: number
  keyword?: string
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

export function listJobApplications(filters: ApplicationFilters = {}): Promise<JobApplicationPage> {
  return get<JobApplicationPage>(`/job-applications${query({ ...filters })}`)
}

export function createJobApplication(payload: CreateJobApplicationRequest): Promise<JobApplicationListItem> {
  return post<JobApplicationListItem>('/job-applications', payload)
}

export function updateJobApplication(
  id: number,
  payload: UpdateJobApplicationRequest,
): Promise<JobApplicationListItem> {
  return patch<JobApplicationListItem>(`/job-applications/${id}`, payload)
}

export function deleteJobApplication(id: number): Promise<void> {
  return del<void>(`/job-applications/${id}`)
}

export function getJobApplicationTimeline(id: number): Promise<JobApplicationTimeline> {
  return get<JobApplicationTimeline>(`/job-applications/${id}/timeline`)
}
