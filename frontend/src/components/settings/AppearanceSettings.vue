<script setup lang="ts">
/**
 * 外观设置：light / dark。默认跟随系统，手动选择后记住（index.html 首屏已应用）。
 */
import { ref } from 'vue'
import { applyTheme, currentTheme, type Theme } from '@/theme'

const theme = ref<Theme>(currentTheme())

function choose(value: Theme): void {
  theme.value = value
  applyTheme(value)
}
</script>

<template>
  <div class="panel">
    <p class="hint">默认跟随系统；手动选择后会记住，下次打开直接生效。</p>

    <div class="themes">
      <button type="button" class="theme" :class="{ active: theme === 'light' }" @click="choose('light')">
        <span class="preview light">
          <i class="bar" />
          <i class="bar short" />
          <i class="bar" />
        </span>
        <span class="theme-name">Light · 白天</span>
      </button>

      <button type="button" class="theme" :class="{ active: theme === 'dark' }" @click="choose('dark')">
        <span class="preview dark">
          <i class="bar" />
          <i class="bar short" />
          <i class="bar" />
        </span>
        <span class="theme-name">Dark · 黑夜</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-3);
}

.themes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-3);
}

.theme {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
  text-align: left;
  transition: border-color 0.12s ease;
}

.theme:hover {
  border-color: var(--border-strong);
}

.theme.active {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px var(--primary-soft);
}

.preview {
  display: flex;
  flex-direction: column;
  gap: 5px;
  height: 72px;
  padding: 12px;
  border-radius: var(--radius-btn);
  border: 1px solid var(--border);
}

.preview.light {
  background: #f7f5f1;
}

.preview.dark {
  background: #141311;
  border-color: #302c26;
}

.bar {
  height: 6px;
  width: 100%;
  border-radius: 3px;
  background: rgba(36, 59, 99, 0.55);
}

.preview.dark .bar {
  background: rgba(76, 125, 240, 0.65);
}

.bar.short {
  width: 62%;
  background: rgba(111, 106, 97, 0.35);
}

.preview.dark .bar.short {
  background: rgba(163, 171, 184, 0.35);
}

.theme-name {
  font-size: 13px;
  color: var(--text);
}
</style>
