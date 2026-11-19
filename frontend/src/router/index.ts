import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由表：一级业务为简历中心与岗位库（M2）；模型配置、场景绑定与主题在左下角设置抽屉中（§6.10）。
 * 简历对比属于简历中心子页面；`/chat` 为模型能力验证载体，从设置抽屉进入。
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
    path: '/resumes/compare',
    name: 'resume-compare',
    component: () => import('@/views/ComparisonView.vue'),
    meta: { title: '简历对比' },
  },
  {
    path: '/resumes/:id/report',
    name: 'resume-report',
    component: () => import('@/views/ResumeReportView.vue'),
    meta: { title: '分析报告' },
  },
  {
    path: '/jobs',
    name: 'jobs',
    component: () => import('@/views/JobListView.vue'),
    meta: { title: '岗位库' },
  },
  {
    path: '/jobs/:id',
    name: 'job-detail',
    component: () => import('@/views/JobDetailView.vue'),
    meta: { title: '岗位详情' },
  },
  {
    path: '/interviews',
    name: 'interviews',
    component: () => import('@/views/InterviewListView.vue'),
    meta: { title: '模拟面试' },
  },
  {
    path: '/interviews/:id',
    name: 'interview-room',
    component: () => import('@/views/InterviewRoomView.vue'),
    meta: { title: '模拟面试' },
  },
  {
    path: '/offers',
    name: 'offers',
    component: () => import('@/views/OfferView.vue'),
    meta: { title: 'Offer 决策' },
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '模型对话（能力验证）' },
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
