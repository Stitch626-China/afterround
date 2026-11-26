<script setup lang="ts">
/**
 * 真实面试复盘列表（F4）：录音 / 转写文本 / 手动笔记三种输入，异步转写与分析，失败可重试。
 * 状态轮询只在存在处理中的记录时开启，离开页面即清理。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
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
import { listCompanies, listJobPositions, type Company, type JobPositionListItem } from '@/api/jobs'
import { listResumes, type ResumeListItem } from '@/api/resumes'
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
const jobs = ref<JobPositionListItem[]>([])
const companies = ref<Company[]>([])
const companySuggestions = ref<Company[]>([])
let companyTimer: ReturnType<typeof setTimeout> | undefined
const resumes = ref<ResumeListItem[]>([])
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
const sourceMode = ref<'JOB' | 'APPLICATION' | 'QUICK'>('JOB')

const draft = reactive<{
  applicationId: number | null
  jobPositionId: number | null
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
  jobPositionId: null,
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

/** 快速新建：公司 + 岗位名，后端按精确同名复用或新建；可选补充分析背景。 */
const quick = reactive({
  companyName: '',
  jobTitle: '',
  companyIntro: '',
  jdText: '',
  resumeId: 0,
})
const matchedRounds = ref<number | null>(null)

/** 同名公司与岗位都命中时提示复用，避免重复创建。 */
const matchedCompany = computed(() => {
  const name = quick.companyName.trim()
  if (!name) {
    return null
  }
  return [...companySuggestions.value, ...companies.value]
    .find((company) => company.name === name) ?? null
})
const matchedJob = computed(() => {
  const name = quick.companyName.trim()
  const title = quick.jobTitle.trim()
  if (!name || !title) {
    return null
  }
  return jobs.value.find((job) => job.title === title && (job.companyName ?? '').trim() === name) ?? null
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
  const [applicationResult, jobResult, companyResult, resumeResult] = await Promise.allSettled([
    listJobApplications({ page: 1, size: 100 }),
    listJobPositions({ page: 1, size: 100 }),
    listCompanies('', 100),
    listResumes(1, 100),
  ])
  if (applicationResult.status === 'fulfilled') {
    applications.value = applicationResult.value.items
  }
  if (jobResult.status === 'fulfilled') {
    jobs.value = jobResult.value.items
  }
  if (companyResult.status === 'fulfilled') {
    companies.value = companyResult.value.items
    companySuggestions.value = companyResult.value.items
  }
  if (resumeResult.status === 'fulfilled') {
    resumes.value = resumeResult.value.items
  }
}

/** 公司联想（FR5.3）：输入时远端检索候选，只提示复用，不自动合并。 */
function searchCompanySuggestions(): void {
  if (companyTimer) {
    clearTimeout(companyTimer)
  }
  companyTimer = setTimeout(async () => {
    const keyword = quick.companyName.trim()
    if (!keyword) {
      companySuggestions.value = companies.value
      return
    }
    try {
      companySuggestions.value = (await listCompanies(keyword, 10)).items
    } catch {
      // 联想失败保留上一次候选，不阻断手输
    }
  }, 250)
}

function switchSource(mode: 'JOB' | 'APPLICATION' | 'QUICK'): void {
  if (sourceMode.value === mode) {
    return
  }
  sourceMode.value = mode
  draft.applicationId = null
  draft.jobPositionId = null
  draft.roundNo = 1
  matchedRounds.value = null
}

/** 命中已有岗位时自动带出下一轮；切到「按岗位」后复用同一套轮次逻辑。 */
watch(
  () => matchedJob.value?.id,
  async (jobId) => {
    matchedRounds.value = null
    if (!jobId) {
      return
    }
    try {
      const result = await listJobApplications({ jobPositionId: jobId, page: 1, size: 100 })
      if (matchedJob.value?.id !== jobId) {
        return
      }
      const latest = result.items.reduce((max, item) => Math.max(max, item.latestRoundNo ?? 0), 0)
      matchedRounds.value = latest
      if (sourceMode.value === 'QUICK') {
        draft.roundNo = latest + 1
      }
    } catch {
      matchedRounds.value = null
    }
  },
)

/** 从快速新建切到已有岗位，保留轮次与输入材料。 */
function useMatchedJob(): void {
  const job = matchedJob.value
  if (!job) {
    return
  }
  sourceMode.value = 'JOB'
  draft.jobPositionId = job.id
  void onJobChange()
}

async function onJobChange(): Promise<void> {
  const jobPositionId = draft.jobPositionId
  if (!jobPositionId) {
    draft.roundNo = 1
    return
  }
  try {
    const page = await listJobApplications({ jobPositionId, page: 1, size: 100 })
    if (sourceMode.value !== 'JOB' || draft.jobPositionId !== jobPositionId) {
      return
    }
    const latest = page.items.reduce((max, item) => Math.max(max, item.latestRoundNo ?? 0), 0)
    draft.roundNo = latest + 1
  } catch {
    draft.roundNo = 1
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
  sourceMode.value = 'JOB'
  draft.applicationId = null
  draft.jobPositionId = null
  draft.roundNo = 1
  draft.roundType = 'TECH'
  draft.interviewerRole = ''
  draft.format = ''
  draft.interviewTime = ''
  draft.inputType = 'AUDIO'
  draft.transcript = ''
  draft.notes = ''
  draft.audio = null
  quick.companyName = ''
  quick.jobTitle = ''
  quick.companyIntro = ''
  quick.jdText = ''
  quick.resumeId = 0
  matchedRounds.value = null
}

async function create(): Promise<void> {
  if (sourceMode.value === 'JOB' && !draft.jobPositionId) {
    error.value = '请选择岗位'
    return
  }
  if (sourceMode.value === 'APPLICATION' && !draft.applicationId) {
    error.value = '请选择投递记录'
    return
  }
  if (sourceMode.value === 'QUICK' && !quick.companyName.trim()) {
    error.value = '请选择或填写公司'
    return
  }
  if (sourceMode.value === 'QUICK' && !quick.jobTitle.trim()) {
    error.value = '请填写岗位名称'
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
    const quickMode = sourceMode.value === 'QUICK'
    await createReviewSession({
      audio: draft.inputType === 'AUDIO' ? draft.audio : null,
      transcript: draft.inputType === 'TEXT' ? draft.transcript.trim() : undefined,
      notes: draft.inputType === 'NOTES' ? draft.notes.trim() : undefined,
      applicationId: sourceMode.value === 'APPLICATION' ? draft.applicationId : null,
      jobPositionId: sourceMode.value === 'JOB' ? draft.jobPositionId : null,
      companyId: quickMode ? matchedCompany.value?.id ?? null : null,
      companyName: quickMode && !matchedCompany.value ? quick.companyName.trim() : undefined,
      companyIntro: quickMode ? quick.companyIntro.trim() || undefined : undefined,
      jobTitle: quickMode ? quick.jobTitle.trim() : undefined,
      jdText: quickMode ? quick.jdText.trim() || undefined : undefined,
      resumeId: quickMode && quick.resumeId ? quick.resumeId : null,
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
    void loadOptions()
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
  if (companyTimer) {
    clearTimeout(companyTimer)
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
      <p class="hint">
        面试结束后直接选岗位即可，系统会自动补建投递与面试轮次；岗位还没入库时用「快速新建」，不用先导入 JD。
      </p>
      <div class="tabs">
        <button
          type="button"
          class="tab"
          :class="{ active: sourceMode === 'JOB' }"
          @click="switchSource('JOB')"
        >
          按岗位
        </button>
        <button
          type="button"
          class="tab"
          :class="{ active: sourceMode === 'APPLICATION' }"
          @click="switchSource('APPLICATION')"
        >
          按投递
        </button>
        <button
          type="button"
          class="tab"
          :class="{ active: sourceMode === 'QUICK' }"
          @click="switchSource('QUICK')"
        >
          快速新建
        </button>
      </div>
      <div class="form-grid">
        <template v-if="sourceMode === 'QUICK'">
          <label>
            公司
            <input
              v-model="quick.companyName"
              list="review-company-options"
              maxlength="128"
              placeholder="输入公司名称，如 字节跳动"
              aria-label="填写公司"
              @input="searchCompanySuggestions"
            />
            <datalist id="review-company-options">
              <option v-for="company in companySuggestions" :key="company.id" :value="company.name" />
            </datalist>
          </label>
          <label>
            岗位名称
            <input
              v-model="quick.jobTitle"
              maxlength="128"
              placeholder="如 Java 后端开发工程师"
              aria-label="填写岗位名称"
            />
          </label>
        </template>
        <label v-else-if="sourceMode === 'JOB'">
          岗位
          <select v-model.number="draft.jobPositionId" aria-label="选择岗位" @change="onJobChange">
            <option :value="null" disabled>选择岗位</option>
            <option v-for="job in jobs" :key="job.id" :value="job.id">
              {{ job.title || '未命名岗位' }}{{ job.companyName ? ` · ${job.companyName}` : '' }}
            </option>
          </select>
        </label>
        <label v-else>
          投递记录
          <select
            v-model.number="draft.applicationId"
            aria-label="选择投递"
            @change="onApplicationChange"
          >
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

      <div v-if="sourceMode === 'QUICK'" class="quick-hint">
        <div v-if="matchedJob" class="banner info split">
          <span>
            已存在岗位「{{ matchedJob.companyName }} · {{ matchedJob.title }}」{{
              matchedRounds !== null ? `（已录入 ${matchedRounds} 轮）` : ''
            }}，将复用该岗位并新增下一轮。
          </span>
          <button type="button" class="link" @click="useMatchedJob">改用该岗位</button>
        </div>
        <p v-else-if="matchedCompany" class="hint">
          公司「{{ matchedCompany.name }}」已存在，将复用；岗位不存在，将新建并标记「手动录入」。
        </p>
        <p v-else class="hint">
          公司和岗位不存在时会自动新建，岗位标记「手动录入」，不参与行情统计；之后可在岗位库补充 JD。
        </p>
      </div>

      <details v-if="sourceMode === 'QUICK'" class="quick-extra">
        <summary>补充背景（可选）：公司介绍 / JD / 简历，填了会让分析更有针对性</summary>
        <div class="form-grid">
          <label class="full">
            公司介绍
            <textarea
              v-model="quick.companyIntro"
              rows="3"
              maxlength="10000"
              placeholder="公司业务、团队规模、面试风格等"
            />
          </label>
          <label class="full">
            岗位 JD
            <textarea
              v-model="quick.jdText"
              rows="4"
              maxlength="200000"
              placeholder="粘贴 JD 文本；会保存到岗位库并用于本次分析"
            />
          </label>
          <label>
            简历版本
            <select v-model.number="quick.resumeId" aria-label="选择简历版本">
              <option :value="0">不指定</option>
              <option v-for="resume in resumes" :key="resume.id" :value="resume.id">
                {{ resume.fileName || `简历 #${resume.id}` }}
              </option>
            </select>
          </label>
        </div>
      </details>

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

      <div v-if="sourceMode === 'JOB' && !jobs.length" class="banner warning">
        还没有岗位；请先到「岗位库」导入 JD，再回来创建复盘。
        <RouterLink to="/jobs" class="link">去岗位库 →</RouterLink>
      </div>
      <div v-else-if="sourceMode === 'APPLICATION' && !applications.length" class="banner warning">
        还没有投递记录；可以切换到「按岗位」直接创建，或先到「投递档案」记录投递。
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

.full {
  grid-column: 1 / -1;
}

.quick-extra summary {
  cursor: pointer;
  color: var(--text-2);
  font-size: var(--fs-sm);
}

.quick-extra summary:hover {
  color: var(--primary);
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
