<script setup lang="ts">
/**
 * 改进看板（F4 改进闭环）：汇总复盘生成的行动，维护状态、截止时间与验证证据。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createImprovementAction,
  deleteImprovementAction,
  listImprovementActions,
  updateImprovementAction,
  type ImprovementAction,
  type ImprovementActionStatus,
} from '@/api/reviews'
import { ACTION_STATUSES, ACTION_STATUS_TEXT, ACTION_STATUS_TONE } from '@/utils/review'
import EmptyState from '@/components/EmptyState.vue'

const items = ref<ImprovementAction[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')
const statusFilter = ref<ImprovementActionStatus | ''>('')
const formOpen = ref(false)
const confirmId = ref<number | null>(null)
const evidenceId = ref<number | null>(null)
const evidenceDraft = ref('')

const draft = reactive<{ action: string; category: string; dueAt: string; sourceReviewId: number | null }>({
  action: '',
  category: '',
  dueAt: '',
  sourceReviewId: null,
})

const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listImprovementActions({
      page: page.value,
      size,
      status: statusFilter.value || undefined,
    })
    items.value = result.items
    total.value = result.total
    error.value = ''
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function create(): Promise<void> {
  if (!draft.action.trim()) {
    error.value = '请填写改进行动'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await createImprovementAction({
      action: draft.action.trim(),
      category: draft.category.trim() || null,
      dueAt: draft.dueAt ? new Date(draft.dueAt).toISOString() : null,
      sourceReviewId: draft.sourceReviewId,
    })
    notice.value = '已添加改进行动'
    draft.action = ''
    draft.category = ''
    draft.dueAt = ''
    draft.sourceReviewId = null
    formOpen.value = false
    page.value = 1
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '新增行动失败'
  } finally {
    saving.value = false
  }
}

async function changeStatus(item: ImprovementAction, event: Event): Promise<void> {
  const status = (event.target as HTMLSelectElement).value as ImprovementActionStatus
  error.value = ''
  try {
    await updateImprovementAction(item.id, { status })
    notice.value = `已更新为「${ACTION_STATUS_TEXT[status] ?? status}」`
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '更新状态失败'
    await load()
  }
}

function editEvidence(item: ImprovementAction): void {
  evidenceId.value = item.id
  evidenceDraft.value = item.evidence ?? ''
}

async function saveEvidence(item: ImprovementAction): Promise<void> {
  error.value = ''
  try {
    await updateImprovementAction(item.id, { evidence: evidenceDraft.value.trim() || null })
    evidenceId.value = null
    notice.value = '验证证据已保存'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '保存证据失败'
  }
}

async function remove(item: ImprovementAction): Promise<void> {
  error.value = ''
  try {
    await deleteImprovementAction(item.id)
    confirmId.value = null
    if (items.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    notice.value = '改进行动已删除'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '删除失败'
  }
}

function search(): void {
  page.value = 1
  void load()
}

function go(delta: number): void {
  const next = page.value + delta
  if (next < 1 || next > pages.value) {
    return
  }
  page.value = next
  void load()
}

function dueText(value: string | null | undefined): string {
  return value ? `截止 ${new Date(value).toLocaleDateString('zh-CN')}` : ''
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="lead">
        复盘确认后生成的行动会汇总到这里：按「待开始 → 进行中 → 已验证」推进，并在后续面试中记录验证证据。
      </p>
      <button type="button" class="btn primary" @click="formOpen = !formOpen">
        {{ formOpen ? '收起' : '新增行动' }}
      </button>
    </header>

    <section v-if="formOpen" class="form">
      <label>
        改进行动
        <textarea v-model="draft.action" rows="3" maxlength="500" placeholder="具体、可执行的行动，如「手写 JVM 内存模型笔记并口述一遍」" />
      </label>
      <div class="form-grid">
        <label>
          分类
          <input v-model="draft.category" maxlength="64" placeholder="知识点补强 / 表达结构" />
        </label>
        <label>
          截止日期
          <input v-model="draft.dueAt" type="date" />
        </label>
      </div>
      <div class="actions">
        <button type="button" class="btn primary" :disabled="saving" @click="create">
          {{ saving ? '保存中…' : '保存' }}
        </button>
        <button type="button" class="btn ghost" @click="formOpen = false">取消</button>
      </div>
    </section>

    <div class="filters">
      <select v-model="statusFilter" @change="search">
        <option value="">全部状态</option>
        <option v-for="status in ACTION_STATUSES" :key="status" :value="status">
          {{ ACTION_STATUS_TEXT[status] }}
        </option>
      </select>
      <button type="button" class="btn ghost" @click="search">刷新</button>
    </div>

    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-else-if="notice" class="banner success">{{ notice }}</div>

    <section v-if="loading" class="skeleton">
      <div class="skeleton-row" />
      <div class="skeleton-row" />
    </section>

    <EmptyState
      v-else-if="!items.length"
      title="还没有改进行动"
      hint="在复盘详情中确认分析会自动生成行动，也可以在这里手动补充。"
      action-label="新增行动"
      @action="formOpen = true"
    />

    <template v-else>
      <ul class="list">
        <li v-for="item in items" :key="item.id" class="row">
          <div class="row-main">
            <div class="row-title-line">
              <strong class="row-title">{{ item.action }}</strong>
              <span class="badge" :class="ACTION_STATUS_TONE[item.status]">
                {{ ACTION_STATUS_TEXT[item.status] ?? item.status }}
              </span>
              <span v-if="item.category" class="badge muted">{{ item.category }}</span>
            </div>
            <p class="row-meta">
              <span v-if="dueText(item.dueAt)">{{ dueText(item.dueAt) }}</span>
              <RouterLink v-if="item.sourceReviewId" :to="`/reviews/${item.sourceReviewId}`" class="link">
                来源复盘 #{{ item.sourceReviewId }}
              </RouterLink>
            </p>
            <p v-if="item.evidence" class="row-evidence">证据：{{ item.evidence }}</p>
            <div v-if="evidenceId === item.id" class="evidence-editor">
              <textarea v-model="evidenceDraft" rows="2" maxlength="2000" placeholder="写下验证方式与结果" />
              <div class="actions">
                <button type="button" class="btn primary" @click="saveEvidence(item)">保存证据</button>
                <button type="button" class="btn ghost" @click="evidenceId = null">取消</button>
              </div>
            </div>
          </div>
          <div class="row-actions">
            <select :value="item.status" aria-label="更新行动状态" @change="changeStatus(item, $event)">
              <option v-for="status in ACTION_STATUSES" :key="status" :value="status">
                {{ ACTION_STATUS_TEXT[status] }}
              </option>
            </select>
            <button type="button" class="link" @click="editEvidence(item)">验证证据</button>
            <template v-if="confirmId === item.id">
              <button type="button" class="link danger" @click="remove(item)">确认删除</button>
              <button type="button" class="link" @click="confirmId = null">取消</button>
            </template>
            <button v-else type="button" class="link danger" @click="confirmId = item.id">删除</button>
          </div>
        </li>
      </ul>

      <div v-if="pages > 1" class="pager">
        <button type="button" class="btn ghost" :disabled="page <= 1" @click="go(-1)">上一页</button>
        <span>{{ page }} / {{ pages }}</span>
        <button type="button" class="btn ghost" :disabled="page >= pages" @click="go(1)">下一页</button>
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

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
}

.lead {
  margin: 0;
  color: var(--text-2);
  font-size: 13px;
  max-width: 720px;
}

.btn {
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text-2);
  font-size: 13px;
  white-space: nowrap;
}

.btn.primary {
  border-color: var(--primary);
  background: var(--primary);
  color: #fff;
}

.btn:disabled {
  opacity: 0.55;
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-3);
}

.form label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: var(--text-3);
  font-size: 12px;
}

.form select,
.form input,
.form textarea,
.filters select,
.row-actions select,
.evidence-editor textarea {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 13px;
}

.form textarea {
  resize: vertical;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.banner {
  padding: var(--space-3);
  border-radius: var(--radius-btn);
  font-size: 13px;
}

.banner.danger {
  background: var(--danger-soft);
  color: var(--danger);
}

.banner.success {
  background: var(--success-soft);
  color: var(--success);
}

.skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.skeleton-row {
  height: 64px;
  border-radius: var(--radius-card);
  background: var(--surface-muted);
}

.list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.row-main {
  flex: 1 1 280px;
  min-width: 0;
}

.row-title-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.row-title {
  font-size: 14px;
  font-weight: 500;
}

.row-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: 4px 0 0;
  color: var(--text-3);
  font-size: 12px;
}

.row-evidence {
  margin: 4px 0 0;
  color: var(--text-2);
  font-size: 12px;
}

.badge {
  padding: 1px 6px;
  border-radius: var(--radius-tag);
  font-size: 11px;
  background: var(--surface-muted);
  color: var(--text-3);
}

.badge.success {
  background: var(--success-soft);
  color: var(--success);
}

.badge.warning {
  background: var(--warning-soft);
  color: var(--warning);
}

.badge.danger {
  background: var(--danger-soft);
  color: var(--danger);
}

.badge.muted {
  background: var(--surface-muted);
  color: var(--text-3);
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.evidence-editor {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.evidence-editor textarea {
  resize: vertical;
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: var(--primary);
  font-size: 12px;
  cursor: pointer;
  text-decoration: none;
}

.link.danger {
  color: var(--danger);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  color: var(--text-3);
  font-size: 12px;
}
</style>
