<script setup lang="ts">
/**
 * 应用外壳（§6.10 v1.15）：侧栏按「档案 / 复盘 / 决策 / 练习」分组，48px 面包屑顶栏；
 * 模型配置/主题收进左下角齿轮的右侧抽屉。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import SettingsDrawer from '@/components/settings/SettingsDrawer.vue'
import { openSettings, settingsOpen } from '@/composables/useSettings'

/** 图标统一 24 视窗、1.6 描边，保持线性克制。 */
const groups = [
  {
    label: '档案',
    items: [
      {
        to: '/resumes',
        label: '简历中心',
        icon: 'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M9 13h6M9 17h4',
      },
      {
        to: '/jobs',
        label: '岗位库',
        icon: 'M4 8h16v11a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1zM9 8V6a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2',
      },
      {
        to: '/applications',
        label: '投递档案',
        icon: 'M22 2 11 13M22 2l-7 20-4-9-9-4z',
      },
    ],
  },
  {
    label: '复盘',
    items: [
      {
        to: '/reviews',
        label: '面试复盘',
        icon: 'M4 5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2zM8 8h8M8 12h8M8 16h5',
      },
      {
        to: '/improvements',
        label: '改进看板',
        icon: 'M8 11.5l3 3 5-6M4 5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z',
      },
      {
        to: '/insights',
        label: '求职洞察',
        icon: 'M3 17l6-6 4 4 7-8M17 7h4v4',
      },
    ],
  },
  {
    label: '决策',
    items: [
      {
        to: '/offers',
        label: 'Offer 决策',
        icon: 'M12 15a6 6 0 1 0 0-12 6 6 0 0 0 0 12zM8.2 13.9 7 22l5-3 5 3-1.2-8.1',
      },
    ],
  },
  {
    label: '练习',
    items: [
      {
        to: '/interviews',
        label: '模拟面试',
        icon: 'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v4',
      },
    ],
  },
]

const route = useRoute()
/** 工具入口放在侧栏底部，与业务主线区分。 */
const toolItem = {
  to: '/llm-workbench',
  label: 'LLM 工作台',
  icon: 'M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6',
}
const pageTitle = computed(() => (route.meta.title as string) ?? '面后')
/** 少数页面自带页标题（文件名、对话全屏等），避免重复渲染。 */
const ownTitle = computed(() => route.meta.ownTitle === true)
const currentGroup = computed(() => groups.find((group) => group.items.some((item) => isActive(item.to)))?.label
  ?? (isActive(toolItem.to) ? '工具' : ''))

/** 子页面（如 /resumes/:id/report）也要点亮父级入口。 */
function isActive(to: string): boolean {
  return route.path === to || route.path.startsWith(`${to}/`)
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">面</span>
        <span class="brand-text">
          <strong>面后</strong>
          <small>AfterRound</small>
        </span>
      </div>

      <nav class="nav">
        <div v-for="group in groups" :key="group.label" class="nav-group">
          <p class="nav-label">{{ group.label }}</p>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            class="nav-item"
            :class="{ active: isActive(item.to) }"
          >
            <svg
              viewBox="0 0 24 24"
              width="15"
              height="15"
              fill="none"
              stroke="currentColor"
              stroke-width="1.6"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path :d="item.icon" />
            </svg>
            <span>{{ item.label }}</span>
          </RouterLink>
        </div>
      </nav>

      <div class="sidebar-bottom">
        <RouterLink
          :to="toolItem.to"
          class="nav-item"
          :class="{ active: isActive(toolItem.to) }"
        >
          <svg
            viewBox="0 0 24 24"
            width="15"
            height="15"
            fill="none"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path :d="toolItem.icon" />
          </svg>
          <span>{{ toolItem.label }}</span>
        </RouterLink>
        <button type="button" class="settings-btn" @click="openSettings('model')">
          <svg
            viewBox="0 0 24 24"
            width="15"
            height="15"
            fill="none"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <circle cx="12" cy="12" r="3" />
            <path
              d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.09a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.09a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"
            />
          </svg>
          设置
        </button>
        <div class="sidebar-foot">
          <span class="dot" />
          M3 · 个人自用版
        </div>
      </div>
    </aside>

    <main class="main">
      <header class="topbar">
        <nav class="crumb" aria-label="面包屑">
          <template v-if="currentGroup">
            <span class="crumb-group">{{ currentGroup }}</span>
            <span class="crumb-sep">/</span>
          </template>
          <span class="crumb-current">{{ pageTitle }}</span>
        </nav>
      </header>
      <section class="content">
        <h1 v-if="!ownTitle" class="page-title">{{ pageTitle }}</h1>
        <RouterView />
      </section>
    </main>

    <SettingsDrawer :open="settingsOpen" @close="settingsOpen = false" />
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100%;
}

