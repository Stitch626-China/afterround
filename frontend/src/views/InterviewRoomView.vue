<script setup lang="ts">
/**
 * 模拟面试房间（F3）：出题轮询 → 逐题作答 / 跳过 → 追问 → 交卷 → 评估报告。
 * 会话内支持 Provider + Model 两级切换，执行中与终态按契约禁用。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  answerInterviewQuestion,
  getInterviewEvaluation,
  getInterviewSession,
  retryInterviewQuestions,
  skipInterviewQuestion,
  submitInterviewSession,
  switchInterviewModel,
  type InterviewEvaluation,
  type InterviewQuestion,
  type InterviewSessionDetail,
} from '@/api/interview'
import { listProviders, type LlmProvider } from '@/api/llm'
import {
  DIFFICULTY_TEXT,
  QUESTION_TYPE_TEXT,
  SESSION_STATUS_TEXT,
  SESSION_STATUS_TONE,
  evaluationStatusText,
  nextQuestion,
  progressText,
  scoreTone,
} from '@/utils/interview'

const route = useRoute()
const router = useRouter()
const sessionId = Number(route.params.id)

const detail = ref<InterviewSessionDetail | null>(null)
const evaluation = ref<InterviewEvaluation | null>(null)
const providers = ref<LlmProvider[]>([])
const loading = ref(true)
const actionBusy = ref(false)
const error = ref('')
const notice = ref('')
const answerText = ref('')
const submitConfirm = ref(false)
const skipTarget = ref<number | null>(null)
const showAnswered = ref(false)
const modelForm = reactive({ providerKey: '', model: '' })

const questions = computed(() => detail.value?.questions ?? [])
const current = computed(() => nextQuestion(questions.value))
const status = computed(() => detail.value?.status ?? 'PREPARING')
const answered = computed(() =>
  questions.value.filter((item) => item.answerStatus !== 'UNANSWERED'),
)
const unanswered = computed(() =>
  questions.value.filter((item) => item.answerStatus === 'UNANSWERED').length,
)
const running = computed(() => status.value === 'PREPARING' || status.value === 'GENERATING')
const evaluating = computed(
  () =>
    status.value === 'SUBMITTED' &&
    (detail.value?.evaluateStatus === 'PENDING' || detail.value?.evaluateStatus === 'PROCESSING'),
)
const evaluationFailed = computed(
  () => detail.value?.evaluateStatus === 'FAILED' || evaluation.value?.status === 'FAILED',
)
const report = computed(() =>
  evaluation.value?.status === 'COMPLETED' ? evaluation.value : null,
)
const canSwitch = computed(() => status.value === 'IN_PROGRESS' || status.value === 'FAILED')
const canSubmit = computed(() => status.value === 'IN_PROGRESS' && questions.value.length > 0)
const models = computed(
  () =>
    providers.value
      .find((item) => item.providerKey === modelForm.providerKey)
      ?.models?.filter((item) => item.enabled) ?? [],
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
  if (!disposed && (running.value || evaluating.value)) {
    timer = setTimeout(() => void refresh(), 1500)
  }
}

async function refresh(initial = false): Promise<void> {
  if (initial) {
    loading.value = true
  }
  try {
    const data = await getInterviewSession(sessionId)
    detail.value = data
    syncModel()
    if (data.status === 'SUBMITTED' || data.status === 'COMPLETED') {
      await loadEvaluation()
    }
    error.value = ''
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载失败'
  } finally {
    loading.value = false
    schedule()
  }
}

async function loadEvaluation(): Promise<void> {
  try {
    evaluation.value = await getInterviewEvaluation(sessionId)
  } catch {
    // 未交卷时评估接口返回 409；此时不展示评估信息
    evaluation.value = null
  }
}

async function loadProviders(): Promise<void> {
  try {
    providers.value = await listProviders()
    syncModel()
  } catch {
    providers.value = []
  }
}

function syncModel(): void {
  const providerKey = detail.value?.currentProviderKey ?? ''
  const model = detail.value?.currentModel ?? ''
  if (!modelForm.providerKey && providerKey) {
    modelForm.providerKey = providerKey
  }
  if (!modelForm.model && model) {
    modelForm.model = model
  }
}

function onProviderChange(): void {
  modelForm.model =
    models.value.find((item) => item.isDefault)?.modelName ?? models.value[0]?.modelName ?? ''
}

async function switchModel(): Promise<void> {
  if (!modelForm.providerKey || !modelForm.model) {
    error.value = '请选择 Provider 与模型'
    return
  }
  actionBusy.value = true
  error.value = ''
  try {
    await switchInterviewModel(sessionId, modelForm.providerKey, modelForm.model)
    notice.value = '会话模型已切换，只影响后续出题、追问与评估'
    await refresh()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '切换失败'
  } finally {
    actionBusy.value = false
  }
}

async function answer(): Promise<void> {
  const question = current.value
  if (!question) {
    return
  }
  const text = answerText.value.trim()
  if (!text) {
    error.value = '请先输入回答'
    return
  }
  actionBusy.value = true
  error.value = ''
  notice.value = ''
  try {
    await answerInterviewQuestion(sessionId, question.questionIndex, text)
    answerText.value = ''
    notice.value = '作答已保存'
    await refresh()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '提交失败'
  } finally {
    actionBusy.value = false
  }
}

async function skip(): Promise<void> {
  const question = current.value
  if (!question) {
    return
  }
  actionBusy.value = true
  error.value = ''
  try {
    await skipInterviewQuestion(sessionId, question.questionIndex)
    skipTarget.value = null
    answerText.value = ''
    notice.value = '已跳过本题，按 0 分计入总分'
    await refresh()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '跳过失败'
  } finally {
    actionBusy.value = false
  }
}

async function submit(): Promise<void> {
  actionBusy.value = true
  error.value = ''
  try {
    await submitInterviewSession(sessionId)
    submitConfirm.value = false
    notice.value = '已交卷，正在生成评估报告…'
    await refresh()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '交卷失败'
  } finally {
    actionBusy.value = false
  }
}

async function retry(): Promise<void> {
  actionBusy.value = true
  error.value = ''
  try {
    await retryInterviewQuestions(sessionId)
    notice.value = '已重新触发出题'
    await refresh()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '重试失败'
  } finally {
    actionBusy.value = false
  }
}

function questionStatusText(item: InterviewQuestion): string {
  if (item.answerStatus === 'ANSWERED') {
    return '已作答'
  }
  return item.answerStatus === 'SKIPPED' ? '已跳过' : '未作答'
}

function questionStatusTone(item: InterviewQuestion): string {
  if (item.answerStatus === 'ANSWERED') {
    return 'success'
  }
  return item.answerStatus === 'SKIPPED' ? 'warning' : 'muted'
}

function difficultyTone(difficulty: string): string {
  if (difficulty === 'EASY') {
    return 'success'
  }
  return difficulty === 'HARD' ? 'danger' : 'warning'
}

function refs(): string {
  const parts = [
    detail.value?.resumeFileName,
    detail.value?.jobPositionTitle,
    detail.value?.companyName,
  ].filter(Boolean)
  return parts.length ? parts.join(' · ') : '仅方向出题'
}

function formatTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

onMounted(async () => {
  await Promise.all([refresh(true), loadProviders()])
})
onBeforeUnmount(() => {
  disposed = true
  clearTimer()
})
</script>

<template>
  <div class="room">
    <div class="room-head">
      <div>
        <button type="button" class="back" @click="router.push('/interviews')">← 返回面试列表</button>
        <h2>{{ detail?.directionLabel ?? '模拟面试' }}</h2>
        <p class="meta">{{ refs() }}</p>
      </div>
      <div class="head-status">
        <span class="badge" :class="SESSION_STATUS_TONE[status]">
          {{ SESSION_STATUS_TEXT[status] ?? status }}
        </span>
        <span v-if="detail?.currentProviderKey" class="model-label">
          {{ detail.currentProviderKey }} / {{ detail.currentModel }}
        </span>
      </div>
    </div>

    <section class="model-switch">
      <span class="model-title">会话模型</span>
      <select v-model="modelForm.providerKey" :disabled="!canSwitch" @change="onProviderChange">
        <option value="" disabled>选择 Provider</option>
        <option v-for="item in providers" :key="item.providerKey" :value="item.providerKey">
          {{ item.label }}
        </option>
      </select>
      <select v-model="modelForm.model" :disabled="!canSwitch">
        <option value="" disabled>选择模型</option>
        <option v-for="item in models" :key="item.modelName" :value="item.modelName">
          {{ item.label || item.modelName }}
        </option>
      </select>
      <button type="button" class="btn ghost" :disabled="!canSwitch || actionBusy" @click="switchModel">
        切换模型
      </button>
      <span class="hint">{{ canSwitch ? '仅影响后续出题、追问与评估' : '当前状态不可切换模型' }}</span>
    </section>

    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-else-if="notice" class="banner success">{{ notice }}</div>

    <section v-if="loading" class="state-card">
      <h3>正在加载面试…</h3>
    </section>

    <section v-else-if="status === 'PREPARING'" class="state-card">
      <h3>正在生成题目…</h3>
      <p>
        共 {{ detail?.questionCount }} 道主问题，每题 {{ detail?.followUpCount }} 个追问；
        生成完成后会自动进入答题。
      </p>
    </section>

    <section v-else-if="status === 'FAILED'" class="state-card danger">
      <h3>出题失败</h3>
      <p>{{ detail?.error || '模型暂时不可用' }}</p>
      <p class="hint">已自动重试 {{ detail?.retryCount ?? 0 }} 次，可切换模型后重新生成。</p>
      <button type="button" class="btn primary" :disabled="actionBusy" @click="retry">重新生成题目</button>
    </section>

    <template v-else-if="status === 'IN_PROGRESS' || status === 'GENERATING'">
      <div class="progress-line">
        <span class="numeric">{{ progressText(detail?.answeredCount ?? 0, questions.length) }}</span>
        <span v-if="status === 'GENERATING'" class="badge warning">正在生成追问…</span>
      </div>

      <section v-if="current" class="question-card">
        <div class="q-head">
          <span class="badge primary">第 {{ current.questionIndex }} 题</span>
          <span class="badge muted">{{ QUESTION_TYPE_TEXT[current.type] ?? current.type }}</span>
          <span class="badge" :class="difficultyTone(current.difficulty)">
            {{ DIFFICULTY_TEXT[current.difficulty] ?? current.difficulty }}
          </span>
          <span v-if="current.topicSummary" class="topic">{{ current.topicSummary }}</span>
        </div>
        <p class="q-content">{{ current.content }}</p>
        <textarea
          v-model="answerText"
          class="answer-box"
          rows="6"
          maxlength="5000"
          placeholder="写下你的回答，尽量包含具体做法、取舍与结果…"
          :disabled="status === 'GENERATING'"
        />
        <div class="q-actions">
          <button
            type="button"
            class="btn primary"
            :disabled="actionBusy || status === 'GENERATING'"
            @click="answer"
          >
            {{ actionBusy ? '提交中…' : '提交作答' }}
          </button>
          <template v-if="skipTarget === current.questionIndex">
            <button type="button" class="link danger" :disabled="actionBusy" @click="skip">
              确认跳过
            </button>
            <button type="button" class="link" @click="skipTarget = null">取消</button>
          </template>
          <button
            v-else
            type="button"
            class="link"
            :disabled="status === 'GENERATING'"
            @click="skipTarget = current.questionIndex"
          >
            跳过本题
          </button>
        </div>
        <p class="hint">提交后不可修改；主问题提交后会自动生成追问。</p>
      </section>

      <section v-else class="state-card">
        <h3>所有题目都已处理</h3>
        <p>可以交卷生成评估报告。</p>
      </section>

      <section class="answered">
        <button type="button" class="link" @click="showAnswered = !showAnswered">
          {{ showAnswered ? '收起' : '查看' }}已答题目（{{ answered.length }}）
        </button>
        <ul v-if="showAnswered" class="answered-list">
          <li v-for="item in answered" :key="item.questionIndex">
            <div class="answered-head">
              <span>第 {{ item.questionIndex }} 题 · {{ QUESTION_TYPE_TEXT[item.type] ?? item.type }}</span>
              <span class="badge" :class="questionStatusTone(item)">{{ questionStatusText(item) }}</span>
            </div>
            <p class="answered-q">{{ item.content }}</p>
            <p v-if="item.answer" class="answered-a">回答：{{ item.answer }}</p>
          </li>
        </ul>
      </section>

      <section class="submit-bar">
        <span>
          还有 {{ unanswered }} 题未作答；跳过与未答按 0 分计入总分，交卷后答案不可修改。
        </span>
        <div class="submit-actions">
          <template v-if="submitConfirm">
            <button type="button" class="btn danger" :disabled="actionBusy" @click="submit">
              确认交卷
            </button>
            <button type="button" class="btn ghost" @click="submitConfirm = false">取消</button>
          </template>
          <button
            v-else
            type="button"
            class="btn primary"
            :disabled="!canSubmit || actionBusy"
            @click="submitConfirm = true"
          >
            交卷并评估
          </button>
        </div>
      </section>
    </template>

    <template v-else-if="status === 'SUBMITTED'">
      <section v-if="evaluationFailed" class="state-card danger">
        <h3>评估失败</h3>
        <p>{{ detail?.evaluateError || evaluation?.error || '模型暂时不可用' }}</p>
        <p class="hint">
          已自动重试 {{ detail?.evaluateRetryCount ?? evaluation?.retryCount ?? 0 }} 次；
          逐题作答已保存，可稍后刷新查看。
        </p>
      </section>
      <section v-else class="state-card">
        <h3>已交卷，正在生成评估报告…</h3>
        <p>{{ evaluationStatusText(detail?.evaluateStatus ?? 'PROCESSING') }}；完成后自动展示总分与逐题点评。</p>
      </section>
    </template>

    <section v-else-if="status === 'COMPLETED' && !report" class="state-card">
      <h3>评估报告加载中…</h3>
    </section>

    <section v-if="report" class="report">
      <div class="score-head">
        <div class="score-big numeric" :class="scoreTone(report.overallScore)">
          {{ report.overallScore ?? '—' }}
        </div>
        <div class="score-summary">
          <h3>评估完成</h3>
          <p>{{ report.summary || '暂无整体评语' }}</p>
        </div>
      </div>

      <div class="categories">
        <div v-for="item in report.categories" :key="item.key" class="cat-row">
          <div class="cat-head">
            <span>{{ item.label }}</span>
            <strong class="numeric">{{ item.score }}</strong>
          </div>
          <div class="track">
            <div
              class="fill"
              :class="scoreTone(item.score)"
              :style="{ width: `${Math.max(0, Math.min(100, item.score))}%` }"
            />
          </div>
        </div>
      </div>

      <div class="two-col">
        <div>
          <h4>优势</h4>
          <ul>
            <li v-for="(item, index) in report.strengths" :key="`s-${index}`">{{ item }}</li>
          </ul>
        </div>
        <div>
          <h4>改进建议</h4>
          <ul>
            <li v-for="(item, index) in report.improvements" :key="`i-${index}`">{{ item }}</li>
          </ul>
        </div>
      </div>

      <p class="hint">
        {{ report.llmProvider }} / {{ report.llmModel }} · {{ formatTime(report.evaluatedAt) }}
      </p>

      <section class="review">
        <h4>逐题点评</h4>
        <article v-for="item in questions" :key="item.questionIndex" class="review-item">
          <div class="review-head">
            <span>第 {{ item.questionIndex }} 题 · {{ QUESTION_TYPE_TEXT[item.type] ?? item.type }}</span>
            <span class="badge" :class="questionStatusTone(item)">{{ questionStatusText(item) }}</span>
            <strong v-if="item.score != null" class="numeric">{{ item.score }} 分</strong>
          </div>
          <p class="review-q">{{ item.content }}</p>
          <p v-if="item.answer" class="review-a">你的回答：{{ item.answer }}</p>
          <p v-if="item.feedback" class="review-feedback">点评：{{ item.feedback }}</p>
          <p v-if="item.referenceAnswer" class="review-ref">参考答案：{{ item.referenceAnswer }}</p>
          <p v-if="item.keyPoints?.length" class="review-key">关键点：{{ item.keyPoints.join('、') }}</p>
          <p v-if="item.answerStatus !== 'ANSWERED'" class="review-skip">未作答，按 0 分计入总分。</p>
        </article>
      </section>
    </section>
  </div>
</template>

<style scoped>
.room {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 920px;
}

.room-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-3);
}

.back {
  padding: 0;
  border: none;
  background: none;
  color: var(--text-3);
  font-size: 12.5px;
}

.back:hover {
  color: var(--primary);
}

.room-head h2 {
  margin-top: var(--space-2);
  font-size: 17px;
}

.meta {
  margin: 2px 0 0;
  color: var(--text-2);
  font-size: 12.5px;
}

.head-status {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-2);
}

.model-label {
  color: var(--text-3);
  font-size: 11.5px;
}

.model-switch {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.model-title {
  font-size: 12.5px;
  color: var(--text-2);
}

.model-switch select {
  padding: 5px var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 12px;
}

.hint {
  margin: 0;
  color: var(--text-3);
  font-size: 12px;
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

.state-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-5);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.state-card.danger {
  border-color: var(--danger);
}

.state-card h3 {
  font-size: 14.5px;
}

.state-card p {
  margin: 0;
  color: var(--text-2);
  font-size: 13px;
}

.progress-line {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  color: var(--text-2);
  font-size: 12.5px;
}

.question-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-5);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-rest);
}

.q-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.topic {
  color: var(--text-3);
  font-size: 12px;
}

.q-content {
  margin: 0;
  font-size: 14.5px;
  line-height: 1.7;
}

.answer-box {
  width: 100%;
  resize: vertical;
  padding: var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.7;
}

.q-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
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

.btn.danger {
  background: var(--danger);
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
}

.link.danger {
  color: var(--danger);
}

.answered {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.answered-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.answered-list li {
  padding: var(--space-3) var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
}

.answered-head {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
  font-size: 12.5px;
  color: var(--text-2);
}

.answered-q {
  margin: var(--space-2) 0 0;
  font-size: 13px;
}

.answered-a {
  margin: var(--space-1) 0 0;
  color: var(--text-2);
  font-size: 12.5px;
  white-space: pre-wrap;
}

.submit-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--surface-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  color: var(--text-2);
  font-size: 12.5px;
}

.submit-actions {
  display: flex;
  gap: var(--space-2);
}

.report {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
  padding: var(--space-5);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.score-head {
  display: flex;
  align-items: center;
  gap: var(--space-5);
}

.score-big {
  font-size: 44px;
  font-weight: 600;
  line-height: 1;
}

.score-big.high {
  color: var(--score-high);
}

.score-big.mid {
  color: var(--score-mid);
}

.score-big.low {
  color: var(--score-low);
}

.score-summary h3 {
  font-size: 15px;
}

.score-summary p {
  margin: var(--space-1) 0 0;
  color: var(--text-2);
  font-size: 13px;
}

.categories {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.cat-head {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--text-2);
}

.cat-head strong {
  color: var(--text);
}

.track {
  height: 6px;
  margin-top: 4px;
  border-radius: 3px;
  background: var(--surface-muted);
  border: 1px solid var(--border);
  overflow: hidden;
}

.fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.3s ease;
}

.fill.high {
  background: var(--score-high);
}

.fill.mid {
  background: var(--score-mid);
}

.fill.low {
  background: var(--score-low);
}

.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
}

.two-col h4,
.review h4 {
  margin: 0 0 var(--space-2);
  font-size: 13.5px;
}

.two-col ul {
  margin: 0;
  padding-left: 18px;
  color: var(--text-2);
  font-size: 12.5px;
  line-height: 1.8;
}

.review {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.review-item {
  padding: var(--space-3) var(--space-4);
  background: var(--surface-muted);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
}

.review-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  font-size: 12.5px;
  color: var(--text-2);
}

.review-q {
  margin: var(--space-2) 0 0;
  font-size: 13px;
  font-weight: 500;
}

.review-a,
.review-feedback,
.review-ref,
.review-key,
.review-skip {
  margin: var(--space-1) 0 0;
  font-size: 12.5px;
  color: var(--text-2);
  white-space: pre-wrap;
}

.review-ref {
  color: var(--text-3);
}

.review-skip {
  color: var(--warning);
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

.badge.muted {
  color: var(--text-3);
  background: var(--surface-muted);
}

@media (max-width: 760px) {
  .room-head,
  .submit-bar,
  .score-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .head-status {
    align-items: flex-start;
  }

  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
