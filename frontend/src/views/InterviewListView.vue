<script setup lang="ts">
/**
 * 模拟面试列表（F3）：选择方向 / 关联简历或 JD 后创建，异步出题；
 * 列表支持状态与方向筛选、继续未完成面试与删除（§6.10 专业克制）。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  createInterviewSession,
  deleteInterviewSession,
  listInterviewDirections,
  listInterviewSessions,
  type InterviewDirection,
  type InterviewSession,
  type InterviewSessionStatus,
} from '@/api/interview'
import { listJobPositions, type JobPositionListItem } from '@/api/jobs'
import { listResumes, type ResumeListItem } from '@/api/resumes'
import EmptyState from '@/components/EmptyState.vue'
import { SESSION_STATUS_TEXT, SESSION_STATUS_TONE } from '@/utils/interview'

const router = useRouter()

const directions = ref<InterviewDirection[]>([])
const sessions = ref<InterviewSession[]>([])
const resumes = ref<ResumeListItem[]>([])
const jobs = ref<JobPositionListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const error = ref('')
const notice = ref('')
const filters = reactive<{ direction: string; status: InterviewSessionStatus | '' }>({
  direction: '',
  status: '',
})

const createOpen = ref(false)
const creating = ref(false)
const form = reactive({
  direction: '',
  resumeId: '',
  jobPositionId: '',
  questionCount: 8,
  followUpCount: 1,
})
const deletingId = ref<number | null>(null)

const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const running = computed(() =>
  sessions.value.some((item) =>
    ['PREPARING', 'GENERATING', 'SUBMITTED'].includes(item.status),
  ),
)
const completedResumes = computed(() =>
  resumes.value.filter((item) => item.analyzeStatus === 'COMPLETED'),
)
const canCreate = computed(
  () => Boolean(form.direction || form.jobPositionId) && !creating.value,
)

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
  if (!disposed && running.value) {
    timer = setTimeout(() => void load(), 2000)
  }
}

async function load(): Promise<void> {
  try {
    const result = await listInterviewSessions({
      page: page.value,
      size,
      direction: filters.direction || undefined,
      status: filters.status || undefined,
    })
    sessions.value = result.items
    total.value = result.total
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
    const [directionList, resumePage, jobPage] = await Promise.all([
      listInterviewDirections(),
      listResumes(1, 100),
      listJobPositions({ page: 1, size: 100 }),
    ])
    directions.value = directionList
    resumes.value = resumePage.items
    jobs.value = jobPage.items
    if (!form.direction && directionList.length) {
      form.direction = directionList[0].key
    }
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '面试方向加载失败'
  }
}

function search(): void {
  page.value = 1
  loading.value = true
  void load()
}

function reset(): void {
  filters.direction = ''
  filters.status = ''
  search()
}

function go(delta: number): void {
  const next = page.value + delta
  if (next < 1 || next > pages.value) {
    return
  }
  page.value = next
  loading.value = true
  void load()
}

function openCreate(): void {
  createOpen.value = true
  error.value = ''
  notice.value = ''
}

async function create(): Promise<void> {
  if (!form.direction && !form.jobPositionId) {
    error.value = '请选择面试方向，或关联一个岗位 JD'
    return
  }
  creating.value = true
  error.value = ''
  notice.value = ''
  try {
    const session = await createInterviewSession({
      direction: form.direction || undefined,
      resumeId: form.resumeId ? Number(form.resumeId) : null,
      jobPositionId: form.jobPositionId ? Number(form.jobPositionId) : null,
      questionCount: Math.min(15, Math.max(5, Number(form.questionCount) || 8)),
      followUpCount: Math.min(3, Math.max(0, Number(form.followUpCount) || 0)),
    })
    notice.value = '面试已创建，正在生成题目…'
    await router.push(`/interviews/${session.id}`)
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '创建失败'
  } finally {
    creating.value = false
  }
}

async function remove(item: InterviewSession): Promise<void> {
  error.value = ''
  try {
    await deleteInterviewSession(item.id)
    deletingId.value = null
    if (sessions.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    notice.value = '面试记录已删除'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '删除失败'
  }
}

function open(item: InterviewSession): void {
  void router.push(`/interviews/${item.id}`)
}

function refs(item: InterviewSession): string {
  const parts = [item.resumeFileName, item.jobPositionTitle, item.companyName].filter(Boolean)
  return parts.length ? parts.join(' · ') : '仅方向出题'
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  void loadOptions()
  void load()
})
onBeforeUnmount(() => {
  disposed = true
  clearTimer()
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="lead">
        按方向或岗位 JD 生成模拟面试；关联简历后约 60% 的题目会深挖项目。出题、追问与评估均在后台异步执行。
      </p>
      <button type="button" class="btn primary" @click="openCreate">新建面试</button>
    </header>

    <section v-if="createOpen" class="create">
      <div class="create-grid">
        <label>
          <span>面试方向</span>
          <select v-model="form.direction">
            <option value="">由岗位 JD 自动匹配</option>
            <option v-for="item in directions" :key="item.key" :value="item.key">
              {{ item.label }}
            </option>
          </select>
        </label>
        <label>
          <span>关联岗位（可选）</span>
          <select v-model="form.jobPositionId">
            <option value="">不关联</option>
            <option v-for="item in jobs" :key="item.id" :value="String(item.id)">
              {{ item.title }}{{ item.companyName ? ` · ${item.companyName}` : '' }}
            </option>
          </select>
        </label>
        <label>
          <span>关联简历（可选）</span>
          <select v-model="form.resumeId">
            <option value="">不关联（全部方向题）</option>
            <option v-for="item in completedResumes" :key="item.id" :value="String(item.id)">
              {{ item.fileName }}
            </option>
          </select>
        </label>
        <label>
          <span>主问题数量</span>
          <input v-model.number="form.questionCount" type="number" min="5" max="15" />
        </label>
        <label>
          <span>每题追问数</span>
          <select v-model.number="form.followUpCount">
            <option :value="0">不追问</option>
            <option :value="1">1 个</option>
            <option :value="2">2 个</option>
            <option :value="3">3 个</option>
          </select>
        </label>
      </div>
      <p class="hint">
        模型按「场景模型 → INTERVIEW_QUESTION」绑定，未绑定则使用全局默认；进入面试后可随时两级切换。
      </p>
      <div class="create-actions">
        <button type="button" class="btn primary" :disabled="!canCreate" @click="create">
          {{ creating ? '创建中…' : '开始面试' }}
        </button>
        <button type="button" class="btn ghost" @click="createOpen = false">取消</button>
      </div>
    </section>

    <div class="filters">
      <select v-model="filters.direction" @change="search">
        <option value="">全部方向</option>
        <option v-for="item in directions" :key="item.key" :value="item.key">
          {{ item.label }}
        </option>
      </select>
      <select v-model="filters.status" @change="search">
        <option value="">全部状态</option>
        <option value="PREPARING">出题中</option>
        <option value="IN_PROGRESS">进行中</option>
        <option value="GENERATING">追问生成中</option>
        <option value="SUBMITTED">评估中</option>
        <option value="COMPLETED">已完成</option>
        <option value="FAILED">出题失败</option>
      </select>
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
      v-else-if="!sessions.length"
      title="还没有模拟面试"
      hint="选择方向或关联岗位 JD，系统会按知识体系生成题目；关联简历后会更贴近你的项目经历。"
      action-label="新建面试"
      @action="openCreate"
    />

    <template v-else>
      <ul class="list">
        <li v-for="item in sessions" :key="item.id" class="row">
          <div class="row-main" @click="open(item)">
            <div class="row-title-line">
              <strong class="row-title">{{ item.directionLabel ?? item.direction ?? '模拟面试' }}</strong>
              <span class="badge" :class="SESSION_STATUS_TONE[item.status]">
                {{ SESSION_STATUS_TEXT[item.status] ?? item.status }}
              </span>
              <span
                v-if="item.status === 'SUBMITTED' || item.status === 'COMPLETED'"
                class="badge"
                :class="item.evaluateStatus === 'FAILED' ? 'danger' : item.evaluateStatus === 'COMPLETED' ? 'success' : 'warning'"
              >
                评估 {{ item.evaluateStatus }}
              </span>
            </div>
            <p class="row-meta">{{ refs(item) }}</p>
            <p class="row-progress numeric">
              已答 {{ item.answeredCount }} / 主问题 {{ item.questionCount }} · 每题追问 {{ item.followUpCount }}
              · {{ formatTime(item.createdAt) }}
            </p>
            <p v-if="item.status === 'FAILED'" class="row-error">出题失败，可进入详情重试。</p>
          </div>
          <div class="row-actions">
            <template v-if="deletingId === item.id">
              <button type="button" class="link danger" @click="remove(item)">确认删除</button>
              <button type="button" class="link" @click="deletingId = null">取消</button>
            </template>
            <template v-else>
              <button type="button" class="link" @click="open(item)">
                {{ item.status === 'COMPLETED' ? '查看报告' : '继续面试' }}
              </button>
              <button type="button" class="link danger" @click="deletingId = item.id">删除</button>
            </template>
          </div>
        </li>
      </ul>

      <footer v-if="total > size" class="pager">
        <span>共 {{ total }} 场面试 · 第 {{ page }} / {{ pages }} 页</span>
        <div class="pager-actions">
          <button type="button" class="btn ghost" :disabled="page <= 1" @click="go(-1)">上一页</button>
          <button type="button" class="btn ghost" :disabled="page >= pages" @click="go(1)">下一页</button>
        </div>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-3);
}

.lead {
  margin: 0;
  color: var(--text-2);
  font-size: 13.5px;
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

.create {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.create-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: var(--space-3);
}

.create-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.create-grid span {
  font-size: 12px;
  color: var(--text-3);
}

.create-grid select,
.create-grid input {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 12.5px;
}

.hint {
  margin: 0;
  font-size: 12px;
  color: var(--text-3);
}

.create-actions {
  display: flex;
  gap: var(--space-2);
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.filters select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 12.5px;
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

.skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.skeleton-row {
  height: 76px;
  border-radius: var(--radius-card);
  border: 1px solid var(--border);
  background: var(--surface);
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.row {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.row:hover {
  border-color: var(--border-strong);
}

.row-main {
  cursor: pointer;
  min-width: 0;
}

.row-title-line {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.row-title {
  font-size: 13.5px;
  font-weight: 500;
}

.row-meta {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--text-2);
}

.row-progress {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--text-3);
}

.row-error {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--danger);
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

.badge.warning {
  color: var(--warning);
  background: var(--warning-soft);
}

.row-actions {
  display: flex;
  gap: var(--space-3);
  white-space: nowrap;
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: var(--primary);
  font-size: 12.5px;
}

.link.danger {
  color: var(--danger);
}

.pager {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12.5px;
  color: var(--text-3);
}

.pager-actions {
  display: flex;
  gap: var(--space-2);
}

@media (max-width: 760px) {
  .page-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .row {
    grid-template-columns: 1fr;
  }

  .row-actions {
    justify-content: flex-end;
  }
}
</style>
