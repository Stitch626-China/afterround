<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const pageTitle = computed(() => (route.meta.title as string) ?? '职点')

const navs = [
  { path: '/resumes', label: '简历中心' },
  { path: '/llm', label: '模型设置' },
  { path: '/chat', label: '模型对话' },
]
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
        <RouterLink v-for="item in navs" :key="item.path" :to="item.path" class="nav-item">
          {{ item.label }}
        </RouterLink>
      </nav>

      <div class="sidebar-foot">
        <span class="dot" />
        M1 · 个人自用版
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

.sidebar-foot {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: var(--space-2);
  padding: var(--space-3) var(--space-3) 0;
  border-top: 1px solid var(--border);
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
