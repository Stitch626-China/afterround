<script setup lang="ts">
/**
 * 岗位详情（F2）：修正结构化字段、调整生命周期、重试解析、发起简历匹配。
 * 匹配走场景 JOB_MATCH 绑定模型；执行中重复发起会返回 409。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  createJobMatch,
  deleteJobPosition,
  getJobMatch,
  getJobPosition,
  listCompanies,
  listJobMatches,
  retryJobParse,
  updateJobPosition,
  type Company,
  type JobMatch,
  type JobPositionDetail,
  type JobPositionStatus,
} from '@/api/jobs'
import { listResumes, type ResumeListItem } from '@/api/resumes'
import ScoreBar from '@/components/report/ScoreBar.vue'

const route = useRoute()
const router = useRouter()
const jobId = Number(route.params.id)

const job = ref<JobPositionDetail | null>(null)
const loading = ref(true)
const error = ref('')
const notice = ref('')
const saving = ref(false)
const deleting = ref(false)
const confirmingDelete = ref(false)
const retrying = ref(false)
let formDirty = false

const form = reactive({
  title: '',
  companyId: null as number | null,
  city: '',
  salaryMin: null as number | null,
  salaryMax: null as number | null,
  experience: '',
  education: '',
  mustHaveSkills: '',
  niceToHaveSkills: '',
  responsibility: '',
  status: 'SAVED' as JobPositionStatus,
})

const companies = ref<Company[]>([])
const resumes = ref<ResumeListItem[]>([])
const selectedResumeId = ref<number | null>(null)
const matches = ref<JobMatch[]>([])
const currentMatch = ref<JobMatch | null>(null)
const matching = ref(false)

const parseRunning = computed(
  () => job.value?.parseStatus === 'PENDING' || job.value?.parseStatus === 'PROCESSING',
)
const matchRunning = computed(
  () => currentMatch.value?.status === 'PENDING' || currentMatch.value?.status === 'PROCESSING',
)
const completedResumes = computed(() => resumes.value.filter((item) => item.analyzeStatus === 'COMPLETED'))
const canMatch = computed(() => job.value?.parseStatus === 'COMPLETED' && selectedResumeId.value != null)

const PARSE_TEXT: Record<string, string> = {
  PENDING: '待解析',
  PROCESSING: '解析中',
  COMPLETED: '已解析',
  FAILED: '解析失败',
}
const PARSE_TONE: Record<string, string> = {
  PENDING: 'muted',
  PROCESSING: 'primary',
  COMPLETED: 'success',
  FAILED: 'danger',
}
const STATUS_TEXT: Record<string, string> = {
  SAVED: '待跟进',
  APPLIED: '已投递',
  INTERVIEWING: '面试中',
  CLOSED: '已关闭',
}
const MATCH_TEXT: Record<string, string> = {
  PENDING: '排队中',
  PROCESSING: '匹配中',
  COMPLETED: '已完成',
  FAILED: '失败',
}
const MATCH_TONE: Record<string, string> = {
  PENDING: 'muted',
  PROCESSING: 'primary',
  COMPLETED: 'success',
  FAILED: 'danger',
}

let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

function clearTimer(): void {
  if (timer) {
    clearTimeout(timer)
    timer = undefined
  }
}

function schedule(): void {
  clearTimer()
  if (disposed) {
    return
  }
  if (parseRunning.value || matchRunning.value) {
    timer = setTimeout(() => void poll(), 2000)
  }
}

async function poll(): Promise<void> {
  await load(false)
  if (currentMatch.value && matchRunning.value) {
    try {
      currentMatch.value = await getJobMatch(currentMatch.value.id)
    } catch {
      // 轮询失败保留上一次结果，下一轮继续
    }
  }
  schedule()
}

function fillForm(detail: JobPositionDetail): void {
  form.title = detail.title ?? ''
  form.companyId = detail.companyId ?? null
  form.city = detail.city ?? ''
  form.salaryMin = detail.salaryMin ?? null
  form.salaryMax = detail.salaryMax ?? null
  form.experience = detail.experience ?? ''
  form.education = detail.education ?? ''
  form.mustHaveSkills = (detail.mustHaveSkills ?? []).join('、')
  form.niceToHaveSkills = (detail.niceToHaveSkills ?? []).join('、')
  form.responsibility = detail.responsibility ?? ''
  form.status = detail.status
}

async function load(initial = false): Promise<void> {
  try {
    const detail = await getJobPosition(jobId)
    job.value = detail
    if (initial || !formDirty) {
      fillForm(detail)
    }
    error.value = ''
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载失败'
  } finally {
    loading.value = false
    schedule()
  }
}

async function loadOptions(): Promise<void> {
  try {
    const [companyPage, resumePage, matchPage] = await Promise.all([
      listCompanies('', 100),
      listResumes(1, 100),
      listJobMatches({ jobPositionId: jobId, size: 10 }),
    ])
    companies.value = companyPage.items
    resumes.value = resumePage.items
    matches.value = matchPage.items
    selectedResumeId.value =
      completedResumes.value.find((item) => item.id === selectedResumeId.value)?.id ??
      completedResumes.value[0]?.id ??
      null
    currentMatch.value =
      matchPage.items.find((item) => item.status === 'PROCESSING' || item.status === 'PENDING') ??
      matchPage.items[0] ??
      null
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '选项加载失败'
  }
}

function splitSkills(value: string): string[] {
  return value
    .split(/[,，、;；]/)
    .map((item) => item.trim())
    .filter(Boolean)
}

/** 数字输入清空时 v-model.number 会给出空串，提交前统一转 null。 */
function numeric(value: number | null): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

