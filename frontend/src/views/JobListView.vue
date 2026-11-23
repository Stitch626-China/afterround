<script setup lang="ts">
/**
 * 岗位库（F2）：JD 导入 → 异步解析轮询 → 生命周期管理 → 批量删除；
 * 行情洞察作为同页 Tab，避免一级导航膨胀（§6.10）。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  batchDeleteJobPositions,
  deleteJobPosition,
  importJobPosition,
  listJobPositions,
  retryJobParse,
  updateJobPosition,
  type JobPositionListItem,
  type JobPositionStatus,
} from '@/api/jobs'
import EmptyState from '@/components/EmptyState.vue'
import MarketPanel from '@/components/jobs/MarketPanel.vue'

const router = useRouter()

const tab = ref<'list' | 'market'>('list')
const items = ref<JobPositionListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const error = ref('')
const notice = ref('')
const filters = reactive<{ status: JobPositionStatus | ''; city: string; keyword: string }>({
  status: '',
  city: '',
  keyword: '',
})
const selected = ref<number[]>([])
const deletingId = ref<number | null>(null)
const batchConfirm = ref(false)
const remarkEditingId = ref<number | null>(null)
const remarkDraft = ref('')
const savingRemark = ref(false)

const importOpen = ref(false)
const importMode = ref<'file' | 'text'>('file')
const importText = ref('')
const importing = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const running = computed(() =>
  items.value.some((item) => item.parseStatus === 'PENDING' || item.parseStatus === 'PROCESSING'),
)
const allSelected = computed(
  () => items.value.length > 0 && items.value.every((item) => selected.value.includes(item.id)),
)
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false

const STATUS_TEXT: Record<string, string> = {
  SAVED: '待跟进',
  APPLIED: '已投递',
  INTERVIEWING: '面试中',
  OFFERED: '录用',
  REJECTED: '淘汰',
}
const STATUS_TONE: Record<string, string> = {
  SAVED: 'muted',
  APPLIED: 'primary',
  INTERVIEWING: 'warning',
  OFFERED: 'success',
  REJECTED: 'danger',
}
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
    const result = await listJobPositions({
      page: page.value,
      size,
      status: filters.status || undefined,
      city: filters.city.trim() || undefined,
      keyword: filters.keyword.trim() || undefined,
    })
    items.value = result.items
    total.value = result.total
    selected.value = selected.value.filter((id) => result.items.some((item) => item.id === id))
    error.value = ''
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载失败'
  } finally {
    loading.value = false
    schedule()
  }
}

function search(): void {
  page.value = 1
  selected.value = []
  loading.value = true
  void load()
}

function reset(): void {
  filters.status = ''
  filters.city = ''
  filters.keyword = ''
  search()
}

function go(delta: number): void {
  const next = page.value + delta
  if (next < 1 || next > pages.value) {
    return
  }
  page.value = next
  selected.value = []
  loading.value = true
  void load()
}

function toggleAll(): void {
  selected.value = allSelected.value ? [] : items.value.map((item) => item.id)
}

function toggleItem(id: number): void {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((value) => value !== id)
    : [...selected.value, id]
}

function validFile(file: File): string {
  const ext = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (!['pdf', 'docx', 'doc', 'txt'].includes(ext)) {
    return '仅支持 PDF / DOCX / DOC / TXT 文件'
  }
  if (file.size > 5 * 1024 * 1024) {
    return '文件不能超过 5MB'
  }
  return ''
}

async function submitImport(input: { file?: File; text?: string }): Promise<void> {
  importing.value = true
  error.value = ''
  notice.value = ''
  try {
    await importJobPosition(input)
    notice.value = '已导入，正在解析；完成后可直接发起简历匹配。'
    page.value = 1
    await load()
    // 列表刷新成功后才清空输入并收起面板；刷新失败时保留用户已填的 JD 文本
    if (!error.value) {
      importText.value = ''
      importOpen.value = false
    }
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '导入失败'
  } finally {
    importing.value = false
  }
}

async function onPick(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  const invalid = validFile(file)
  if (invalid) {
    error.value = invalid
    return
  }
  await submitImport({ file })
}

async function importPasted(): Promise<void> {
  if (!importText.value.trim()) {
    error.value = '请先粘贴 JD 文本'
    return
  }
  await submitImport({ text: importText.value })
}

async function retry(item: JobPositionListItem): Promise<void> {
  error.value = ''
  try {
    await retryJobParse(item.id)
    notice.value = '已重新触发解析'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '触发解析失败'
  }
}

async function remove(item: JobPositionListItem): Promise<void> {
  error.value = ''
  try {
    await deleteJobPosition(item.id)
    deletingId.value = null
    if (items.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    notice.value = '岗位已删除，行情统计已同步更新'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '删除失败'
  }
}

async function removeSelected(): Promise<void> {
  if (!selected.value.length) {
    return
  }
  error.value = ''
  try {
    await batchDeleteJobPositions(selected.value)
    notice.value = `已删除 ${selected.value.length} 个岗位`
    selected.value = []
    batchConfirm.value = false
    if (items.value.length === 0 && page.value > 1) {
      page.value -= 1
    }
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '批量删除失败'
  }
}

function openDetail(item: JobPositionListItem): void {
  void router.push(`/jobs/${item.id}`)
}

function startRemark(item: JobPositionListItem): void {
  remarkEditingId.value = item.id
  remarkDraft.value = item.remark ?? ''
}

function cancelRemark(): void {
  remarkEditingId.value = null
  remarkDraft.value = ''
}

/** 列表内快速备注：传空字符串即清空（契约约定）。 */
async function saveRemark(item: JobPositionListItem): Promise<void> {
  savingRemark.value = true
  error.value = ''
  try {
    await updateJobPosition(item.id, { remark: remarkDraft.value.trim() })
    cancelRemark()
    notice.value = '备注已保存'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '备注保存失败'
  } finally {
    savingRemark.value = false
  }
}

