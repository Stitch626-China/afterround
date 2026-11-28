<script setup lang="ts">
/**
 * 基线对比：run vs run 的质量 / 回归 / 成本 / 耗时四维对比。
 * 基线只是被贴标签的运行；不可比时只展示原因，不伪造指标。
 */
import { computed, onMounted, ref } from 'vue'
import {
  activateEvalBaseline,
  compareEvalRuns,
  listEvalBaselines,
  listEvalRuns,
  listEvalScenes,
  type EvalBaselineSummary,
  type EvalRunCompare,
  type EvalRunSummary,
  type EvalSceneSummary,
} from '@/api/eval'
import { shortTime } from '@/utils/prompt'
import {
  caseStatusLabel,
  changeLabel,
  formatDuration,
  formatRate,
  formatTokens,
  runStatusLabel,
} from '@/utils/eval'

const scenes = ref<EvalSceneSummary[]>([])
const runs = ref<EvalRunSummary[]>([])
const baselines = ref<EvalBaselineSummary[]>([])
const baselineTotal = ref(0)
const baselinePage = ref(1)
const leftId = ref<number | null>(null)
const rightId = ref<number | null>(null)
const compare = ref<EvalRunCompare | null>(null)
const filter = ref('')
const loading = ref(true)
const busy = ref(false)
const activating = ref<number | null>(null)
const error = ref('')
const notice = ref('')

const filters = [
  { value: '', label: '全部' },
  { value: 'REGRESSED', label: '新增失败' },
  { value: 'FIXED', label: '修复' },
  { value: 'SCORE_DRIFT', label: '分数漂移' },
  { value: 'TAG_CHANGED', label: '标签变化' },
]

const dimensions = computed(() => new Set(compare.value?.applicableDimensions ?? []))
const scoreApplicable = computed(() => dimensions.value.has('SCORE_DRIFT'))
const tagApplicable = computed(() => dimensions.value.has('TAG_CHANGE'))
const filteredCases = computed(() => {
  const items = compare.value?.cases ?? []
  return filter.value ? items.filter((item) => item.changes.some((change) => change === filter.value)) : items
})
const runOptions = computed(() => {
  const map = new Map<number, string>()
  baselines.value.forEach((item) => {
    map.set(item.runId, `#${item.runId} · 基线「${item.label || item.promptVersion}」`)
  })
  runs.value.forEach((item) => {
    const info = `#${item.id} · ${item.scenes.map(sceneName).join(' / ')} · ${runStatusLabel(item.status)}${item.partial ? ' · 部分' : ''}`
    map.set(item.id, map.get(item.id) ?? info)
  })
  return [...map.entries()].sort((a, b) => b[0] - a[0]).map(([id, label]) => ({ id, label }))
})

function sceneName(key: string): string {
  return scenes.value.find((item) => item.scene === key)?.name ?? key
}

function sideLabel(id: number | null): string {
  if (id == null) {
    return '未选择'
  }
  const baseline = baselines.value.find((item) => item.runId === id)
  return baseline ? `#${id}（基线「${baseline.label || baseline.promptVersion}」）` : `#${id}`
}

function rateDelta(value: number | null | undefined): string {
  return value == null ? '—' : `${value > 0 ? '+' : ''}${(value * 100).toFixed(1)}pp`
}

function tokenDelta(value: number | null | undefined): string {
  return value == null ? '—' : `${value > 0 ? '+' : ''}${value.toLocaleString('zh-CN')}`
}

function msDelta(value: number | null | undefined): string {
  return value == null ? '—' : `${value > 0 ? '+' : ''}${formatDuration(value)}`
}

function changeClass(change: string): string {
  if (change === 'FIXED') {
    return 'success'
  }
  if (change === 'REGRESSED') {
    return 'danger'
  }
  return change === 'UNCHANGED' ? '' : 'warning'
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [sceneList, runPage, baselineList] = await Promise.all([
      listEvalScenes(),
      listEvalRuns(1, 50),
      listEvalBaselines(undefined, 1, 20),
    ])
    scenes.value = sceneList
    runs.value = runPage.items
    baselines.value = baselineList.items
    baselineTotal.value = baselineList.total
    baselinePage.value = baselineList.page
    const current = baselineList.items.find((item) => item.current)
    if (current) {
      leftId.value = current.runId
    }
    const latest = runPage.items.find((item) => item.status === 'COMPLETED'
      && !item.partial && item.id !== leftId.value)
    rightId.value = latest?.id ?? null
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '基线数据加载失败'
  } finally {
    loading.value = false
  }
}

async function runCompare(): Promise<void> {
  if (leftId.value == null || rightId.value == null) {
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    compare.value = await compareEvalRuns(leftId.value, rightId.value)
    filter.value = ''
  } catch (ex) {
    compare.value = null
    error.value = ex instanceof Error ? ex.message : '运行对比失败'
  } finally {
    busy.value = false
  }
}

