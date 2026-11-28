<script setup lang="ts">
/**
 * LLM 工作台（1.25.0）：模型与绑定 / 提示词 / 评测运行 / 基线对比 / 调用统计。
 * 提示词发布只改线上生效版本，不自动跑评测；有未保存修改时统一拦截。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { listPrompts, type PromptSummary } from '@/api/prompts'
import BaselineComparePanel from '@/components/workbench/BaselineComparePanel.vue'
import EvalRunPanel from '@/components/workbench/EvalRunPanel.vue'
import ModelBindingPanel from '@/components/workbench/ModelBindingPanel.vue'
import PromptEditor from '@/components/workbench/PromptEditor.vue'
import PromptList from '@/components/workbench/PromptList.vue'
import UsagePanel from '@/components/workbench/UsagePanel.vue'

type Tab = 'models' | 'prompts' | 'eval' | 'baseline' | 'usage'

const tabs: { key: Tab; label: string }[] = [
  { key: 'models', label: '模型与绑定' },
  { key: 'prompts', label: '提示词' },
  { key: 'eval', label: '评测运行' },
  { key: 'baseline', label: '基线对比' },
  { key: 'usage', label: '调用统计' },
]

const route = useRoute()
const router = useRouter()
const tab = ref<Tab>('models')
const evalFocus = ref<string[]>([])
const prompts = ref<PromptSummary[]>([])
const selectedKey = ref('')
const loading = ref(true)
const error = ref('')
const dirty = ref(false)
const editorRef = ref<{ saveDraft: () => Promise<boolean> } | null>(null)

const guardOpen = ref(false)
const pendingAction = ref<(() => void) | null>(null)
let bypassGuard = false

async function loadPrompts(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    prompts.value = await listPrompts()
    if (!selectedKey.value && prompts.value.length) {
      selectedKey.value = prompts.value[0].key
    }
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '提示词列表加载失败'
  } finally {
    loading.value = false
  }
}

/** 有未保存修改时先弹三选一，否则直接执行。 */
function guard(action: () => void): void {
  if (tab.value === 'prompts' && dirty.value) {
    pendingAction.value = action
    guardOpen.value = true
    return
  }
  action()
}

function proceed(): void {
  const action = pendingAction.value
  guardOpen.value = false
  pendingAction.value = null
  dirty.value = false
  if (action) {
    bypassGuard = true
    action()
    window.setTimeout(() => {
      bypassGuard = false
    }, 0)
  }
}

async function saveAndProceed(): Promise<void> {
  const saved = await editorRef.value?.saveDraft()
  if (saved) {
    proceed()
  }
}

function cancelGuard(): void {
  guardOpen.value = false
  pendingAction.value = null
}

function selectPrompt(key: string): void {
  if (key === selectedKey.value) {
    return
  }
  guard(() => {
    selectedKey.value = key
  })
}

function switchTab(next: Tab): void {
  if (next === tab.value) {
    return
  }
  guard(() => {
    tab.value = next
  })
}

/** 提示词详情「去评测」：只切 Tab 并预选受影响场景，不自动开始。 */
function goEval(sceneKeys: string[]): void {
  evalFocus.value = sceneKeys
  guard(() => {
    tab.value = 'eval'
  })
}

/** 支持深链 /llm-workbench?tab=eval&scenes=resume-analysis。 */
function applyQuery(): void {
  const target = route.query.tab
  if (typeof target === 'string' && tabs.some((item) => item.key === target)) {
    tab.value = target as Tab
  }
  const raw = Array.isArray(route.query.scenes) ? route.query.scenes.join(',') : route.query.scenes
  if (typeof raw === 'string' && raw) {
    evalFocus.value = raw.split(',').filter(Boolean)
  }
}

onBeforeRouteLeave((to) => {
  if (bypassGuard || !dirty.value) {
    return true
  }
  pendingAction.value = () => {
    void router.push(to.fullPath)
  }
  guardOpen.value = true
  return false
})

watch(tab, (value) => {
  if (value !== 'prompts') {
    dirty.value = false
  }
})

const promptCount = computed(() => (loading.value ? '加载中' : `${prompts.value.length} 组`))

watch(() => route.query, applyQuery)
onMounted(() => {
  applyQuery()
  void loadPrompts()
})
</script>

<template>
  <div class="workbench page">
    <header class="page-head">
      <div>
        <h1 class="title">LLM 工作台</h1>
        <p class="lead">
          集中查看与修改所有大模型提示词、全局与场景模型绑定，查看调用观测，并按需手动跑场景级评测、
          把满意的运行设为基线做对比。修改配置不会自动触发评测；跑评测必须由你主动发起。
        </p>
      </div>
      <span class="tag">{{ promptCount }}提示词</span>
    </header>

    <nav class="tabs" aria-label="LLM 工作台">
      <button
        v-for="item in tabs"
        :key="item.key"
        type="button"
        :class="{ active: tab === item.key }"
        @click="switchTab(item.key)"
      >
        {{ item.label }}
      </button>
    </nav>

    <div v-if="error" class="banner danger">{{ error }}</div>

    <ModelBindingPanel v-if="tab === 'models'" />

    <section v-else-if="tab === 'prompts'" class="prompts">
      <PromptList
        :items="prompts"
        :selected-key="selectedKey"
        :loading="loading"
        @select="selectPrompt"
      />
      <PromptEditor
        v-if="selectedKey"
        ref="editorRef"
        :prompt-key="selectedKey"
        @update:dirty="dirty = $event"
        @go-eval="goEval"
      />
      <p v-else-if="!loading" class="meta">还没有提示词。</p>
    </section>

    <EvalRunPanel
      v-else-if="tab === 'eval'"
      :initial-scenes="evalFocus"
      @go-models="switchTab('models')"
    />

    <BaselineComparePanel v-else-if="tab === 'baseline'" />

    <UsagePanel v-else />
  </div>

  <Teleport to="body">
    <div v-if="guardOpen" class="guard-mask" @click.self="cancelGuard">
      <div class="guard" role="dialog" aria-label="未保存修改">
        <h2>有未保存的修改</h2>
        <p>切换后会丢失当前编辑内容。要保存为草稿再继续吗？</p>
        <div class="guard-actions">
          <button type="button" class="btn primary" @click="saveAndProceed">保存草稿</button>
          <button type="button" class="btn ghost" @click="proceed">放弃修改</button>
          <button type="button" class="btn quiet" @click="cancelGuard">取消</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.workbench {
  gap: var(--space-4);
}

.title {
  margin: 0 0 var(--space-2);
  font-size: var(--fs-h1);
  font-weight: 600;
}

.tabs {
  display: flex;
  gap: var(--space-1);
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

.prompts {
  display: grid;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: var(--space-4);
  align-items: start;
}

.guard-mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: grid;
  place-items: center;
  background: var(--overlay);
}

.guard {
  width: 380px;
  max-width: calc(100vw - 32px);
  padding: var(--space-5);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
  box-shadow: var(--shadow-float);
}

.guard h2 {
  margin: 0 0 var(--space-2);
  font-size: var(--fs-h2);
  font-weight: 600;
}

.guard p {
  margin: 0 0 var(--space-4);
  color: var(--text-2);
  font-size: var(--fs-sm);
}

.guard-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

@media (max-width: 900px) {
  .prompts {
    grid-template-columns: 1fr;
  }
}
</style>
