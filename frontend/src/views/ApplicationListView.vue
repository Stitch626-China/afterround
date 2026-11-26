<script setup lang="ts">
/**
 * 投递档案（F8）：记录投递 → 状态流转 → 时间线查看。
 * 状态向前同步岗位状态（只升不降，撤回不影响岗位），由后端保证。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createJobApplication,
  deleteJobApplication,
  getJobApplicationTimeline,
  listJobApplications,
  updateJobApplication,
  type JobApplicationListItem,
  type JobApplicationStatus,
  type JobApplicationTimeline,
} from '@/api/applications'
import { listJobPositions, type JobPositionListItem } from '@/api/jobs'
import {
  deleteInterviewEvent,
  updateInterviewEvent,
  type InterviewEventListItem,
  type InterviewFormat,
  type InterviewRoundType,
} from '@/api/reviews'
import { listResumes, type ResumeListItem } from '@/api/resumes'
import EmptyState from '@/components/EmptyState.vue'
import {
  FORMATS,
  FORMAT_TEXT,
  INTERVIEW_RESULT_TEXT,
  REVIEW_STATUS_TEXT,
  ROUND_TYPES,
  ROUND_TYPE_TEXT,
  pendingVerifyActions,
} from '@/utils/review'

const items = ref<JobApplicationListItem[]>([])
const jobs = ref<JobPositionListItem[]>([])
const resumes = ref<ResumeListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const error = ref('')
const notice = ref('')

const filters = reactive<{ status: JobApplicationStatus | ''; keyword: string }>({
  status: '',
  keyword: '',
})
const formOpen = ref(false)
const saving = ref(false)
const draft = reactive<{
  jobPositionId: number | null
  resumeId: number
  channel: string
  appliedAt: string
  status: JobApplicationStatus
  outcome: string
  notes: string
}>({
  jobPositionId: null,
  resumeId: 0,
  channel: '',
  appliedAt: '',
  status: 'APPLIED',
  outcome: '',
  notes: '',
})
const confirmId = ref<number | null>(null)
const timelineId = ref<number | null>(null)
const timeline = ref<JobApplicationTimeline | null>(null)

/** 面试事件编辑（FR5.1）：复用 PATCH /interview-events；无复盘的事件可删除。 */
const eventEditId = ref<number | null>(null)
const eventSaving = ref(false)
const eventDeletingId = ref<number | null>(null)
const eventConfirmId = ref<number | null>(null)
const eventDraft = reactive<{
  roundType: InterviewRoundType
  interviewerRole: string
  format: InterviewFormat | ''
  interviewTime: string
  notes: string
}>({ roundType: 'TECH', interviewerRole: '', format: '', interviewTime: '', notes: '' })
const EVENT_RESULT_TONE: Record<string, string> = {
  PASS: 'success',
  FAIL: 'danger',
  PENDING: 'muted',
}

/** FR5.6：最近一轮面试后仍未验证的旧行动，用于时间线提示。 */
const pendingVerifyCount = computed(() =>
  timeline.value ? pendingVerifyActions(timeline.value.actions, timeline.value.events).length : 0,
)
const latestRoundNo = computed(() =>
  timeline.value ? timeline.value.events.reduce((max, event) => Math.max(max, event.roundNo), 0) : 0,
)

