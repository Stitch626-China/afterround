<script setup lang="ts">
/**
 * Offer 决策（F6）：录入 / 文本图片提取 / 保留淘汰分组 / 固定维度对比 / 前景分析。
 * 对比矩阵的行由后端固定返回，前端只按顺序渲染，保证同一行永远比同一项。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  analyzeOffer,
  createOffer,
  deleteOffer,
  extractOffer,
  getOffer,
  getOfferComparison,
  listOffers,
  updateOffer,
  type Offer,
  type OfferComparison,
  type OfferDetail,
  type OfferGroup,
} from '@/api/offers'
import { listCompanies, listJobPositions, type Company, type JobPositionListItem } from '@/api/jobs'
import { listJobApplications, type JobApplicationListItem } from '@/api/applications'
import EmptyState from '@/components/EmptyState.vue'
import {
  emptyOfferForm,
  extractionToForm,
  formToPayload,
  groupText,
  offerToForm,
  salaryText,
  type OfferFormState,
} from '@/utils/offer'

const tab = ref<'list' | 'compare'>('list')
const offers = ref<Offer[]>([])
const total = ref(0)
const page = ref(1)
const size = 20
const groupFilter = ref<OfferGroup | ''>('')
const loading = ref(true)
const error = ref('')
const notice = ref('')
const companies = ref<Company[]>([])
const jobs = ref<JobPositionListItem[]>([])
const applications = ref<JobApplicationListItem[]>([])

const formOpen = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive<OfferFormState>(emptyOfferForm())
const extractText = ref('')
const extractFile = ref<File | null>(null)
const extractInput = ref<HTMLInputElement | null>(null)
const extracting = ref(false)

const detailId = ref<number | null>(null)
const detail = ref<OfferDetail | null>(null)
const detailLoading = ref(false)
const analyzing = ref(false)
const webSearch = ref(false)
const deletingId = ref<number | null>(null)
const movingId = ref<number | null>(null)

const comparison = ref<OfferComparison | null>(null)
const comparisonLoading = ref(false)

const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const keepCount = computed(() => offers.value.filter((item) => item.group === 'KEEP').length)
const formTitle = computed(() => (editingId.value ? '编辑 Offer' : '新增 Offer'))

const TENDENCY_TEXT: Record<string, string> = {
  ACCEPT: '值得接受',
  CONFIRM: '需要确认',
  DECLINE: '建议放弃',
}
const TENDENCY_TONE: Record<string, string> = {
  ACCEPT: 'success',
  CONFIRM: 'warning',
  DECLINE: 'danger',
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listOffers({ page: page.value, size, group: groupFilter.value || undefined })
    offers.value = result.items
    total.value = result.total
    error.value = ''
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadOptions(): Promise<void> {
  try {
    const [companyPage, jobPage, applicationPage] = await Promise.all([
      listCompanies('', 100),
      listJobPositions({ page: 1, size: 100 }),
      listJobApplications({ page: 1, size: 100 }),
    ])
    companies.value = companyPage.items
    jobs.value = jobPage.items
    applications.value = applicationPage.items
  } catch {
    // 选项加载失败不阻断列表；保存时会提示未选择公司/岗位
  }
}

function search(): void {
  page.value = 1
  void load()
}

function reset(): void {
  groupFilter.value = ''
  search()
}

function go(delta: number): void {
  const next = page.value + delta
  if (next < 1 || next > pages.value) {
    return
  }
  page.value = next
  void load()
}

function openCreate(): void {
  Object.assign(form, emptyOfferForm())
  editingId.value = null
  extractText.value = ''
  extractFile.value = null
  formOpen.value = true
  error.value = ''
  notice.value = ''
}

function openEdit(offer: Offer): void {
  Object.assign(form, offerToForm(offer))
  editingId.value = offer.id
  extractText.value = ''
  extractFile.value = null
  formOpen.value = true
  detailId.value = null
  error.value = ''
  notice.value = ''
}

function closeForm(): void {
  formOpen.value = false
  editingId.value = null
}

function invalidNumber(value: string | number, min: number, max: number): boolean {
  const raw = String(value).trim()
  if (!raw) {
    return false
  }
  const parsed = Number(raw)
  return !Number.isFinite(parsed) || parsed < min || parsed > max
}

async function save(): Promise<void> {
  if (!form.companyId && !form.jobPositionId) {
    error.value = '公司与岗位至少选择一个'
    return
  }
  if (invalidNumber(form.monthlySalary, 0, 10_000_000)
    || invalidNumber(form.baseSalary, 0, 10_000_000)) {
    error.value = '月薪 / 底薪需在 0 - 10000000 元之间'
    return
  }
  if (invalidNumber(form.performanceRatio, 0, 100)) {
    error.value = '绩效占比需在 0 - 100 之间'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const payload = formToPayload(form)
    if (editingId.value) {
      await updateOffer(editingId.value, payload)
      notice.value = 'Offer 已更新'
    } else {
      await createOffer(payload)
      notice.value = 'Offer 已保存'
    }
    closeForm()
    await load()
    if (tab.value === 'compare') {
      await loadComparison()
    }
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '保存失败'
  } finally {
    saving.value = false
  }
}

function pickExtractFile(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) {
    return
  }
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) {
    error.value = '仅支持 PNG / JPEG / WEBP 图片'
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    error.value = '图片不能超过 5MB'
    return
  }
  extractFile.value = file
  error.value = ''
}

async function extract(): Promise<void> {
  if (!extractText.value.trim() && !extractFile.value) {
    error.value = '请粘贴 Offer 文本或选择截图'
    return
  }
  extracting.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await extractOffer({
      text: extractText.value.trim() || undefined,
      file: extractFile.value ?? undefined,
    })
    Object.assign(form, extractionToForm(result, form))
    const company = companies.value.find((item) => item.name === result.companyName?.trim())
    if (company) {
      form.companyId = String(company.id)
    }
    const job = jobs.value.find((item) => item.title === result.jobPositionTitle?.trim())
    if (job) {
      form.jobPositionId = String(job.id)
      if (job.companyId) {
        form.companyId = String(job.companyId)
      }
    }
    const matched = Boolean(company || job)
    notice.value = matched
      ? '已提取字段并匹配到已有公司 / 岗位，请核对后保存'
      : '已提取字段；公司 / 岗位未匹配，请手动选择后保存'
    extractText.value = ''
    extractFile.value = null
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '提取失败'
  } finally {
    extracting.value = false
  }
}

async function move(offer: Offer, group: OfferGroup): Promise<void> {
  movingId.value = offer.id
  error.value = ''
  try {
    await updateOffer(offer.id, { group })
    notice.value = group === 'KEEP' ? '已移入保留组' : '已移入淘汰组'
    await load()
    if (tab.value === 'compare') {
      await loadComparison()
    }
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '移动失败'
  } finally {
    movingId.value = null
  }
}

async function remove(offer: Offer): Promise<void> {
  error.value = ''
  try {
    await deleteOffer(offer.id)
    deletingId.value = null
    if (detailId.value === offer.id) {
      detailId.value = null
      detail.value = null
    }
    if (offers.value.length === 1 && page.value > 1) {
      page.value -= 1
    }
    notice.value = 'Offer 已删除'
    await load()
    if (tab.value === 'compare') {
      await loadComparison()
    }
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '删除失败'
  }
}

async function toggleDetail(offer: Offer): Promise<void> {
  if (detailId.value === offer.id) {
    detailId.value = null
    detail.value = null
    return
  }
  detailId.value = offer.id
  detail.value = null
  detailLoading.value = true
  error.value = ''
  try {
    detail.value = await getOffer(offer.id)
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '详情加载失败'
  } finally {
    detailLoading.value = false
  }
}

async function runAnalysis(): Promise<void> {
  if (!detailId.value) {
    return
  }
  analyzing.value = true
  error.value = ''
  try {
    const result = await analyzeOffer(detailId.value, webSearch.value)
    if (detail.value) {
      detail.value = { ...detail.value, analysis: result }
    }
    notice.value = '分析完成，结果已保存'
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '分析失败'
  } finally {
    analyzing.value = false
  }
}

async function loadComparison(): Promise<void> {
  comparisonLoading.value = true
  try {
    comparison.value = await getOfferComparison()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '对比加载失败'
  } finally {
    comparisonLoading.value = false
  }
}

async function switchTab(next: 'list' | 'compare'): Promise<void> {
  tab.value = next
  if (next === 'compare') {
    await loadComparison()
  }
}

function jobLabel(offer: Offer): string {
  return [offer.companyName, offer.jobPositionTitle].filter(Boolean).join(' · ') || '公司 / 岗位未填写'
}

function formatTime(value: string): string {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

onMounted(() => {
  void loadOptions()
  void load()
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="lead">
        记录多个 Offer 的薪资、五险一金与福利，保留组最多 4 个参与同维度对比；前景分析默认只基于你提供的资料。
      </p>
      <button type="button" class="btn" :class="formOpen ? 'ghost' : 'primary'" @click="openCreate">
        新增 Offer
      </button>
    </header>

    <nav class="tabs">
      <button type="button" :class="{ active: tab === 'list' }" @click="switchTab('list')">
        Offer 列表
      </button>
      <button type="button" :class="{ active: tab === 'compare' }" @click="switchTab('compare')">
        保留组对比
      </button>
    </nav>

    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-else-if="notice" class="banner success">{{ notice }}</div>

    <template v-if="tab === 'list'">
      <section v-if="formOpen" class="form-card">
        <div class="form-head">
          <h3>{{ formTitle }}</h3>
          <span class="hint">字段留空会按「未填写」参与对比；更新时清空即删除该字段。</span>
        </div>

        <section class="extract">
          <div class="extract-head">
            <strong>从文本 / 图片提取</strong>
            <span class="hint">提取只填充表单，不会直接保存</span>
          </div>
          <textarea
            v-model="extractText"
            rows="3"
            placeholder="粘贴 Offer 文本，如：字节跳动 Java 后端，月薪 30K，双休，年终 3 个月…"
          />
          <div class="extract-actions">
            <input
              ref="extractInput"
              type="file"
              accept="image/png,image/jpeg,image/webp"
              hidden
              @change="pickExtractFile"
            />
            <button type="button" class="btn ghost" @click="extractInput?.click()">
              {{ extractFile ? extractFile.name : '选择截图' }}
            </button>
            <button type="button" class="btn ghost" :disabled="extracting" @click="extract">
              {{ extracting ? '提取中…' : '提取字段' }}
            </button>
            <button v-if="extractFile" type="button" class="link" @click="extractFile = null">
              移除截图
            </button>
          </div>
        </section>

        <div class="form-grid">
          <label>
            <span>公司</span>
            <select v-model="form.companyId">
              <option value="">未选择</option>
              <option v-for="item in companies" :key="item.id" :value="String(item.id)">
                {{ item.name }}
              </option>
            </select>
          </label>
          <label>
            <span>岗位</span>
            <select v-model="form.jobPositionId">
              <option value="">未选择</option>
              <option v-for="item in jobs" :key="item.id" :value="String(item.id)">
                {{ item.title }}{{ item.companyName ? ` · ${item.companyName}` : '' }}
              </option>
            </select>
          </label>
          <label>
            <span>关联投递（可选）</span>
            <select v-model="form.applicationId">
              <option value="">不关联</option>
              <option v-for="item in applications" :key="item.id" :value="String(item.id)">
                {{ item.companyName || '公司未填' }} · {{ item.jobTitle || '岗位未填' }} ·
                {{ item.appliedAt.slice(0, 10) }}
              </option>
            </select>
          </label>
          <label>
            <span>月薪（元/月）</span>
            <input v-model="form.monthlySalary" type="number" min="0" placeholder="30000" />
          </label>
          <label>
            <span>底薪（元/月）</span>
            <input v-model="form.baseSalary" type="number" min="0" placeholder="24000" />
          </label>
          <label>
            <span>绩效占比（%）</span>
            <input v-model="form.performanceRatio" type="number" min="0" max="100" placeholder="20" />
          </label>
          <label>
            <span>是否双休</span>
            <select v-model="form.twoDayWeekend">
              <option value="UNSET">未填写</option>
              <option value="YES">双休</option>
              <option value="NO">非双休</option>
            </select>
          </label>
          <label>
            <span>五险一金基数</span>
            <input v-model="form.socialInsuranceBase" placeholder="如 12000 或 按最低基数" />
          </label>
          <label>
            <span>五险一金比例</span>
            <input v-model="form.socialInsuranceRatio" placeholder="如 10.5%" />
          </label>
          <label>
            <span>公积金基数</span>
            <input v-model="form.housingFundBase" placeholder="如 12000" />
          </label>
          <label>
            <span>公积金比例</span>
            <input v-model="form.housingFundRatio" placeholder="如 12%" />
          </label>
          <label>
            <span>上下班时间</span>
            <input v-model="form.workHours" placeholder="如 10:00-19:00" />
          </label>
          <label>
            <span>年终奖</span>
            <input v-model="form.annualBonus" placeholder="如 3 个月" />
          </label>
          <label>
            <span>季度奖</span>
            <input v-model="form.quarterlyBonus" placeholder="如 0.5 个月" />
          </label>
          <label>
            <span>分组</span>
            <select v-model="form.group">
              <option value="KEEP">保留组</option>
              <option value="DROP">淘汰组</option>
            </select>
          </label>
          <label class="wide">
            <span>福利（用逗号分隔）</span>
            <input v-model="form.benefits" placeholder="餐补、房补、免费班车" />
          </label>
          <label class="wide">
            <span>各项补贴（用逗号分隔）</span>
            <input v-model="form.allowances" placeholder="交通补贴、通讯补贴" />
          </label>
          <label class="wide">
            <span>备注</span>
            <input v-model="form.remark" maxlength="500" placeholder="如：团队氛围、面试感受、待确认事项" />
          </label>
        </div>

        <div class="form-actions">
          <button type="button" class="btn primary" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存 Offer' }}
          </button>
          <button type="button" class="btn ghost" @click="closeForm">取消</button>
        </div>
      </section>

      <div class="filters">
        <select v-model="groupFilter" @change="search">
          <option value="">全部分组</option>
          <option value="KEEP">保留组</option>
          <option value="DROP">淘汰组</option>
        </select>
        <span class="hint">当前页保留组 {{ keepCount }} / 4</span>
        <button type="button" class="btn ghost" @click="search">查询</button>
        <button type="button" class="btn ghost" @click="reset">重置</button>
      </div>

      <section v-if="loading" class="skeleton">
        <div class="skeleton-row" />
        <div class="skeleton-row" />
      </section>

      <EmptyState
        v-else-if="!offers.length"
        title="还没有 Offer"
        hint="录入公司、岗位与薪资条款，或粘贴 Offer 文本 / 截图自动提取；保留组最多 4 个参与对比。"
        action-label="新增 Offer"
        @action="openCreate"
      />

      <template v-else>
        <ul class="list">
          <li v-for="item in offers" :key="item.id" class="offer-row">
            <div class="offer-main">
              <div class="row-title-line">
                <strong class="row-title">{{ jobLabel(item) }}</strong>
                <span class="status" :class="item.group === 'KEEP' ? 'success' : 'muted'">
                  {{ groupText(item.group) }}
                </span>
              </div>
              <p class="row-salary">{{ salaryText(item) }}</p>
              <p class="row-meta">
                双休：{{ item.twoDayWeekend == null ? '未填写' : item.twoDayWeekend ? '是' : '否' }}
                · 更新时间 {{ formatTime(item.updatedAt) }}
              </p>
              <p v-if="item.remark" class="row-remark">备注：{{ item.remark }}</p>
            </div>
            <div class="row-actions">
              <button type="button" class="link" @click="toggleDetail(item)">
                {{ detailId === item.id ? '收起分析' : '分析' }}
              </button>
              <button type="button" class="link" @click="openEdit(item)">编辑</button>
              <button
                v-if="item.group === 'KEEP'"
                type="button"
                class="link warning"
                :disabled="movingId === item.id"
                @click="move(item, 'DROP')"
              >
                移入淘汰
              </button>
              <button
                v-else
                type="button"
                class="link"
                :disabled="movingId === item.id"
                @click="move(item, 'KEEP')"
              >
                移入保留
              </button>
              <template v-if="deletingId === item.id">
                <button type="button" class="link danger" @click="remove(item)">确认删除</button>
                <button type="button" class="link" @click="deletingId = null">取消</button>
              </template>
              <button v-else type="button" class="link danger" @click="deletingId = item.id">
                删除
              </button>
            </div>

            <section v-if="detailId === item.id" class="analysis">
              <p v-if="detailLoading" class="hint">正在加载分析…</p>
              <template v-else>
                <div class="analysis-actions">
                  <label class="check">
                    <input v-model="webSearch" type="checkbox" />
                    联网搜索（需当前模型支持）
                  </label>
                  <button type="button" class="btn primary" :disabled="analyzing" @click="runAnalysis">
                    {{ analyzing ? '分析中…' : detail?.analysis ? '重新分析' : '生成前景分析' }}
                  </button>
                </div>

                <template v-if="detail?.analysis">
                  <div class="analysis-head">
                    <span class="tag" :class="TENDENCY_TONE[detail.analysis.tendency]">
                      {{ TENDENCY_TEXT[detail.analysis.tendency] ?? detail.analysis.tendency }}
                    </span>
                    <span class="hint">
                      {{ detail.analysis.basedOnProvidedMaterials ? '基于所给资料' : '已开启联网' }}
                      · {{ detail.analysis.llmProvider }} / {{ detail.analysis.llmModel }}
                    </span>
                  </div>
                  <div class="analysis-grid">
                    <div>
                      <h4>倾向理由</h4>
                      <ul>
                        <li v-for="(reason, index) in detail.analysis.reasons" :key="`r-${index}`">
                          {{ reason }}
                        </li>
                      </ul>
                    </div>
                    <div>
                      <h4>风险与待确认</h4>
                      <ul>
                        <li v-for="(risk, index) in detail.analysis.risks" :key="`k-${index}`">
                          {{ risk }}
                        </li>
                      </ul>
                    </div>
                  </div>
                  <p class="prospect">{{ detail.analysis.prospect }}</p>
                  <p v-if="detail.analysis.sources.length" class="hint">
                    来源：{{ detail.analysis.sources.join('；') }}
                  </p>
                </template>
                <p v-else class="hint">尚未分析。默认只基于你提供的公司与 Offer 资料，不编造外部事实。</p>
              </template>
            </section>
          </li>
        </ul>

        <footer v-if="total > size" class="pager">
          <span>共 {{ total }} 个 Offer · 第 {{ page }} / {{ pages }} 页</span>
          <div class="pager-actions">
            <button type="button" class="btn ghost" :disabled="page <= 1" @click="go(-1)">上一页</button>
            <button type="button" class="btn ghost" :disabled="page >= pages" @click="go(1)">下一页</button>
          </div>
        </footer>
      </template>
    </template>

    <template v-else>
      <section v-if="comparisonLoading" class="skeleton">
        <div class="skeleton-row" />
      </section>
      <EmptyState
        v-else-if="!comparison || !comparison.columns.length"
        title="保留组还没有 Offer"
        hint="把想比较的 Offer 移入保留组（最多 4 个），即可按固定维度横向对比。"
        action-label="回到列表"
        @action="switchTab('list')"
      />
      <section v-else class="compare">
        <table>
          <thead>
            <tr>
              <th class="dim">对比维度</th>
              <th v-for="column in comparison.columns" :key="column.offerId">
                <strong>{{ column.companyName || '公司未填写' }}</strong>
                <span>{{ column.jobPositionTitle || '岗位未填写' }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in comparison.rows" :key="row.key">
              <th class="dim">{{ row.label }}</th>
              <td v-for="(value, index) in row.values" :key="index">
                {{ value }}
              </td>
            </tr>
          </tbody>
        </table>
        <p class="hint">缺失字段统一显示「未填写」；行顺序固定，不会因某个 Offer 缺字段而错位。</p>
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

.lead {
  margin: 0;
  max-width: 680px;
  color: var(--text-2);
}

.tabs {
  display: flex;
  gap: var(--space-1);
  border-bottom: 1px solid var(--border);
}

.tabs button {
  position: relative;
  padding: var(--space-2) var(--space-3);
  border: none;
  background: none;
  color: var(--text-3);
  font-size: 13px;
}

.tabs button.active {
  color: var(--text);
  font-weight: 500;
}

.tabs button.active::after {
  content: '';
  position: absolute;
  left: var(--space-3);
  right: var(--space-3);
  bottom: -1px;
  height: 2px;
  border-radius: 1px;
  background: var(--primary);
}

.hint {
  margin: 0;
  color: var(--text-3);
  font-size: var(--fs-sm);
}

.form-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  padding: var(--space-5);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

.form-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--space-3);
}

.form-head h3 {
  font-size: var(--fs-h2);
  font-weight: 600;
}

/* 提取区是一段浅底工具带，不再画第二层虚线卡片 */
.extract {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding: var(--space-3);
  background: var(--surface-muted);
  border-radius: var(--radius-btn);
}

