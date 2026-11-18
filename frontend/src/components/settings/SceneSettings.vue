<script setup lang="ts">
/**
 * 场景级模型绑定（FRX.11）：7 个场景可分别指定 Provider + Model，未配置时回落全局默认。
 * 保存即生效；被场景引用的 Provider/Model 在后端删除时会被拒绝。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { listProviders, type LlmProvider } from '@/api/llm'
import {
  bindSceneModel,
  listSceneBindings,
  unbindSceneModel,
  type LlmScene,
  type SceneBinding,
} from '@/api/jobs'

const scenes = ref<SceneBinding[]>([])
const providers = ref<LlmProvider[]>([])
const loading = ref(true)
const error = ref('')
const notice = ref('')
const editing = ref<LlmScene | null>(null)
const busy = ref(false)
const form = reactive({ providerKey: '', model: '' })

const models = computed(() => {
  const provider = providers.value.find((item) => item.providerKey === form.providerKey)
  return (provider?.models ?? []).filter((model) => model.enabled)
})
const hasProvider = computed(() => providers.value.length > 0)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [sceneList, providerList] = await Promise.all([listSceneBindings(), listProviders()])
    scenes.value = sceneList
    providers.value = providerList
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '场景配置加载失败'
  } finally {
    loading.value = false
  }
}

function begin(scene: SceneBinding): void {
  editing.value = scene.scene
  form.providerKey = scene.effectiveProviderKey ?? ''
  form.model = scene.effectiveModel ?? ''
  error.value = ''
  notice.value = ''
}

function cancel(): void {
  editing.value = null
}

/** Provider 变更时把 model 重置为该 Provider 的启用模型，避免提交跨 Provider 的模型。 */
function onProviderChange(): void {
  form.model = models.value[0]?.modelName ?? ''
}

async function save(scene: LlmScene): Promise<void> {
  if (!form.providerKey || !form.model) {
    error.value = '请选择 Provider 与模型'
    return
  }
  busy.value = true
  error.value = ''
  try {
    await bindSceneModel(scene, form.providerKey, form.model)
    notice.value = '场景绑定已保存，下一次调用立即生效'
    editing.value = null
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '保存失败'
  } finally {
    busy.value = false
  }
}

async function reset(scene: LlmScene): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await unbindSceneModel(scene)
    notice.value = '已恢复为全局默认'
    editing.value = null
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '恢复失败'
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="scenes">
    <p class="lead">
      不同任务可指定不同模型：未绑定的场景自动使用全局默认，保存后立即生效。
    </p>

    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-else-if="notice" class="banner success">{{ notice }}</div>
    <div v-if="!loading && !hasProvider" class="banner warning">
      还没有可用的 Provider，请先在「模型服务」中添加服务商与模型。
    </div>

    <div v-if="loading" class="skeleton" />

    <ul v-else class="list">
      <li v-for="scene in scenes" :key="scene.scene" class="row">
        <div class="row-head">
          <div>
            <strong>{{ scene.label }}</strong>
            <span class="scene-key">{{ scene.scene }}</span>
          </div>
          <span class="badge" :class="scene.inherited ? 'muted' : 'primary'">
            {{ scene.inherited ? '继承全局' : '已绑定' }}
          </span>
        </div>

        <p class="effective">
          <template v-if="scene.effectiveProviderKey">
            {{ scene.effectiveProviderKey }} · {{ scene.effectiveModel }}
          </template>
          <template v-else>未配置全局默认</template>
        </p>

        <div v-if="editing === scene.scene" class="editor">
          <select v-model="form.providerKey" @change="onProviderChange">
            <option value="" disabled>选择 Provider</option>
            <option v-for="provider in providers" :key="provider.providerKey" :value="provider.providerKey">
              {{ provider.label }}（{{ provider.providerKey }}）
            </option>
          </select>
          <select v-model="form.model">
            <option value="" disabled>选择模型</option>
            <option v-for="model in models" :key="model.modelName" :value="model.modelName">
              {{ model.label || model.modelName }}
            </option>
          </select>
          <div class="editor-actions">
            <button type="button" class="btn primary" :disabled="busy" @click="save(scene.scene)">保存</button>
            <button type="button" class="btn ghost" @click="cancel">取消</button>
            <button
              v-if="!scene.inherited"
              type="button"
              class="link"
              :disabled="busy"
              @click="reset(scene.scene)"
            >
              恢复全局
            </button>
          </div>
        </div>
        <div v-else class="row-actions">
          <button type="button" class="link" :disabled="!hasProvider" @click="begin(scene)">
            {{ scene.inherited ? '绑定模型' : '修改绑定' }}
          </button>
          <button
            v-if="!scene.inherited"
            type="button"
            class="link"
            :disabled="busy"
            @click="reset(scene.scene)"
          >
            恢复全局
          </button>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.scenes {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.lead {
  margin: 0;
  color: var(--text-3);
  font-size: 12.5px;
}

.banner {
  padding: var(--space-2) var(--space-4);
  border-radius: var(--radius-btn);
  font-size: 12.5px;
}

.banner.danger {
  color: var(--danger);
  background: var(--danger-soft);
  border: 1px solid var(--danger);
}

.banner.success {
  color: var(--success);
  background: var(--success-soft);
  border: 1px solid var(--success);
}

.banner.warning {
  color: var(--warning);
  background: var(--warning-soft);
  border: 1px solid var(--warning);
}

.skeleton {
  height: 240px;
  border-radius: var(--radius-card);
  border: 1px solid var(--border);
  background: var(--surface);
}

.list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.row {
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
}

.row-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-2);
}

.row-head strong {
  font-size: 13px;
}

.scene-key {
  margin-left: var(--space-2);
  color: var(--text-3);
  font-size: 11px;
}

.badge {
  padding: 1px 8px;
  border-radius: var(--radius-tag);
  font-size: 11.5px;
}

.badge.muted {
  color: var(--text-3);
  background: var(--surface-muted);
}

.badge.primary {
  color: var(--primary);
  background: var(--primary-soft);
}

.effective {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--text-2);
}

.row-actions {
  display: flex;
  gap: var(--space-3);
  margin-top: var(--space-2);
}

.editor {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-top: var(--space-2);
}

.editor select {
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 12.5px;
}

.editor-actions {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.btn {
  padding: 5px 12px;
  border-radius: var(--radius-btn);
  border: 1px solid transparent;
  font-size: 12.5px;
}

.btn.primary {
  background: var(--primary);
  color: #fff;
}

.btn.ghost {
  background: var(--surface);
  border-color: var(--border);
  color: var(--text-2);
}

.btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: var(--primary);
  font-size: 12.5px;
}

.link:disabled {
  color: var(--text-3);
  cursor: not-allowed;
}
</style>