async function save(): Promise<void> {
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    await updateJobPosition(jobId, {
      title: form.title.trim() || null,
      companyId: form.companyId,
      city: form.city.trim(),
      salaryMin: numeric(form.salaryMin),
      salaryMax: numeric(form.salaryMax),
      experience: form.experience.trim(),
      education: form.education.trim(),
      mustHaveSkills: splitSkills(form.mustHaveSkills),
      niceToHaveSkills: splitSkills(form.niceToHaveSkills),
      responsibility: form.responsibility.trim(),
      status: form.status,
    })
    formDirty = false
    notice.value = '已保存'
    await load(true)
    await loadOptions()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function retryParse(): Promise<void> {
  retrying.value = true
  error.value = ''
  try {
    await retryJobParse(jobId)
    notice.value = '已重新触发解析'
    await load(true)
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '触发解析失败'
  } finally {
    retrying.value = false
  }
}

async function startMatch(): Promise<void> {
  if (!canMatch.value || selectedResumeId.value == null) {
    return
  }
  matching.value = true
  error.value = ''
  try {
    currentMatch.value = await createJobMatch(selectedResumeId.value, jobId)
    await refreshMatches()
    schedule()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '发起匹配失败'
  } finally {
    matching.value = false
  }
}

async function refreshMatches(): Promise<void> {
  try {
    const page = await listJobMatches({ jobPositionId: jobId, size: 10 })
    matches.value = page.items
  } catch {
    // 列表刷新失败不阻断主流程
  }
}

async function remove(): Promise<void> {
  deleting.value = true
  error.value = ''
  try {
    await deleteJobPosition(jobId)
    await router.push('/jobs')
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '删除失败'
    deleting.value = false
  }
}

function back(): void {
  void router.push('/jobs')
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function resumeLabel(item: ResumeListItem): string {
  return `${item.fileName || `简历 #${item.id}`}${item.overallScore != null ? ` · ${item.overallScore} 分` : ''}`
}

onMounted(async () => {
  await Promise.all([load(true), loadOptions()])
  schedule()
})
onBeforeUnmount(() => {
  disposed = true
  clearTimer()
})
</script>

<template>
  <div class="page">
    <button type="button" class="back" @click="back">← 返回岗位库</button>

    <div v-if="loading" class="skeleton" />
    <div v-else-if="!job" class="banner danger">{{ error || '岗位不存在' }}</div>

    <template v-else>
      <header class="page-head">
        <div>
          <h2 class="title">{{ job.title || '未命名岗位' }}</h2>
          <p class="meta">
            {{ [job.companyName, job.city, job.experience, job.education].filter(Boolean).join(' · ') || '结构化信息待补全' }}
          </p>
        </div>
        <div class="badges">
          <span class="badge" :class="PARSE_TONE[job.parseStatus]">
            {{ PARSE_TEXT[job.parseStatus] ?? job.parseStatus }}
          </span>
          <span class="badge muted">{{ STATUS_TEXT[job.status] ?? job.status }}</span>
          <span v-if="job.llmProvider" class="badge muted">{{ job.llmProvider }} · {{ job.llmModel }}</span>
        </div>
      </header>

      <div v-if="error" class="banner danger">{{ error }}</div>
      <div v-else-if="notice" class="banner success">{{ notice }}</div>

      <div v-if="job.parseStatus === 'FAILED'" class="banner danger split">
        <span>解析失败：{{ job.parseError || '未知原因' }}（已重试 {{ job.retryCount }} 次）</span>
        <button type="button" class="link" :disabled="retrying" @click="retryParse">重试解析</button>
      </div>
      <div v-else-if="parseRunning" class="banner info">正在解析 JD，完成后可发起匹配；本页会自动刷新。</div>

      <div class="columns">
        <section class="card">
          <h3>岗位信息</h3>
          <p class="hint">可直接修正解析结果；保存后行情统计会立即更新。</p>

          <div class="form-grid">
            <label>
              <span>岗位名称</span>
              <input v-model="form.title" type="text" maxlength="128" @input="formDirty = true" />
            </label>
            <label>
              <span>公司</span>
              <select v-model="form.companyId" @change="formDirty = true">
                <option :value="null">未关联公司</option>
                <option v-for="company in companies" :key="company.id" :value="company.id">
                  {{ company.name }}
                </option>
              </select>
            </label>
            <label>
              <span>城市</span>
              <input v-model="form.city" type="text" maxlength="64" @input="formDirty = true" />
            </label>
            <label>
              <span>经验要求</span>
              <input v-model="form.experience" type="text" maxlength="64" @input="formDirty = true" />
            </label>
            <label>
              <span>薪资下限（元/月）</span>
              <input v-model.number="form.salaryMin" type="number" min="0" max="1000000" @input="formDirty = true" />
            </label>
            <label>
              <span>薪资上限（元/月）</span>
              <input v-model.number="form.salaryMax" type="number" min="0" max="1000000" @input="formDirty = true" />
            </label>
            <label>
              <span>学历要求</span>
              <input v-model="form.education" type="text" maxlength="64" @input="formDirty = true" />
            </label>
            <label>
              <span>生命周期</span>
              <select v-model="form.status" @change="formDirty = true">
                <option value="SAVED">待跟进</option>
                <option value="APPLIED">已投递</option>
                <option value="INTERVIEWING">面试中</option>
                <option value="CLOSED">已关闭</option>
              </select>
            </label>
            <label class="full">
              <span>必备技能（用顿号或逗号分隔）</span>
              <input v-model="form.mustHaveSkills" type="text" @input="formDirty = true" />
            </label>
            <label class="full">
              <span>加分技能</span>
              <input v-model="form.niceToHaveSkills" type="text" @input="formDirty = true" />
            </label>
            <label class="full">
              <span>职责摘要</span>
              <textarea v-model="form.responsibility" rows="3" maxlength="5000" @input="formDirty = true" />
            </label>
          </div>

          <div class="form-actions">
            <button type="button" class="btn primary" :disabled="saving" @click="save">
              {{ saving ? '保存中…' : '保存修改' }}
            </button>
            <button
              v-if="job.parseStatus !== 'PROCESSING'"
              type="button"
              class="btn ghost"
              :disabled="retrying"
              @click="retryParse"
            >
              {{ job.parseStatus === 'FAILED' ? '重试解析' : '重新解析' }}
            </button>
            <template v-if="confirmingDelete">
              <button type="button" class="link danger" :disabled="deleting" @click="remove">确认删除</button>
              <button type="button" class="link" @click="confirmingDelete = false">取消</button>
            </template>
            <button v-else type="button" class="link danger" @click="confirmingDelete = true">删除岗位</button>
          </div>

          <details class="raw">
            <summary>查看 JD 原文</summary>
            <pre>{{ job.rawText }}</pre>
          </details>
        </section>

        <section class="card">
          <h3>简历匹配</h3>
          <p class="hint">基于简历原文与 JD 结构做三项评分，总分由本地按 50 / 30 / 20 加权。</p>

          <div class="match-form">
            <select v-model="selectedResumeId" :disabled="!completedResumes.length">
              <option :value="null" disabled>{{ completedResumes.length ? '选择一份已完成分析的简历' : '暂无已完成分析的简历' }}</option>
              <option v-for="resume in completedResumes" :key="resume.id" :value="resume.id">
                {{ resumeLabel(resume) }}
              </option>
            </select>
            <button type="button" class="btn primary" :disabled="!canMatch || matching" @click="startMatch">
              {{ matching ? '发起中…' : currentMatch ? '重新匹配' : '开始匹配' }}
            </button>
          </div>
          <p v-if="!completedResumes.length" class="hint">
            先到「简历中心」上传简历并等待分析完成，再回来匹配。
          </p>

          <div v-if="currentMatch" class="match-result">
            <div class="match-head">
              <strong>匹配结果</strong>
              <span class="badge" :class="MATCH_TONE[currentMatch.status]">
                {{ MATCH_TEXT[currentMatch.status] ?? currentMatch.status }}
              </span>
            </div>

            <div v-if="currentMatch.status === 'COMPLETED'" class="match-body">
              <p class="match-score numeric">{{ currentMatch.overallScore }}<small> / 100</small></p>
              <div class="bars">
                <ScoreBar
                  v-for="dimension in currentMatch.detail?.dimensions ?? []"
                  :key="dimension.key"
                  :label="dimension.label"
                  :score="dimension.score"
                  :weight="dimension.weight"
                />
              </div>
              <div class="columns-inner">
                <div>
                  <h4>差距清单</h4>
                  <ul v-if="currentMatch.detail?.gaps?.length" class="plain">
                    <li v-for="gap in currentMatch.detail.gaps" :key="gap">{{ gap }}</li>
                  </ul>
                  <p v-else class="hint">没有明显差距</p>
                </div>
                <div>
                  <h4>准备建议</h4>
                  <ul v-if="currentMatch.detail?.preparationAdvice?.length" class="plain">
                    <li v-for="advice in currentMatch.detail.preparationAdvice" :key="advice">{{ advice }}</li>
                  </ul>
                  <p v-else class="hint">暂无建议</p>
                </div>
              </div>
              <p class="match-meta">
                {{ currentMatch.llmProvider }} · {{ currentMatch.llmModel }} · {{ formatTime(currentMatch.updatedAt) }}
              </p>
            </div>

            <p v-else-if="currentMatch.status === 'FAILED'" class="banner danger">
              匹配失败：{{ currentMatch.error || '未知原因' }}（已重试 {{ currentMatch.retryCount }} 次）
            </p>
            <p v-else class="hint">匹配进行中，本页会自动刷新…</p>
          </div>

          <details v-if="matches.length" class="history">
            <summary>历史匹配（{{ matches.length }}）</summary>
            <ul class="plain">
              <li v-for="item in matches" :key="item.id">
                <button type="button" class="link" @click="currentMatch = item">
                  #{{ item.id }} · {{ MATCH_TEXT[item.status] ?? item.status }}
                  <template v-if="item.overallScore != null"> · {{ item.overallScore }} 分</template>
                  · {{ formatTime(item.createdAt) }}
                </button>
              </li>
            </ul>
          </details>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.back {
  align-self: flex-start;
  padding: 0;
  border: none;
  background: none;
  color: var(--text-3);
  font-size: 12.5px;
}

.back:hover {
  color: var(--text);
}

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-3);
}

