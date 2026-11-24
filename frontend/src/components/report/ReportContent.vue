<script setup lang="ts">
/**
 * 分析报告内容（纯展示）：供真实报告页与开发预览共用。
 * 传入 salaryEditable 时可在基本信息区修改薪资，用户值优先于 LLM 提取值。
 */
import { ref, watch } from 'vue'
import type { ResumeAnalysisReport } from '@/api/resumes'
import ScoreBar from './ScoreBar.vue'

const props = defineProps<{
  report: ResumeAnalysisReport
  salaryEditable?: boolean
  saving?: boolean
}>()

const emit = defineEmits<{
  (event: 'save-salary', payload: { currentMonthlySalary: number | null; expectedMonthlySalary: number | null }): void
}>()

const editingSalary = ref(false)
const currentInput = ref<number | string | null>(null)
const expectedInput = ref<number | string | null>(null)

function years(value?: number | null): string {
  return value == null ? '—' : `${value} 年`
}

function money(value?: number | null): string {
  return value == null ? '未填写' : `¥${value.toLocaleString('zh-CN')}/月`
}

/** 空输入或非法值统一转为 null（后端按「未填写」处理）。 */
function normalize(value: number | string | null | undefined): number | null {
  if (value === '' || value == null) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.max(0, Math.trunc(parsed)) : null
}

function beginEditSalary(): void {
  currentInput.value = props.report.basicInfo?.currentMonthlySalary ?? null
  expectedInput.value = props.report.basicInfo?.expectedMonthlySalary ?? null
  editingSalary.value = true
}

function submitSalary(): void {
  emit('save-salary', {
    currentMonthlySalary: normalize(currentInput.value),
    expectedMonthlySalary: normalize(expectedInput.value),
  })
}

// 保存结束（无论成败）回到展示态；失败信息由父组件错误横幅承担。
watch(() => props.saving, (now, before) => {
  if (before && !now) {
    editingSalary.value = false
  }
})
</script>

<template>
  <div class="report">
    <!-- 总分 + 五维：总分是整页唯一的主数字 -->
    <section class="card overview">
      <div class="overall">
        <p class="overline">综合评分</p>
        <div class="overall-score num-hero">{{ report.overallScore }}</div>
        <small class="overall-note">满分 100 · 五维加权</small>
      </div>
      <div class="dimensions">
        <ScoreBar
          v-for="dimension in report.dimensions"
          :key="dimension.key"
          :label="dimension.label"
          :score="dimension.score"
          :weight="dimension.weight"
        />
      </div>
    </section>

    <!-- 基本信息 -->
    <section v-if="report.basicInfo" class="card basic">
      <div class="basic-item">
        <span class="basic-label">姓名</span>
        <span>{{ report.basicInfo.name || '—' }}</span>
      </div>
      <div class="basic-item">
        <span class="basic-label">工作年限</span>
        <span class="numeric">{{ years(report.basicInfo.yearsOfExperience) }}</span>
      </div>
      <div class="basic-item">
        <span class="basic-label">技术方向</span>
        <span>{{ report.basicInfo.techDirection || '—' }}</span>
      </div>
      <div class="basic-item">
        <span class="basic-label">当前月薪</span>
        <span class="numeric">{{ money(report.basicInfo.currentMonthlySalary) }}</span>
      </div>
      <div class="basic-item">
        <span class="basic-label">期望月薪</span>
        <span class="numeric">{{ money(report.basicInfo.expectedMonthlySalary) }}</span>
      </div>

      <div v-if="salaryEditable" class="salary-actions">
        <template v-if="editingSalary">
          <label class="basic-item">
            <span class="basic-label">当前月薪（元）</span>
            <input
              v-model.number="currentInput"
              class="salary-input numeric"
              type="number"
              min="0"
              max="1000000"
              :disabled="saving"
            />
          </label>
          <label class="basic-item">
            <span class="basic-label">期望月薪（元）</span>
            <input
              v-model.number="expectedInput"
              class="salary-input numeric"
              type="number"
              min="0"
              max="1000000"
              :disabled="saving"
            />
          </label>
          <button type="button" class="btn primary quiet" :disabled="saving" @click="submitSalary">
            {{ saving ? '保存中…' : '保存' }}
          </button>
          <button type="button" class="btn ghost quiet" :disabled="saving" @click="editingSalary = false">取消</button>
        </template>
        <button v-else type="button" class="link" @click="beginEditSalary">修改薪资</button>
      </div>
    </section>

    <!-- 优势 / 短板 -->
    <div class="columns">
      <section class="card">
        <h3 class="card-title">优势</h3>
        <ul v-if="report.strengths?.length" class="quote-list">
          <li v-for="(item, index) in report.strengths" :key="index" class="quote">
            <p class="quote-text">“{{ item.quote }}”</p>
            <p class="quote-comment">{{ item.comment }}</p>
          </li>
        </ul>
        <p v-else class="muted">暂无</p>
      </section>

      <section class="card">
        <h3 class="card-title">短板</h3>
        <ul v-if="report.weaknesses?.length" class="quote-list">
          <li v-for="(item, index) in report.weaknesses" :key="index" class="quote">
            <p class="quote-text">“{{ item.quote }}”</p>
            <p class="quote-comment">{{ item.comment }}</p>
          </li>
        </ul>
        <p v-else class="muted">暂无</p>
      </section>
    </div>

    <!-- 优化建议 -->
    <section class="card">
      <h3 class="card-title">优化建议</h3>
      <ul v-if="report.suggestions?.length" class="suggestions">
        <li v-for="(item, index) in report.suggestions" :key="index" class="suggestion">
          <div class="suggestion-row">
            <span class="suggestion-tag">原句</span>
            <p class="original">{{ item.original }}</p>
          </div>
          <div class="suggestion-row">
            <span class="suggestion-tag improved-tag">优化</span>
            <p class="improved">{{ item.improved }}</p>
          </div>
          <p v-if="item.reason" class="reason">{{ item.reason }}</p>
        </li>
      </ul>
      <p v-else class="muted">暂无</p>
    </section>

    <!-- 岗位方向推荐 -->
    <section class="card">
      <h3 class="card-title">岗位方向推荐</h3>
      <div v-if="report.careerDirections?.length" class="directions">
        <article v-for="(item, index) in report.careerDirections" :key="index" class="direction">
          <h4 class="direction-title">{{ item.direction }}</h4>
          <p class="direction-reason">{{ item.matchReason }}</p>
          <div v-if="item.skillGaps?.length" class="tag-group">
            <span class="tag-label">技能差距</span>
            <span v-for="gap in item.skillGaps" :key="gap" class="tag gap">{{ gap }}</span>
          </div>
          <div v-if="item.interviewFocus?.length" class="tag-group">
            <span class="tag-label">面试重点</span>
            <span v-for="focus in item.interviewFocus" :key="focus" class="tag focus">{{ focus }}</span>
          </div>
        </article>
      </div>
      <p v-else class="muted">暂无</p>
    </section>
  </div>