function salary(item: JobPositionListItem): string {
  const min = item.salaryMin
  const max = item.salaryMax
  if (min == null && max == null) {
    return '薪资未写'
  }
  if (min != null && max != null) {
    return `${Math.round(min / 1000)}-${Math.round(max / 1000)}K`
  }
  return min != null ? `${Math.round(min / 1000)}K 起` : `最高 ${Math.round(max! / 1000)}K`
}

function title(item: JobPositionListItem): string {
  if (item.title) {
    return item.title
  }
  return item.parseStatus === 'FAILED' ? '解析失败' : '解析中…'
}

function meta(item: JobPositionListItem): string {
  return [item.companyName, item.city, item.experience, item.education, salary(item)]
    .filter(Boolean)
    .join(' · ')
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  clearTimer()
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="lead">
        导入 JD 后自动结构化解析，支持生命周期跟踪、简历匹配与行情统计；解析与匹配都在后台异步执行。
      </p>
      <button type="button" class="btn primary" @click="importOpen = !importOpen">
        {{ importOpen ? '收起导入' : '导入 JD' }}
      </button>
    </header>

    <nav class="tabs">
      <button type="button" :class="{ active: tab === 'list' }" @click="tab = 'list'">岗位列表</button>
      <button type="button" :class="{ active: tab === 'market' }" @click="tab = 'market'">行情洞察</button>
    </nav>

    <MarketPanel v-if="tab === 'market'" />

    <template v-else>
      <section v-if="importOpen" class="import">
        <div class="import-switch">
          <button type="button" :class="{ active: importMode === 'file' }" @click="importMode = 'file'">文件</button>
          <button type="button" :class="{ active: importMode === 'text' }" @click="importMode = 'text'">粘贴文本</button>
        </div>
        <template v-if="importMode === 'file'">
          <input ref="fileInput" type="file" accept=".pdf,.docx,.doc,.txt" hidden @change="onPick" />
          <button type="button" class="dropzone" :disabled="importing" @click="fileInput?.click()">
            <strong>{{ importing ? '正在导入…' : '选择 JD 文件' }}</strong>
            <span>PDF / DOCX / DOC / TXT · 单文件不超过 5MB</span>
          </button>
        </template>
        <template v-else>
          <textarea v-model="importText" rows="6" placeholder="把招聘 JD 粘贴到这里…" />
          <div class="import-actions">
            <button type="button" class="btn primary" :disabled="importing" @click="importPasted">
              {{ importing ? '导入中…' : '导入并解析' }}
            </button>
            <button type="button" class="btn ghost" @click="importOpen = false; importText = ''">取消</button>
          </div>
        </template>
      </section>

      <div class="filters">
        <input
          v-model="filters.keyword"
          type="search"
          placeholder="搜索岗位名或公司"
          @keyup.enter="search"
        />
        <select v-model="filters.status" @change="search">
          <option value="">全部状态</option>
          <option value="SAVED">待跟进</option>
          <option value="APPLIED">已投递</option>
          <option value="INTERVIEWING">面试中</option>
          <option value="OFFERED">录用</option>
          <option value="REJECTED">淘汰</option>
        </select>
        <input v-model="filters.city" type="search" placeholder="城市（精确）" @keyup.enter="search" />
        <button type="button" class="btn ghost" @click="search">查询</button>
        <button type="button" class="btn ghost" @click="reset">重置</button>
      </div>

      <div v-if="error" class="banner danger">{{ error }}</div>
      <div v-else-if="notice" class="banner success">{{ notice }}</div>

      <div v-if="selected.length" class="bulk">
        <span>已选 {{ selected.length }} 个岗位</span>
        <div class="bulk-actions">
          <template v-if="batchConfirm">
            <button type="button" class="link danger" @click="removeSelected">确认删除</button>
            <button type="button" class="link" @click="batchConfirm = false">取消</button>
          </template>
          <button v-else type="button" class="link danger" @click="batchConfirm = true">批量删除</button>
        </div>
      </div>

      <section v-if="loading" class="skeleton">
        <div class="skeleton-row" />
        <div class="skeleton-row" />
      </section>

      <EmptyState
        v-else-if="!items.length"
        title="还没有岗位"
        hint="导入 PDF / DOCX / DOC / TXT，或直接粘贴 JD 文本；解析完成后可发起简历匹配。"
        action-label="导入 JD"
        @action="importOpen = true"
      />

      <template v-else>
        <ul class="list">
          <li v-for="item in items" :key="item.id" class="row">
            <input
              type="checkbox"
              class="check"
              :checked="selected.includes(item.id)"
              :aria-label="`选择 ${title(item)}`"
              @change="toggleItem(item.id)"
            />
            <div class="row-main" @click="openDetail(item)">
              <div class="row-title-line">
                <strong class="row-title">{{ title(item) }}</strong>
                <span class="badge" :class="PARSE_TONE[item.parseStatus]">
                  {{ PARSE_TEXT[item.parseStatus] ?? item.parseStatus }}
                </span>
                <span class="badge" :class="STATUS_TONE[item.status]">
                  {{ STATUS_TEXT[item.status] ?? item.status }}
                </span>
              </div>
              <p class="row-meta">{{ meta(item) }}</p>
              <p v-if="item.remark" class="row-remark">备注：{{ item.remark }}</p>
              <div v-if="remarkEditingId === item.id" class="remark-editor" @click.stop>
                <input
                  v-model="remarkDraft"
                  type="text"
                  maxlength="200"
                  placeholder="如：一面 / 二面、简历淘汰 / 面试淘汰"
                  @keyup.enter="saveRemark(item)"
                />
                <button type="button" class="link" :disabled="savingRemark" @click="saveRemark(item)">
                  {{ savingRemark ? '保存中…' : '保存' }}
                </button>
                <button type="button" class="link" @click="cancelRemark">取消</button>
              </div>
              <p v-if="item.parseStatus === 'FAILED'" class="row-error">
                解析失败，可重试；已重试 {{ item.retryCount }} 次
              </p>
            </div>
            <div class="row-actions">
              <template v-if="deletingId === item.id">
                <button type="button" class="link danger" @click="remove(item)">确认删除</button>
                <button type="button" class="link" @click="deletingId = null">取消</button>
              </template>
              <template v-else>
                <button
                  v-if="item.parseStatus === 'FAILED' || item.parseStatus === 'PENDING'"
                  type="button"
                  class="link"
                  @click="retry(item)"
                >
                  {{ item.parseStatus === 'FAILED' ? '重试解析' : '开始解析' }}
                </button>
                <button type="button" class="link" @click="startRemark(item)">
                  {{ item.remark ? '改备注' : '备注' }}
                </button>
                <button type="button" class="link" @click="openDetail(item)">详情</button>
                <button type="button" class="link danger" @click="deletingId = item.id">删除</button>
              </template>
            </div>
          </li>
        </ul>

        <div class="bulk-select">
          <label>
            <input type="checkbox" :checked="allSelected" @change="toggleAll" />
            全选本页
          </label>
          <span class="updated">最近更新 {{ formatTime(items[0]?.updatedAt ?? '') }}</span>
        </div>
      </template>

      <footer v-if="total > size" class="pager">
        <span>共 {{ total }} 个岗位 · 第 {{ page }} / {{ pages }} 页</span>
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

.tabs {
  display: flex;
  gap: var(--space-1);
  border-bottom: 1px solid var(--border);
}

.tabs button {
  position: relative;
  padding: var(--space-2) var(--space-3);
  border: none;
  background: none;
  color: var(--text-3);
  font-size: 13px;
}

.tabs button.active {
  color: var(--text);
  font-weight: 500;
}

.tabs button.active::after {
  content: '';
  position: absolute;
  left: var(--space-3);
  right: var(--space-3);
  bottom: -1px;
  height: 2px;
  border-radius: 1px;
  background: var(--primary);
}

.import {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.import-switch {
  display: flex;
  gap: var(--space-2);
}

.import-switch button {
  padding: 3px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-tag);
  background: var(--surface);
  color: var(--text-3);
  font-size: 12px;
}

.import-switch button.active {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-soft);
}

