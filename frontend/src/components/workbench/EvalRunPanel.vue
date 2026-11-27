<script setup lang="ts">
/**
 * 评测运行面板：3 个场景、手动开始、进度轮询、取消与逐用例结果。
 * 评测始终使用当前生效提示词与模型快照；配置变更不会自动触发运行。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  cancelEvalRun,
  createEvalBaseline,
  createEvalRun,
  getEvalRun,
  listEvalRuns,
  listEvalScenes,
  type EvalRunDetail,
  type EvalRunSummary,
  type EvalSceneSummary,
} from '@/api/eval'
import { shortTime } from '@/utils/prompt'
import {
  caseStatusLabel,
  failureCounts,
  formatDuration,
  formatRate,
  formatTokens,
  isTerminal,
  progressPercent,
  runStatusLabel,
} from '@/utils/eval'

const props = withDefaults(defineProps<{ initialScenes?: string[] }>(), { initialScenes: () => [] })
const emit = defineEmits<{ (e: 'go-models'): void }>()

const scenes = ref<EvalSceneSummary[]>([])
const runs = ref<EvalRunSummary[]>([])
const selected = ref<string[]>([])
const repeat = ref<1 | 2>(1)
const detail = ref<EvalRunDetail | null>(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const startOpen = ref(false)
const cancelOpen = ref(false)
const baselineOpen = ref(false)
const baselineLabel = ref('')
let timer: number | undefined

const activeRun = computed(() => runs.value.find((run) => !isTerminal(run.status)) ?? null)
const selectedScenes = computed(() => scenes.value.filter((scene) => selected.value.includes(scene.scene)))
const totalSelectedCases = computed(() => selectedScenes.value.reduce((sum, scene) => sum + scene.caseCount, 0))
const canStart = computed(() => selected.value.length > 0 && !busy.value && !activeRun.value)
const canBaseline = computed(() => detail.value?.status === 'COMPLETED'
  && !detail.value.partial && detail.value.errorCases === 0)
const canRetry = computed(() => detail.value?.status === 'COMPLETED'
  && detail.value.errorCases > 0 && !activeRun.value)
const metrics = computed(() => detail.value?.metrics ?? null)
const counts = computed(() => failureCounts(detail.value?.cases ?? []))
const progress = computed(() => (detail.value ? progressPercent(detail.value) : 0))

/** 只预选传入的受影响场景，仍由用户手动点开始。 */
function applyInitialScenes(keys: string[]): void {
  if (!keys.length) {
    return
  }
  selected.value = scenes.value
    .filter((scene) => scene.model && keys.includes(scene.scene))
    .map((scene) => scene.scene)
}

function toggle(scene: EvalSceneSummary): void {
  if (!scene.model) {
    return
  }
  selected.value = selected.value.includes(scene.scene)
    ? selected.value.filter((key) => key !== scene.scene)
    : [...selected.value, scene.scene]
}

function selectAll(): void {
  selected.value = scenes.value.filter((scene) => scene.model).map((scene) => scene.scene)
}

function toSummary(run: EvalRunDetail): EvalRunSummary {
  return {
    id: run.id,
    status: run.status,
    scenes: run.scenes,
    totalCases: run.totalCases,
    completedCases: run.completedCases,
    errorCases: run.errorCases,
    partial: run.partial,
    error: run.error,
    createdAt: run.createdAt,
    startedAt: run.startedAt,
    finishedAt: run.finishedAt,
  }
}

function mergeRun(run: EvalRunDetail): void {
  const summary = toSummary(run)
  const index = runs.value.findIndex((item) => item.id === run.id)
  if (index >= 0) {
    runs.value[index] = summary
  } else {
    runs.value = [summary, ...runs.value]
  }
}

async function refreshDetail(): Promise<void> {
  if (!detail.value) {
    return
  }
  try {
    detail.value = await getEvalRun(detail.value.id)
    mergeRun(detail.value)
    if (isTerminal(detail.value.status)) {
      stopPolling()
      notice.value = `运行 #${detail.value.id} ${runStatusLabel(detail.value.status)}`
      scenes.value = await listEvalScenes()
    }
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '评测进度刷新失败'
    stopPolling()
  }
}

