import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由表：M1 三屏（简历中心 / 模型设置 / 模型对话）。
 * 分析报告页随后续阶段补充。
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

export default createRouter({
  history: createWebHistory(),
  routes,
})