.dropzone {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  padding: var(--space-5);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface-muted);
  color: var(--text-2);
  font-size: 13px;
}

.dropzone span {
  color: var(--text-3);
  font-size: 12px;
}

.dropzone:hover:not(:disabled) {
  border-color: var(--primary);
  color: var(--primary);
}

.import textarea {
  width: 100%;
  resize: vertical;
  padding: var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 13px;
}

.import-actions {
  display: flex;
  gap: var(--space-2);
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.filters input,
.filters select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 12.5px;
}

.filters input {
  min-width: 160px;
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

.bulk {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-btn);
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 12.5px;
}

.bulk-actions {
  display: flex;
  gap: var(--space-3);
}

.skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.skeleton-row {
  height: 72px;
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
  grid-template-columns: 20px 1fr auto;
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

.check {
  margin: 0;
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
  color: var(--text-3);
}

.row-error {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--danger);
}

.row-remark {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--text-2);
}

.remark-editor {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  margin-top: 6px;
}

.remark-editor input {
  flex: 1;
  min-width: 0;
  max-width: 420px;
  padding: 4px var(--space-2);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 12px;
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

.bulk-select {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--text-3);
}

.bulk-select label {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  cursor: pointer;
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
  .page-head,
  .bulk-select {
    flex-direction: column;
    align-items: flex-start;
  }

  .row {
    grid-template-columns: 20px 1fr;
  }

  .row-actions {
    grid-column: 1 / -1;
  }
}
</style>
