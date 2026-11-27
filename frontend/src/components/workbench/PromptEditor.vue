<script setup lang="ts">
/**
 * 提示词编辑器：system/user 双栏、即时校验、草稿/发布/回滚。
 * 发布只改线上生效版本，不触发任何评测；有未保存修改时由工作台统一拦截。
 */
import { computed, onMounted, ref, watch } from 'vue'
import {
  discardPromptDraft,
  getPrompt,
  publishPrompt,
  savePromptDraft,
  type PromptDetail,
} from '@/api/prompts'
import PromptDiffView from './PromptDiffView.vue'
import PromptVersionHistory from './PromptVersionHistory.vue'
import {
  hasUnsavedChanges,
  mergeValidation,
  shortTime,
  validatePrompt,
  versionLabel,
  type ValidationMessages,
} from '@/utils/prompt'

const props = defineProps<{ promptKey: string }>()
const emit = defineEmits<{
  'update:dirty': [dirty: boolean]
  changed: []
  'go-eval': [scenes: string[]]
}>()

const detail = ref<PromptDetail | null>(null)
const systemContent = ref('')
const userContent = ref('')
const note = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const showDiff = ref(false)
const showHistory = ref(false)
const diffBase = ref<'active' | 'builtIn'>('active')

const dirty = computed(() => hasUnsavedChanges(detail.value, systemContent.value, userContent.value, note.value))

const localValidation = computed<ValidationMessages>(() => {
  if (!detail.value) {
    return { valid: true, errors: [], warnings: [] }
  }
  const result = validatePrompt(detail.value, systemContent.value, userContent.value)
  const warnings = [...result.warnings]
  if (detail.value.hasDraft) {
    warnings.push('存在未发布草稿；发布后才会生效')
  }
  if (detail.value.active.source === 'DB' && !detail.value.draft
      && systemContent.value === detail.value.builtIn.systemContent
      && userContent.value === detail.value.builtIn.userContent) {
    warnings.push('当前编辑内容与内置默认相同；发布后会以 DB 版本覆盖')
  }
  return { valid: result.valid, errors: result.errors, warnings }
})

const validation = computed(() => mergeValidation(
  localValidation.value,
  {
    valid: true,
    errors: [],
    warnings: detail.value?.validation.warnings ?? [],
  },
))

const baseContent = computed(() => (diffBase.value === 'builtIn'
  ? detail.value?.builtIn
  : detail.value?.active))

const suggestions = computed(() => detail.value?.suggestedEvalScenes ?? [])
const staleScenes = computed(() => suggestions.value.filter((item) => item.currentBaselineId != null))
const noBaselineScenes = computed(() => suggestions.value.filter((item) => item.currentBaselineId == null))
const impactReasons = computed(() => [...new Set(suggestions.value.flatMap((item) => item.reasons))].join('；'))
const impactTargets = computed(() => suggestions.value.map((item) => item.scene))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    apply(await getPrompt(props.promptKey))
  } catch (ex) {
    detail.value = null
    error.value = ex instanceof Error ? ex.message : '提示词加载失败'
  } finally {
    loading.value = false
  }
}

function apply(data: PromptDetail): void {
  detail.value = data
  const base = data.draft ?? data.active
  systemContent.value = base.systemContent
  userContent.value = base.userContent
  note.value = data.draft?.note ?? ''
  showDiff.value = false
  showHistory.value = false
  emit('update:dirty', false)
}

/** 保存草稿：允许保存无效草稿，但空模板直接拦截。 */
async function saveDraft(): Promise<boolean> {
  if (!detail.value) {
    return false
  }
  if (!systemContent.value.trim() || !userContent.value.trim()) {
    error.value = 'system / user 模板不能为空'
    return false
  }
  busy.value = true
  error.value = ''
  try {
    const data = await savePromptDraft(props.promptKey, {
      systemContent: systemContent.value,
      userContent: userContent.value,
      note: note.value || null,
    })
    apply(data)
    notice.value = '草稿已保存，尚未生效'
    emit('changed')
    return true
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '草稿保存失败'
    return false
  } finally {
    busy.value = false
  }
}

