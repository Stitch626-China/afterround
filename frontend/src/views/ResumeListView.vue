<script setup lang="ts">
/**
 * 简历中心：上传（拖拽 / 选择 / 粘贴）→ 留在列表观察进度 → 点击进入报告。
 * 契约见 contracts/openapi.yaml；历史简历分页展示，不自动跳转。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  deleteResume,
  listResumes,
  triggerAnalysis,
  uploadResume,
  type ResumeListItem,
} from '@/api/resumes'
import EmptyState from '@/components/EmptyState.vue'

const router = useRouter()

const items = ref<ResumeListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const uploading = ref(false)
const dragging = ref(false)
const pasteOpen = ref(false)
const pasteText = ref('')
const deletingId = ref<number | null>(null)
const error = ref('')
const notice = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined

const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const running = computed(() => items.value.some((item) => item.analyzeStatus === 'PROCESSING'))
let disposed = false

const STATUS_TEXT: Record<string, string> = {
  PENDING: '待分析',
  PROCESSING: '分析中',
  COMPLETED: '已完成',
  FAILED: '失败',
}

const STATUS_TONE: Record<string, string> = {
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
    const result = await listResumes(page.value, size)
    items.value = result.items
    total.value = result.total
    error.value = ''
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载失败'
  } finally {
    loading.value = false
    schedule()
  }
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

async function submit(input: { file?: File; text?: string }): Promise<void> {
  uploading.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await uploadResume(input)
    notice.value = result.duplicated
      ? '这份简历之前上传过，已定位到已有记录，未重复分析。'
      : result.resume.analyzeStatus === 'PENDING'
        ? '已上传，点击「开始分析」生成报告；也可先在设置中配置默认模型。'
        : '已上传，正在自动分析；完成后点击卡片查看报告。'
    pasteOpen.value = false
    pasteText.value = ''
    page.value = 1
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '上传失败'
  } finally {
    uploading.value = false
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
  await submit({ file })
}

async function onDrop(event: DragEvent): Promise<void> {
  dragging.value = false
  const file = event.dataTransfer?.files?.[0]
  if (!file) {
    return
  }
  const invalid = validFile(file)
  if (invalid) {
    error.value = invalid
    return
  }
  await submit({ file })
}

async function uploadPaste(): Promise<void> {
  if (!pasteText.value.trim()) {
    error.value = '请先粘贴简历文本'
    return
  }
  await submit({ text: pasteText.value })
}

async function retry(item: ResumeListItem): Promise<void> {
  error.value = ''
  try {
    await triggerAnalysis(item.id)
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '触发分析失败'
  }
}

async function remove(item: ResumeListItem): Promise<void> {
  error.value = ''
  try {
    await deleteResume(item.id)
    deletingId.value = null
    if (items.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '删除失败'
  }
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

function openReport(item: ResumeListItem): void {
  void router.push(`/resumes/${item.id}/report`)
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function formatSize(value?: number | null): string {
  if (!value) {
    return ''
  }
  return value >= 1024 * 1024
    ? `${(value / 1024 / 1024).toFixed(1)} MB`
    : `${Math.max(1, Math.round(value / 1024))} KB`
}

function meta(item: ResumeListItem): string {
  return [item.fileType, formatSize(item.fileSize), formatTime(item.createdAt)]
    .filter(Boolean)
    .join(' · ')
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
      <div>
        <p class="lead">上传简历，获得五维评分、短板分析与岗位方向推荐；历史上传的记录都会保留。</p>
      </div>
      <button type="button" class="btn ghost" @click="pasteOpen = !pasteOpen">
        {{ pasteOpen ? '收起粘贴' : '粘贴文本' }}
      </button>
    </header>

    <!-- 上传区：拖拽 / 点击 -->
    <section
      class="dropzone"
      :class="{ dragging }"
      @click="fileInput?.click()"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <input ref="fileInput" type="file" accept=".pdf,.docx,.doc,.txt" hidden @change="onPick" />
      <p class="drop-title"><strong>拖入简历文件</strong>，或点击选择</p>
      <p class="drop-hint">PDF / DOCX / DOC / TXT · 单文件不超过 5MB</p>
      <p v-if="uploading" class="drop-busy">上传中…</p>
    </section>

    <!-- 粘贴文本 -->
    <section v-if="pasteOpen" class="paste">
      <textarea v-model="pasteText" rows="6" placeholder="把简历纯文本粘贴到这里…" />
      <div class="paste-actions">
        <button type="button" class="btn primary" :disabled="uploading" @click="uploadPaste">开始分析</button>
        <button type="button" class="btn ghost" @click="pasteOpen = false; pasteText = ''">取消</button>
      </div>
    </section>

    <p v-if="error" class="banner danger">{{ error }}</p>
    <p v-else-if="notice" class="banner success">{{ notice }}</p>

    <section v-if="loading" class="skeleton">
      <div class="skeleton-row" />
      <div class="skeleton-row" />
    </section>

    <EmptyState
      v-else-if="!items.length"
      title="还没有简历"
      hint="支持 PDF / DOCX / DOC / TXT，单文件不超过 5MB；也可以直接粘贴纯文本。"
      action-label="上传简历"
      @action="fileInput?.click()"
    />

    <ul v-else class="list">
      <li
        v-for="item in items"
        :key="item.id"
        class="row"
        @click="openReport(item)"
      >
        <div class="row-main">
          <strong class="row-title">{{ item.fileName || '粘贴文本简历' }}</strong>
          <p class="row-meta">{{ meta(item) }}</p>
        </div>

        <span class="badge" :class="STATUS_TONE[item.analyzeStatus]">
          {{ STATUS_TEXT[item.analyzeStatus] ?? item.analyzeStatus }}
        </span>

        <span v-if="item.overallScore != null" class="score numeric">{{ item.overallScore }}</span>
        <span v-else class="score placeholder">—</span>

        <div class="row-actions" @click.stop>
          <template v-if="deletingId === item.id">
            <button type="button" class="link danger" @click="remove(item)">确认删除</button>
            <button type="button" class="link" @click="deletingId = null">取消</button>
          </template>
          <template v-else>
            <button v-if="item.analyzeStatus === 'FAILED'" type="button" class="link" @click="retry(item)">
              重试
            </button>
            <button v-else-if="item.analyzeStatus === 'PENDING'" type="button" class="link" @click="retry(item)">
              开始分析
            </button>
            <button type="button" class="link" @click="openReport(item)">查看报告</button>
            <button type="button" class="link danger" @click="deletingId = item.id">删除</button>
          </template>
        </div>
      </li>
    </ul>

    <footer v-if="total > size" class="pager">
      <span>共 {{ total }} 份 · 第 {{ page }} / {{ pages }} 页</span>
      <div class="pager-actions">
        <button type="button" class="btn ghost" :disabled="page <= 1" @click="go(-1)">上一页</button>
        <button type="button" class="btn ghost" :disabled="page >= pages" @click="go(1)">下一页</button>
      </div>
    </footer>
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

.dropzone {
  padding: var(--space-6);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-card);
  background: var(--surface);
  cursor: pointer;
  text-align: center;
  transition: border-color 0.12s ease, background 0.12s ease;
}

.dropzone:hover,
.dropzone.dragging {
  border-color: var(--primary);
  background: var(--primary-soft);
}

.drop-title {
  margin: 0;
  font-size: 13.5px;
  color: var(--text-2);
}

.drop-title strong {
  color: var(--text);
}

.drop-hint {
  margin: var(--space-1) 0 0;
  font-size: 12px;
  color: var(--text-3);
}

.drop-busy {
  margin: var(--space-2) 0 0;
  font-size: 12.5px;
  color: var(--primary);
}

.paste {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
}

.paste textarea {
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

.paste-actions {
  display: flex;
  gap: var(--space-2);
}

.banner {
  margin: 0;
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
  height: 64px;
  border-radius: var(--radius-card);
  background: var(--surface);
  border: 1px solid var(--border);
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
  grid-template-columns: 1fr auto 48px auto;
  align-items: center;
  gap: var(--space-4);
  padding: var(--space-3) var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  cursor: pointer;
  transition: border-color 0.12s ease, box-shadow 0.12s ease;
}

.row:hover {
  border-color: var(--border-strong);
  box-shadow: var(--shadow-rest);
}

.row-title {
  font-size: 13.5px;
  font-weight: 500;
}

.row-meta {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-3);
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

.score {
  text-align: right;
  font-size: 18px;
  font-weight: 600;
}

.score.placeholder {
  color: var(--text-3);
  font-weight: 400;
}

.row-actions {
  display: flex;
  gap: var(--space-3);
  justify-content: flex-end;
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

@media (max-width: 720px) {
  .page-head {
    flex-direction: column;
  }

  .row {
    grid-template-columns: 1fr auto;
  }

  .score,
  .row-actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
    text-align: left;
  }
}
</style>
