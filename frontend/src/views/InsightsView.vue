<script setup lang="ts">
/**
 * 跨场洞察（F5）：知识点 × 结果、失分频次、轮次通过率、公司画像、高频问题、
 * 投递 / 简历漏斗、改进闭环与趋势。样本不足时只展示原始统计，不输出因果结论。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  getInsights,
  listInsightInterviewEvents,
  type InsightEventItem,
  type InsightsOverview,
} from '@/api/insights'
import { listCompanies, type Company } from '@/api/jobs'
import { getReviewTaxonomy, type ReviewTaxonomy } from '@/api/reviews'
import EmptyState from '@/components/EmptyState.vue'
import {
  INSIGHT_WINDOWS,
  barWidth,
  deltaText,
  pct,
  sampleHint,
  sampleText,
} from '@/utils/insights'
import { ACTION_STATUS_TEXT, INTERVIEW_RESULT_TEXT, ROUND_TYPE_TEXT } from '@/utils/review'

const router = useRouter()
const data = ref<InsightsOverview | null>(null)
const companies = ref<Company[]>([])
const loading = ref(true)
const error = ref('')
const days = ref(90)
const companyFilter = ref('')

const detailItems = ref<InsightEventItem[]>([])
const detailTotal = ref(0)
const detailPage = ref(1)
const detailSize = 8
const detailLoading = ref(false)
const detailError = ref('')
const detailResult = ref('')
const detailRoundType = ref('')
const detailTopic = ref('')
const taxonomy = ref<ReviewTaxonomy | null>(null)

const RESULT_TONE: Record<string, string> = {
  PASS: 'success',
  FAIL: 'danger',
  PENDING: 'muted',
}
const RESULT_OPTIONS = ['PASS', 'FAIL', 'PENDING']

const detailPages = computed(() => Math.max(1, Math.ceil(detailTotal.value / detailSize)))
/** 三层浏览：公司 → 岗位 → 轮次（当前页内分组，顺序沿用事件时间倒序）。 */
const detailGroups = computed(() => {
  const groups = new Map<string, { key: string; companyName: string; jobTitle: string; items: InsightEventItem[] }>()
  detailItems.value.forEach((item) => {
    const key = `${item.companyId ?? item.companyName ?? 0}-${item.jobPositionId ?? item.jobTitle ?? 0}`
    const group = groups.get(key) ?? {
      key,
      companyName: item.companyName ?? '未关联公司',
      jobTitle: item.jobTitle ?? '未命名岗位',
      items: [],
    }
    group.items.push(item)
    groups.set(key, group)
  })
  return [...groups.values()]
})

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
  void loadDetail(true)
}

async function loadDetail(reset = false): Promise<void> {
  if (reset) {
    detailPage.value = 1
  }
  detailLoading.value = true
  try {
    const result = await listInsightInterviewEvents({
      page: detailPage.value,
      size: detailSize,
      days: days.value,
      companyId: companyFilter.value ? Number(companyFilter.value) : null,
      result: detailResult.value || undefined,
      roundType: detailRoundType.value || undefined,
      topic: detailTopic.value || undefined,
    })
    detailItems.value = result.items
    detailTotal.value = result.total
    detailError.value = ''
  } catch (ex) {
    detailError.value = ex instanceof Error ? ex.message : '加载面试明细失败'
  } finally {
    detailLoading.value = false
  }
}

function goDetail(delta: number): void {
  const next = detailPage.value + delta
  if (next < 1 || next > detailPages.value) {
    return
  }
  detailPage.value = next
  void loadDetail()
}

async function loadCompanies(): Promise<void> {
  try {
    const page = await listCompanies('', 100)
    companies.value = page.items
  } catch {
    // 公司筛选失败不阻断洞察
  }
}

async function loadTaxonomy(): Promise<void> {
  try {
    taxonomy.value = await getReviewTaxonomy()
  } catch {
    // 词表加载失败时知识点筛选降级为无选项
  }
}

function formatTime(value: string | null | undefined): string {
  return value ? new Date(value).toLocaleDateString('zh-CN') : '—'
}