/** 发布：先保存未落库修改，再发布；不自动跑评测。 */
async function publish(): Promise<void> {
  if (!detail.value) {
    return
  }
  if (!localValidation.value.valid) {
    error.value = '仍有校验错误，修正后才能发布'
    return
  }
  busy.value = true
  error.value = ''
  try {
    let current = detail.value
    if (dirty.value) {
      current = await savePromptDraft(props.promptKey, {
        systemContent: systemContent.value,
        userContent: userContent.value,
        note: note.value || null,
      })
    }
    if (!current.hasDraft) {
      error.value = '没有可发布的草稿'
      return
    }
    const data = await publishPrompt(props.promptKey, { note: note.value || null })
    apply(data)
    notice.value = '发布成功，线上已生效；不会自动跑评测'
    emit('changed')
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '发布失败'
  } finally {
    busy.value = false
  }
}

/** 丢弃草稿：有服务端草稿时删除；仅本地修改时恢复为当前生效内容。 */
async function discard(): Promise<void> {
  if (!detail.value) {
    return
  }
  if (!window.confirm(detail.value.hasDraft ? '丢弃未发布的草稿？' : '放弃当前修改？')) {
    return
  }
  if (!detail.value.hasDraft) {
    apply(detail.value)
    notice.value = '已恢复到当前生效版本'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await discardPromptDraft(props.promptKey)
    await load()
    notice.value = '草稿已丢弃，线上版本未变'
    emit('changed')
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '草稿丢弃失败'
  } finally {
    busy.value = false
  }
}

/** 把内置默认填入编辑器；保存并发布后才生效。 */
function restoreBuiltin(): void {
  if (!detail.value) {
    return
  }
  if (dirty.value && !window.confirm('用内置默认覆盖当前编辑内容？')) {
    return
  }
  systemContent.value = detail.value.builtIn.systemContent
  userContent.value = detail.value.builtIn.userContent
  note.value = '恢复内置默认'
  notice.value = '已填入编辑器；保存并发布后才会生效'
}

function toggleHistory(): void {
  if (!showHistory.value && dirty.value && !window.confirm('有未保存修改，打开版本历史会丢失；继续？')) {
    return
  }
  showHistory.value = !showHistory.value
  if (showHistory.value) {
    showDiff.value = false
  }
}

/** 模板里不能直接写双大括号字面量，交给函数拼接。 */
function placeholderLabel(name: string): string {
  return `{{${name}}}`
}

async function onHistoryChanged(): Promise<void> {
  await load()
  notice.value = '已切换生效版本，下一次调用立即使用新内容'
  emit('changed')
}

watch(() => props.promptKey, load)
watch(dirty, (value) => emit('update:dirty', value), { immediate: true })
onMounted(load)

defineExpose({ saveDraft })
</script>