.extract-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--space-2);
  font-size: var(--fs-sm);
}

.extract textarea {
  width: 100%;
  resize: vertical;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: var(--fs-sm);
}

.extract-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: var(--space-3);
}

.form-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.form-grid label.wide {
  grid-column: 1 / -1;
}

.form-grid span {
  color: var(--text-3);
  font-size: var(--fs-overline);
  font-weight: 500;
  letter-spacing: 0.04em;
}

.form-grid input,
.form-grid select,
.filters select {
  height: 34px;
  padding: 0 var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: var(--fs-sm);
}

.form-grid input:focus-visible,
.form-grid select:focus-visible,
.filters select:focus-visible,
.extract textarea:focus-visible {
  outline: 1px solid var(--primary);
  outline-offset: 0;
  box-shadow: 0 0 0 3px var(--primary-soft);
}

.form-actions {
  display: flex;
  gap: var(--space-2);
}

.filters {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-3);
}

.skeleton-row {
  height: 84px;
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  overflow: hidden;
}

.offer-row {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: start;
  gap: var(--space-3);
  padding: 12px 20px;
  border-bottom: 1px solid var(--border);
  transition: background var(--dur-1) var(--ease);
}

.offer-row:last-child {
  border-bottom: none;
}

.offer-row:hover {
  background: var(--surface-muted);
}

