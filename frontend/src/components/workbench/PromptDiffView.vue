<script setup lang="ts">
/** 行级 diff：左侧为基线（当前生效/内置），右侧为编辑中内容。 */
import { computed } from 'vue'
import { lineDiff } from '@/utils/prompt'

const props = defineProps<{
  leftTitle: string
  rightTitle: string
  left: string
  right: string
}>()

const rows = computed(() => lineDiff(props.left, props.right))
const changed = computed(() => rows.value.filter((row) => row.type !== 'same').length)
</script>

<template>
  <section class="diff-block">
    <header class="diff-head">
      <div>
        <strong>{{ leftTitle }}</strong>
        <span class="arrow">→</span>
        <strong>{{ rightTitle }}</strong>
      </div>
      <span class="status" :class="changed ? 'warning' : 'success'">
        {{ changed ? `${changed} 行差异` : '内容一致' }}
      </span>
    </header>
    <div class="diff-scroll">
      <table class="diff">
        <tbody>
          <tr v-for="(row, index) in rows" :key="index" :class="row.type">
            <td class="no">{{ row.leftNo ?? '' }}</td>
            <td class="line">
              <span v-if="row.type === 'remove'" class="mark">−</span>{{ row.type === 'add' ? '' : row.text }}
            </td>
            <td class="no">{{ row.rightNo ?? '' }}</td>
            <td class="line">
              <span v-if="row.type === 'add'" class="mark">+</span>{{ row.type === 'remove' ? '' : row.text }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.diff-block {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  min-width: 0;
}

.diff-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-3);
  font-size: var(--fs-sm);
}

.arrow {
  margin: 0 var(--space-2);
  color: var(--text-3);
}

.diff-scroll {
  max-height: 360px;
  overflow: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
}

.diff {
  width: 100%;
  border-collapse: collapse;
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.55;
  table-layout: fixed;
}

.diff td {
  padding: 1px 6px;
  vertical-align: top;
  word-break: break-word;
  white-space: pre-wrap;
}

.no {
  width: 34px;
  color: var(--text-3);
  text-align: right;
  user-select: none;
}

.line {
  width: calc(50% - 34px);
}

.mark {
  display: inline-block;
  width: 12px;
  color: inherit;
  font-weight: 600;
}

tr.remove td {
  background: var(--danger-soft);
  color: var(--danger);
}

tr.add td {
  background: var(--success-soft);
  color: var(--success);
}
</style>
