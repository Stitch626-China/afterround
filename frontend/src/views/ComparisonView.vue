<script setup lang="ts">
/**
 * 简历版本对比（F7）：手动选择两份已完成分析的简历，同步返回。
 * 分数、五维、优势短板与方向差异由后端本地计算；LLM 只生成一段点评，失败会降级。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  compareResumes,
  listResumes,
  type ResumeComparison,
  type ResumeListItem,
} from '@/api/resumes'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
const router = useRouter()

const resumes = ref<ResumeListItem[]>([])
const loading = ref(true)
const comparing = ref(false)
const error = ref('')
const baseId = ref<number | null>(null)
const targetId = ref<number | null>(null)
const result = ref<ResumeComparison | null>(null)

const completed = computed(() => resumes.value.filter((item) => item.analyzeStatus === 'COMPLETED'))
const canCompare = computed(
  () => baseId.value != null && targetId.value != null && baseId.value !== targetId.value,
)
const maxDelta = computed(() =>
  result.value
    ? Math.max(1, ...result.value.dimensionDeltas.map((item) => Math.abs(item.delta)))
    : 1,
)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await listResumes(1, 100)
    resumes.value = page.items
    const queryBase = Number(route.query.base)
    const queryTarget = Number(route.query.target)
    const ids = completed.value.map((item) => item.id)
    baseId.value = ids.includes(queryBase) ? queryBase : ids[0] ?? null
    targetId.value = ids.includes(queryTarget)
      ? queryTarget
      : ids.find((id) => id !== baseId.value) ?? null
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '简历加载失败'
  } finally {
    loading.value = false
  }
}

async function run(): Promise<void> {
  if (!canCompare.value || baseId.value == null || targetId.value == null) {
    return
  }
  comparing.value = true
  error.value = ''
  try {
    result.value = await compareResumes(baseId.value, targetId.value)
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '对比失败'
    result.value = null
  } finally {
    comparing.value = false
  }
}

function label(item: ResumeListItem): string {
  return `${item.fileName || `简历 #${item.id}`}${item.overallScore != null ? ` · ${item.overallScore} 分` : ''}`
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function signed(value: number): string {
  return value > 0 ? `+${value}` : `${value}`
}

function deltaTone(value: number): string {
  if (value > 0) return 'up'
  if (value < 0) return 'down'
  return 'flat'
}

function back(): void {
  void router.push('/resumes')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <button type="button" class="back" @click="back">← 返回简历中心</button>

    <header>
      <h2>简历版本对比</h2>
      <p class="lead">
        选择两份已完成分析的简历，查看分数、五维、优势短板与岗位方向的变化；点评由模型生成，失败时只展示本地对比。
      </p>
    </header>

    <div v-if="error" class="banner danger">{{ error }}</div>

    <section v-if="loading" class="skeleton" />

    <EmptyState
      v-else-if="completed.length < 2"
      title="至少需要两份已完成分析的简历"
      hint="回到简历中心上传并等待分析完成，再回来对比。"
      action-label="返回简历中心"
      @action="back"
    />

    <template v-else>
      <section class="picker">
        <label>
          <span>基准简历（较早版本）</span>
          <select v-model="baseId" @change="result = null">
            <option v-for="item in completed" :key="item.id" :value="item.id">{{ label(item) }}</option>
          </select>
        </label>
        <label>
          <span>对比简历（较新版本）</span>
          <select v-model="targetId" @change="result = null">
            <option v-for="item in completed" :key="item.id" :value="item.id">{{ label(item) }}</option>
          </select>
        </label>
        <button type="button" class="btn primary" :disabled="!canCompare || comparing" @click="run">
          {{ comparing ? '对比中…' : '开始对比' }}
        </button>
      </section>
      <p v-if="baseId != null && baseId === targetId" class="hint danger-text">
        基准简历与对比简历不能相同。
      </p>

      <template v-if="result">
        <div v-if="result.summary.promptVersionMismatch || result.summary.modelMismatch" class="banner warning">
          <span v-if="result.summary.modelMismatch">两份分析使用的模型不同；</span>
          <span v-if="result.summary.promptVersionMismatch">Prompt 版本不同（含一方未记录）；</span>
          结果仅供参考。
        </div>

        <section class="sides">
          <article class="side-card">
            <span class="side-tag">基准</span>
            <strong>{{ result.base.fileName || `简历 #${result.base.resumeId}` }}</strong>
            <span class="side-score numeric">{{ result.base.overallScore }}</span>
            <p class="side-meta">{{ formatTime(result.base.analyzedAt) }}</p>
            <p class="side-meta">
              {{ result.base.llmProvider }} · {{ result.base.llmModel }}
              <template v-if="result.base.promptVersion"> · {{ result.base.promptVersion }}</template>
            </p>
          </article>
          <article class="side-card">
            <span class="side-tag target">对比</span>
            <strong>{{ result.target.fileName || `简历 #${result.target.resumeId}` }}</strong>
            <span class="side-score numeric">{{ result.target.overallScore }}</span>
            <p class="side-meta">{{ formatTime(result.target.analyzedAt) }}</p>
            <p class="side-meta">
              {{ result.target.llmProvider }} · {{ result.target.llmModel }}
              <template v-if="result.target.promptVersion"> · {{ result.target.promptVersion }}</template>
            </p>
          </article>
          <div class="delta-card">
            <span class="delta-label">总分变化</span>
            <span class="delta-value numeric" :class="deltaTone(result.overallScoreDelta)">
              {{ signed(result.overallScoreDelta) }}
            </span>
          </div>
        </section>

        <section class="card">
          <h3>五维变化</h3>
          <p class="hint">浅色为基准，深色为对比；右侧为差值。</p>
          <div class="dims">
            <div v-for="item in result.dimensionDeltas" :key="item.key" class="dim">
              <div class="dim-head">
                <span>{{ item.label }}</span>
                <span class="dim-values numeric">
                  {{ item.baseScore }} → {{ item.targetScore }}
                  <em :class="deltaTone(item.delta)">{{ signed(item.delta) }}</em>
                </span>
              </div>
              <div class="track">
                <div class="fill base" :style="{ width: `${item.baseScore}%` }" />
              </div>
              <div class="track">
                <div class="fill target" :style="{ width: `${item.targetScore}%` }" />
              </div>
            </div>
          </div>
        </section>

        <section class="changes">
          <article class="card">
            <h3>优势变化</h3>
            <h4 class="up">新增</h4>
            <ul v-if="result.strengthsAdded?.length" class="plain">
              <li v-for="item in result.strengthsAdded ?? []" :key="item.quote">
                {{ item.quote }}<span v-if="item.comment"> · {{ item.comment }}</span>
              </li>
            </ul>
            <p v-else class="hint">没有新增优势</p>
            <h4 class="down">不再具备</h4>
            <ul v-if="result.strengthsRemoved?.length" class="plain">
              <li v-for="item in result.strengthsRemoved ?? []" :key="item.quote">{{ item.quote }}</li>
            </ul>
            <p v-else class="hint">没有减少的优势</p>
          </article>

          <article class="card">
            <h3>短板变化</h3>
            <h4 class="down">新增短板</h4>
            <ul v-if="result.weaknessesAdded?.length" class="plain">
              <li v-for="item in result.weaknessesAdded ?? []" :key="item.quote">
                {{ item.quote }}<span v-if="item.comment"> · {{ item.comment }}</span>
              </li>
            </ul>
            <p v-else class="hint">没有新增短板</p>
            <h4 class="up">已消除</h4>
            <ul v-if="result.weaknessesRemoved?.length" class="plain">
              <li v-for="item in result.weaknessesRemoved ?? []" :key="item.quote">{{ item.quote }}</li>
            </ul>
            <p v-else class="hint">没有消除的短板</p>
          </article>

          <article class="card">
            <h3>岗位方向变化</h3>
            <h4 class="up">新增方向</h4>
            <p v-if="result.directionsAdded?.length" class="chips">
              <span v-for="direction in result.directionsAdded ?? []" :key="direction" class="chip">{{ direction }}</span>
            </p>
            <p v-else class="hint">没有新增方向</p>
            <h4 class="down">不再推荐</h4>
            <p v-if="result.directionsRemoved?.length" class="chips">
              <span v-for="direction in result.directionsRemoved ?? []" :key="direction" class="chip muted">{{ direction }}</span>
            </p>
            <p v-else class="hint">没有移除的方向</p>
          </article>
        </section>

        <section class="card summary">
          <h3>改动点评</h3>
          <p v-if="result.summary.degraded" class="banner warning">
            模型点评生成失败{{ result.summary.degradeReason ? `：${result.summary.degradeReason}` : '' }}；本地对比结果仍然有效。
          </p>
          <p v-else-if="result.summary.content" class="summary-text">{{ result.summary.content }}</p>
          <p v-else class="hint">本次没有生成点评。</p>
        </section>

        <p class="max-delta-hint">五维最大变化幅度：{{ maxDelta }} 分</p>
      </template>
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

h2 {
  font-size: 18px;
}

.lead {
  margin: 4px 0 0;
  color: var(--text-2);
  font-size: 13.5px;
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

.banner.warning {
  margin: 0;
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px solid var(--warning);
}

.skeleton {
  height: 140px;
  border-radius: var(--radius-card);
  border: 1px solid var(--border);
  background: var(--surface);
}

.picker {
  display: flex;
  align-items: flex-end;
  gap: var(--space-3);
  padding: var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.picker label {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--text-3);
}

.picker select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 12.5px;
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

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.hint {
  margin: 4px 0 var(--space-2);
  font-size: 12px;
  color: var(--text-3);
}

.hint.danger-text {
  color: var(--danger);
}

.sides {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
}

.side-card,
.delta-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.side-tag {
  align-self: flex-start;
  padding: 0 6px;
  border-radius: var(--radius-tag);
  background: var(--surface-muted);
  color: var(--text-3);
  font-size: 11px;
}

.side-tag.target {
  background: var(--primary-soft);
  color: var(--primary);
}

.side-score {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.1;
}

.side-meta {
  margin: 0;
  font-size: 11.5px;
  color: var(--text-3);
}

.delta-card {
  justify-content: center;
  align-items: flex-start;
}

.delta-label {
  font-size: 12px;
  color: var(--text-3);
}

.delta-value {
  font-size: 34px;
  font-weight: 600;
  line-height: 1.1;
}

.up {
  color: var(--success);
}

.down {
  color: var(--danger);
}

.flat {
  color: var(--text-3);
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

.card h4 {
  margin: var(--space-3) 0 var(--space-1);
  font-size: 12.5px;
}

.dims {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.dim-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 12.5px;
  color: var(--text-2);
}

.dim-values em {
  margin-left: var(--space-2);
  font-style: normal;
  font-weight: 600;
}

.track {
  height: 5px;
  margin-top: 4px;
  border-radius: 3px;
  background: var(--surface-muted);
  border: 1px solid var(--border);
  overflow: hidden;
}

.fill {
  height: 100%;
  border-radius: 2px;
}

.fill.base {
  background: var(--border-strong);
}

.fill.target {
  background: var(--primary);
}

.changes {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
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

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: 0;
}

.chip {
  padding: 1px 8px;
  border-radius: var(--radius-tag);
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 12px;
}

.chip.muted {
  background: var(--surface-muted);
  color: var(--text-3);
}

.summary-text {
  margin: var(--space-2) 0 0;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--text-2);
  white-space: pre-wrap;
}

.max-delta-hint {
  margin: 0;
  font-size: 11.5px;
  color: var(--text-3);
}

@media (max-width: 960px) {
  .sides,
  .changes {
    grid-template-columns: 1fr;
  }

  .picker {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