</template>

<style scoped>
.report {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.card-title {
  margin-bottom: var(--space-3);
  font-size: var(--fs-h2);
  font-weight: 500;
  color: var(--text);
}

/* 总分 */
.overview {
  display: grid;
  grid-template-columns: 190px 1fr;
  gap: var(--space-6);
  align-items: center;
}

.overall {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-right: var(--space-5);
  border-right: 1px solid var(--border);
}

.overall-score {
  color: var(--text);
}

.overall-note {
  color: var(--text-3);
  font-size: var(--fs-sm);
}

.dimensions {
  display: grid;
  gap: 14px;
}

/* 基本信息 */
.basic {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--space-4) var(--space-5);
}

.basic-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13.5px;
}

.basic-label {
  color: var(--text-3);
  font-size: var(--fs-overline);
  font-weight: 500;
  letter-spacing: 0.06em;
}

.salary-actions {
  grid-column: 1 / -1;
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  gap: var(--space-2);
}

.salary-input {
  width: 120px;
  height: 30px;
  padding: 0 8px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-size: 13px;
}

.salary-input:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 1px;
}

/* 两栏 */
.columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
}

.quote-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.quote {
  padding-left: var(--space-3);
  border-left: 2px solid var(--border-strong);
}

.quote-text {
  margin: 0 0 2px;
  font-size: 13px;
  color: var(--text);
}

.quote-comment {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-2);
}

/* 优化建议 */
.suggestions {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.suggestion + .suggestion {
  border-top: 1px solid var(--border);
  padding-top: var(--space-4);
}

.suggestion-row {
  display: flex;
  gap: var(--space-3);
  align-items: flex-start;
}

.suggestion-tag {
  flex: 0 0 36px;
  margin-top: 1px;
  font-size: 11px;
  color: var(--text-3);
  text-align: right;
}

.improved-tag {
  color: var(--success);
}

.original {
  margin: 0;
  font-size: 13px;
  color: var(--text-2);
  text-decoration: line-through;
  text-decoration-color: var(--border-strong);
}

.improved {
  margin: 0;
  font-size: 13px;
  color: var(--text);
}

.reason {
  margin: var(--space-2) 0 0 48px;
  font-size: 12.5px;
  color: var(--text-3);
}

/* 岗位方向 */
.directions {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--space-4);
}

.direction {
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.direction-title {
  font-size: 13.5px;
}

.direction-reason {
  margin: var(--space-1) 0 var(--space-3);
  font-size: 12.5px;
  color: var(--text-2);
}

.tag-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1);
  margin-top: var(--space-2);
}

.tag-label {
  margin: 0 var(--space-1) 0 0;
  color: var(--text-3);
  font-size: var(--fs-overline);
  font-weight: 500;
  letter-spacing: 0.06em;
}

.tag {
  padding: 1px 7px;
  border-radius: var(--radius-tag);
  font-size: 11.5px;
}

.tag.gap {
  color: var(--warning);
  background: var(--warning-soft);
}

.tag.focus {
  color: var(--primary);
  background: var(--primary-soft);
}

.muted {
  margin: 0;
  font-size: 13px;
  color: var(--text-3);
}

@media (max-width: 900px) {
  .overview {
    grid-template-columns: 1fr;
  }

  .overall {
    border-right: none;
    border-bottom: 1px solid var(--border);
    padding-right: 0;
    padding-bottom: var(--space-4);
  }

  .columns {
    grid-template-columns: 1fr;
  }
}
</style>