.offer-main {
  min-width: 0;
}

.row-title-line {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.row-title {
  font-size: 13.5px;
  font-weight: 500;
}

.row-salary {
  margin: 3px 0 0;
  color: var(--text);
  font-size: var(--fs-sm);
}

.row-meta {
  margin: 3px 0 0;
  color: var(--text-3);
  font-size: 12px;
}

.row-remark {
  margin: 3px 0 0;
  color: var(--text-2);
  font-size: 12px;
}

.row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  justify-content: flex-end;
  max-width: 360px;
}

.link.warning {
  color: var(--warning);
}

/* 分析区是行内展开的浅底带，不画第二层卡片 */
.analysis {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  margin-top: var(--space-2);
  padding: var(--space-4);
  background: var(--surface-muted);
  border-radius: var(--radius-btn);
}

.analysis-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.check {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-sm);
  color: var(--text-2);
}

.analysis-head {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.analysis-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
}

.analysis-grid h4 {
  margin: 0 0 var(--space-1);
  font-size: 13px;
  font-weight: 600;
}

.analysis-grid ul {
  margin: 0;
  padding-left: 18px;
  color: var(--text-2);
  font-size: var(--fs-sm);
  line-height: 1.8;
}

.prospect {
  margin: 0;
  color: var(--text-2);
  font-size: var(--fs-sm);
  line-height: 1.8;
}

.pager-actions {
  display: flex;
  gap: var(--space-2);
}

.compare {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-5);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  overflow-x: auto;
}

.compare table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

.compare th,
.compare td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  vertical-align: top;
}

.compare thead th {
  color: var(--text-2);
  font-weight: 500;
  white-space: nowrap;
}

.compare thead th strong {
  font-weight: 600;
}

.compare thead th span {
  display: block;
  color: var(--text-3);
  font-size: var(--fs-xs);
  font-weight: 400;
}

.compare .dim {
  width: 130px;
  color: var(--text-3);
  font-size: var(--fs-overline);
  font-weight: 500;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

@media (max-width: 760px) {
  .page-head,
  .form-head,
  .analysis-actions {
    flex-direction: column;
    align-items: flex-start;
  }

  .offer-row {
    grid-template-columns: 1fr;
  }

  .row-actions {
    justify-content: flex-start;
    max-width: none;
  }

  .analysis-grid {
    grid-template-columns: 1fr;
  }
}
</style>
