<script setup lang="ts">
/**
 * 复盘详情（F4）：转写修正 → 重新分析 → 修正 / 确认结构化分析 → 生成改进行动。
 * 处理中按 3 秒轮询轻量状态接口，终态后自动重新加载详情。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  analyzeReviewSession,
  clearReviewAudio,
  deleteReviewSession,
  getReviewSession,
  getReviewSessionStatus,
  getReviewTaxonomy,
  listImprovementActions,
  listInterviewEvents,
  transcribeReviewSession,
  updateInterviewEvent,
  updateReviewAnalysis,
  updateReviewSession,
  type ImprovementAction,
  type ImprovementActionDraft,
  type InterviewResult,
  type ReviewSessionDetail,
  type ReviewTaxonomy,
} from '@/api/reviews'
import {
  ACTION_STATUS_TEXT,
  ACTION_STATUS_TONE,
  INPUT_TYPE_TEXT,
  INTERVIEW_RESULT_TEXT,
  REVIEW_STATUS_TEXT,
  REVIEW_STATUS_TONE,
  ROUND_TYPE_TEXT,
  buildAnalysisPayload,
  emptyDraft,
  isProcessing,
} from '@/utils/review'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)

const detail = ref<ReviewSessionDetail | null>(null)
const taxonomy = ref<ReviewTaxonomy | null>(null)
const actions = ref<ImprovementAction[]>([])
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const transcriptDraft = ref('')
const summaryDraft = ref({ overall: '', strengths: '', improvements: '', nextPreparation: '' })
const actionDrafts = ref<ImprovementActionDraft[]>([])
const confirmDelete = ref(false)
const confirmClearAudio = ref(false)
const eventResult = ref<InterviewResult | null>(null)
const resultSaving = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const analysis = computed(() => detail.value?.analysis ?? null)
const processing = computed(() => (detail.value ? isProcessing(detail.value.status) : false))
const canAnalyze = computed(() => {
  if (!detail.value || processing.value) {
    return false
  }
  if (detail.value.inputType !== 'AUDIO') {
    return true
  }
  return detail.value.asrStatus === 'COMPLETED'
    || (detail.value.asrStatus === 'FAILED' && !!transcriptDraft.value.trim())
})

const topicLabels = computed(() => new Map((taxonomy.value?.topics ?? []).map((item) => [item.key, item.label])))
const lossLabels = computed(() => new Map((taxonomy.value?.lossTags ?? []).map((item) => [item.key, item.label])))

function tagText(keys: string[] | undefined, labels: Map<string, string>): string {
  return (keys ?? []).map((key) => labels.get(key) ?? key).join(' / ')
}

async function load(): Promise<void> {
  if (!Number.isFinite(id) || id <= 0) {
    error.value = '无效的复盘 ID'
    loading.value = false
    return
  }
  loading.value = true
  try {
    const result = await getReviewSession(id)
    detail.value = result
    transcriptDraft.value = result.transcript ?? ''
    syncDrafts()
    error.value = ''
    syncPolling()
    void loadEventResult(result.applicationId ?? null, result.interviewEventId)
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载失败'
  } finally {
    loading.value = false
  }
  void loadActions()
}

async function loadActions(): Promise<void> {
  try {
    const result = await listImprovementActions({ page: 1, size: 50, sourceReviewId: id })
    actions.value = result.items
  } catch {
    // 行动列表失败不阻断详情
  }
}

/** 读取本轮面试结果；契约没有单条 GET，按投递筛选后按事件 id 匹配。 */
async function loadEventResult(applicationId: number | null, eventId: number): Promise<void> {
  if (!applicationId) {
    return
  }
  try {
    const page = await listInterviewEvents({ applicationId, page: 1, size: 100 })
    const event = page.items.find((item) => item.id === eventId)
    eventResult.value = event?.result ?? 'PENDING'
  } catch {
    // 结果读取失败不阻断复盘详情，用户可刷新重试
  }
}

async function changeResult(value: InterviewResult): Promise<void> {
  if (!detail.value || resultSaving.value) {
    return
  }
  const previous = eventResult.value
  eventResult.value = value
  resultSaving.value = true
  error.value = ''
  try {
    const updated = await updateInterviewEvent(detail.value.interviewEventId, { result: value })
    eventResult.value = updated.result
    notice.value = `本轮结果已标记为「${INTERVIEW_RESULT_TEXT[updated.result] ?? updated.result}」`
  } catch (ex) {
    eventResult.value = previous
    error.value = ex instanceof Error ? ex.message : '更新本轮结果失败'
  } finally {
    resultSaving.value = false
  }
}