onMounted(() => {
  void load()
  void loadCompanies()
  void loadTaxonomy()
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
        <button type="button" class="btn ghost" @click="load">刷新</button>
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
          <strong class="numeric">{{ data.sample.applications }}</strong>
        </div>
        <div class="stat">
          <span>面试</span>
          <strong class="numeric">{{ data.sample.interviews }}</strong>
        </div>
        <div class="stat">
          <span>已确认复盘</span>
          <strong class="numeric">{{ data.sample.confirmedReviews }}</strong>
        </div>
        <div class="stat">
          <span>Offer</span>
          <strong class="numeric">{{ data.sample.offers }}</strong>
        </div>
      </div>

      <div class="banner" :class="data.sample.sufficient ? 'success' : 'warning'">
        <strong>{{ sampleText(data.sample) }}</strong>
        <span>{{ sampleHint(data.sample) }}</span>
      </div>

      <section class="card summary">
        <div class="card-head">
          <h3 class="card-title">归因总结</h3>
          <span class="tag">本地聚合 · 不调用 LLM</span>
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
            <h3 class="card-title">知识点 × 结果</h3>
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
              <strong class="metric-value numeric">{{ pct(item.failRate) }}</strong>
              <span v-if="!item.sufficient" class="tag">样本不足</span>
            </div>
          </div>
          <p v-else class="empty-line">暂无可聚合的知识点标签。</p>
        </section>

        <section class="card">
          <div class="card-head">
            <h3 class="card-title">失分类型频次</h3>
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
                  class="bar-fill chart"
                  :style="{ width: barWidth(item.count / maxLossCount) }"
                />
              </div>
              <strong class="metric-value numeric">{{ item.count }} 场</strong>
            </div>
          </div>
          <p v-else class="empty-line">暂无失分标签。</p>
        </section>

        <section class="card">
          <div class="card-head">
            <h3 class="card-title">轮次通过率</h3>
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
              <strong class="metric-value numeric">{{ pct(item.passRate) }}</strong>
              <span v-if="!item.sufficient" class="tag">样本不足</span>
            </div>
          </div>
          <p v-else class="empty-line">暂无面试轮次记录。</p>
        </section>

        <section class="card">
          <div class="card-head">
            <h3 class="card-title">公司 / 岗位画像</h3>
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
                <span v-for="topic in company.topTopics" :key="topic.tag" class="tag primary">
                  {{ topic.label }} · {{ topic.count }}
                </span>
                <span v-if="!company.topTopics.length" class="meta">暂无标签</span>
              </div>
              <div class="tag-line">
                <span class="tag-label">失分</span>
                <span v-for="tag in company.commonLossTags" :key="tag.tag" class="tag danger">
                  {{ tag.label }} · {{ tag.count }}
                </span>
                <span v-if="!company.commonLossTags.length" class="meta">暂无标签</span>
              </div>
              <span v-if="!company.sufficient" class="tag">样本不足</span>
            </article>
          </div>
          <p v-else class="empty-line">暂无公司维度数据。</p>
        </section>

        <section class="card">
          <div class="card-head">
            <h3 class="card-title">高频问题 Top 10</h3>
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
            <h3 class="card-title">跨场趋势</h3>
            <span class="tag" :class="data.trend.sufficient ? 'success' : ''">
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
          <h3 class="card-title">投递与简历漏斗</h3>
          <span class="meta">基于所选窗口内创建的投递</span>
        </div>
        <div class="funnel">
          <div class="funnel-row">
            <span class="funnel-label">投递</span>
            <div class="funnel-track"><div class="funnel-fill f1" style="width: 100%" /></div>
            <strong class="funnel-value numeric">{{ data.funnel.applications }}</strong>
          </div>
          <div class="funnel-row">
            <span class="funnel-label">进入面试</span>
            <div class="funnel-track">
              <div class="funnel-fill f2" :style="{ width: barWidth(data.funnel.interviewRate) }" />
            </div>
            <strong class="funnel-value numeric">{{ data.funnel.interviews }}</strong>
          </div>
          <div class="funnel-row">
            <span class="funnel-label">拿到 Offer</span>
            <div class="funnel-track">
              <div class="funnel-fill f3" :style="{ width: barWidth(data.funnel.offerRate) }" />
            </div>
            <strong class="funnel-value numeric">{{ data.funnel.offers }}</strong>
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
                  <span v-if="!row.sufficient" class="tag">样本不足</span>
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
          <h3 class="card-title">渠道 / 公司类型对比</h3>
          <span class="meta">公司类型取公司标签首项；投递少于 5 视为样本不足</span>
        </div>
        <div class="funnel-group">
          <h4>按渠道</h4>
          <div v-if="data.channels.length" class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>渠道</th>
                  <th>投递</th>
                  <th>面试</th>
                  <th>Offer</th>
                  <th>邀约率</th>
                  <th>Offer 率</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in data.channels" :key="`channel-${row.label}`">
                  <td>
                    {{ row.label }}
                    <span v-if="!row.sufficient" class="tag">样本不足</span>
                  </td>
                  <td>{{ row.applications }}</td>
                  <td>{{ row.interviews }}</td>
                  <td>{{ row.offers }}</td>
                  <td>{{ pct(row.interviewRate) }}</td>
                  <td>{{ pct(row.offerRate) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="empty-line">暂无渠道数据。</p>
        </div>
        <div class="funnel-group">
          <h4>按公司类型</h4>
          <div v-if="data.companyTypes.length" class="table-wrap">
            <table class="table">
              <thead>
                <tr>
                  <th>公司类型</th>
                  <th>投递</th>
                  <th>面试</th>
                  <th>Offer</th>
                  <th>邀约率</th>
                  <th>Offer 率</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in data.companyTypes" :key="`type-${row.label}`">
                  <td>
                    {{ row.label }}
                    <span v-if="!row.sufficient" class="tag">样本不足</span>
                  </td>
                  <td>{{ row.applications }}</td>
                  <td>{{ row.interviews }}</td>
                  <td>{{ row.offers }}</td>
                  <td>{{ pct(row.interviewRate) }}</td>
                  <td>{{ pct(row.offerRate) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="empty-line">暂无公司类型数据；可在岗位详情维护公司标签。</p>
        </div>
      </section>

      <section class="card">
        <div class="card-head">
          <h3 class="card-title">面试明细</h3>
          <span class="meta">按公司 → 岗位 → 轮次浏览，或按结果 / 轮次 / 知识点筛选</span>
        </div>
        <div class="filters detail-filters">
          <select v-model="detailResult" aria-label="结果筛选" @change="loadDetail(true)">
            <option value="">全部结果</option>
            <option v-for="result in RESULT_OPTIONS" :key="result" :value="result">
              {{ INTERVIEW_RESULT_TEXT[result] ?? result }}
            </option>
          </select>
          <select v-model="detailRoundType" aria-label="轮次类型筛选" @change="loadDetail(true)">
            <option value="">全部轮次</option>
            <option v-for="(text, key) in ROUND_TYPE_TEXT" :key="key" :value="key">{{ text }}</option>
          </select>
          <select v-model="detailTopic" aria-label="知识点筛选" @change="loadDetail(true)">
            <option value="">全部知识点</option>
            <option v-for="item in taxonomy?.topics ?? []" :key="item.key" :value="item.key">
              {{ item.label }}
            </option>
          </select>
          <button type="button" class="btn ghost" @click="loadDetail(true)">查询</button>
        </div>
        <div v-if="detailError" class="banner danger">{{ detailError }}</div>
        <div v-else-if="detailLoading" class="skeleton">
          <div class="skeleton-row" />
        </div>
        <p v-else-if="!detailItems.length" class="empty-line">所选条件下没有面试记录。</p>
        <div v-else class="detail-groups">
          <article v-for="group in detailGroups" :key="group.key" class="detail-group">
            <div class="detail-group-head">
              <strong>{{ group.companyName }}</strong>
              <span class="meta">{{ group.jobTitle }}</span>
            </div>
            <ul class="detail-rounds">
              <li v-for="item in group.items" :key="item.id" class="detail-round">
                <span class="tag">{{ ROUND_TYPE_TEXT[item.roundType] ?? item.roundType }}</span>
                <span>第 {{ item.roundNo }} 轮</span>
                <span class="status" :class="RESULT_TONE[item.result]">
                  {{ INTERVIEW_RESULT_TEXT[item.result] ?? item.result }}
                </span>
                <span class="meta">{{ formatTime(item.interviewTime) }}</span>
                <span v-for="topic in item.topics" :key="topic.tag" class="tag primary">
                  {{ topic.label }}
                </span>
                <RouterLink v-if="item.reviewSessionId" :to="`/reviews/${item.reviewSessionId}`" class="link">
                  复盘 →
                </RouterLink>
                <span v-else class="meta">未复盘</span>
              </li>
            </ul>
          </article>
        </div>
        <div v-if="detailPages > 1" class="pager">
          <button type="button" class="btn ghost" :disabled="detailPage <= 1" @click="goDetail(-1)">
            上一页
          </button>
          <span>{{ detailPage }} / {{ detailPages }}</span>
          <button
            type="button"
            class="btn ghost"
            :disabled="detailPage >= detailPages"
            @click="goDetail(1)"
          >
            下一页
          </button>
        </div>
      </section>

      <section class="card">
        <div class="card-head">
          <h3 class="card-title">改进闭环</h3>
          <RouterLink to="/improvements" class="link">改进看板 →</RouterLink>
        </div>
        <div class="sample-grid compact">
          <div class="stat"><span>待开始</span><strong class="numeric">{{ data.improvements.todo }}</strong></div>
          <div class="stat"><span>进行中</span><strong class="numeric">{{ data.improvements.inProgress }}</strong></div>
          <div class="stat"><span>已验证</span><strong class="numeric">{{ data.improvements.verified }}</strong></div>
          <div class="stat"><span>已放弃</span><strong class="numeric">{{ data.improvements.dropped }}</strong></div>
        </div>
        <p class="meta">
          验证率 {{ pct(data.improvements.verificationRate) }} · 共 {{ data.improvements.total }} 条行动
        </p>
        <div v-if="data.improvements.staleActions.length" class="stale-list">
          <p class="tag-label">超过 14 天未验证</p>
          <div v-for="item in data.improvements.staleActions" :key="item.id" class="stale-row">
            <span>{{ item.action }}</span>
            <span class="tag">{{ ACTION_STATUS_TEXT[item.status] ?? item.status }}</span>
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
  gap: var(--space-5);
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
  max-width: 680px;
  color: var(--text-2);
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

.filters select {
  height: 34px;
  padding: 0 var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-size: var(--fs-sm);
}

.detail-filters {
  margin-bottom: var(--space-3);
}

.funnel-group h4 {
  margin: 0 0 var(--space-2);
  font-size: 13px;
  font-weight: 500;
}

.funnel-group + .funnel-group {
  margin-top: var(--space-4);
}

.detail-groups {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.detail-group {
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.detail-group:first-child {
  padding-top: 0;
  border-top: none;
}

.detail-group-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-2);
  font-size: 13.5px;
}

.detail-rounds {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: var(--space-2) 0 0;
  padding: 0;
  list-style: none;
}

.detail-round {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-2);
  font-size: 12.5px;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  margin-top: var(--space-3);
  color: var(--text-3);
  font-size: var(--fs-sm);
}

/* 指标条：一个容器 + 竖分隔线，替代四张独立卡片 */
.sample-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  overflow: hidden;
}

.sample-grid .stat {
  padding: var(--space-4) var(--space-5);
  border: 0;
  border-left: 1px solid var(--border);
  border-radius: 0;
}

.sample-grid .stat:first-child {
  border-left: none;
}

.sample-grid.compact .stat {
  padding: var(--space-3) var(--space-4);
}

.stat span {
  color: var(--text-3);
  font-size: var(--fs-overline);
  font-weight: 500;
  letter-spacing: 0.06em;
}

.stat strong {
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.card-title {
  margin: 0;
}

.summary-text {
  margin: 0;
  color: var(--text-2);
  line-height: 1.7;
}

.metric-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
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
  font-size: 13.5px;
}

.bar {
  flex: 1 1 180px;
  height: 6px;
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

.bar-fill.chart {
  background: var(--chart-3);
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
  font-variant-numeric: tabular-nums;
}

.company-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

/* 公司画像不再套卡片，用顶部墨线分隔 */
.company {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3) 0;
  border-top: 1px solid var(--border);
}

.company-list .company:first-child {
  border-top: none;
  padding-top: 0;
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
  font-size: var(--fs-overline);
  font-weight: 500;
  letter-spacing: 0.06em;
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
  flex-direction: column;
  gap: 10px;
}

.funnel-row {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) 56px;
  align-items: center;
  gap: var(--space-3);
}

.funnel-label {
  font-size: 12.5px;
  color: var(--text-2);
}

.funnel-track {
  height: 8px;
  border-radius: 999px;
  background: var(--surface-muted);
  overflow: hidden;
}

.funnel-fill {
  height: 100%;
  border-radius: 999px;
}

.funnel-fill.f1 {
  background: var(--chart-1);
}

.funnel-fill.f2 {
  background: var(--chart-2);
}

.funnel-fill.f3 {
  background: var(--chart-3);
}

.funnel-value {
  text-align: right;
  font-size: 13px;
}

.table-wrap {
  overflow-x: auto;
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
  font-size: var(--fs-sm);
}

.skeleton-row {
  height: 96px;
}
</style>
