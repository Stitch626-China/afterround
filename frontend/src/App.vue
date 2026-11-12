<script setup lang="ts">
/**
 * 应用外壳（§6.10）：一级导航只放业务主线；模型配置/主题收进左下角齿轮的右侧抽屉。
 */
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import SettingsDrawer from '@/components/settings/SettingsDrawer.vue'

const route = useRoute()
const pageTitle = computed(() => (route.meta.title as string) ?? '职点')
const settingsOpen = ref(false)
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">职</span>
        <span class="brand-text">
          <strong>职点</strong>
          <small>JobPilot</small>
        </span>
      </div>

      <nav class="nav">
        <RouterLink to="/resumes" class="nav-item">简历中心</RouterLink>
        <span class="nav-item planned">模拟面试<em>M2</em></span>
        <span class="nav-item planned">面试复盘<em>M3</em></span>
        <span class="nav-item planned">求职洞察<em>M3</em></span>
      </nav>

      <div class="sidebar-bottom">
        <button type="button" class="settings-btn" @click="settingsOpen = true">
          <svg
            viewBox="0 0 24 24"
            width="15"
            height="15"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
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
          M1 · 个人自用版
        </div>
      </div>
    </aside>

    <main class="main">
      <header class="topbar">
        <h1>{{ pageTitle }}</h1>
      </header>
      <section class="content">
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
  padding: var(--space-5) var(--space-3);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: 0 var(--space-2) var(--space-5);
}

.brand-mark {
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-tag);
  background: var(--primary);
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.brand-text strong {
  font-size: 15px;
  letter-spacing: 0.02em;
}

.brand-text small {
  color: var(--text-3);
  font-size: 11px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-2) var(--space-3);
  border-radius: var(--radius-btn);
  color: var(--text-2);
  font-size: 13.5px;
  transition: background 0.12s ease, color 0.12s ease;
}

.nav-item:hover {
  background: var(--surface-muted);
  color: var(--text);
}

.nav-item.router-link-active {
  background: var(--primary-soft);
  color: var(--primary);
  font-weight: 500;
}

.nav-item.planned {
  color: var(--text-3);
  cursor: default;
}

.nav-item.planned:hover {
  background: none;
  color: var(--text-3);
}

.nav-item em {
  font-style: normal;
  font-size: 10.5px;
  padding: 0 5px;
  border-radius: var(--radius-tag);
  background: var(--surface-muted);
  color: var(--text-3);
}

.sidebar-bottom {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.settings-btn {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-2) var(--space-3);
  border: none;
  border-radius: var(--radius-btn);
  background: none;
  color: var(--text-2);
  font-size: 13px;
  text-align: left;
}

.settings-btn:hover {
  background: var(--surface-muted);
  color: var(--text);
}

.sidebar-foot {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: 0 var(--space-3);
  color: var(--text-3);
  font-size: 11.5px;
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
  height: 56px;
  flex: 0 0 56px;
  display: flex;
  align-items: center;
  padding: 0 var(--space-6);
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}

.topbar h1 {
  font-size: 15px;
}

.content {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-6);
}
</style>