<template>
  <section class="editor">
    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-if="notice" class="banner success">{{ notice }}</div>

    <div v-if="loading" class="skeleton">
      <div class="skeleton-row" />
      <div class="skeleton-row" />
    </div>

    <template v-else-if="detail">
      <header class="head">
        <div>
          <h2>{{ detail.name }}</h2>
          <p class="meta">{{ detail.key }} · {{ detail.category }} · {{ detail.description }}</p>
        </div>
        <div class="badges">
          <span class="tag primary">{{ versionLabel(detail.activeVersion, detail.builtinVersion) }}</span>
          <span v-if="detail.hasDraft" class="tag warning">草稿未发布</span>
          <span v-if="dirty" class="tag">未保存</span>
          <span v-if="staleScenes.length" class="tag warning" :title="impactReasons">基线已过期</span>
          <span v-else-if="noBaselineScenes.length" class="tag" :title="impactReasons">尚无基线</span>
        </div>
      </header>

      <div class="facts">
        <span>输出 {{ detail.outputMode }}</span>
        <span>模型 {{ detail.modelScope }}{{ detail.scene ? ` · ${detail.scene}` : '' }}</span>
        <span v-if="detail.evalScene">评测场景 {{ detail.evalScene }}</span>
        <span v-else>无评测语料</span>
        <span>更新 {{ shortTime(detail.updatedAt) }}</span>
      </div>

      <p v-if="suggestions.length" class="impact">
        <span>{{ impactReasons }}。</span>
        <button type="button" class="link" @click="emit('go-eval', impactTargets)">去评测</button>
      </p>

      <p class="functions">
        影响功能：
        <span v-for="name in detail.affectedFunctions" :key="name" class="tag">{{ name }}</span>
      </p>

      <p class="placeholders">
        占位符：
        <code v-for="name in detail.placeholders" :key="name">{{ placeholderLabel(name) }}</code>
        <span v-if="!detail.placeholders.length" class="meta">无</span>
      </p>
      <p v-if="detail.requiredFields.length" class="placeholders">
        发布必须保留的 JSON 字段：
        <code v-for="field in detail.requiredFields" :key="field">{{ field }}</code>
      </p>

      <div v-if="detail.active.source === 'DB'" class="banner warning">
        <span>当前使用 DB 覆盖版本；内置默认 {{ detail.builtinVersion }} 可随时恢复。</span>
        <button type="button" class="link" @click="restoreBuiltin">恢复内置默认</button>
      </div>

      <div v-if="validation.errors.length" class="banner danger">
        <span>{{ validation.errors.join('；') }}</span>
      </div>
      <div v-if="validation.warnings.length" class="banner warning">
        <span>{{ validation.warnings.join('；') }}</span>
      </div>

      <div class="grid">
        <label class="field">
          <span>system</span>
          <textarea v-model="systemContent" aria-label="system 模板" rows="14" spellcheck="false" />
        </label>
        <label class="field">
          <span>user</span>
          <textarea v-model="userContent" aria-label="user 模板" rows="14" spellcheck="false" />
        </label>
      </div>

      <label class="note">
        <span>版本备注（可选）</span>
        <input v-model="note" aria-label="版本备注" maxlength="200" placeholder="例如：补强引用原文约束" />
      </label>

      <div class="actions">
        <button type="button" class="btn primary" :disabled="busy || !validation.valid" @click="publish">
          发布
        </button>
        <button type="button" class="btn ghost" :disabled="busy" @click="saveDraft">保存草稿</button>
        <button
          v-if="detail.hasDraft || dirty"
          type="button"
          class="btn quiet"
          :disabled="busy"
          @click="discard"
        >
          丢弃草稿
        </button>
        <button type="button" class="btn quiet" :disabled="busy" @click="restoreBuiltin">恢复内置默认</button>
        <button type="button" class="btn quiet" :disabled="busy" @click="showDiff = !showDiff">
          {{ showDiff ? '收起对比' : '与当前生效对比' }}
        </button>
        <button type="button" class="btn quiet" :disabled="busy" @click="toggleHistory">
          {{ showHistory ? '收起版本历史' : '版本历史' }}
        </button>
        <span class="meta">发布会立即生效，但不会自动跑评测。</span>
      </div>

      <div v-if="showDiff && baseContent" class="panel">
        <div class="panel-head">
          <span>对比基线</span>
          <select v-model="diffBase" aria-label="对比基线">
            <option value="active">当前生效版本</option>
            <option value="builtIn">代码内置默认</option>
          </select>
        </div>
        <PromptDiffView
          :left-title="diffBase === 'builtIn' ? `内置 ${detail.builtinVersion}` : versionLabel(detail.activeVersion, detail.builtinVersion)"
          right-title="编辑中"
          :left="baseContent.systemContent + '\n---\n' + baseContent.userContent"
          :right="systemContent + '\n---\n' + userContent"
        />
      </div>

      <PromptVersionHistory
        v-if="showHistory"
        :prompt-key="detail.key"
        @changed="onHistoryChanged"
        @close="showHistory = false"
      />
    </template>
  </section>
</template>

<style scoped>
.editor {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-width: 0;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.head h2 {
  margin: 0;
  font-size: var(--fs-h2);
  font-weight: 600;
}

.badges {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
  justify-content: flex-end;
}

.facts {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-3);
  color: var(--text-3);
  font-size: var(--fs-xs);
}

.functions,
.placeholders {
  margin: 0;
  color: var(--text-2);
  font-size: var(--fs-xs);
  line-height: 1.8;
}

.impact {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: 0;
  color: var(--text-2);
  font-size: var(--fs-xs);
}

.functions .tag {
  margin-right: 4px;
}

code {
  margin-right: 6px;
  padding: 1px 5px;
  border-radius: var(--radius-tag);
  background: var(--surface-muted);
  color: var(--text-2);
  font-family: var(--font-mono);
  font-size: 11px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  color: var(--text-3);
  font-size: var(--fs-overline);
  letter-spacing: 0.08em;
}

textarea,
input,
select {
  width: 100%;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.55;
}

textarea {
  resize: vertical;
  min-height: 220px;
}

.note {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-3);
  font-size: var(--fs-xs);
}

.note input {
  font-family: var(--font-sans);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.actions .meta {
  margin-left: auto;
}

.panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.panel-head {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  color: var(--text-3);
  font-size: var(--fs-xs);
}

.panel-head select {
  width: auto;
  font-family: var(--font-sans);
}

@media (max-width: 900px) {
  .grid {
    grid-template-columns: 1fr;
  }

  .actions .meta {
    margin-left: 0;
  }
}
</style>