.title {
  font-size: 18px;
}

.meta {
  margin: 4px 0 0;
  color: var(--text-3);
  font-size: 12.5px;
}

.badges {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.badge {
  padding: 1px 8px;
  border-radius: var(--radius-tag);
  font-size: 11.5px;
  white-space: nowrap;
}

.badge.success {
  color: var(--success);
  background: var(--success-soft);
}

.badge.danger {
  color: var(--danger);
  background: var(--danger-soft);
}

.badge.primary {
  color: var(--primary);
  background: var(--primary-soft);
}

.badge.muted {
  color: var(--text-3);
  background: var(--surface-muted);
}

.banner {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-btn);
  font-size: 12.5px;
}

.banner.danger {
  color: var(--danger);
  background: var(--danger-soft);
  border: 1px solid var(--danger);
}

.banner.success {
  color: var(--success);
  background: var(--success-soft);
  border: 1px solid var(--success);
}

.banner.info {
  color: var(--primary);
  background: var(--primary-soft);
  border: 1px solid var(--primary);
}

.banner.split {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
}

.columns {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: var(--space-4);
  align-items: start;
}

.card {
  padding: var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.card h3 {
  font-size: 14px;
}

.hint {
  margin: 4px 0 var(--space-3);
  font-size: 12px;
  color: var(--text-3);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
}

.form-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--text-3);
}

