<script setup lang="ts">
/**
 * 模型服务设置：Provider / Model 增删改、全局默认、连通测试、模型列举。
 * 全部走 /api/llm/*（契约唯一源），保存即生效。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import {
  createModel,
  createProvider,
  deleteModel,
  deleteProvider,
  getGlobalSetting,
  listProviders,
  listUpstreamModels,
  testProvider,
  updateGlobalSetting,
  updateModel,
  updateProvider,
  type ConnectivityTest,
  type GlobalSetting,
  type LlmModel,
  type LlmProvider,
  type UpstreamModelList,
} from '@/api/llm'

const emit = defineEmits<{ navigate: [] }>()

const providers = ref<LlmProvider[]>([])
const global = ref<GlobalSetting>({ defaultProviderKey: null, defaultModel: null })
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')

const showForm = ref(false)
const editingKey = ref<string | null>(null)
const providerForm = reactive({
  providerKey: '',
  label: '',
  baseUrl: '',
  apiKey: '',
  enabled: true,
  sortOrder: 10,
})

const drafts = reactive<Record<string, { modelName: string; label: string }>>({})
const tests = reactive<Record<string, ConnectivityTest | null>>({})
const upstream = reactive<Record<string, UpstreamModelList | null>>({})
const confirming = ref('')
const editingModelKey = ref('')
const editingLabel = ref('')

function modelKey(provider: LlmProvider, model: LlmModel): string {
  return `${provider.providerKey}/${model.modelName}`
}

function beginEditModel(provider: LlmProvider, model: LlmModel): void {
  editingModelKey.value = modelKey(provider, model)
  editingLabel.value = model.label
}

async function saveModelLabel(provider: LlmProvider, model: LlmModel): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await updateModel(provider.providerKey, model.modelName, { label: editingLabel.value.trim() || model.modelName })
    editingModelKey.value = ''
    notice.value = '模型展示名已更新'
    await load()
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

async function addUpstreamModel(provider: LlmProvider, modelName: string): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await createModel(provider.providerKey, {
      modelName,
      label: modelName,
      enabled: true,
      isDefault: modelsOf(provider).length === 0,
      sortOrder: 10,
    })
    notice.value = `已添加模型 ${modelName}`
    await load()
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

const globalProvider = computed(() =>
  providers.value.find((item) => item.providerKey === global.value.defaultProviderKey),
)

function draftFor(key: string): { modelName: string; label: string } {
  if (!drafts[key]) {
    drafts[key] = { modelName: '', label: '' }
  }
  return drafts[key]
}

function modelsOf(provider: LlmProvider): LlmModel[] {
  return provider.models ?? []
}

function isGlobal(provider: LlmProvider, model: LlmModel): boolean {
  return global.value.defaultProviderKey === provider.providerKey
    && global.value.defaultModel === model.modelName
}

function fail(ex: unknown): void {
  error.value = ex instanceof Error ? ex.message : '操作失败'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [list, setting] = await Promise.all([listProviders(), getGlobalSetting()])
    providers.value = list
    global.value = setting
    error.value = ''
  } catch (ex) {
    fail(ex)
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingKey.value = null
  Object.assign(providerForm, {
    providerKey: '', label: '', baseUrl: '', apiKey: '', enabled: true, sortOrder: 10,
  })
  showForm.value = true
  error.value = ''
}

function openEdit(provider: LlmProvider): void {
  editingKey.value = provider.providerKey
  Object.assign(providerForm, {
    providerKey: provider.providerKey,
    label: provider.label,
    baseUrl: provider.baseUrl,
    apiKey: '',
    enabled: provider.enabled,
    sortOrder: provider.sortOrder,
  })
  showForm.value = true
  error.value = ''
}

async function saveProvider(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    if (editingKey.value) {
      await updateProvider(editingKey.value, {
        label: providerForm.label,
        baseUrl: providerForm.baseUrl,
        apiKey: providerForm.apiKey || undefined,
        enabled: providerForm.enabled,
        sortOrder: providerForm.sortOrder,
      })
      notice.value = '服务商已更新，已立即生效'
    } else {
      await createProvider({
        providerKey: providerForm.providerKey,
        label: providerForm.label,
        baseUrl: providerForm.baseUrl,
        apiKey: providerForm.apiKey,
        enabled: providerForm.enabled,
        sortOrder: providerForm.sortOrder,
      })
      notice.value = '服务商已创建，可继续添加模型'
    }
    showForm.value = false
    await load()
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

async function removeProvider(provider: LlmProvider): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await deleteProvider(provider.providerKey)
    confirming.value = ''
    notice.value = `已删除服务商 ${provider.label}`
    await load()
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

async function runTest(provider: LlmProvider): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    tests[provider.providerKey] = await testProvider(provider.providerKey)
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

async function syncModels(provider: LlmProvider): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    upstream[provider.providerKey] = await listUpstreamModels(provider.providerKey)
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

async function addModel(provider: LlmProvider): Promise<void> {
  const draft = draftFor(provider.providerKey)
  if (!draft.modelName.trim()) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    await createModel(provider.providerKey, {
      modelName: draft.modelName.trim(),
      label: (draft.label || draft.modelName).trim(),
      enabled: true,
      isDefault: modelsOf(provider).length === 0,
      sortOrder: 10,
    })
    draft.modelName = ''
    draft.label = ''
    notice.value = '模型已添加'
    await load()
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

async function removeModel(provider: LlmProvider, model: LlmModel): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await deleteModel(provider.providerKey, model.modelName)
    confirming.value = ''
    notice.value = `已删除模型 ${model.label}`
    await load()
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

async function toggleModel(provider: LlmProvider, model: LlmModel): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    await updateModel(provider.providerKey, model.modelName, { enabled: !model.enabled })
    await load()
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

async function setGlobal(provider: LlmProvider, model: LlmModel): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    global.value = await updateGlobalSetting({
      defaultProviderKey: provider.providerKey,
      defaultModel: model.modelName,
    })
    notice.value = `全局默认已切换为 ${provider.label} · ${model.label}`
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="panel">
    <section class="global">
      <span class="global-label">全局默认</span>
      <template v-if="globalProvider && global.defaultModel">
        <strong>{{ globalProvider.label }}</strong>
        <code>{{ global.defaultModel }}</code>
      </template>
      <span v-else class="global-empty">未设置 · 上传简历后不会自动分析</span>
    </section>

    <div class="toolbar">
      <button type="button" class="btn primary" @click="openCreate">新增服务商</button>
      <button type="button" class="btn ghost" @click="load">刷新</button>
      <button type="button" class="btn ghost" @click="emit('navigate')">打开模型对话</button>
    </div>

    <p v-if="error" class="banner danger">{{ error }}</p>
    <p v-else-if="notice" class="banner success">{{ notice }}</p>

    <!-- 新增 / 编辑服务商 -->
    <form v-if="showForm" class="card form" @submit.prevent="saveProvider">
      <h3>{{ editingKey ? '编辑服务商' : '新增服务商' }}</h3>
      <label>
        <span>标识（创建后不可改）</span>
        <input v-model="providerForm.providerKey" :disabled="!!editingKey" placeholder="corp-gateway" required />
      </label>
      <label>
        <span>展示名称</span>
        <input v-model="providerForm.label" placeholder="公司内网网关" required />
      </label>
      <label>
        <span>Base URL（含 /v1）</span>
        <input v-model="providerForm.baseUrl" placeholder="http://host:3000/v1" required />
      </label>
      <label>
        <span>API Key {{ editingKey ? '（留空保持不变）' : '' }}</span>
        <input v-model="providerForm.apiKey" type="password" :required="!editingKey" placeholder="sk-..." />
      </label>
      <div class="form-actions">
        <button type="submit" class="btn primary" :disabled="busy">保存</button>
        <button type="button" class="btn ghost" @click="showForm = false">取消</button>
      </div>
    </form>

    <p v-if="loading" class="muted">加载中…</p>
    <p v-else-if="!providers.length" class="muted">
      还没有服务商。新增一个，填入地址与密钥即可使用；一个服务商可挂多个模型。
    </p>

    <section v-for="provider in providers" :key="provider.providerKey" class="card provider">
      <header class="provider-head">
        <div class="provider-title">
          <strong>{{ provider.label }}</strong>
          <code>{{ provider.providerKey }}</code>
          <span v-if="!provider.enabled" class="tag muted">已停用</span>
        </div>
        <div class="row-actions">
          <button type="button" class="link" :disabled="busy" @click="runTest(provider)">连通测试</button>
          <button type="button" class="link" :disabled="busy" @click="syncModels(provider)">上游模型</button>
          <button type="button" class="link" @click="openEdit(provider)">编辑</button>
          <button
            v-if="confirming === `provider:${provider.providerKey}`"
            type="button"
            class="link danger"
            :disabled="busy"
            @click="removeProvider(provider)"
          >确认删除</button>
          <button
            v-else
            type="button"
            class="link danger"
            @click="confirming = `provider:${provider.providerKey}`"
          >删除</button>
        </div>
      </header>

      <p class="provider-meta">{{ provider.baseUrl }} · {{ provider.apiKeyMasked || '密钥未回显' }}</p>

      <p v-if="tests[provider.providerKey]" class="test-result" :class="tests[provider.providerKey]!.success ? 'ok' : 'bad'">
        {{ tests[provider.providerKey]!.success ? '连通正常' : '连接失败' }}
        · {{ tests[provider.providerKey]!.latencyMs }}ms
        <template v-if="tests[provider.providerKey]!.upstreamModel">
          · 上游实际模型 {{ tests[provider.providerKey]!.upstreamModel }}
        </template>
        <template v-if="tests[provider.providerKey]!.message">
          · {{ tests[provider.providerKey]!.message }}
        </template>
      </p>

      <ul class="models">
        <li v-for="model in modelsOf(provider)" :key="model.modelName" class="model">
          <div class="model-title">
            <input
              v-if="editingModelKey === modelKey(provider, model)"
              v-model="editingLabel"
              class="inline-input"
              :disabled="busy"
            />
            <span v-else>{{ model.label }}</span>
            <code>{{ model.modelName }}</code>
            <span v-if="model.enabled === false" class="tag muted">停用</span>
            <span v-if="isGlobal(provider, model)" class="tag primary">全局默认</span>
          </div>
          <div class="row-actions">
            <template v-if="editingModelKey === modelKey(provider, model)">
              <button type="button" class="link" :disabled="busy" @click="saveModelLabel(provider, model)">保存</button>
              <button type="button" class="link" @click="editingModelKey = ''">取消</button>
            </template>
            <template v-else>
              <button type="button" class="link" :disabled="busy || isGlobal(provider, model)" @click="setGlobal(provider, model)">
                设为默认
              </button>
              <button type="button" class="link" :disabled="busy" @click="beginEditModel(provider, model)">
                编辑
              </button>
              <button type="button" class="link" :disabled="busy" @click="toggleModel(provider, model)">
                {{ model.enabled === false ? '启用' : '停用' }}
              </button>
              <button
                v-if="confirming === `model:${provider.providerKey}/${model.modelName}`"
                type="button"
                class="link danger"
                :disabled="busy"
                @click="removeModel(provider, model)"
              >确认删除</button>
              <button
                v-else
                type="button"
                class="link danger"
                @click="confirming = `model:${provider.providerKey}/${model.modelName}`"
              >删除</button>
            </template>
          </div>
        </li>
      </ul>

      <p v-if="!modelsOf(provider).length" class="muted small">还没有模型；添加一个后即可设为全局默认。</p>

      <form class="model-form" @submit.prevent="addModel(provider)">
        <input v-model="draftFor(provider.providerKey).modelName" placeholder="模型名，如 Qwen3.8-27B" />
        <input v-model="draftFor(provider.providerKey).label" placeholder="展示名（可空）" />
        <button type="submit" class="btn ghost" :disabled="busy">添加模型</button>
      </form>

      <p v-if="upstream[provider.providerKey]" class="upstream">
        上游 {{ upstream[provider.providerKey]!.source === 'UPSTREAM' ? '实时' : '回退本地' }}模型：
        <span v-for="name in upstream[provider.providerKey]!.models" :key="name" class="upstream-item">
          <code>{{ name }}</code>
          <button
            v-if="!modelsOf(provider).some((model) => model.modelName === name)"
            type="button"
            class="link"
            :disabled="busy"
            @click="addUpstreamModel(provider, name)"
          >添加</button>
        </span>
      </p>
    </section>
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.global {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-btn);
  background: var(--surface-muted);
  font-size: 13px;
}

.global-label {
  color: var(--text-3);
  font-size: 12px;
}

.global code,
.provider code,
.model code {
  font-family: var(--font-mono);
  font-size: 11.5px;
  color: var(--text-2);
  background: var(--surface-muted);
  padding: 1px 6px;
  border-radius: var(--radius-tag);
}

.global-empty {
  color: var(--text-3);
}

.toolbar {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.btn {
  padding: 6px 12px;
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

.banner {
  margin: 0;
  padding: var(--space-2) var(--space-3);
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

.card {
  padding: var(--space-4);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.form h3 {
  font-size: 13.5px;
}

.form label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--text-3);
}

input {
  padding: 6px 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-size: 13px;
}

input:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 1px;
}

.form-actions {
  display: flex;
  gap: var(--space-2);
}

.provider-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-3);
}

.provider-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  font-size: 13.5px;
}

.provider-meta {
  margin: var(--space-1) 0 var(--space-3);
  font-size: 12px;
  color: var(--text-3);
  word-break: break-all;
}

.row-actions {
  display: flex;
  gap: var(--space-3);
  flex-wrap: wrap;
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: var(--primary);
  font-size: 12px;
}

.link.danger {
  color: var(--danger);
}

.link:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.test-result {
  margin: 0 0 var(--space-3);
  font-size: 12px;
}

.test-result.ok {
  color: var(--success);
}

.test-result.bad {
  color: var(--danger);
}

.models {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.model {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) 0;
  border-top: 1px solid var(--border);
}

.model-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: 12.5px;
  flex-wrap: wrap;
}

.tag {
  padding: 0 6px;
  border-radius: var(--radius-tag);
  font-size: 10.5px;
}

.tag.primary {
  color: var(--primary);
  background: var(--primary-soft);
}

.tag.muted {
  color: var(--text-3);
  background: var(--surface-muted);
}

.model-form {
  display: flex;
  gap: var(--space-2);
  margin-top: var(--space-3);
  flex-wrap: wrap;
}

.model-form input {
  flex: 1;
  min-width: 120px;
}

.upstream {
  margin: var(--space-2) 0 0;
  font-size: 11.5px;
  color: var(--text-3);
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
  align-items: center;
}

.upstream-item {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
}

.inline-input {
  width: 140px;
  padding: 2px 8px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-size: 12.5px;
}

.muted {
  margin: 0;
  color: var(--text-3);
  font-size: 12.5px;
}

.small {
  font-size: 11.5px;
  margin-top: var(--space-2);
}
</style>
