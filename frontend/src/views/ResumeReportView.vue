<script setup lang="ts">
/**
 * 分析报告页（视觉样板）：轮询状态、五维横向条形、优势短板、优化建议、岗位方向。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getAnalysis,
  getResume,
  triggerAnalysis,
  updateResumeSalary,
  type ResumeAnalysisStatus,
  type ResumeDetail,
} from '@/api/resumes'
import ReportContent from '@/components/report/ReportContent.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const resumeId = Number(route.params.id)

const resume = ref<ResumeDetail | null>(null)
const analysis = ref<ResumeAnalysisStatus | null>(null)
const loading = ref(true)
const busy = ref(false)
const savingSalary = ref(false)
const error = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
/** 加载序号：触发分析后让更早发出的加载响应失效。 */
let loadSeq = 0

const status = computed(() => analysis.value?.status ?? resume.value?.analyzeStatus ?? 'PENDING')
const report = computed(() => analysis.value?.report ?? null)
const running = computed(() => status.value === 'PROCESSING')
let disposed = false

const actionText = computed(() => {
  if (status.value === 'FAILED') {
    return '重试分析'
  }
  return status.value === 'COMPLETED' ? '重新分析' : '开始分析'
})

const statusText = computed(() => {
  switch (status.value) {
    case 'PROCESSING':
      return '分析中'
    case 'COMPLETED':
      return '已完成'
    case 'FAILED':
      return '失败'
    default:
      return '待分析'
  }
})

const statusTone = computed(() => {
  switch (status.value) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'PROCESSING':
      return 'primary'
    default:
      return 'muted'
  }
})

function formatTime(value?: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function clearTimer(): void {
  if (timer) {
    clearTimeout(timer)
    timer = undefined
  }
}

async function load(): Promise<void> {
  const seq = ++loadSeq
  try {
    const [detail, current] = await Promise.all([getResume(resumeId), getAnalysis(resumeId)])
    if (disposed || seq !== loadSeq) {
      return
    }
    resume.value = detail
    analysis.value = current
    error.value = ''
  } catch (ex) {
    if (!disposed && seq === loadSeq) {
      error.value = ex instanceof Error ? ex.message : '加载失败'
    }
  } finally {
    if (!disposed && seq === loadSeq) {
      loading.value = false
      schedule()
    }
  }
}

function schedule(): void {
  clearTimer()
  if (!disposed && running.value) {
    timer = setTimeout(load, 2000)
  }
}

async function reanalyze(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    const next = await triggerAnalysis(resumeId)
    if (disposed) {
      return
    }
    // 让触发前发出的加载响应失效，避免旧 PENDING 覆盖 PROCESSING
    loadSeq += 1
    analysis.value = next
    schedule()
  } catch (ex) {
    if (!disposed) {
      error.value = ex instanceof Error ? ex.message : '触发分析失败'
    }
  } finally {
    busy.value = false
  }
}

