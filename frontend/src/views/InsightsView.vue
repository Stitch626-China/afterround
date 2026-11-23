<script setup lang="ts">
/**
 * 跨场洞察（F5）：知识点 × 结果、失分频次、轮次通过率、公司画像、高频问题、
 * 投递 / 简历漏斗、改进闭环与趋势。样本不足时只展示原始统计，不输出因果结论。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getInsights, type InsightsOverview } from '@/api/insights'
import { listCompanies, type Company } from '@/api/jobs'
import EmptyState from '@/components/EmptyState.vue'
import {
  INSIGHT_WINDOWS,
  barWidth,
  deltaText,
  pct,
  sampleHint,
  sampleText,
} from '@/utils/insights'
import { ACTION_STATUS_TEXT } from '@/utils/review'

const router = useRouter()
const data = ref<InsightsOverview | null>(null)
const companies = ref<Company[]>([])
const loading = ref(true)
const error = ref('')
const days = ref(90)
const companyFilter = ref('')

const maxLossCount = computed(() => {
  const counts = data.value?.lossTags.map((item) => item.count) ?? [1]
  return Math.max(1, ...counts)
})

async function load(): Promise<void> {
  loading.value = true
  try {
    data.value = await getInsights({
      days: days.value,
      companyId: companyFilter.value ? Number(companyFilter.value) : null,
    })
    error.value = ''
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载洞察失败'
  } finally {
    loading.value = false
  }
}

async function loadCompanies(): Promise<void> {
  try {
    const page = await listCompanies('', 100)
    companies.value = page.items
  } catch {
    // 公司筛选失败不阻断洞察
  }
}

function formatTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleDateString('zh-CN') : '—'
}

onMounted(() => {
  void load()
  void loadCompanies()
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="lead">
        只聚合已确认的复盘分析，回答三个问题：我有没有变好、为什么、下一步做什么。样本不足时只给原始统计。
      </p>
      <div class="filters">
        <select v-model.number="days" aria-label="统计窗口" @change="load">
          <option v-for="window in INSIGHT_WINDOWS" :key="window" :value="window">近 {{ window }} 天</option>
        </select>
        <select v-model="companyFilter" aria-label="公司筛选" @change="load">
          <option value="">全部公司</option>
          <option v-for="company in companies" :key="company.id" :value="String(company.id)">
            {{ company.name }}
          </option>
        </select>
        <button type="button" class="btn" @click="load">刷新</button>
      </div>
    </header>

    <div v-if="error" class="banner danger">{{ error }}</div>

    <section v-if="loading" class="skeleton">
      <div class="skeleton-row" />
      <div class="skeleton-row" />
    </section>

    <template v-else-if="data">
      <div class="sample-grid">
        <div class="stat">
          <span>投递</span>
          <strong>{{ data.sample.applications }}</strong>
        </div>
        <div class="stat">
          <span>面试</span>
          <strong>{{ data.sample.interviews }}</strong>
        </div>
        <div class="stat">
          <span>已确认复盘</span>
          <strong>{{ data.sample.confirmedReviews }}</strong>
        </div>
        <div class="stat">
          <span>Offer</span>
          <strong>{{ data.sample.offers }}</strong>
        </div>
      </div>

      <div class="banner" :class="data.sample.sufficient ? 'success' : 'warning'">
        <strong>{{ sampleText(data.sample) }}</strong>
        <span>{{ sampleHint(data.sample) }}</span>
      </div>

      <section class="card summary">
        <div class="card-head">
          <h3>归因总结</h3>
          <span class="badge muted">本地聚合 · 不调用 LLM</span>
        </div>
        <p class="summary-text">{{ data.summary.content }}</p>
      </section>

      <EmptyState
        v-if="data.sample.confirmedReviews === 0"
        title="还没有可归因的复盘"
        hint="完成真实面试复盘并确认分析后，这里会按知识点、失分类型、轮次与公司聚合出跨场规律。"
        action-label="去新建复盘"
        @action="router.push('/reviews')"
      />

      <template v-else>
        <section class="card">
          <div class="card-head">
            <h3>知识点 × 结果</h3>
            <span class="meta">失败率 = 失败 /（通过 + 失败）</span>
          </div>
          <div v-if="data.topics.length" class="metric-list">
            <div v-for="item in data.topics" :key="item.topic" class="metric-row">
              <div class="metric-label">
                <span>{{ item.label }}</span>
                <span class="meta">
                  {{ item.interviewCount }} 场 · 通过 {{ item.passCount }} · 失败 {{ item.failCount }} ·
                  待定 {{ item.pendingCount }}
                </span>
              </div>
              <div class="bar">
                <div class="bar-fill danger" :style="{ width: barWidth(item.failRate) }" />
              </div>
              <strong class="metric-value">{{ pct(item.failRate) }}</strong>
              <span v-if="!item.sufficient" class="badge muted">样本不足</span>
            </div>
          </div>
          <p v-else class="empty-line">暂无可聚合的知识点标签。</p>
        </section>

        <section class="card">
          <div class="card-head">
            <h3>失分类型频次</h3>
            <span class="meta">出现场数，按频次排序</span>
          </div>
          <div v-if="data.lossTags.length" class="metric-list">
            <div v-for="item in data.lossTags" :key="item.tag" class="metric-row">
              <div class="metric-label">
                <span>{{ item.label }}</span>
                <span class="meta">最近 {{ formatTime(item.lastSeenAt) }}</span>
              </div>
              <div class="bar">
                <div
                  class="bar-fill warning"
                  :style="{ width: barWidth(item.count / maxLossCount) }"
                />
              </div>
              <strong class="metric-value">{{ item.count }} 场</strong>
            </div>
          </div>
          <p v-else class="empty-line">暂无失分标签。</p>
        </section>

        <section class="card">
          <div class="card-head">
            <h3>轮次通过率</h3>
            <span class="meta">通过率 = 通过 /（通过 + 失败）</span>
          </div>
          <div v-if="data.roundPassRates.length" class="metric-list">
            <div v-for="item in data.roundPassRates" :key="item.roundType" class="metric-row">
              <div class="metric-label">
                <span>{{ item.label }}</span>
                <span class="meta">
                  {{ item.total }} 场 · 通过 {{ item.passCount }} · 失败 {{ item.failCount }} ·
                  待定 {{ item.pendingCount }}
                </span>
              </div>
              <div class="bar">
                <div class="bar-fill success" :style="{ width: barWidth(item.passRate) }" />
              </div>
              <strong class="metric-value">{{ pct(item.passRate) }}</strong>
              <span v-if="!item.sufficient" class="badge muted">样本不足</span>
            </div>
          </div>
          <p v-else class="empty-line">暂无面试轮次记录。</p>
        </section>

        <section class="card">
          <div class="card-head">
            <h3>公司 / 岗位画像</h3>
            <span class="meta">常考知识点与高频失分类型（各取 Top 3）</span>
          </div>
          <div v-if="data.companyProfiles.length" class="company-list">
            <article v-for="company in data.companyProfiles" :key="company.companyId" class="company">
              <div class="company-head">
                <strong>{{ company.companyName }}</strong>
                <span class="meta">
                  {{ company.interviewCount }} 场 · 通过 {{ company.passCount }} · 失败 {{ company.failCount }} ·
                  最近 {{ formatTime(company.lastInterviewAt) }}
                </span>
              </div>
              <div class="tag-line">
                <span class="tag-label">常考</span>
                <span v-for="topic in company.topTopics" :key="topic.tag" class="badge primary">
                  {{ topic.label }} · {{ topic.count }}
                </span>
                <span v-if="!company.topTopics.length" class="meta">暂无标签</span>
              </div>
              <div class="tag-line">
                <span class="tag-label">失分</span>
                <span v-for="tag in company.commonLossTags" :key="tag.tag" class="badge danger">
                  {{ tag.label }} · {{ tag.count }}
                </span>
                <span v-if="!company.commonLossTags.length" class="meta">暂无标签</span>
              </div>
              <span v-if="!company.sufficient" class="badge muted">样本不足</span>
            </article>
          </div>
          <p v-else class="empty-line">暂无公司维度数据。</p>
        </section>

        <section class="card">
          <div class="card-head">
            <h3>高频问题 Top 10</h3>
            <span class="meta">来自已确认复盘，可反哺下一次准备</span>
          </div>
          <ol v-if="data.topQuestions.length" class="question-list">
            <li v-for="item in data.topQuestions" :key="item.question">
              <span class="question-text">{{ item.question }}</span>
              <span class="meta">{{ item.askedCount }} 次 · 最近 {{ formatTime(item.lastAskedAt) }}</span>
            </li>
          </ol>
          <p v-else class="empty-line">暂无问题记录。</p>
        </section>

        <section class="card">
          <div class="card-head">
            <h3>跨场趋势</h3>
            <span class="badge" :class="data.trend.sufficient ? 'success' : 'muted'">
              {{ data.trend.sufficient ? '样本充足' : '样本不足' }}
            </span>
          </div>
          <p class="meta">
            当前窗口 {{ data.trend.currentReviews }} 份已确认复盘 vs 上一窗口
            {{ data.trend.previousReviews }} 份 · 通过率差值 {{ pct(data.trend.passRateDelta) }}
          </p>
          <div v-if="data.trend.lossTagDeltas.length" class="delta-list">
            <div v-for="item in data.trend.lossTagDeltas" :key="item.tag" class="delta-row">
              <span>{{ item.label }}</span>
              <span class="meta">{{ item.previousCount }} → {{ item.currentCount }}</span>
              <strong :class="{ danger: item.delta > 0, success: item.delta < 0 }">
                {{ deltaText(item.delta) }}
              </strong>
            </div>
          </div>
          <p v-else class="empty-line">暂无可对比的失分类型。</p>
        </section>
      </template>

      <section class="card">
        <div class="card-head">
          <h3>投递与简历漏斗</h3>
          <span class="meta">基于所选窗口内创建的投递</span>
        </div>
        <div class="funnel">
          <div class="funnel-step">
            <strong>{{ data.funnel.applications }}</strong>
            <span>投递</span>
          </div>
          <span class="funnel-arrow">→</span>
          <div class="funnel-step">
            <strong>{{ data.funnel.interviews }}</strong>
            <span>进入面试</span>
          </div>
          <span class="funnel-arrow">→</span>
          <div class="funnel-step">
            <strong>{{ data.funnel.offers }}</strong>
            <span>拿到 Offer</span>
          </div>
        </div>
        <p class="meta">
          面试邀约率 {{ pct(data.funnel.interviewRate) }} · Offer 转化率
          {{ pct(data.funnel.offerRate) }} · 淘汰 {{ data.funnel.rejected }} · 撤回
          {{ data.funnel.withdrawn }}
        </p>
        <div v-if="data.resumeFunnel.length" class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>简历版本</th>
                <th>投递</th>
                <th>面试</th>
                <th>Offer</th>
                <th>邀约率</th>
                <th>通过率</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in data.resumeFunnel" :key="row.resumeId ?? 'none'">
                <td>
                  {{ row.fileName || '未指定简历' }}
                  <span v-if="!row.sufficient" class="badge muted">样本不足</span>
                </td>
                <td>{{ row.applications }}</td>
                <td>{{ row.interviews }}</td>
                <td>{{ row.offers }}</td>
                <td>{{ pct(row.interviewRate) }}</td>
                <td>{{ pct(row.passRate) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="empty-line">暂无投递记录。</p>
      </section>

      <section class="card">
        <div class="card-head">
          <h3>改进闭环</h3>
          <RouterLink to="/improvements" class="link">改进看板 →</RouterLink>
        </div>
        <div class="sample-grid compact">
          <div class="stat"><span>待开始</span><strong>{{ data.improvements.todo }}</strong></div>
          <div class="stat"><span>进行中</span><strong>{{ data.improvements.inProgress }}</strong></div>
          <div class="stat"><span>已验证</span><strong>{{ data.improvements.verified }}</strong></div>
          <div class="stat"><span>已放弃</span><strong>{{ data.improvements.dropped }}</strong></div>
        </div>
        <p class="meta">
          验证率 {{ pct(data.improvements.verificationRate) }} · 共 {{ data.improvements.total }} 条行动
        </p>
        <div v-if="data.improvements.staleActions.length" class="stale-list">
          <p class="tag-label">超过 14 天未验证</p>
          <div v-for="item in data.improvements.staleActions" :key="item.id" class="stale-row">
            <span>{{ item.action }}</span>
            <span class="badge muted">{{ ACTION_STATUS_TEXT[item.status] ?? item.status }}</span>
            <span class="meta">{{ formatTime(item.createdAt) }}</span>
          </div>
        </div>
        <p v-else class="empty-line">没有长期未验证的行动。</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  max-width: 1080px;
}

.page-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.lead {
  margin: 0;
  color: var(--text-2);
  font-size: 13px;
  max-width: 720px;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.btn {
  padding: var(--space-2) var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text-2);
  font-size: 13px;
}

.filters select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-size: 13px;
}

.sample-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--space-3);
}

.sample-grid.compact {
  grid-template-columns: repeat(auto-fit, minmax(100px, 1fr));
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--space-3) var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.stat span {
  color: var(--text-3);
  font-size: 12px;
}

.stat strong {
  font-size: 22px;
  font-weight: 600;
}

.banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3);
  border-radius: var(--radius-btn);
  font-size: 13px;
}

.banner.warning {
  background: var(--warning-soft);
  color: var(--warning);
}

.banner.success {
  background: var(--success-soft);
  color: var(--success);
}

.banner.danger {
  background: var(--danger-soft);
  color: var(--danger);
}

.card {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-4);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.card h3 {
  margin: 0;
  font-size: 14px;
}

.summary-text {
  margin: 0;
  color: var(--text-2);
  font-size: 13.5px;
  line-height: 1.7;
}

.metric-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.metric-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.metric-label {
  flex: 1 1 260px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  font-size: 13px;
}

.bar {
  flex: 1 1 180px;
  height: 8px;
  border-radius: 999px;
  background: var(--surface-muted);
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 999px;
}

.bar-fill.danger {
  background: var(--danger);
}

.bar-fill.warning {
  background: var(--warning);
}

.bar-fill.success {
  background: var(--success);
}

.metric-value {
  width: 56px;
  text-align: right;
  font-size: 13px;
}

.company-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.company {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface-muted);
}

.company-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
  font-size: 13px;
}

.tag-line {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.tag-label {
  margin: 0;
  color: var(--text-3);
  font-size: 12px;
}

.question-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin: 0;
  padding-left: var(--space-5);
  font-size: 13px;
}

.question-list li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}

.question-text {
  flex: 1 1 320px;
}

.funnel {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
}

.funnel-step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 96px;
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
}

.funnel-step strong {
  font-size: 20px;
}

.funnel-step span {
  color: var(--text-3);
  font-size: 12px;
}

.funnel-arrow {
  color: var(--text-3);
}

.table-wrap {
  overflow-x: auto;
}

.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
}

.table th,
.table td {
  padding: var(--space-2);
  border-bottom: 1px solid var(--border);
  text-align: left;
}

.table th {
  color: var(--text-3);
  font-weight: 500;
}

.delta-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.delta-row {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: 13px;
}

.delta-row > span:first-child {
  flex: 1 1 200px;
}

.danger {
  color: var(--danger);
}

.success {
  color: var(--success);
}

.stale-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.stale-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  font-size: 13px;
}

.meta {
  margin: 0;
  color: var(--text-3);
  font-size: 12px;
}

.empty-line {
  margin: 0;
  color: var(--text-3);
  font-size: 12.5px;
}

.badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: var(--radius-tag);
  font-size: 11px;
  background: var(--surface-muted);
  color: var(--text-3);
}

.badge.primary {
  background: var(--primary-soft);
  color: var(--primary);
}

.badge.success {
  background: var(--success-soft);
  color: var(--success);
}

.badge.danger {
  background: var(--danger-soft);
  color: var(--danger);
}

.badge.muted {
  background: var(--surface-muted);
  color: var(--text-3);
}

.skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.skeleton-row {
  height: 96px;
  border-radius: var(--radius-card);
  background: var(--surface-muted);
}

.link {
  color: var(--primary);
  font-size: 12px;
  text-decoration: none;
}
</style>
