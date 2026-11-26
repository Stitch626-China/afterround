<script setup lang="ts">
/**
 * 调用统计（NFR6）：只读聚合 llm_call_logs，入口固定在设置抽屉，任何页面都能打开查看。
 * 汇总 / 每日 token / 按场景 / 按模型四块，数据为空时给明确空态而不是 0%。
 */
import { computed, onMounted, ref } from 'vue'
import { getLlmUsage, type LlmUsage } from '@/api/llm'

const days = ref(30)
const usage = ref<LlmUsage | null>(null)
const loading = ref(true)
const error = ref('')

/** 每日条形图的相对高度基于窗口内 token 峰值，空数据时最小为 1 避免除零。 */
const peak = computed(() => Math.max(1, ...(usage.value?.daily ?? []).map((day) => day.totalTokens)))

async function load(window: number): Promise<void> {
  days.value = window
  loading.value = true
  error.value = ''
  try {
    usage.value = await getLlmUsage(window)
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '调用统计加载失败'
  } finally {
    loading.value = false
  }
}

function percent(rate: number | null | undefined): string {
  return rate == null ? '—' : `${(rate * 100).toFixed(1)}%`
}

function barHeight(total: number): string {
  return `${Math.max(4, Math.round((total / peak.value) * 100))}%`
}

onMounted(() => void load(30))
</script>

<template>
  <section class="usage">
    <div class="head">
      <p class="lead">统计全部场景的模型调用与 token 消耗，数据来自调用日志，只读。</p>
      <div class="windows">
        <button
          v-for="window in [30, 90]"
          :key="window"
          type="button"
          class="btn quiet"
          :class="{ ghost: days === window }"
          :disabled="loading"
          @click="load(window)"
        >
          近 {{ window }} 天
        </button>
      </div>
    </div>

    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-else-if="loading" class="skeleton">
      <div class="skeleton-row" />
      <div class="skeleton-row" />
    </div>

    <template v-else-if="usage">
      <div v-if="usage.summary.calls === 0" class="banner warning">
        近 {{ usage.days }} 天还没有模型调用记录，产生调用后这里会自动统计。
      </div>

      <template v-else>
        <div class="metrics">
          <div class="stat">
            <span>调用次数</span>
            <strong>{{ usage.summary.calls }}</strong>
            <span class="sub">成功 {{ usage.summary.successCalls }} 次</span>
          </div>
          <div class="stat">
            <span>成功率</span>
            <strong>{{ percent(usage.summary.successRate) }}</strong>
            <span class="sub">失败计入调用总数</span>
          </div>
          <div class="stat">
            <span>总 token</span>
            <strong>{{ usage.summary.totalTokens.toLocaleString() }}</strong>
            <span class="sub">
              输入 {{ usage.summary.promptTokens.toLocaleString() }} · 输出
              {{ usage.summary.completionTokens.toLocaleString() }}
            </span>
          </div>
          <div class="stat">
            <span>平均耗时</span>
            <strong>{{ usage.summary.avgDurationMs }} ms</strong>
            <span class="sub">按单次调用平均</span>
          </div>
        </div>

        <section v-if="usage.daily.length" class="block">
          <h3>每日 token</h3>
          <div class="chart">
            <div
              v-for="day in usage.daily"
              :key="day.date"
              class="bar-wrap"
              :title="`${day.date}：${day.calls} 次调用，${day.totalTokens.toLocaleString()} token`"
            >
              <i class="bar" :style="{ height: barHeight(day.totalTokens) }" />
            </div>
          </div>
          <p class="axis">
            <span>{{ usage.daily[0]?.date }}</span>
            <span>{{ usage.daily[usage.daily.length - 1]?.date }}</span>
          </p>
        </section>

        <section v-if="usage.scenes.length" class="block">
          <h3>按场景</h3>
          <table class="table">
            <thead>
              <tr>
                <th>场景</th>
                <th>调用</th>
                <th>成功率</th>
                <th>token</th>
                <th>平均耗时</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="scene in usage.scenes" :key="scene.scene">
                <td>
                  {{ scene.label }}<span class="key">{{ scene.scene }}</span>
                </td>
                <td>{{ scene.calls }}</td>
                <td>{{ percent(scene.successRate) }}</td>
                <td>{{ scene.totalTokens.toLocaleString() }}</td>
                <td>{{ scene.avgDurationMs }} ms</td>
              </tr>
            </tbody>
          </table>
        </section>

        <section v-if="usage.models.length" class="block">
          <h3>按模型</h3>
          <table class="table">
            <thead>
              <tr>
                <th>Provider</th>
                <th>模型</th>
                <th>调用</th>
                <th>token</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="model in usage.models" :key="`${model.providerKey}/${model.model}`">
                <td>{{ model.providerKey || '—' }}</td>
                <td>{{ model.model || '—' }}</td>
                <td>{{ model.calls }}</td>
                <td>{{ model.totalTokens.toLocaleString() }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.usage {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.windows {
  display: flex;
  gap: var(--space-1);
  flex-shrink: 0;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2);
}

.sub {
  color: var(--text-3);
  font-size: var(--fs-overline);
}

.block {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.block h3 {
  margin: 0;
  color: var(--text-2);
  font-size: var(--fs-sm);
  font-weight: 600;
}

.chart {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 96px;
  padding-top: var(--space-2);
  border-bottom: 1px solid var(--border);
}

.bar-wrap {
  flex: 1;
  height: 100%;
  display: flex;
  align-items: flex-end;
}

.bar {
  display: block;
  width: 100%;
  border-radius: 2px 2px 0 0;
  background: var(--primary);
  opacity: 0.5;
  transition: height var(--dur-2) var(--ease), opacity var(--dur-1) var(--ease);
}

.bar-wrap:hover .bar {
  opacity: 1;
}

.axis {
  display: flex;
  justify-content: space-between;
  margin: 0;
  color: var(--text-3);
  font-size: var(--fs-overline);
}

.key {
  margin-left: 6px;
  color: var(--text-3);
  font-size: var(--fs-overline);
}
</style>