function startPolling(): void {
  stopPolling()
  if (detail.value && !isTerminal(detail.value.status)) {
    timer = window.setInterval(() => void refreshDetail(), 3000)
  }
}

function stopPolling(): void {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [sceneList, runPage] = await Promise.all([listEvalScenes(), listEvalRuns()])
    scenes.value = sceneList
    runs.value = runPage.items
    applyInitialScenes(props.initialScenes)
    const active = runPage.items.find((run) => !isTerminal(run.status))
    if (active) {
      detail.value = await getEvalRun(active.id)
      startPolling()
    }
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '评测数据加载失败'
  } finally {
    loading.value = false
  }
}

async function openRun(runId: number): Promise<void> {
  error.value = ''
  try {
    detail.value = await getEvalRun(runId)
    startPolling()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '运行详情加载失败'
  }
}

async function confirmStart(): Promise<void> {
  startOpen.value = false
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await createEvalRun({
      scenes: selected.value as EvalSceneSummary['scene'][],
      repeat: repeat.value,
      retryErrorsOnly: false,
    })
    detail.value = created
    mergeRun(created)
    notice.value = '评测已开始，使用启动时的模型与提示词快照'
    startPolling()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '评测启动失败'
  } finally {
    busy.value = false
  }
}

async function confirmCancel(): Promise<void> {
  cancelOpen.value = false
  if (!detail.value) {
    return
  }
  busy.value = true
  try {
    detail.value = await cancelEvalRun(detail.value.id)
    mergeRun(detail.value)
    if (isTerminal(detail.value.status)) {
      stopPolling()
    }
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '取消评测失败'
  } finally {
    busy.value = false
  }
}

/** 设为基线：只贴标签并复用运行结果，不重跑。 */
async function confirmBaseline(): Promise<void> {
  baselineOpen.value = false
  if (!detail.value) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    const results = await createEvalBaseline({
      runId: detail.value.id,
      label: baselineLabel.value.trim() || null,
    })
    const created = results.filter((item) => item.created)
    detail.value = { ...detail.value, baselines: results }
    notice.value = created.length
      ? `已设为基线：${created.map((item) => item.scene).join(' / ')}（复用本次结果，未重跑）`
      : results.map((item) => item.reason).filter(Boolean).join('；') || '没有可设为基线的场景'
    scenes.value = await listEvalScenes()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '设为基线失败'
  } finally {
    busy.value = false
  }
}

/** 部分重跑：只重跑当前运行的执行错误用例，新运行不参与完整对比。 */
async function retryErrors(): Promise<void> {
  if (!detail.value) {
    return
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await createEvalRun({
      sourceRunId: detail.value.id,
      retryErrorsOnly: true,
      repeat: 1,
    })
    detail.value = created
    mergeRun(created)
    notice.value = `只重跑 ${created.totalCases} 个执行错误用例；结果标记为部分重跑`
    startPolling()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '重跑失败用例失败'
  } finally {
    busy.value = false
  }
}

watch(() => props.initialScenes, (keys) => applyInitialScenes(keys))
onMounted(load)
onBeforeUnmount(stopPolling)
</script>

