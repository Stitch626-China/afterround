<script setup lang="ts">
/**
 * 行情洞察（FR2.4）：基于已解析完成的 JD 实时聚合，不落快照。
 * 技能词频取 Top20；薪资按中位数分桶；经验与方向按原文统计（契约明确不归一化）。
 */
import { computed, onMounted, ref } from 'vue'
import { getMarketInsights, type CountItem, type JobMarketInsights, type SkillCountItem } from '@/api/jobs'
import EmptyState from '@/components/EmptyState.vue'

const data = ref<JobMarketInsights | null>(null)
const loading = ref(true)
const error = ref('')

const empty = computed(() => !loading.value && (!data.value || data.value.sampleSize === 0))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    data.value = await getMarketInsights()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '行情加载失败'
  } finally {
    loading.value = false
  }
}

function maxOf(items: Array<{ count: number }>): number {
  return items.reduce((max, item) => Math.max(max, item.count), 0)
}

function pct(count: number, max: number): number {
  return max > 0 ? Math.round((count / max) * 100) : 0
}

function skillRows(): SkillCountItem[] {
  return data.value?.skillTop ?? []
}

function top(items: CountItem[], size = 10): CountItem[] {
  return items.slice(0, size)
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <section class="market">
    <header class="market-head">
      <p class="lead">
        基于已解析完成的 JD 实时统计；导入或删除岗位后结果立即变化。
      </p>
      <div class="head-actions">
        <span v-if="data" class="meta numeric">
          样本 {{ data.sampleSize }} 份 · {{ formatTime(data.generatedAt) }}
        </span>
        <button type="button" class="btn ghost" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>

    <div v-if="error" class="banner danger">{{ error }}</div>

    <div v-if="loading" class="skeleton">
      <div class="skeleton-row tall" />
      <div class="skeleton-row tall" />
    </div>

    <EmptyState
      v-else-if="empty"
      title="还没有可统计的 JD"
      hint="先在「岗位列表」导入 JD 并等待解析完成，行情会自动汇总技能、薪资与方向分布。"
    />

    <div v-else class="grid">
      <article class="card">
        <h2>技能词频 Top {{ Math.min(20, skillRows().length) }}</h2>
        <p class="card-hint">必备与加分合并，小写归一后统计</p>
        <ul class="bars">
          <li v-for="item in skillRows()" :key="item.skill">
            <div class="bar-head">
              <span class="bar-label">{{ item.skill }}</span>
              <span class="bar-value numeric">{{ item.count }}</span>
            </div>
            <div class="track">
              <div class="fill" :style="{ width: `${pct(item.count, maxOf(skillRows()))}%` }" />
            </div>
          </li>
        </ul>
      </article>

      <article class="card">
        <h2>薪资分布</h2>
        <p class="card-hint">按 (下限 + 上限) / 2 分桶，缺失薪资不参与</p>
        <ul class="bars">
          <li v-for="item in data?.salaryDistribution ?? []" :key="item.label">
            <div class="bar-head">
              <span class="bar-label">{{ item.label }}</span>
              <span class="bar-value numeric">{{ item.count }}</span>
            </div>
            <div class="track">
              <div class="fill" :style="{ width: `${pct(item.count, maxOf(data?.salaryDistribution ?? []))}%` }" />
            </div>
          </li>
        </ul>
      </article>

      <article class="card">
        <h2>经验要求分布</h2>
        <p class="card-hint">按 JD 原文统计，同义写法分别计数</p>
        <ul v-if="top(data?.experienceDistribution ?? []).length" class="bars">
          <li v-for="item in top(data?.experienceDistribution ?? [])" :key="item.label">
            <div class="bar-head">
              <span class="bar-label">{{ item.label }}</span>
              <span class="bar-value numeric">{{ item.count }}</span>
            </div>
            <div class="track">
              <div class="fill" :style="{ width: `${pct(item.count, maxOf(data?.experienceDistribution ?? []))}%` }" />
            </div>
          </li>
        </ul>
        <p v-else class="card-empty">没有解析出经验要求</p>
      </article>

      <article class="card">
        <h2 class="card-title">岗位方向分布</h2>
        <p class="card-hint">按岗位名原文取 Top 10</p>
        <ul v-if="top(data?.directionDistribution ?? []).length" class="bars">
          <li v-for="item in top(data?.directionDistribution ?? [])" :key="item.label">
            <div class="bar-head">
              <span class="bar-label">{{ item.label }}</span>
              <span class="bar-value numeric">{{ item.count }}</span>
            </div>
            <div class="track">
              <div class="fill" :style="{ width: `${pct(item.count, maxOf(data?.directionDistribution ?? []))}%` }" />
            </div>
          </li>
        </ul>
        <p v-else class="card-empty">没有解析出岗位方向</p>
      </article>
    </div>
  </section>
</template>

<style scoped>
.market {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.skeleton-row.tall {
  height: 180px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-4);
}

.card-hint {
  margin: 2px 0 var(--space-4);
  font-size: var(--fs-sm);
  color: var(--text-3);
}

.card-empty {
  margin: var(--space-3) 0 0;
  font-size: var(--fs-sm);
  color: var(--text-3);
}

.bars {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.bar-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--space-2);
}

.bar-label {
  font-size: 12.5px;
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar-value {
  font-size: 12px;
  color: var(--text-3);
}

.track {
  height: 6px;
  margin-top: 4px;
  border-radius: 999px;
  background: var(--surface-muted);
  overflow: hidden;
}

.fill {
  height: 100%;
  border-radius: 999px;
  background: var(--chart-1);
  transition: width 0.3s var(--ease);
}

@media (max-width: 900px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
