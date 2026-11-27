<script setup lang="ts">
/** 12 组提示词列表：名称、生效版本、草稿标记与影响功能。 */
import type { PromptSummary } from '@/api/prompts'
import { versionLabel } from '@/utils/prompt'

defineProps<{
  items: PromptSummary[]
  selectedKey: string
  loading: boolean
}>()

const emit = defineEmits<{ select: [key: string] }>()
</script>

<template>
  <aside class="prompt-list">
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