<template>
  <section class="eval-panel">
    <p class="lead">
      评测按当前生效提示词与模型跑一遍语料并打分，供你判断改动是否变好。
      运行必须点「开始运行」才会发生；发布提示词或改模型都不会自动触发。
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
            <h3>选择场景</h3>
            <p class="meta">全量 = 3 个场景 27 例；未配置模型的场景不可勾选。</p>
          </div>
          <button type="button" class="btn ghost" @click="selectAll">全量 27 例</button>
        </div>

        <div class="scene-grid">
          <article
            v-for="scene in scenes"
            :key="scene.scene"
            class="scene-card"
            :class="{ disabled: !scene.model, selected: selected.includes(scene.scene) }"
          >
            <label class="scene-check">
              <input
                type="checkbox"
                :checked="selected.includes(scene.scene)"
                :disabled="!scene.model"
                :aria-label="`选择${scene.name}`"
                @change="toggle(scene)"
              />
              <span class="scene-name">{{ scene.name }}</span>
              <span class="tag">{{ scene.caseCount }} 例</span>
            </label>
            <dl class="scene-meta">
              <div><dt>提示词</dt><dd>{{ scene.promptVersion }}</dd></div>
              <div>
                <dt>模型</dt>
                <dd v-if="scene.model">{{ scene.providerKey }} · {{ scene.model }}</dd>
                <dd v-else class="missing">未配置模型</dd>
              </div>
              <div>
                <dt>上次运行</dt>
                <dd>{{ scene.lastRun ? `${runStatusLabel(scene.lastRun.status)} · ${shortTime(scene.lastRun.createdAt)}` : '暂无' }}</dd>
              </div>
              <div><dt>当前基线</dt><dd>{{ scene.currentBaseline ? scene.currentBaseline.label || `#${scene.currentBaseline.id}` : '尚无基线' }}</dd></div>
            </dl>
            <p v-if="!scene.model" class="scene-action">
              <button type="button" class="link" @click="emit('go-models')">去配置</button>
            </p>
            <p v-else-if="scene.rebuildReasons.length" class="scene-reasons">
              {{ scene.rebuildReasons.join('；') }}
            </p>
          </article>
        </div>

        <div class="start-bar">
          <div class="start-info">
            <strong>{{ selectedScenes.length }} 个场景 · {{ totalSelectedCases }} 例</strong>
            <label class="repeat">
              重复
              <select v-model.number="repeat" aria-label="重复次数">
                <option :value="1">1 次</option>
                <option :value="2">2 次（稳定性）</option>
              </select>
            </label>
            <span class="meta">评测使用当前生效版本，草稿不参与；运行中改配置不影响本次。</span>
          </div>
          <button type="button" class="btn primary" :disabled="!canStart" @click="startOpen = true">
            {{ activeRun ? '已有运行进行中' : '开始运行' }}
          </button>
        </div>
      </section>

      <section v-if="detail" class="block detail">
        <div class="block-head">
          <div>
            <h3>
              运行 #{{ detail.id }}
              <span class="status" :class="isTerminal(detail.status) ? 'success' : 'primary'">{{ runStatusLabel(detail.status) }}</span>
              <span v-if="detail.partial" class="tag warning">部分重跑</span>
            </h3>
            <p class="meta">
              创建 {{ shortTime(detail.createdAt) }} · 开始 {{ shortTime(detail.startedAt) }} · 结束 {{ shortTime(detail.finishedAt) }}
            </p>
          </div>
          <div class="detail-actions">
            <button
              v-if="!isTerminal(detail.status)"
              type="button"
              class="btn danger"
              :disabled="busy"
              @click="cancelOpen = true"
            >
              取消运行
            </button>
            <button
              v-if="detail.status === 'COMPLETED' && detail.errorCases > 0"
              type="button"
              class="btn ghost"
              :disabled="busy || !canRetry"
              title="只重跑执行错误（ERROR）用例；结果标记为部分重跑"
              @click="retryErrors"
            >
              重跑失败用例
            </button>
            <button
              type="button"
              class="btn ghost"
              :disabled="busy || !canBaseline"
              :title="canBaseline ? '只贴标签，复用本次运行结果，不重跑' : '仅完成且无执行错误的运行可设为基线'"
              @click="baselineOpen = true"
            >
              设为基线
            </button>
          </div>
        </div>

        <div v-if="detail.error" class="banner danger">{{ detail.error }}</div>

        <div class="progress-line">
          <div class="progress-track">
            <div class="progress-value" :style="{ width: `${progress}%` }" />
          </div>
          <span class="meta">{{ detail.completedCases }} / {{ detail.totalCases }} 例 · {{ progress }}%</span>
        </div>

        <div class="metric-grid">
          <article class="metric">
            <span class="metric-label">L1 通过率</span>
            <strong>{{ formatRate(metrics?.l1PassRate) }}</strong>
            <span class="meta">{{ metrics?.l1Failed ?? '—' }} / {{ metrics?.l1Total ?? '—' }} 例失败</span>
          </article>
          <article class="metric">
            <span class="metric-label">L2 通过率</span>
            <strong>{{ formatRate(metrics?.l2PassRate) }}</strong>
            <span class="meta">{{ metrics?.l2Failed ?? '—' }} / {{ metrics?.l2Total ?? '—' }} 条断言失败</span>
          </article>
          <article class="metric">
            <span class="metric-label">成本</span>
            <strong>{{ formatTokens(metrics?.totalTokens) }}</strong>
            <span class="meta">平均 {{ formatTokens(metrics?.avgTokens) }} tokens</span>
          </article>
          <article class="metric">
            <span class="metric-label">耗时</span>
            <strong>{{ formatDuration(metrics?.avgElapsedMs) }}</strong>
            <span class="meta">P95 {{ formatDuration(metrics?.p95ElapsedMs) }}</span>
          </article>
        </div>

        <div class="result-counts">
          <span class="status danger">质量失败 {{ counts.failed }}</span>
          <span class="status warning">执行错误 {{ counts.errors }}</span>
          <span class="status success">通过 {{ detail.cases.filter((item) => item.status === 'PASS').length }}</span>
        </div>

        <div class="snapshot-grid">
          <section>
            <h4>模型快照</h4>
            <ul class="snapshot-list">
              <li v-for="item in detail.models" :key="`m-${item.scene}`">
                <span>{{ item.scene }}</span>
                <span class="meta">{{ item.providerKey }} · {{ item.model }}</span>
              </li>
            </ul>
          </section>
          <section>
            <h4>提示词快照</h4>
            <ul class="snapshot-list">
              <li v-for="item in detail.prompts" :key="`p-${item.scene}`">
                <span>{{ item.scene }}</span>
                <span class="meta">{{ item.promptKey }} · {{ item.promptVersion }}</span>
              </li>
            </ul>
          </section>
        </div>

        <div class="cases">
          <table class="table">
            <thead>
              <tr>
                <th>场景</th>
                <th>用例</th>
                <th>状态</th>
                <th>L1</th>
                <th>L2</th>
                <th>tokens</th>
                <th>耗时</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in detail.cases" :key="`${item.scene}/${item.caseId}`">
                <td>{{ item.scene }}</td>
                <td class="case-id">{{ item.caseId }}</td>
                <td>
                  <span class="status" :class="item.status === 'PASS' ? 'success' : item.status === 'ERROR' ? 'warning' : item.status === 'FAIL' ? 'danger' : ''">
                    {{ caseStatusLabel(item.status) }}
                  </span>
                </td>
                <td>{{ item.l1Passed == null ? '—' : item.l1Passed ? '通' : '败' }}</td>
                <td>{{ item.l2Passed == null ? '—' : item.l2Passed ? '通' : '败' }}</td>
                <td>{{ item.tokens ?? '—' }}</td>
                <td>{{ formatDuration(item.elapsedMs) }}</td>
                <td class="case-error">{{ item.error ?? '' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section class="block">
        <div class="block-head">
          <h3>历史运行</h3>
          <span class="meta">默认按创建时间倒序</span>
        </div>
        <p v-if="!runs.length" class="meta">还没有评测运行。选择场景后点「开始运行」。</p>
        <table v-else class="table">
          <thead>
            <tr>
              <th>#</th>
              <th>状态</th>
              <th>场景</th>
              <th>进度</th>
              <th>错误</th>
              <th>创建时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="run in runs" :key="run.id" class="clickable" @click="openRun(run.id)">
              <td>{{ run.id }}</td>
              <td>
                {{ runStatusLabel(run.status) }}
                <span v-if="run.partial" class="tag warning">部分</span>
              </td>
              <td>{{ run.scenes.join(' / ') }}</td>
              <td>{{ run.completedCases }} / {{ run.totalCases }}（{{ progressPercent(run) }}%）</td>
              <td>{{ run.errorCases }}</td>
              <td>{{ shortTime(run.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>

    <Teleport to="body">
      <div v-if="startOpen" class="dialog-mask" @click.self="startOpen = false">
        <div class="dialog" role="dialog" aria-label="开始评测">
          <h2>开始评测？</h2>
          <p>
            将调用真实模型运行 {{ selectedScenes.length }} 个场景共 {{ totalSelectedCases }} 例，
            产生 token 费用；使用当前生效提示词与模型快照。
          </p>
          <div class="dialog-actions">
            <button type="button" class="btn primary" :disabled="busy" @click="confirmStart">开始运行</button>
            <button type="button" class="btn quiet" @click="startOpen = false">取消</button>
          </div>
        </div>
      </div>

      <div v-if="cancelOpen" class="dialog-mask" @click.self="cancelOpen = false">
        <div class="dialog" role="dialog" aria-label="取消评测">
          <h2>取消这次评测？</h2>
          <p>当前用例完成后停止，剩余用例标记为已跳过；取消的运行不能设为基线。</p>
          <div class="dialog-actions">
            <button type="button" class="btn danger" :disabled="busy" @click="confirmCancel">确认取消</button>
            <button type="button" class="btn quiet" @click="cancelOpen = false">再想想</button>
          </div>
        </div>
      </div>

      <div v-if="baselineOpen" class="dialog-mask" @click.self="baselineOpen = false">
        <div class="dialog" role="dialog" aria-label="设为基线">
          <h2>设为基线？</h2>
          <p>
            基线只是给运行 #{{ detail?.id }} 贴标签，复用已有结果，不会重跑；
            同一场景只保留一个当前基线，历史基线仍保留。
          </p>
          <label class="dialog-field">
            备注（可选）
            <input v-model="baselineLabel" maxlength="100" aria-label="基线备注" placeholder="例如：v3 发布后的首次运行" />
          </label>
          <div class="dialog-actions">
            <button type="button" class="btn primary" :disabled="busy" @click="confirmBaseline">设为基线</button>
            <button type="button" class="btn quiet" @click="baselineOpen = false">取消</button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.eval-panel {
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

.scene-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-3);
}

.scene-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
}

.scene-card.selected {
  border-color: var(--primary);
}

.scene-card.disabled {
  opacity: 0.65;
}

.scene-check {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
}

.scene-check input {
  accent-color: var(--primary);
}

.scene-name {
  font-weight: 500;
}

.scene-meta {
  display: grid;
  gap: 4px;
  margin: 0;
  font-size: var(--fs-xs);
}

.scene-meta div {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
}

.scene-meta dt {
  color: var(--text-3);
}

.scene-meta dd {
  margin: 0;
  text-align: right;
}

.missing {
  color: var(--danger, #b04a3f);
}

.scene-action {
  margin: 0;
}

.scene-reasons {
  margin: 0;
  color: var(--text-3);
  font-size: var(--fs-xs);
}

.start-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.start-info {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.repeat {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-2);
  font-size: var(--fs-sm);
}

.repeat select {
  height: 30px;
  padding: 0 var(--space-2);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: var(--fs-sm);
}

.detail-actions {
  display: flex;
  gap: var(--space-2);
}

.progress-line {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.progress-track {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--border);
  overflow: hidden;
}

.progress-value {
  height: 100%;
  border-radius: 3px;
  background: var(--primary);
  transition: width 0.3s ease;
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
  font-size: 20px;
  font-weight: 600;
}

.result-counts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.snapshot-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
}

.snapshot-grid h4 {
  margin: 0 0 var(--space-2);
  font-size: var(--fs-sm);
  font-weight: 500;
}

.snapshot-list {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
}

.snapshot-list li {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border-top: 1px solid var(--border);
  font-size: var(--fs-sm);
}

.snapshot-list li:first-child {
  border-top: none;
}

.case-id {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.case-error {
  max-width: 320px;
  color: var(--text-3);
  font-size: var(--fs-xs);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.clickable {
  cursor: pointer;
}

.dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: grid;
  place-items: center;
  background: var(--overlay);
}

.dialog {
  width: 420px;
  max-width: calc(100vw - 32px);
  padding: var(--space-5);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
  box-shadow: var(--shadow-float);
}

.dialog h2 {
  margin: 0 0 var(--space-2);
  font-size: var(--fs-h2);
  font-weight: 600;
}

.dialog p {
  margin: 0 0 var(--space-4);
  color: var(--text-2);
  font-size: var(--fs-sm);
}

.dialog-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: var(--space-4);
  color: var(--text-3);
  font-size: var(--fs-xs);
}

.dialog-field input {
  height: 34px;
  padding: 0 var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: var(--fs-sm);
}

td .tag {
  margin-left: 4px;
}

.dialog-actions {
  display: flex;
  gap: var(--space-2);
}

@media (max-width: 900px) {
  .scene-grid,
  .metric-grid,
  .snapshot-grid {
    grid-template-columns: 1fr;
  }

  .start-bar {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