/* ---------- 侧栏 ---------- */
.sidebar {
  width: var(--nav-width);
  flex: 0 0 var(--nav-width);
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border-right: 1px solid var(--border);
  padding: var(--space-5) var(--space-3) var(--space-4);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 0 var(--space-2) var(--space-5);
}

.brand-mark {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  background: var(--primary);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.brand-text strong {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.brand-text small {
  color: var(--text-3);
  font-size: 10.5px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.nav-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-label {
  margin: 0 0 4px;
  padding: 0 10px;
  color: var(--text-3);
  font-size: 10.5px;
  font-weight: 500;
  letter-spacing: 0.12em;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 34px;
  padding: 0 10px;
  border-radius: var(--radius-btn);
  color: var(--text-2);
  font-size: 13px;
  transition: background var(--dur-1) var(--ease), color var(--dur-1) var(--ease);
}

.nav-item svg {
  flex: 0 0 15px;
  color: var(--text-3);
  transition: color var(--dur-1) var(--ease);
}

.nav-item:hover {
  background: var(--surface-muted);
  color: var(--text);
}

.nav-item:hover svg {
  color: var(--text-2);
}

.nav-item.active {
  background: var(--primary-soft);
  color: var(--primary);
  font-weight: 500;
}

.nav-item.active svg {
  color: var(--primary);
}

/* 当前页：左侧墨线，与侧栏边缘对齐 */
.nav-item.active::before {
  content: '';
  position: absolute;
  left: calc(var(--space-3) * -1);
  top: 7px;
  bottom: 7px;
  width: 2px;
  border-radius: 0 2px 2px 0;
  background: var(--primary);
}

.sidebar-bottom {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.settings-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 34px;
  padding: 0 10px;
  border: none;
  border-radius: var(--radius-btn);
  background: none;
  color: var(--text-2);
  font-size: 13px;
  text-align: left;
  transition: background var(--dur-1) var(--ease), color var(--dur-1) var(--ease);
}

.settings-btn svg {
  color: var(--text-3);
}

.settings-btn:hover {
  background: var(--surface-muted);
  color: var(--text);
}

.sidebar-foot {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 10px;
  color: var(--text-3);
  font-size: var(--fs-xs);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--success);
}

/* ---------- 主区 ---------- */
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 48px;
  flex: 0 0 48px;
  display: flex;
  align-items: center;
  padding: 0 var(--space-6);
  max-width: var(--measure);
  width: 100%;
  margin: 0 auto;
}

.crumb {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-sm);
}

.crumb-group {
  color: var(--text-3);
  font-size: var(--fs-xs);
  letter-spacing: 0.08em;
}

.crumb-sep {
  color: var(--text-3);
}

.crumb-current {
  color: var(--text-3);
}

.content {
  flex: 1;
  width: 100%;
  max-width: var(--measure);
  margin: 0 auto;
  overflow-y: auto;
  padding: 0 var(--space-6) var(--space-6);
}

.page-title {
  margin: 0 0 var(--space-5);
}
</style>
