<script setup lang="ts">
/**
 * 版本历史：只展示 ACTIVE / ARCHIVED；草稿在编辑器顶部单独提示。
 * 查看版本内容、回滚到历史版本都不触发评测。
 */
import { onMounted, ref, watch } from 'vue'
import {
  activatePromptVersion,
  getPromptVersion,
  listPromptVersions,
  type PromptVersionDetail,
  type PromptVersionSummary,
} from '@/api/prompts'
import { shortTime, versionLabel } from '@/utils/prompt'

const props = defineProps<{ promptKey: string }>()
const emit = defineEmits<{ changed: []; close: [] }>()

const versions = ref<PromptVersionSummary[]>([])
const total = ref(0)
const pageNo = ref(1)
const preview = ref<PromptVersionDetail | null>(null)
const loading = ref(false)
const busy = ref(false)
const error = ref('')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  preview.value = null
  try {
    const page = await listPromptVersions(props.promptKey, 1, 20)
    versions.value = page.items
    total.value = page.total
    pageNo.value = page.page
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '版本历史加载失败'
  } finally {
    loading.value = false
  }
}

async function view(version: PromptVersionSummary): Promise<void> {
  try {
    preview.value = await getPromptVersion(props.promptKey, version.versionNo)
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '版本详情加载失败'
  }
}

async function activate(version: PromptVersionSummary): Promise<void> {
  if (!window.confirm(`回滚到 v${version.versionNo}？当前生效版本会归档，且不会自动跑评测。`)) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await activatePromptVersion(props.promptKey, version.versionNo)
    emit('changed')
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '回滚失败'
  } finally {
    busy.value = false
  }
}

/** 历史版本按版本号倒序累计，超出首屏时按页追加。 */
async function loadMore(): Promise<void> {
  try {
    const page = await listPromptVersions(props.promptKey, pageNo.value + 1, 20)
    versions.value = [...versions.value, ...page.items]
    total.value = page.total
    pageNo.value = page.page
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '版本历史加载失败'
  }
}

watch(() => props.promptKey, load)
onMounted(load)
</script>

<template>
  <section class="history">
    <header class="history-head">
      <strong>版本历史</strong>
      <button type="button" class="link" @click="emit('close')">收起</button>
    </header>

    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-else-if="loading" class="skeleton">
      <div class="skeleton-row" />
    </div>
    <p v-else-if="!versions.length" class="meta">还没有 DB 覆盖版本；当前使用代码内置默认。</p>

    <ul v-else class="list">
      <li v-for="version in versions" :key="version.versionNo" class="row">
        <div class="row-head">
          <span>
            <strong>{{ versionLabel(version) }}</strong>
            <span class="status" :class="version.status === 'ACTIVE' ? 'success' : ''">
              {{ version.status === 'ACTIVE' ? '生效中' : '已归档' }}
            </span>
          </span>
          <span class="meta">{{ shortTime(version.publishedAt ?? version.createdAt) }}</span>
        </div>
        <p v-if="version.note" class="note">{{ version.note }}</p>
        <div class="actions">
          <button type="button" class="link" @click="view(version)">查看</button>
          <button
            v-if="version.status !== 'ACTIVE'"
            type="button"
            class="link"
            :disabled="busy"
            @click="activate(version)"
          >
            设为生效
          </button>
        </div>
        <div v-if="preview?.versionNo === version.versionNo" class="preview">
          <p class="preview-label">system</p>
          <pre>{{ preview.systemContent }}</pre>
          <p class="preview-label">user</p>
          <pre>{{ preview.userContent }}</pre>
        </div>
      </li>
    </ul>
    <button
      v-if="versions.length < total"
      type="button"
      class="link"
      @click="loadMore"
    >
      加载更多（{{ versions.length }} / {{ total }}）
    </button>
  </section>
</template>

<style scoped>
.history {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--fs-sm);
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.row {
  padding: var(--space-2) 0;
  border-top: 1px solid var(--border);
}

.row:first-child {
  border-top: none;
}

.row-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  font-size: var(--fs-sm);
}

.row-head .status {
  margin-left: var(--space-2);
}

.note {
  margin: 3px 0 0;
  color: var(--text-2);
  font-size: var(--fs-xs);
}

.actions {
  display: flex;
  gap: var(--space-3);
  margin-top: 4px;
}

.preview {
  margin-top: var(--space-2);
}

.preview-label {
  margin: var(--space-2) 0 2px;
  color: var(--text-3);
  font-size: var(--fs-overline);
  letter-spacing: 0.08em;
}

pre {
  max-height: 180px;
  margin: 0;
  padding: var(--space-2);
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface-muted);
  font-family: var(--font-mono);
  font-size: 11.5px;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