/** 只切换当前基线标记，不重跑、不改历史。 */
async function activate(item: EvalBaselineSummary): Promise<void> {
  activating.value = item.id
  error.value = ''
  try {
    await activateEvalBaseline(item.id)
    const page = await listEvalBaselines(undefined, 1, 20)
    baselines.value = page.items
    baselineTotal.value = page.total
    baselinePage.value = page.page
    scenes.value = await listEvalScenes()
    notice.value = `已将「${item.label || item.promptVersion}」设为当前基线`
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '切换基线失败'
  } finally {
    activating.value = null
  }
}

/** 基线历史按创建时间累计，超出首屏时按页追加。 */
async function loadMoreBaselines(): Promise<void> {
  try {
    const page = await listEvalBaselines(undefined, baselinePage.value + 1, 20)
    baselines.value = [...baselines.value, ...page.items]
    baselineTotal.value = page.total
    baselinePage.value = page.page
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '基线加载失败'
  }
}

onMounted(load)
</script>

<template>
  <section class="compare-panel">
    <p class="lead">
      对比两次已完成运行的质量、回归、成本与耗时。基线只是被「设为基线」的那次运行，
      不会重跑；提示词是被测变量，不参与可比性判定。
    </p>

    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-else-if="notice" class="banner success">{{ notice }}</div>

    <div v-if="loading" class="skeleton">
      <div class="skeleton-row" />
      <div class="skeleton-row" />
    </div>

    <template v-else>
      <section class="block">
        <div class="block-head">
          <div>
            <h3>选择对比</h3>
            <p class="meta">左侧通常是上次基线，右侧是本次运行；也可以任选两次运行。</p>
          </div>
        </div>

        <div class="pick-row">
          <label>
            <span>基线 / 左侧运行</span>
            <select v-model.number="leftId" aria-label="左侧运行">
              <option :value="null">请选择</option>
              <option v-for="item in runOptions" :key="`l-${item.id}`" :value="item.id">{{ item.label }}</option>
            </select>
          </label>
          <label>
            <span>本次 / 右侧运行</span>
            <select v-model.number="rightId" aria-label="右侧运行">
              <option :value="null">请选择</option>
              <option v-for="item in runOptions" :key="`r-${item.id}`" :value="item.id">{{ item.label }}</option>
            </select>
          </label>
          <button
            type="button"
            class="btn primary"
            :disabled="busy || leftId == null || rightId == null"
            @click="runCompare"
          >
            开始对比
          </button>
        </div>
        <p v-if="!baselines.length" class="meta">
          尚无基线：先到「评测运行」完成一次运行，再点「设为基线」；也可以直接对比任意两次运行。
        </p>
      </section>

      <section v-if="compare" class="block">
        <div v-if="!compare.comparable" class="banner danger">
          <span>不可比：{{ compare.reasons.join('；') }}</span>
        </div>
        <template v-else>
          <div class="compare-line">
            <span>左 {{ sideLabel(compare.left.runId) }} · {{ compare.left.model }} · {{ compare.left.promptVersion }}</span>
            <span>右 {{ sideLabel(compare.right.runId) }} · {{ compare.right.model }} · {{ compare.right.promptVersion }}</span>
          </div>
          <div v-if="compare.warnings.length" class="banner warning">{{ compare.warnings.join('；') }}</div>

          <div class="metric-grid">
            <article class="metric">
              <span class="metric-label">质量</span>
              <strong>{{ formatRate(compare.left.metrics.l1PassRate) }} → {{ formatRate(compare.right.metrics.l1PassRate) }}</strong>
              <span class="meta">
                L1 {{ rateDelta(compare.quality?.l1Delta) }} · L2 {{ rateDelta(compare.quality?.l2Delta) }}
              </span>
            </article>
            <article class="metric">
              <span class="metric-label">回归</span>
              <strong>修复 {{ compare.regression?.fixedCases ?? 0 }}</strong>
              <span class="meta">
                新增失败 {{ compare.regression?.newFailedCases ?? 0 }} ·
                {{ scoreApplicable ? `分数漂移 ${compare.regression?.scoreDriftCases ?? 0}` : '分数漂移不适用' }} ·
                {{ tagApplicable ? `标签变化 ${compare.regression?.tagChangedCases ?? 0}` : '标签变化不适用' }}
              </span>
            </article>
            <article class="metric">
              <span class="metric-label">成本</span>
              <strong>{{ formatTokens(compare.cost?.leftTokens) }} → {{ formatTokens(compare.cost?.rightTokens) }}</strong>
              <span class="meta">Δ {{ tokenDelta(compare.cost?.delta) }} tokens · 平均 {{ formatTokens(compare.cost?.leftAvgTokens) }} → {{ formatTokens(compare.cost?.rightAvgTokens) }}</span>
            </article>
            <article class="metric">
              <span class="metric-label">耗时</span>
              <strong>{{ formatDuration(compare.latency?.leftAvgMs) }} → {{ formatDuration(compare.latency?.rightAvgMs) }}</strong>
              <span class="meta">
                平均 Δ {{ msDelta(compare.latency?.avgDelta) }} · P95 {{ formatDuration(compare.latency?.leftP95Ms) }} → {{ formatDuration(compare.latency?.rightP95Ms) }}（Δ {{ msDelta(compare.latency?.p95Delta) }}）
              </span>
            </article>
          </div>

          <div class="case-head">
            <h4>用例变化</h4>
            <label class="filter">
              筛选
              <select v-model="filter" aria-label="用例筛选">
                <option v-for="item in filters" :key="item.value" :value="item.value">{{ item.label }}</option>
              </select>
            </label>
          </div>
          <p v-if="!filteredCases.length" class="meta">当前筛选没有用例。</p>
          <table v-else class="table">
            <thead>
              <tr>
                <th>用例</th>
                <th>左</th>
                <th>右</th>
                <th>变化</th>
                <th>分数差</th>
                <th>标签 Jaccard</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in filteredCases" :key="item.caseId">
                <td class="case-id">{{ item.caseId }}</td>
                <td>{{ caseStatusLabel(item.leftStatus) }}</td>
                <td>{{ caseStatusLabel(item.rightStatus) }}</td>
                <td>
                  <span v-for="change in item.changes" :key="change" class="status" :class="changeClass(change)">
                    {{ changeLabel(change) }}
                  </span>
                </td>
                <td>{{ item.scoreDrift == null ? '—' : item.scoreDrift.toFixed(1) }}</td>
                <td>{{ item.tagJaccard == null ? '—' : item.tagJaccard.toFixed(3) }}</td>
                <td class="case-detail">{{ item.details ?? '' }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </section>

      <section class="block">
        <div class="block-head">
          <h3>基线列表</h3>
          <span class="meta">设为当前只切换标记，不会重跑</span>
        </div>
        <p v-if="!baselines.length" class="meta">还没有基线。完成运行后在「评测运行」里设为基线。</p>
        <table v-else class="table">
          <thead>
            <tr>
              <th>场景</th>
              <th>标签</th>
              <th>运行</th>
              <th>提示词</th>
              <th>创建时间</th>
              <th>状态</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in baselines" :key="item.id">
              <td>{{ sceneName(item.scene) }}</td>
              <td>{{ item.label || '—' }}</td>
              <td>#{{ item.runId }}</td>
              <td>{{ item.promptVersion }}</td>
              <td>{{ shortTime(item.createdAt) }}</td>
              <td>
                <span class="status" :class="item.current ? 'success' : ''">{{ item.current ? '当前基线' : '历史' }}</span>
              </td>
              <td>
                <button
                  type="button"
                  class="btn quiet"
                  :disabled="item.current || activating != null"
                  @click="activate(item)"
                >
                  设为当前
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <button
          v-if="baselines.length < baselineTotal"
          type="button"
          class="btn quiet"
          @click="loadMoreBaselines"
        >
          加载更多（{{ baselines.length }} / {{ baselineTotal }}）
        </button>
      </section>
    </template>
  </section>
</template>

<style scoped>
.compare-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.block {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.block-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.block-head h3 {
  margin: 0;
  font-size: var(--fs-h2);
  font-weight: 500;
}

.block-head .meta {
  margin: 3px 0 0;
}

.pick-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--space-3);
}

.pick-row label {
  display: flex;
  flex: 1 1 260px;
  flex-direction: column;
  gap: 4px;
  color: var(--text-3);
  font-size: var(--fs-xs);
}

.pick-row select,
.filter select {
  height: 32px;
  padding: 0 var(--space-2);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: var(--fs-sm);
}

.compare-line {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-4);
  color: var(--text-3);
  font-size: var(--fs-xs);
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-3);
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
}

.metric-label {
  color: var(--text-3);
  font-size: var(--fs-xs);
}

.metric strong {
  font-size: 18px;
  font-weight: 600;
}

.case-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.case-head h4 {
  margin: 0;
  font-size: var(--fs-sm);
  font-weight: 500;
}

.filter {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-3);
  font-size: var(--fs-xs);
}

td .status {
  margin-right: 4px;
}

.case-id {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.case-detail {
  max-width: 360px;
  color: var(--text-3);
  font-size: var(--fs-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 900px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }
}
</style>
