<script setup lang="ts">
/**
 * 报告页视觉样板（仅开发环境）：用固定数据检查排版与信息层级，不接入后端。
 */
import type { ResumeAnalysisReport } from '@/api/resumes'
import ReportContent from '@/components/report/ReportContent.vue'

const report: ResumeAnalysisReport = {
  overallScore: 76,
  dimensions: [
    { key: 'technicalDepth', label: '技术深度', score: 82, weight: 30 },
    { key: 'projectQuality', label: '项目质量', score: 75, weight: 25 },
    { key: 'engineeringAbility', label: '工程能力', score: 70, weight: 20 },
    { key: 'expressionStructure', label: '表达结构', score: 65, weight: 15 },
    { key: 'jobMatchPotential', label: '岗位匹配潜力', score: 90, weight: 10 },
  ],
  basicInfo: {
    name: '张三',
    yearsOfExperience: 3,
    techDirection: 'Java 后端',
    currentMonthlySalary: 18000,
    expectedMonthlySalary: 25000,
  },
  strengths: [
    {
      quote: '负责订单系统重构，主导拆分核心链路',
      comment: '有主导经验，能承担核心模块',
    },
    {
      quote: '推动接口平均响应时间从 320ms 降到 90ms',
      comment: '结果有量化，可信度高',
    },
  ],
  weaknesses: [
    {
      quote: '参与需求评审',
      comment: '缺少可验证的产出与影响数据',
    },
    {
      quote: '熟悉 Redis、Kafka 等中间件',
      comment: '罗列技术名词，未体现使用深度',
    },
  ],
  suggestions: [
    {
      original: '参与需求评审',
      improved: '主导 3 个核心模块需求评审，提前识别 5 项接口风险并落地',
      reason: '补充动作、数量与结果，体现主动性和影响范围',
    },
    {
      original: '熟悉 Redis、Kafka 等中间件',
      improved: '使用 Redis 缓存热点数据，QPS 从 1.2k 提升到 4.5k；用 Kafka 解耦订单与库存',
      reason: '把“熟悉”换成具体场景与指标，避免空泛',
    },
  ],
  careerDirections: [
    {
      direction: 'Java 后端开发',
      matchReason: '主技术栈与项目经历高度匹配，可直接投递中高级岗位',
      skillGaps: ['分布式事务', '高并发压测'],
      interviewFocus: ['JVM 内存模型', 'MySQL 索引与事务', '线程池'],
    },
    {
      direction: 'AI 应用开发',
      matchReason: '有 Agent 工具链实践，可向 AI 后端方向延伸',
      skillGaps: ['RAG 评估', 'Prompt 工程体系化'],
      interviewFocus: ['ReAct 循环', 'Function Calling', '上下文管理'],
    },
    {
      direction: '平台工程',
      matchReason: '工程化基础较好，适合做内部平台与基础设施',
      skillGaps: ['Kubernetes', '可观测性体系'],
      interviewFocus: ['容器编排', 'CI/CD', '监控告警'],
    },
  ],
  llmProvider: 'corp-gateway',
  llmModel: 'Qwen3.8-27B',
}
</script>

<template>
  <div class="page">
    <header class="preview-head">
      <h2>分析报告 · 视觉样板</h2>
      <p>仅开发环境可用（/preview/report），用于评审信息层级、五维条与排版，不接入后端。</p>
    </header>
    <ReportContent :report="report" />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.preview-head h2 {
  font-size: 18px;
}

.preview-head p {
  margin: var(--space-1) 0 0;
  font-size: 12.5px;
  color: var(--text-3);
}
</style>