.form-grid label.full {
  grid-column: 1 / -1;
}

.form-grid input,
.form-grid select,
.form-grid textarea,
.match-form select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 12.5px;
}

.form-grid textarea {
  resize: vertical;
}

.form-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-4);
}

.btn {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-btn);
  border: 1px solid transparent;
  font-size: 13px;
  white-space: nowrap;
}

.btn.primary {
  background: var(--primary);
  color: #fff;
}

.btn.ghost {
  background: var(--surface);
  border-color: var(--border);
  color: var(--text-2);
}

.btn.ghost:hover:not(:disabled) {
  border-color: var(--border-strong);
  color: var(--text);
}

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: var(--primary);
  font-size: 12.5px;
  text-align: left;
}

.link.danger {
  color: var(--danger);
}

.raw {
  margin-top: var(--space-4);
  font-size: 12.5px;
  color: var(--text-2);
}

.raw summary {
  cursor: pointer;
}

.raw pre {
  margin: var(--space-2) 0 0;
  padding: var(--space-3);
  max-height: 260px;
  overflow: auto;
  border-radius: var(--radius-btn);
  background: var(--surface-muted);
  border: 1px solid var(--border);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--font-sans);
  font-size: 12.5px;
  line-height: 1.55;
}

.match-form {
  display: flex;
  gap: var(--space-2);
}

