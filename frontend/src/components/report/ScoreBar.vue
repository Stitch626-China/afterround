<script setup lang="ts">
/**
 * 五维评分条：横向条形 + 数值（§6.10 硬性决策，不用雷达图）。
 */
import { computed } from 'vue'

const props = defineProps<{
  label: string
  score: number
  weight: number
}>()

const tone = computed(() => {
  if (props.score >= 80) return 'high'
  if (props.score >= 60) return 'mid'
  return 'low'
})
</script>

<template>
  <div class="score-bar">
    <div class="score-head">
      <span class="score-label">{{ label }}</span>
      <span class="score-meta numeric">
        <strong>{{ score }}</strong>
        <span class="weight">{{ weight }}%</span>
      </span>
    </div>
    <div class="track">
      <div class="fill" :class="tone" :style="{ width: `${Math.max(0, Math.min(100, score))}%` }" />
    </div>
  </div>
</template>

<style scoped>
.score-bar {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.score-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.score-label {
  font-size: 13px;
  color: var(--text-2);
}

.score-meta {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}

.score-meta strong {
  font-size: 14px;
  font-weight: 600;
  color: var(--text);
}

.weight {
  font-size: 11px;
  color: var(--text-3);
}

.track {
  height: 6px;
  border-radius: 3px;
  background: var(--surface-muted);
  border: 1px solid var(--border);
  overflow: hidden;
}

.fill {
  height: 100%;
  border-radius: 2px;
  transition: width 0.3s ease;
}

.fill.high {
  background: var(--score-high);
}

.fill.mid {
  background: var(--score-mid);
}

.fill.low {
  background: var(--score-low);
}
</style>
