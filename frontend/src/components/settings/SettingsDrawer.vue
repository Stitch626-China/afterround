<script setup lang="ts">
/**
 * 设置抽屉（§6.10）：左下角齿轮唤起，右侧滑出，不打断当前页面。
 * 四个 Tab：模型服务 / 场景模型 / 外观 / 调用统计（NFR6 全局入口）。
 */
import { onBeforeUnmount, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import ModelSettings from './ModelSettings.vue'
import SceneSettings from './SceneSettings.vue'
import AppearanceSettings from './AppearanceSettings.vue'
import UsageSettings from './UsageSettings.vue'
import { settingsTab, type SettingsTab } from '@/composables/useSettings'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()
const router = useRouter()

function selectTab(tab: SettingsTab): void {
  settingsTab.value = tab
}

/** 关闭抽屉并跳转到模型对话页（M1 能力验证入口）。 */
function openChat(): void {
  emit('close')
  void router.push('/chat')
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && props.open) {
    emit('close')
  }
}

watch(() => props.open, (open) => {
  document.body.style.overflow = open ? 'hidden' : ''
})

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
})
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="open" class="mask" @click.self="emit('close')">
        <aside class="drawer" role="dialog" aria-label="设置">
          <header class="drawer-head">
            <h2>设置</h2>
            <button type="button" class="close" @click="emit('close')">关闭</button>
          </header>

          <nav class="tabs">
            <button type="button" :class="{ active: settingsTab === 'model' }" @click="selectTab('model')">模型服务</button>
            <button type="button" :class="{ active: settingsTab === 'scene' }" @click="selectTab('scene')">场景模型</button>
            <button type="button" :class="{ active: settingsTab === 'appearance' }" @click="selectTab('appearance')">外观</button>
            <button type="button" :class="{ active: settingsTab === 'usage' }" @click="selectTab('usage')">调用统计</button>
          </nav>

          <div class="drawer-body">
            <ModelSettings v-if="settingsTab === 'model'" @navigate="openChat" />
            <SceneSettings v-else-if="settingsTab === 'scene'" />
            <UsageSettings v-else-if="settingsTab === 'usage'" />
            <AppearanceSettings v-else />
          </div>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: var(--overlay);
  display: flex;
  justify-content: flex-end;
}

.drawer {
  width: 480px;
  max-width: 100vw;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--surface);
  border-left: 1px solid var(--border);
  box-shadow: var(--shadow-float);
}

.drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-5) var(--space-5) var(--space-3);
}

.drawer-head h2 {
  font-size: var(--fs-h2);
  font-weight: 600;
}

.close {
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text-2);
  font-size: var(--fs-sm);
}

.close:hover {
  background: var(--surface-muted);
  color: var(--text);
}

.tabs {
  display: flex;
  gap: var(--space-1);
  padding: 0 var(--space-5);
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

.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-5);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--dur-2) var(--ease);
}

.fade-enter-active .drawer,
.fade-leave-active .drawer {
  transition: transform var(--dur-2) var(--ease);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.fade-enter-from .drawer,
.fade-leave-to .drawer {
  transform: translateX(24px);
}
</style>
