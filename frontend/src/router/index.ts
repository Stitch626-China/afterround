import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由表：M1 四屏（简历中心 / 分析报告 / 模型设置 / 模型对话）。
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/resumes' },
  {
    path: '/resumes',
    name: 'resumes',
    component: () => import('@/views/ResumeListView.vue'),
    meta: { title: '简历中心' },
  },
  {
    path: '/resumes/:id/report',
    name: 'resume-report',
    component: () => import('@/views/ResumeReportView.vue'),
    meta: { title: '分析报告' },
  },
  {
    path: '/llm',
    name: 'llm',
    component: () => import('@/views/LlmSettingsView.vue'),
    meta: { title: '模型设置' },
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '模型对话' },
  },
]

// 开发环境提供报告页视觉样板，方便评审；生产构建不包含该路由
if (import.meta.env.DEV) {
  routes.push({
    path: '/preview/report',
    name: 'report-preview',
    component: () => import('@/views/ReportPreviewView.vue'),
    meta: { title: '报告视觉样板（开发预览）' },
  })
}

export default createRouter({
  history: createWebHistory(),
  routes,
})