.match-form select {
  flex: 1;
  min-width: 0;
}

.match-result {
  margin-top: var(--space-4);
  padding-top: var(--space-4);
  border-top: 1px solid var(--border);
}

.match-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
}

.match-score {
  margin: var(--space-3) 0 var(--space-2);
  font-size: 34px;
  font-weight: 600;
  line-height: 1;
}

.match-score small {
  font-size: 13px;
  font-weight: 400;
  color: var(--text-3);
}

.bars {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.columns-inner {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
  margin-top: var(--space-3);
}

.columns-inner h4 {
  margin: 0 0 var(--space-1);
  font-size: 12.5px;
}

.plain {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  font-size: 12.5px;
  color: var(--text-2);
}

.plain li::before {
  content: '·';
  margin-right: 6px;
  color: var(--text-3);
}

.match-meta {
  margin: var(--space-3) 0 0;
  font-size: 11.5px;
  color: var(--text-3);
}

.history {
  margin-top: var(--space-4);
  font-size: 12.5px;
}

.history summary {
  cursor: pointer;
  color: var(--text-2);
}

.history .plain {
  margin-top: var(--space-2);
}

.skeleton {
  height: 280px;
  border-radius: var(--radius-card);
  border: 1px solid var(--border);
  background: var(--surface);
}

@media (max-width: 980px) {
  .columns,
  .columns-inner {
    grid-template-columns: 1fr;
  }

  .page-head {
    flex-direction: column;
  }
}

@media (max-width: 620px) {
  .form-grid {
    grid-template-columns: 1fr;
  }

  .match-form {
    flex-direction: column;
  }
}
</style>