const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const STATUSES: JobApplicationStatus[] = ['APPLIED', 'INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN']
const STATUS_TEXT: Record<string, string> = {
  APPLIED: '已投递',
  INTERVIEWING: '面试中',
  OFFERED: '已录用',
  REJECTED: '已淘汰',
  WITHDRAWN: '已撤回',
}
const STATUS_TONE: Record<string, string> = {
  APPLIED: 'primary',
  INTERVIEWING: 'warning',
  OFFERED: 'success',
  REJECTED: 'danger',
  WITHDRAWN: 'muted',
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listJobApplications({
      page: page.value,
      size,
      status: filters.status || undefined,
      keyword: filters.keyword.trim() || undefined,
    })
    items.value = result.items
    total.value = result.total
    error.value = ''
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadOptions(): Promise<void> {
  try {
    const [jobPage, resumePage] = await Promise.all([
      listJobPositions({ page: 1, size: 100 }),
      listResumes(1, 100),
    ])
    jobs.value = jobPage.items
    resumes.value = resumePage.items
  } catch {
    // 选项加载失败不阻断列表：创建面板会显示「暂无可选岗位」
  }
}

function search(): void {
  page.value = 1
  void load()
}

function reset(): void {
  filters.status = ''
  filters.keyword = ''
  search()
}

function go(delta: number): void {
  const next = page.value + delta
  if (next < 1 || next > pages.value) {
    return
  }
  page.value = next
  void load()
}

async function create(): Promise<void> {
  if (!draft.jobPositionId) {
    error.value = '请选择岗位'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await createJobApplication({
      jobPositionId: draft.jobPositionId,
      resumeId: draft.resumeId || null,
      channel: draft.channel.trim() || null,
      appliedAt: draft.appliedAt ? new Date(draft.appliedAt).toISOString() : null,
      status: draft.status,
      outcome: draft.outcome.trim() || null,
      notes: draft.notes.trim() || null,
    })
    notice.value = '已记录投递，岗位状态已同步'
    formOpen.value = false
    draft.jobPositionId = null
    draft.resumeId = 0
    draft.channel = ''
    draft.appliedAt = ''
    draft.status = 'APPLIED'
    draft.outcome = ''
    draft.notes = ''
    page.value = 1
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '记录投递失败'
  } finally {
    saving.value = false
  }
}

async function changeStatus(item: JobApplicationListItem, event: Event): Promise<void> {
  const status = (event.target as HTMLSelectElement).value as JobApplicationStatus
  error.value = ''
  try {
    await updateJobApplication(item.id, { status })
    notice.value = `已更新为「${STATUS_TEXT[status] ?? status}」`
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '更新状态失败'
    await load()
  }
}

async function remove(item: JobApplicationListItem): Promise<void> {
  error.value = ''
  try {
    await deleteJobApplication(item.id)
    confirmId.value = null
    if (items.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    notice.value = '投递记录已删除'
    if (timelineId.value === item.id) {
      timelineId.value = null
      timeline.value = null
    }
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '删除失败'
  }
}

async function toggleTimeline(item: JobApplicationListItem): Promise<void> {
  if (timelineId.value === item.id) {
    timelineId.value = null
    timeline.value = null
    eventEditId.value = null
    eventConfirmId.value = null
    return
  }
  error.value = ''
  try {
    timeline.value = await getJobApplicationTimeline(item.id)
    timelineId.value = item.id
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载时间线失败'
  }
}

/** ISO 时间转 datetime-local（本地时区，精确到分钟）。 */
function toLocalInput(value: string): string {
  const date = new Date(value)
  const pad = (part: number): string => String(part).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function startEventEdit(event: InterviewEventListItem): void {
  eventEditId.value = event.id
  eventConfirmId.value = null
  eventDraft.roundType = event.roundType
  eventDraft.interviewerRole = event.interviewerRole ?? ''
  eventDraft.format = event.format ?? ''
  eventDraft.interviewTime = toLocalInput(event.interviewTime)
  eventDraft.notes = event.notes ?? ''
}

async function reloadTimeline(): Promise<void> {
  const applicationId = timelineId.value
  if (!applicationId) {
    return
  }
  try {
    timeline.value = await getJobApplicationTimeline(applicationId)
  } catch {
    // 刷新失败保留当前时间线，避免打断编辑
  }
}

async function saveEvent(event: InterviewEventListItem): Promise<void> {
  eventSaving.value = true
  error.value = ''
  try {
    await updateInterviewEvent(event.id, {
      roundType: eventDraft.roundType,
      interviewerRole: eventDraft.interviewerRole.trim() || null,
      format: eventDraft.format || null,
      interviewTime: eventDraft.interviewTime
        ? new Date(eventDraft.interviewTime).toISOString()
        : null,
      notes: eventDraft.notes.trim() || null,
    })
    eventEditId.value = null
    notice.value = '面试信息已更新'
    await reloadTimeline()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '更新面试信息失败'
  } finally {
    eventSaving.value = false
  }
}

async function removeEvent(event: InterviewEventListItem): Promise<void> {
  eventDeletingId.value = event.id
  error.value = ''
  try {
    await deleteInterviewEvent(event.id)
    eventConfirmId.value = null
    notice.value = '面试轮次已删除'
    await reloadTimeline()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '删除面试轮次失败'
  } finally {
    eventDeletingId.value = null
  }
}

function meta(item: JobApplicationListItem): string {
  return [item.companyName, item.channel, formatTime(item.appliedAt), item.resumeFileName]
    .filter(Boolean)
    .join(' · ')
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function statusText(status: string): string {
  return REVIEW_STATUS_TEXT[status] ?? status
}

function salary(value: number | null | undefined): string {
  return value == null ? '薪资未填' : `${Math.round(value / 1000)}K`
}

onMounted(() => {
  void load()
  void loadOptions()
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="lead">
        一次投递 = 岗位 + 简历版本；状态向前同步岗位，投递时间线串起后续面试与 Offer。
      </p>
      <button type="button" class="btn" :class="formOpen ? 'ghost' : 'primary'" @click="formOpen = !formOpen">
        {{ formOpen ? '收起' : '记录投递' }}
      </button>
    </header>

    <section v-if="formOpen" class="form">
      <div class="form-grid">
        <label>
          岗位
          <select v-model.number="draft.jobPositionId">
            <option :value="null" disabled>选择岗位</option>
            <option v-for="job in jobs" :key="job.id" :value="job.id">
              {{ job.title || '解析中…' }}{{ job.companyName ? ` · ${job.companyName}` : '' }}
            </option>
          </select>
        </label>
        <label>
          简历版本
          <select v-model.number="draft.resumeId">
            <option :value="0">不指定</option>
            <option v-for="resume in resumes" :key="resume.id" :value="resume.id">
              {{ resume.fileName }}
            </option>
          </select>
        </label>
        <label>
          投递渠道
          <input v-model="draft.channel" maxlength="64" placeholder="BOSS / 官网 / 内推" />
        </label>
        <label>
          投递时间
          <input v-model="draft.appliedAt" type="datetime-local" />
        </label>
        <label>
          初始状态
          <select v-model="draft.status">
            <option v-for="status in STATUSES" :key="status" :value="status">{{ STATUS_TEXT[status] }}</option>
          </select>
        </label>
        <label>
          结果备注
          <input v-model="draft.outcome" maxlength="500" placeholder="如 简历淘汰 / 面试淘汰（可选）" />
        </label>
      </div>
      <textarea v-model="draft.notes" rows="2" maxlength="2000" placeholder="备注（可选）" />
      <div class="actions">
        <button type="button" class="btn primary" :disabled="saving" @click="create">
          {{ saving ? '保存中…' : '保存' }}
        </button>
        <button type="button" class="btn ghost" @click="formOpen = false">取消</button>
      </div>
    </section>

    <div class="filters">
      <select v-model="filters.status" @change="search">
        <option value="">全部状态</option>
        <option v-for="status in STATUSES" :key="status" :value="status">{{ STATUS_TEXT[status] }}</option>
      </select>
      <input
        v-model="filters.keyword"
        type="search"
        placeholder="公司名或岗位名"
        @keyup.enter="search"
      />
      <button type="button" class="btn ghost" @click="search">查询</button>
      <button type="button" class="btn ghost" @click="reset">重置</button>
    </div>

    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-else-if="notice" class="banner success">{{ notice }}</div>

    <section v-if="loading" class="skeleton">
      <div class="skeleton-row" />
      <div class="skeleton-row" />
    </section>

    <EmptyState
      v-else-if="!items.length"
      title="还没有投递记录"
      hint="从岗位库选择一个岗位记录投递；后续真实面试与 Offer 都会挂在这条投递上。"
      action-label="记录投递"
      @action="formOpen = true"
    />

    <template v-else>
      <ul class="list">
        <li v-for="item in items" :key="item.id" class="row">
          <div class="row-main">
            <div class="row-title-line">
              <strong class="row-title">{{ item.jobTitle || '未命名岗位' }}</strong>
              <span class="status" :class="STATUS_TONE[item.status]">
                {{ STATUS_TEXT[item.status] ?? item.status }}
              </span>
              <span v-if="item.interviewCount" class="tag">{{ item.interviewCount }} 轮面试</span>
            </div>
            <p class="row-meta">{{ meta(item) }}</p>
            <p v-if="item.outcome" class="row-outcome">结果：{{ item.outcome }}</p>
          </div>
          <div class="row-actions">
            <select :value="item.status" aria-label="更新投递状态" @change="changeStatus(item, $event)">
              <option v-for="status in STATUSES" :key="status" :value="status">
                {{ STATUS_TEXT[status] }}
              </option>
            </select>
            <button type="button" class="link" @click="toggleTimeline(item)">
              {{ timelineId === item.id ? '收起时间线' : '时间线' }}
            </button>
            <template v-if="confirmId === item.id">
              <button type="button" class="link danger" @click="remove(item)">确认删除</button>
              <button type="button" class="link" @click="confirmId = null">取消</button>
            </template>
            <button v-else type="button" class="link danger" @click="confirmId = item.id">删除</button>
          </div>
          <div v-if="timelineId === item.id && timeline" class="timeline">
            <div v-if="pendingVerifyCount" class="banner warning">
              第 {{ latestRoundNo }} 轮面试已记录，有 {{ pendingVerifyCount }} 条旧行动待验证
              <RouterLink to="/improvements" class="link">去验证 →</RouterLink>
            </div>
            <p class="timeline-line">
              投递于 {{ formatTime(timeline.application.appliedAt) }}
              <span v-if="timeline.application.channel"> · {{ timeline.application.channel }}</span>
            </p>
            <div v-if="timeline.offer" class="timeline-offer">
              <strong>Offer</strong>
              <span>
                {{ timeline.offer.companyName || '公司未填' }} ·
                {{ timeline.offer.jobPositionTitle || '岗位未填' }} ·
                {{ salary(timeline.offer.monthlySalary) }} ·
                {{ timeline.offer.group === 'KEEP' ? '保留' : '淘汰' }}
              </span>
            </div>
            <p v-else class="timeline-empty">暂无关联 Offer；可在 Offer 决策中关联这条投递。</p>
            <p class="timeline-empty">
              面试轮次 {{ timeline.events.length }} 条、改进行动 {{ timeline.actions.length }} 条。
            </p>
            <ul v-if="timeline.events.length" class="timeline-events">
              <li v-for="event in timeline.events" :key="event.id" class="timeline-event">
                <div class="timeline-event-line">
                  <span>第 {{ event.roundNo }} 轮</span>
                  <span class="tag">{{ ROUND_TYPE_TEXT[event.roundType] ?? event.roundType }}</span>
                  <span class="status" :class="EVENT_RESULT_TONE[event.result]">
                    {{ INTERVIEW_RESULT_TEXT[event.result] ?? event.result }}
                  </span>
                  <span class="meta">{{ formatTime(event.interviewTime) }}</span>
                  <span v-if="event.interviewerRole" class="meta">{{ event.interviewerRole }}</span>
                  <span v-if="event.format" class="meta">{{ FORMAT_TEXT[event.format] ?? event.format }}</span>
                  <button
                    v-if="eventEditId !== event.id"
                    type="button"
                    class="link"
                    @click="startEventEdit(event)"
                  >
                    编辑
                  </button>
                  <template v-if="!event.reviewSessionId">
                    <template v-if="eventConfirmId === event.id">
                      <button
                        type="button"
                        class="link danger"
                        :disabled="eventDeletingId === event.id"
                        @click="removeEvent(event)"
                      >
                        确认删除
                      </button>
                      <button type="button" class="link" @click="eventConfirmId = null">取消</button>
                    </template>
                    <button v-else type="button" class="link danger" @click="eventConfirmId = event.id">
                      删除
                    </button>
                  </template>
                  <RouterLink v-else :to="`/reviews/${event.reviewSessionId}`" class="link">
                    复盘 →
                  </RouterLink>
                </div>
                <div v-if="eventEditId === event.id" class="event-edit">
                  <label>
                    轮次类型
                    <select v-model="eventDraft.roundType">
                      <option v-for="type in ROUND_TYPES" :key="type" :value="type">
                        {{ ROUND_TYPE_TEXT[type] }}
                      </option>
                    </select>
                  </label>
                  <label>
                    面试官
                    <input v-model="eventDraft.interviewerRole" maxlength="64" placeholder="如 技术负责人" />
                  </label>
                  <label>
                    形式
                    <select v-model="eventDraft.format">
                      <option value="">未填写</option>
                      <option v-for="format in FORMATS" :key="format" :value="format">
                        {{ FORMAT_TEXT[format] }}
                      </option>
                    </select>
                  </label>
                  <label>
                    时间
                    <input v-model="eventDraft.interviewTime" type="datetime-local" />
                  </label>
                  <label class="wide">
                    备注
                    <input v-model="eventDraft.notes" maxlength="1000" placeholder="备注（可选）" />
                  </label>
                  <div class="event-actions">
                    <button
                      type="button"
                      class="btn ghost"
                      :disabled="eventSaving"
                      @click="saveEvent(event)"
                    >
                      {{ eventSaving ? '保存中…' : '保存' }}
                    </button>
                    <button type="button" class="btn quiet" @click="eventEditId = null">取消</button>
                  </div>
                </div>
              </li>
            </ul>
            <ul v-if="timeline.reviews.length" class="timeline-reviews">
              <li v-for="review in timeline.reviews" :key="review.id">
                <RouterLink :to="`/reviews/${review.id}`" class="link">
                  第 {{ review.roundNo }} 轮复盘 · {{ statusText(review.status) }} →
                </RouterLink>
              </li>
            </ul>
            <RouterLink v-else to="/reviews" class="link">还没有复盘，去创建 →</RouterLink>
          </div>
        </li>
      </ul>

      <div v-if="pages > 1" class="pager">
        <button type="button" class="btn ghost" :disabled="page <= 1" @click="go(-1)">上一页</button>
        <span>{{ page }} / {{ pages }}</span>
        <button type="button" class="btn ghost" :disabled="page >= pages" @click="go(1)">下一页</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.lead {
  margin: 0;
  max-width: 680px;
  color: var(--text-2);
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-5);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-3);
}

.form label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: var(--text-3);
  font-size: var(--fs-overline);
  font-weight: 500;
  letter-spacing: 0.04em;
}

.form select,
.form input,
.form textarea,
.filters select,
.filters input,
.row-actions select {
  height: 34px;
  padding: 0 var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: var(--fs-sm);
}

.form textarea {
  height: auto;
  padding: var(--space-2) var(--space-3);
  resize: vertical;
}

.form select:focus-visible,
.form input:focus-visible,
.form textarea:focus-visible,
.filters select:focus-visible,
.filters input:focus-visible,
.row-actions select:focus-visible {
  outline: 1px solid var(--primary);
  outline-offset: 0;
  box-shadow: 0 0 0 3px var(--primary-soft);
}

.actions {
  display: flex;
  gap: var(--space-2);
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.list {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  list-style: none;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  overflow: hidden;
}

.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  padding: 12px 20px;
  border-bottom: 1px solid var(--border);
  transition: background var(--dur-1) var(--ease);
}

.row:last-child {
  border-bottom: none;
}

.row:hover {
  background: var(--surface-muted);
}

.row-main {
  flex: 1 1 260px;
  min-width: 0;
}

.row-title-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.row-title {
  font-size: 13.5px;
  font-weight: 500;
}

.row-meta,
.row-outcome {
  margin: 4px 0 0;
  color: var(--text-3);
  font-size: 12px;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.timeline {
  flex: 1 1 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.timeline-line,
.timeline-empty {
  margin: 0;
  color: var(--text-3);
  font-size: 12px;
}

.timeline-offer {
  display: flex;
  gap: var(--space-2);
  font-size: 12px;
  color: var(--text-2);
}

.timeline-reviews {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding-left: var(--space-4);
  font-size: 12px;
}

.timeline-events {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.timeline-event {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.timeline-event-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-2);
  font-size: 12px;
}

.event-edit {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--space-2);
  padding: var(--space-3);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-btn);
}

.event-edit label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: var(--text-3);
  font-size: var(--fs-overline);
}

.event-edit label.wide,
.event-actions {
  grid-column: 1 / -1;
}

.event-edit select,
.event-edit input {
  height: 30px;
  padding: 0 var(--space-2);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-size: 12.5px;
}

.event-actions {
  display: flex;
  gap: var(--space-2);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  color: var(--text-3);
  font-size: var(--fs-sm);
}
</style>