async function loadTaxonomy(): Promise<void> {
  try {
    taxonomy.value = await getReviewTaxonomy()
  } catch {
    // 词表失败时直接显示 key
  }
}

function syncDrafts(): void {
  const current = analysis.value
  summaryDraft.value = {
    overall: current?.summary?.overall ?? '',
    strengths: (current?.summary?.strengths ?? []).join('\n'),
    improvements: (current?.summary?.improvements ?? []).join('\n'),
    nextPreparation: (current?.summary?.nextPreparation ?? []).join('\n'),
  }
  actionDrafts.value = (current?.actions ?? []).map((item) => ({
    action: item.action,
    category: item.category ?? null,
    reason: item.reason ?? null,
  }))
}

function syncPolling(): void {
  if (processing.value && !timer) {
    timer = setInterval(async () => {
      try {
        const status = await getReviewSessionStatus(id)
        if (!detail.value) {
          return
        }
        detail.value = {
          ...detail.value,
          status: status.status,
          asrStatus: status.asrStatus,
          analyzeStatus: status.analyzeStatus,
          asrRetryCount: status.asrRetryCount,
          analyzeRetryCount: status.analyzeRetryCount,
          asrError: status.asrError ?? null,
          analyzeError: status.analyzeError ?? null,
        }
        if (!isProcessing(status.status)) {
          stopPolling()
          await load()
        }
      } catch {
        stopPolling()
      }
    }, 3000)
  } else if (!processing.value) {
    stopPolling()
  }
}

function stopPolling(): void {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function saveTranscript(): Promise<void> {
  if (!detail.value) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    const updated = await updateReviewSession(id, { transcript: transcriptDraft.value.trim() || null })
    detail.value = updated
    notice.value = '转写已保存；重新分析后生效'
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '保存转写失败'
  } finally {
    busy.value = false
  }
}

async function reanalyze(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await analyzeReviewSession(id)
    notice.value = '已触发重新分析'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '触发分析失败'
  } finally {
    busy.value = false
  }
}

async function retryAsr(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await transcribeReviewSession(id)
    notice.value = '已重新触发转写'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '触发转写失败'
  } finally {
    busy.value = false
  }
}

function onResultChange(event: Event): void {
  void changeResult((event.target as HTMLSelectElement).value as InterviewResult)
}

/** ASR 失败时用用户补录的文本继续分析（FR4.1 降级）。 */
async function useTextFallback(): Promise<void> {
  if (!transcriptDraft.value.trim()) {
    error.value = '请先在上方粘贴或填写转写文本'
    return
  }
  busy.value = true
  error.value = ''
  try {
    const updated = await updateReviewSession(id, { transcript: transcriptDraft.value.trim() })
    detail.value = updated
    await analyzeReviewSession(id)
    notice.value = '已改用文本分析'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '改用文本分析失败'
  } finally {
    busy.value = false
  }
}

function addAction(): void {
  if (actionDrafts.value.length >= 3) {
    error.value = '确认时最多保留 3 条改进行动'
    return
  }
  actionDrafts.value.push(emptyDraft())
}

function removeAction(index: number): void {
  actionDrafts.value.splice(index, 1)
}

async function saveAnalysis(confirmed: boolean): Promise<void> {
  const current = analysis.value
  if (!current) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    const updated = await updateReviewAnalysis(
      id,
      buildAnalysisPayload({
        questions: current.questions,
        topics: current.topics,
        lossTags: current.lossTags,
        summary: {
          overall: summaryDraft.value.overall,
          strengths: splitLines(summaryDraft.value.strengths),
          improvements: splitLines(summaryDraft.value.improvements),
          nextPreparation: splitLines(summaryDraft.value.nextPreparation),
        },
        actions: actionDrafts.value,
        confirmed,
      }),
    )
    detail.value = updated
    syncDrafts()
    notice.value = confirmed ? '分析已确认，改进行动已同步到改进看板' : '分析修正已保存'
    await loadActions()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '保存分析失败'
  } finally {
    busy.value = false
  }
}

function splitLines(text: string): string[] {
  return text.split('\n').map((item) => item.trim()).filter(Boolean)
}

async function remove(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await deleteReviewSession(id)
    await router.push('/reviews')
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '删除失败'
    busy.value = false
  }
}

/** 清理音频（NFR7）：成功后重新拉详情，音频名消失、转写与分析保留。 */
async function clearAudio(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await clearReviewAudio(id)
    detail.value = await getReviewSession(id)
    confirmClearAudio.value = false
    notice.value = '音频已清理，转写与分析保留'
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '清理音频失败'
  } finally {
    busy.value = false
  }
}

function formatTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
}

onMounted(() => {
  void load()
  void loadTaxonomy()
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <div>
        <RouterLink to="/reviews" class="back">← 返回复盘列表</RouterLink>
        <h2 v-if="detail" class="title">
          {{ detail.jobTitle || detail.companyName || '未关联岗位' }}
          <span v-if="detail.roundNo" class="sub">
            第 {{ detail.roundNo }} 轮
            {{ detail.roundType ? ROUND_TYPE_TEXT[detail.roundType] ?? detail.roundType : '' }}
          </span>
        </h2>
      </div>
      <div v-if="detail" class="head-actions">
        <button v-if="canAnalyze" type="button" class="btn ghost" :disabled="busy" @click="reanalyze">
          重新分析
        </button>
        <button
          v-if="detail.inputType === 'AUDIO' && detail.asrStatus === 'FAILED' && detail.audioFileName"
          type="button"
          class="btn ghost"
          :disabled="busy"
          @click="retryAsr"
        >
          重试转写
        </button>
        <button
          v-if="detail.inputType === 'AUDIO' && detail.asrStatus === 'FAILED'"
          type="button"
          class="btn ghost"
          :disabled="busy"
          @click="useTextFallback"
        >
          改用文本分析
        </button>
        <template v-if="confirmDelete">
          <button type="button" class="btn danger" :disabled="busy" @click="remove">确认删除</button>
          <button type="button" class="btn ghost" @click="confirmDelete = false">取消</button>
        </template>
        <button v-else type="button" class="btn ghost" @click="confirmDelete = true">删除</button>
      </div>
    </header>

    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-else-if="notice" class="banner success">{{ notice }}</div>

    <section v-if="loading" class="skeleton">
      <div class="skeleton-row" />
      <div class="skeleton-row" />
    </section>

    <template v-else-if="detail">
      <div class="status-line">
        <span class="status" :class="REVIEW_STATUS_TONE[detail.status]">
          {{ REVIEW_STATUS_TEXT[detail.status] ?? detail.status }}
        </span>
        <span class="tag">{{ INPUT_TYPE_TEXT[detail.inputType] }}</span>
        <span v-if="detail.confirmed" class="tag success">已确认</span>
        <label v-if="eventResult" class="result-field">
          <span>本轮结果</span>
          <select
            :value="eventResult"
            :disabled="resultSaving"
            aria-label="本轮面试结果"
            @change="onResultChange"
          >
            <option v-for="(text, value) in INTERVIEW_RESULT_TEXT" :key="value" :value="value">
              {{ text }}
            </option>
          </select>
        </label>
        <span class="meta">创建于 {{ formatTime(detail.createdAt) }}</span>
        <span v-if="processing" class="meta">处理中，自动刷新…</span>
      </div>

      <div v-if="detail.asrError" class="banner danger">转写失败：{{ detail.asrError }}</div>
      <div v-if="detail.analyzeError" class="banner danger">分析失败：{{ detail.analyzeError }}</div>

      <section class="card">
        <div class="card-head">
          <h3 class="card-title">转写与面试上下文</h3>
          <div v-if="detail.audioFileName" class="audio-meta">
            <span class="meta">音频：{{ detail.audioFileName }}</span>
            <template v-if="confirmClearAudio">
              <button type="button" class="btn danger quiet" :disabled="busy" @click="clearAudio">
                确认清理
              </button>
              <button type="button" class="btn ghost quiet" @click="confirmClearAudio = false">取消</button>
            </template>
            <button
              v-else-if="detail.analyzeStatus === 'COMPLETED'"
              type="button"
              class="btn ghost quiet"
              :disabled="busy || processing"
              @click="confirmClearAudio = true"
            >
              清理音频
            </button>
            <span v-else class="meta">分析完成后可清理</span>
          </div>
          <span v-else-if="detail.inputType === 'AUDIO'" class="meta">音频已清理（转写与分析保留）</span>
        </div>
        <p v-if="detail.inputType === 'NOTES'" class="hint">
          本复盘基于手动笔记创建；如需修正内容，可直接在下方写入转写文本后重新分析。
        </p>
        <textarea
          v-model="transcriptDraft"
          rows="8"
          maxlength="200000"
          placeholder="转写文本为空；可粘贴完整面试记录"
          :disabled="processing"
        />
        <p v-if="detail.inputType === 'AUDIO' && detail.asrStatus === 'FAILED'" class="hint">
          转写失败时可粘贴上面的转写文本，再点「改用文本分析」，不需要删除复盘重来。
        </p>
        <div class="actions">
          <button type="button" class="btn ghost" :disabled="busy || processing" @click="saveTranscript">
            保存转写
          </button>
          <span class="hint">保存后点击「重新分析」才会更新分析结果。</span>
        </div>
      </section>

      <section v-if="!analysis" class="card">
        <h3 class="card-title">结构化分析</h3>
        <p class="hint">
          {{ processing ? '分析进行中，完成后自动展示。' : '尚无分析结果；可手动触发分析。' }}
        </p>
        <button v-if="canAnalyze" type="button" class="btn primary" :disabled="busy" @click="reanalyze">
          开始分析
        </button>
      </section>

      <section v-else class="card">
        <div class="card-head">
          <h3 class="card-title">结构化分析</h3>
          <span class="meta">
            {{ analysis.promptVersion }} · {{ analysis.rubricVersion }} ·
            {{ analysis.taxonomyVersion }}
            <template v-if="analysis.llmModel"> · {{ analysis.llmModel }}</template>
          </span>
        </div>
        <div v-if="analysis.degraded" class="banner warning">
          LLM 分析不完整，当前结果已降级；请补全转写后重新分析。
        </div>

        <div class="tag-line">
          <span class="tag-label">知识点</span>
          <span v-for="topic in analysis.topics" :key="topic.tag" class="tag primary">
            {{ topic.label || topicLabels.get(topic.tag) || topic.tag }}
          </span>
          <span v-if="!analysis.topics.length" class="meta">未识别</span>
        </div>
        <div class="tag-line">
          <span class="tag-label">失分类型</span>
          <span v-for="tag in analysis.lossTags" :key="tag.tag" class="tag danger">
            {{ tag.label || lossLabels.get(tag.tag) || tag.tag }}
          </span>
          <span v-if="!analysis.lossTags.length" class="meta">未识别</span>
        </div>

        <div class="questions">
          <article v-for="question in analysis.questions" :key="question.questionIndex" class="question">
            <div class="question-head">
              <strong>Q{{ question.questionIndex }} · {{ question.question }}</strong>
              <span v-if="question.score != null" class="tag warning">{{ question.score }} 分</span>
            </div>
            <p v-if="question.answerSummary" class="q-text">
              <span class="q-label">回答摘要</span>{{ question.answerSummary }}
            </p>
            <p v-if="question.feedback" class="q-text">
              <span class="q-label">点评</span>{{ question.feedback }}
            </p>
            <p v-if="question.evidence" class="q-text evidence">
              <span class="q-label">证据</span>{{ question.evidence }}
            </p>
            <p v-if="question.topics?.length" class="q-text">
              <span class="q-label">知识点</span>{{ tagText(question.topics, topicLabels) }}
            </p>
            <p v-if="question.lossTags?.length" class="q-text">
              <span class="q-label">失分</span>{{ tagText(question.lossTags, lossLabels) }}
            </p>
            <p v-if="question.referenceAnswer" class="q-text">
              <span class="q-label">参考回答</span>{{ question.referenceAnswer }}
            </p>
          </article>
        </div>

        <div class="summary-edit">
          <label>
            整体总结
            <textarea v-model="summaryDraft.overall" rows="3" maxlength="4000" />
          </label>
          <div class="summary-grid">
            <label>
              优势（每行一条）
              <textarea v-model="summaryDraft.strengths" rows="3" />
            </label>
            <label>
              待改进（每行一条）
              <textarea v-model="summaryDraft.improvements" rows="3" />
            </label>
            <label>
              下一步准备（每行一条）
              <textarea v-model="summaryDraft.nextPreparation" rows="3" />
            </label>
          </div>
        </div>

        <div class="action-editor">
          <div class="card-head">
            <h4>改进行动（确认时同步，最多 3 条）</h4>
            <button type="button" class="link" @click="addAction">+ 添加一条</button>
          </div>
          <div v-for="(action, index) in actionDrafts" :key="index" class="action-row">
            <div class="action-row-head">
              <span class="action-index">行动 {{ index + 1 }}</span>
              <button type="button" class="link danger" @click="removeAction(index)">移除</button>
            </div>
            <label class="field">
              <span class="field-label">行动内容</span>
              <textarea v-model="action.action" rows="2" maxlength="500" placeholder="具体、可执行的改进动作" />
            </label>
            <div class="action-meta">
              <label class="field category">
                <span class="field-label">分类</span>
                <input v-model="action.category" maxlength="64" placeholder="知识点补强 / 表达结构" />
              </label>
              <label class="field reason">
                <span class="field-label">依据 / 原因（可选）</span>
                <input v-model="action.reason" maxlength="1000" placeholder="为什么需要这条行动" />
              </label>
            </div>
          </div>
          <p v-if="!actionDrafts.length" class="hint">暂无行动；可添加后确认。</p>
        </div>

        <div class="actions">
          <button type="button" class="btn primary" :disabled="busy" @click="saveAnalysis(true)">
            {{ detail.confirmed ? '更新并重新确认' : '确认分析并生成行动' }}
          </button>
          <button type="button" class="btn ghost" :disabled="busy" @click="saveAnalysis(false)">
            仅保存修正
          </button>
        </div>
      </section>

      <section class="card">
        <div class="card-head">
          <h3 class="card-title">由本复盘生成的改进行动</h3>
          <RouterLink to="/improvements" class="link">改进看板 →</RouterLink>
        </div>
        <ul v-if="actions.length" class="action-list">
          <li v-for="action in actions" :key="action.id" class="action-item">
            <span class="status" :class="ACTION_STATUS_TONE[action.status]">
              {{ ACTION_STATUS_TEXT[action.status] ?? action.status }}
            </span>
            <span class="action-text">{{ action.action }}</span>
            <span v-if="action.category" class="tag">{{ action.category }}</span>
          </li>
        </ul>
        <p v-else class="hint">确认分析后会在这里生成行动。</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.audio-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.back {
  color: var(--text-3);
  font-size: var(--fs-sm);
  text-decoration: none;
}

.back:hover {
  color: var(--primary);
}

.title {
  margin: var(--space-2) 0 0;
  font-size: var(--fs-h1);
}

.sub {
  margin-left: var(--space-2);
  color: var(--text-3);
  font-size: var(--fs-sm);
  font-weight: 400;
}

.head-actions,
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.status-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.result-field {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-3);
  font-size: var(--fs-sm);
}

.result-field select {
  height: 32px;
  padding: 0 var(--space-2);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: var(--fs-sm);
}

.card textarea,
.card input {
  width: 100%;
  box-sizing: border-box;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: var(--fs-sm);
}

.card textarea {
  resize: vertical;
  line-height: 1.6;
}

.card textarea:focus-visible,
.card input:focus-visible {
  outline: 1px solid var(--primary);
  outline-offset: 0;
  box-shadow: 0 0 0 3px var(--primary-soft);
}

.hint,
.meta {
  margin: 0;
  color: var(--text-3);
  font-size: var(--fs-sm);
}

.tag-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.tag-label {
  color: var(--text-3);
  font-size: var(--fs-overline);
  font-weight: 500;
  letter-spacing: 0.06em;
}

.questions {
  display: flex;
  flex-direction: column;
}

/* 逐题分析不再套小卡片，用墨线分隔 */
.question {
  padding: var(--space-3) 0;
  border-top: 1px solid var(--border);
}

.questions .question:first-child {
  border-top: none;
  padding-top: 0;
}

.question-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-2);
  font-size: 13.5px;
}