/** 保存用户填写的薪资：null 表示清空用户值、展示回落为 LLM 提取值。 */
async function saveSalary(payload: {
  currentMonthlySalary: number | null
  expectedMonthlySalary: number | null
}): Promise<void> {
  savingSalary.value = true
  error.value = ''
  try {
    analysis.value = await updateResumeSalary(resumeId, payload)
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '保存薪资失败'
  } finally {
    savingSalary.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  loadSeq += 1
  clearTimer()
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div class="head-main">
        <RouterLink to="/resumes" class="back">← 返回简历列表</RouterLink>
        <div class="title-row">
          <h2>{{ resume?.fileName || '粘贴文本简历' }}</h2>
          <span class="badge" :class="statusTone">{{ statusText }}</span>
        </div>
        <p class="meta">
          上传于 {{ formatTime(resume?.createdAt) }}
          <span v-if="resume?.fileType" class="meta-sep">·</span>
          <span v-if="resume?.fileType">{{ resume.fileType }}</span>
          <span v-if="resume?.fileSize" class="meta-sep">·</span>
          <span v-if="resume?.fileSize" class="numeric">{{ (resume.fileSize / 1024).toFixed(0) }} KB</span>
        </p>
      </div>
      <div class="head-actions">
        <button type="button" class="btn ghost" @click="router.push('/resumes')">返回列表</button>
        <button type="button" class="btn primary" :disabled="busy || running" @click="reanalyze">
          {{ actionText }}
        </button>
      </div>
    </header>

    <p v-if="error" class="error-banner">{{ error }}</p>

    <section v-if="loading" class="skeleton">
      <div class="skeleton-card" />
      <div class="skeleton-card short" />
    </section>

    <EmptyState
      v-else-if="!resume"
      title="简历不存在"
      hint="它可能已被删除，返回列表重新上传。"
      action-label="返回简历列表"
      @action="router.push('/resumes')"
    />

    <section v-else-if="running" class="state-card">
      <span class="pulse" />
      <div>
        <h3>正在分析这份简历</h3>
        <p>通常需要十几秒，本页会自动刷新，无需手动操作。</p>
      </div>
    </section>

    <section v-else-if="status === 'FAILED'" class="state-card failed">
      <div>
        <h3>分析失败</h3>
        <p>{{ analysis?.error || resume?.analyzeError || '未知原因' }}</p>
        <p class="retry-hint">已自动重试 {{ analysis?.retryCount ?? resume?.retryCount ?? 0 }} 次，可手动重试。</p>
      </div>
      <button type="button" class="btn primary" :disabled="busy" @click="reanalyze">重试分析</button>
    </section>

    <ReportContent
      v-else-if="report"
      :report="report"
      salary-editable
      :saving="savingSalary"
      @save-salary="saveSalary"
    />

    <EmptyState
      v-else
      title="暂无分析结果"
      hint="点击「开始分析」生成报告。"
      action-label="开始分析"
      @action="reanalyze"
    />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-4);
}

.back {
  font-size: 12.5px;
  color: var(--text-3);
}

.back:hover {
  color: var(--primary);
}

.title-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.title-row h2 {
  font-size: 18px;
}

.badge {
  padding: 1px 8px;
  border-radius: var(--radius-tag);
  font-size: 11.5px;
  font-weight: 500;
}

.badge.success {
  color: var(--success);
  background: #ecfdf5;
}

.badge.danger {
  color: var(--danger);
  background: #fef2f2;
}

.badge.primary {
  color: var(--primary);
  background: var(--primary-soft);
}

.badge.muted {
  color: var(--text-3);
  background: var(--surface-muted);
}

.meta {
  margin: var(--space-1) 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}

.meta-sep {
  margin: 0 6px;
}

.head-actions {
  display: flex;
  gap: var(--space-2);
  flex: 0 0 auto;
}

.btn {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-btn);
  font-size: 13px;
  border: 1px solid transparent;
  transition: background 0.12s ease, border-color 0.12s ease;
}

.btn.primary {
  background: var(--primary);
  color: #fff;
}

.btn.primary:hover:not(:disabled) {
  background: var(--primary-hover);
}

.btn.ghost {
  background: var(--surface);
  border-color: var(--border);
  color: var(--text-2);
}

.btn.ghost:hover {
  border-color: var(--border-strong);
  color: var(--text);
}

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.error-banner {
  margin: 0;
  padding: var(--space-3) var(--space-4);
  border: 1px solid #fecaca;
  border-radius: var(--radius-btn);
  background: #fef2f2;
  color: var(--danger);
  font-size: 13px;
}

.state-card {
  display: flex;
  align-items: flex-start;
  gap: var(--space-4);
  padding: var(--space-5);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-rest);
}

.state-card h3 {
  font-size: 14px;
  margin-bottom: var(--space-1);
}

.state-card p {
  margin: 0;
  font-size: 13px;
  color: var(--text-2);
}

.state-card.failed {
  border-color: var(--danger);
  justify-content: space-between;
}

.state-card.failed h3 {
  color: var(--danger);
}

.retry-hint {
  margin-top: var(--space-1) !important;
  color: var(--text-3) !important;
}

.pulse {
  width: 10px;
  height: 10px;
  margin-top: 5px;
  flex: 0 0 10px;
  border-radius: 50%;
  background: var(--primary);
  animation: pulse 1.4s ease-in-out infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
}

.skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.skeleton-card {
  height: 180px;
  border-radius: var(--radius-card);
  background: var(--surface);
  border: 1px solid var(--border);
}

.skeleton-card.short {
  height: 96px;
}

@media (max-width: 720px) {
  .page-head {
    flex-direction: column;
  }

  .head-actions {
    width: 100%;
  }
}
</style>
