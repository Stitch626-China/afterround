<script setup lang="ts">
/** 12 组提示词列表：名称、生效版本、草稿标记与影响功能；导出由前端聚合详情生成。 */
import { ref } from 'vue'
import { getPrompt, type PromptSummary } from '@/api/prompts'
import { versionLabel } from '@/utils/prompt'

const props = defineProps<{
  items: PromptSummary[]
  selectedKey: string
  loading: boolean
}>()

const emit = defineEmits<{ select: [key: string] }>()

const exporting = ref(false)
const exportError = ref('')

/** 聚合列表 + 详情导出 JSON；不新增后端端点，导出的是当前生效内容。 */
async function exportAll(): Promise<void> {
  exporting.value = true
  exportError.value = ''
  try {
    const prompts = await Promise.all(props.items.map(async (item) => {
      const detail = await getPrompt(item.key)
      return {
        key: detail.key,
        name: detail.name,
        version: detail.active.versionNo > 0 ? `db-${detail.active.versionNo}` : detail.builtinVersion,
        systemContent: detail.active.systemContent,
        userContent: detail.active.userContent,
      }
    }))
    const blob = new Blob([JSON.stringify({ exportedAt: new Date().toISOString(), prompts }, null, 2)],
      { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `llm-prompts-${new Date().toISOString().slice(0, 10)}.json`
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (ex) {
    exportError.value = ex instanceof Error ? ex.message : '导出失败'
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <aside class="prompt-list">
    <div class="list-head">
      <span class="meta">{{ loading ? '加载中' : `${items.length} 组` }}</span>
      <button type="button" class="btn quiet" :disabled="loading || exporting || !items.length" @click="exportAll">
        {{ exporting ? '导出中…' : '导出 JSON' }}
      </button>
    </div>
    <p v-if="exportError" class="export-error">{{ exportError }}</p>
    <div v-if="loading" class="skeleton">
      <div class="skeleton-row" />
      <div class="skeleton-row" />
    </div>
    <ul v-else>
      <li v-for="item in items" :key="item.key">
        <button
          type="button"
          class="row"
          :class="{ active: item.key === selectedKey }"
          @click="emit('select', item.key)"
        >
          <span class="row-top">
            <strong>{{ item.name }}</strong>
            <span v-if="item.hasDraft" class="tag warning">草稿</span>
          </span>
          <span class="row-meta">
            {{ item.category }} · {{ versionLabel(item.activeVersion, item.builtinVersion) }}
          </span>
          <span v-if="item.hasEvalCorpus" class="row-eval">有评测语料</span>
        </button>
      </li>
    </ul>
  </aside>
</template>

<style scoped>
.prompt-list {
  min-width: 0;
  border-right: 1px solid var(--border);
  padding-right: var(--space-3);
}

.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.export-error {
  margin: 0 0 var(--space-2);
  color: var(--danger, #b04a3f);
  font-size: var(--fs-xs);
}

ul {
  list-style: none;
  margin: 0;
  padding: 0;
}

.row {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: var(--space-2) var(--space-3);
  border: none;
  border-radius: var(--radius-btn);
  background: none;
  color: var(--text-2);
  text-align: left;
}

.row:hover {
  background: var(--surface-muted);
}

.row.active {
  background: var(--primary-soft);
  color: var(--text);
}

.row-top {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-sm);
}

.row-top strong {
  font-weight: 500;
}

.row-meta {
  color: var(--text-3);
  font-size: var(--fs-overline);
}

.row-eval {
  color: var(--primary);
  font-size: var(--fs-overline);
}

@media (max-width: 900px) {
  .prompt-list {
    border-right: none;
    border-bottom: 1px solid var(--border);
    padding: 0 0 var(--space-3);
  }

  ul {
    display: flex;
    gap: var(--space-1);
    overflow-x: auto;
  }

  li {
    flex: 0 0 auto;
  }

  .row {
    min-width: 170px;
  }
}
</style>