.q-text {
  margin: 6px 0 0;
  color: var(--text-2);
  font-size: var(--fs-sm);
  line-height: 1.6;
}

.q-label {
  margin-right: 6px;
  color: var(--text-3);
  font-size: var(--fs-xs);
}

.evidence {
  color: var(--text-3);
}

.summary-edit {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.summary-edit label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: var(--text-3);
  font-size: var(--fs-overline);
  font-weight: 500;
  letter-spacing: 0.04em;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--space-3);
}

.action-editor {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.action-editor h4 {
  margin: 0;
  font-size: var(--fs-body);
  font-weight: 600;
}

/* 可编辑行动行用浅底通栏，不画第二层卡片边框 */
.action-row {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-btn);
  background: var(--surface-muted);
}

.action-row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.action-index {
  color: var(--text-3);
  font-size: var(--fs-overline);
  font-weight: 500;
  letter-spacing: 0.06em;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  color: var(--text-3);
  font-size: var(--fs-overline);
  font-weight: 500;
  letter-spacing: 0.04em;
}

.action-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--space-3);
}

.action-meta .category {
  flex: 0 0 180px;
}

.action-meta .reason {
  flex: 1 1 260px;
}

.action-list {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  list-style: none;
}

.action-item {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) 0;
  border-top: 1px solid var(--border);
  font-size: var(--fs-sm);
}

.action-item:first-child {
  border-top: none;
  padding-top: 0;
}

.action-text {
  flex: 1 1 220px;
}

.skeleton-row {
  height: 120px;
}
</style>
