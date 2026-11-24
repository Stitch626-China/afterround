<script setup lang="ts">
/**
 * 真实面试复盘列表（F4）：录音 / 转写文本 / 手动笔记三种输入，异步转写与分析，失败可重试。
 * 状态轮询只在存在处理中的记录时开启，离开页面即清理。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  analyzeReviewSession,
  createReviewSession,
  deleteReviewSession,
  listReviewSessions,
  transcribeReviewSession,
  type InterviewFormat,
  type InterviewRoundType,
  type ReviewInputType,
  type ReviewSessionListItem,
  type ReviewStatus,
} from '@/api/reviews'
import { listJobApplications, type JobApplicationListItem } from '@/api/applications'
import EmptyState from '@/components/EmptyState.vue'
import {
  FORMATS,
  FORMAT_TEXT,
  INPUT_TYPES,
  INPUT_TYPE_TEXT,
  REVIEW_STATUS_TEXT,
  REVIEW_STATUS_TONE,
  ROUND_TYPES,
  ROUND_TYPE_TEXT,
  isProcessing,
} from '@/utils/review'

const items = ref<ReviewSessionListItem[]>([])
const applications = ref<JobApplicationListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const statusFilter = ref<ReviewStatus | ''>('')
const formOpen = ref(false)
const confirmId = ref<number | null>(null)

const draft = reactive<{
  applicationId: number | null
  roundNo: number
  roundType: InterviewRoundType
  interviewerRole: string
  format: InterviewFormat | ''
  interviewTime: string
  inputType: ReviewInputType
  transcript: string
  notes: string
  audio: File | null
}>({
  applicationId: null,
  roundNo: 1,
  roundType: 'TECH',
  interviewerRole: '',
  format: '',
  interviewTime: '',
  inputType: 'AUDIO',
  transcript: '',
  notes: '',
  audio: null,
})

const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const hasProcessing = computed(() => items.value.some((item) => isProcessing(item.status)))
let timer: ReturnType<typeof setInterval> | null = null

async function load(silent = false): Promise<void> {
  if (!silent) {
    loading.value = true
  }
  try {
    const result = await listReviewSessions({
      page: page.value,
      size,
      status: statusFilter.value || undefined,
    })
    items.value = result.items
    total.value = result.total
    error.value = ''
    syncPolling()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载失败'
  } finally {
    loading.value = false
  }
}

function syncPolling(): void {
  if (hasProcessing.value && !timer) {
    timer = setInterval(() => void load(true), 3000)
  } else if (!hasProcessing.value && timer) {
    clearInterval(timer)
    timer = null
  }
}

async function loadOptions(): Promise<void> {
  try {
    const result = await listJobApplications({ page: 1, size: 100 })
    applications.value = result.items
  } catch {
    // 下拉选项失败不阻断列表，提交时会给出明确提示
  }
}

function onApplicationChange(): void {
  const application = applications.value.find((item) => item.id === draft.applicationId)
  if (application) {
    draft.roundNo = (application.latestRoundNo ?? 0) + 1
  }
}

function onFile(event: Event): void {
  const file = (event.target as HTMLInputElement).files?.[0] ?? null
  if (file && file.size > 100 * 1024 * 1024) {
    error.value = '音频不能超过 100MB'
    draft.audio = null
    return
  }
  draft.audio = file
  error.value = ''
}

function resetDraft(): void {
  draft.applicationId = null
  draft.roundNo = 1
  draft.roundType = 'TECH'
  draft.interviewerRole = ''
  draft.format = ''
  draft.interviewTime = ''
  draft.inputType = 'AUDIO'
  draft.transcript = ''
  draft.notes = ''
  draft.audio = null
}

async function create(): Promise<void> {
  if (!draft.applicationId) {
    error.value = '请选择投递记录'
    return
  }
  if (!draft.roundNo || draft.roundNo < 1) {
    error.value = '轮次号需大于 0'
    return
  }
  if (draft.inputType === 'AUDIO' && !draft.audio) {
    error.value = '请选择面试录音'
    return
  }
  if (draft.inputType === 'TEXT' && !draft.transcript.trim()) {
    error.value = '请填写转写文本'
    return
  }
  if (draft.inputType === 'NOTES' && !draft.notes.trim()) {
    error.value = '请填写手动笔记'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await createReviewSession({
      audio: draft.inputType === 'AUDIO' ? draft.audio : null,
      transcript: draft.inputType === 'TEXT' ? draft.transcript.trim() : undefined,
      notes: draft.inputType === 'NOTES' ? draft.notes.trim() : undefined,
      applicationId: draft.applicationId,
      roundNo: draft.roundNo,
      roundType: draft.roundType,
      interviewerRole: draft.interviewerRole.trim() || undefined,
      format: draft.format || null,
      interviewTime: draft.interviewTime ? new Date(draft.interviewTime).toISOString() : null,
      idempotencyKey: crypto.randomUUID(),
    })
    notice.value = draft.inputType === 'AUDIO' ? '已上传录音，转写与分析进行中' : '已创建复盘，分析进行中'
    formOpen.value = false
    resetDraft()
    page.value = 1
    await load()
  } catch (ex) {
    // 失败时保留已填写内容，避免用户重输
    error.value = ex instanceof Error ? ex.message : '创建复盘失败'
  } finally {
    saving.value = false
  }
}

async function retryAsr(item: ReviewSessionListItem): Promise<void> {
  error.value = ''
  try {
    await transcribeReviewSession(item.id)
    notice.value = '已重新触发转写'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '触发转写失败'
  }
}

async function reanalyze(item: ReviewSessionListItem): Promise<void> {
  error.value = ''
  try {
    await analyzeReviewSession(item.id)
    notice.value = '已触发重新分析'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '触发分析失败'
  }
}

async function remove(item: ReviewSessionListItem): Promise<void> {
  error.value = ''
  try {
    await deleteReviewSession(item.id)
    confirmId.value = null
    if (items.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    notice.value = '复盘记录已删除（含转写、分析与生成的改进行动）'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '删除失败'
  }
}

function search(): void {
  page.value = 1
  void load()
}

function go(delta: number): void {
  const next = page.value + delta
  if (next < 1 || next > pages.value) {
    return
  }
  page.value = next
  void load()
}

function title(item: ReviewSessionListItem): string {
  return item.jobTitle || item.companyName || '未关联岗位'
}

function meta(item: ReviewSessionListItem): string {
  return [
    item.companyName && item.jobTitle ? item.companyName : '',
    item.roundNo ? `第 ${item.roundNo} 轮` : '',
    item.roundType ? (ROUND_TYPE_TEXT[item.roundType] ?? item.roundType) : '',
    INPUT_TYPE_TEXT[item.inputType] ?? item.inputType,
    formatTime(item.createdAt),
  ]
    .filter(Boolean)
    .join(' · ')
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  void load()
  void loadOptions()
})

onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="lead">
        把真实面试变成可复盘的证据：上传录音走语音转写，或直接粘贴转写文本 / 手写笔记；分析完成后确认改进行动。
      </p>
      <button type="button" class="btn" :class="formOpen ? 'ghost' : 'primary'" @click="formOpen = !formOpen">
        {{ formOpen ? '收起' : '新建复盘' }}
      </button>
    </header>

    <section v-if="formOpen" class="form">
      <div class="form-grid">
        <label>
          投递记录
          <select v-model.number="draft.applicationId" @change="onApplicationChange">
            <option :value="null" disabled>选择投递</option>
            <option v-for="application in applications" :key="application.id" :value="application.id">
              {{ application.jobTitle || '未命名岗位' }}
              {{ application.companyName ? ` · ${application.companyName}` : '' }}
            </option>
          </select>
        </label>
        <label>
          轮次号
          <input v-model.number="draft.roundNo" type="number" min="1" max="99" />
        </label>
        <label>
          轮次类型
          <select v-model="draft.roundType">
            <option v-for="type in ROUND_TYPES" :key="type" :value="type">
              {{ ROUND_TYPE_TEXT[type] }}
            </option>
          </select>
        </label>
        <label>
          面试形式
          <select v-model="draft.format">
            <option value="">未填写</option>
            <option v-for="format in FORMATS" :key="format" :value="format">
              {{ FORMAT_TEXT[format] }}
            </option>
          </select>
        </label>
        <label>
          面试官角色
          <input v-model="draft.interviewerRole" maxlength="64" placeholder="技术负责人 / HRBP" />
        </label>
        <label>
          面试时间
          <input v-model="draft.interviewTime" type="datetime-local" />
        </label>
      </div>

      <div class="tabs">
        <button
          v-for="type in INPUT_TYPES"
          :key="type"
          type="button"
          class="tab"
          :class="{ active: draft.inputType === type }"
          @click="draft.inputType = type"
        >
          {{ INPUT_TYPE_TEXT[type] }}
        </button>
      </div>

      <div v-if="draft.inputType === 'AUDIO'" class="input-block">
        <input type="file" accept=".mp3,.wav,.m4a,audio/mpeg,audio/wav,audio/mp4" @change="onFile" />
        <p class="hint">支持 mp3 / wav / m4a，≤100MB 或 2 小时；音频仅存本机。</p>
      </div>
      <textarea
        v-else-if="draft.inputType === 'TEXT'"
        v-model="draft.transcript"
        rows="6"
        maxlength="200000"
        placeholder="粘贴完整转写文本（建议保留面试官 / 候选人说话人标记）"
      />
      <textarea
        v-else
        v-model="draft.notes"
        rows="6"
        maxlength="20000"
        placeholder="记录题目、你的回答要点与当场感受"
      />

      <div v-if="!applications.length" class="banner warning">
        还没有投递记录；请先在「投递档案」记录投递，再创建复盘。
      </div>

      <div class="actions">
        <button type="button" class="btn primary" :disabled="saving" @click="create">
          {{ saving ? '提交中…' : '创建复盘' }}
        </button>
        <button type="button" class="btn ghost" @click="formOpen = false">取消</button>
      </div>
    </section>

    <div class="filters">
      <select v-model="statusFilter" @change="search">
        <option value="">全部状态</option>
        <option v-for="(text, status) in REVIEW_STATUS_TEXT" :key="status" :value="status">
          {{ text }}
        </option>
      </select>
      <button type="button" class="btn ghost" @click="search">刷新</button>
      <span v-if="hasProcessing" class="polling">处理中，每 3 秒自动刷新</span>
    </div>

    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-else-if="notice" class="banner success">{{ notice }}</div>

    <section v-if="loading" class="skeleton">
      <div class="skeleton-row" />
      <div class="skeleton-row" />
    </section>

    <EmptyState
      v-else-if="!items.length"
      title="还没有复盘记录"
      hint="面试结束后上传录音或粘贴转写文本，系统会提取问题、知识点与失分原因，并生成改进行动。"
      action-label="新建复盘"
      @action="formOpen = true"
    />

    <template v-else>
      <ul class="list">
        <li v-for="item in items" :key="item.id" class="row">
          <div class="row-main">
            <div class="row-title-line">
              <strong class="row-title">{{ title(item) }}</strong>
              <span class="status" :class="REVIEW_STATUS_TONE[item.status]">
                {{ REVIEW_STATUS_TEXT[item.status] ?? item.status }}
              </span>
              <span class="tag">{{ INPUT_TYPE_TEXT[item.inputType] }}</span>
            </div>
            <p class="row-meta">{{ meta(item) }}</p>
          </div>
          <div class="row-actions">
            <RouterLink :to="`/reviews/${item.id}`" class="link">查看详情</RouterLink>
            <button
              v-if="item.inputType === 'AUDIO' && item.asrStatus === 'FAILED'"
              type="button"
              class="link"
              @click="retryAsr(item)"
            >
              重试转写
            </button>
            <button
              v-if="item.asrStatus === 'COMPLETED' && !isProcessing(item.status)"
              type="button"
              class="link"
              @click="reanalyze(item)"
            >
              重新分析
            </button>
            <template v-if="confirmId === item.id">
              <button type="button" class="link danger" @click="remove(item)">确认删除</button>
              <button type="button" class="link" @click="confirmId = null">取消</button>
            </template>
            <button v-else type="button" class="link danger" @click="confirmId = item.id">删除</button>
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
.filters select {
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
.filters select:focus-visible {
  outline: 1px solid var(--primary);
  outline-offset: 0;
  box-shadow: 0 0 0 3px var(--primary-soft);
}

.input-block {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.hint,
.polling {
  margin: 0;
  color: var(--text-3);
  font-size: var(--fs-sm);
}

.tabs {
  display: flex;
  gap: var(--space-2);
}

.tab {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-2);
  font-size: var(--fs-sm);
}

.tab.active {
  border-color: var(--primary);
  background: var(--primary-soft);
  color: var(--primary);
}

.actions {
  display: flex;
  gap: var(--space-2);
}

.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
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

.row-meta {
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

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  color: var(--text-3);
  font-size: var(--fs-sm);
}
</style>
