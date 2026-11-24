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
const selectedUpstream = reactive<Record<string, string[]>>({})
const fetchingUpstream = reactive<Record<string, boolean>>({})
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

/** 第一个可用模型：未设置全局默认时提供一键设置入口。 */
const firstAvailable = computed(() => {
  for (const provider of providers.value) {
    const model = modelsOf(provider).find((item) => item.enabled !== false)
    if (model) {
      return { provider, model }
    }
  }
  return null
})

/** 上游有、本地还没有登记的模型。 */
function addableUpstream(provider: LlmProvider): string[] {
  const names = upstream[provider.providerKey]?.models ?? []
  return names.filter((name) => !modelsOf(provider).some((model) => model.modelName === name))
}

function selectedOf(provider: LlmProvider): string[] {
  return selectedUpstream[provider.providerKey] ?? []
}

function selectAllUpstream(provider: LlmProvider): void {
  selectedUpstream[provider.providerKey] = [...addableUpstream(provider)]
}

function clearSelected(provider: LlmProvider): void {
  selectedUpstream[provider.providerKey] = []
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
    let createdKey: string | null = null
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
      const created = await createProvider({
        providerKey: providerForm.providerKey,
        label: providerForm.label,
        baseUrl: providerForm.baseUrl,
        apiKey: providerForm.apiKey,
        enabled: providerForm.enabled,
        sortOrder: providerForm.sortOrder,
      })
      createdKey = created.providerKey
    }
    showForm.value = false
    await load()
    if (createdKey) {
      await pullModelsAfterCreate(createdKey)
    }
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
  const local = modelsOf(provider)
  if (local.length && !local.some((model) => model.enabled !== false)) {
    error.value = '该服务商的模型都已停用：请先在模型行点「启用」再测试'
    return
  }
  // 没有模型时先拉上游并引导选择，避免直接报「没有可用模型」
  if (!local.length) {
    const result = await syncModels(provider)
    const addable = result ? addableUpstream(provider) : []
    if (addable.length) {
      selectedUpstream[provider.providerKey] = [addable[0]]
      notice.value = '该服务商还没有模型：已从上游拉取，请勾选后点「添加所选模型」'
    } else {
      error.value = '该服务商还没有可用模型，请手动填写模型名后重试'
    }
    return
  }
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

/** 拉取上游模型并默认勾选第一个可添加项。 */
async function syncModels(provider: LlmProvider): Promise<UpstreamModelList | null> {
  busy.value = true
  error.value = ''
  fetchingUpstream[provider.providerKey] = true
  try {
    const result = await listUpstreamModels(provider.providerKey)
    upstream[provider.providerKey] = result
    const addable = addableUpstream(provider)
    selectedUpstream[provider.providerKey] = addable.slice(0, 1)
    return result
  } catch (ex) {
    fail(ex)
    return null
  } finally {
    fetchingUpstream[provider.providerKey] = false
    busy.value = false
  }
}

/** 新增服务商后自动拉模型：唯一模型直接启用并设为全局默认，多模型交给用户勾选。 */
async function pullModelsAfterCreate(providerKey: string): Promise<void> {
  const provider = providers.value.find((item) => item.providerKey === providerKey)
  if (!provider) {
    return
  }
  notice.value = '服务商已创建，正在从上游拉取可用模型…'
  const result = await syncModels(provider)
  if (!result) {
    return
  }
  const addable = addableUpstream(provider)
  if (result.source === 'UPSTREAM' && addable.length === 1) {
    selectedUpstream[provider.providerKey] = [addable[0]]
    await addSelectedUpstream(provider)
    return
  }
  if (addable.length > 1) {
    notice.value = `上游发现 ${addable.length} 个可用模型，请勾选后点「添加所选模型」`
    return
  }
  notice.value = result.source === 'UPSTREAM'
    ? '上游未返回可用模型，请手动填写模型名'
    : '上游模型列举不可用（请检查 Base URL / API Key），可手动填写模型名'
}

/** 批量添加勾选的上游模型；若尚无全局默认，自动把第一个新增模型设为默认。 */
async function addSelectedUpstream(provider: LlmProvider): Promise<void> {
  const names = selectedOf(provider)
  if (!names.length) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    const hadModels = modelsOf(provider).length > 0
    for (const [index, name] of names.entries()) {
      await createModel(provider.providerKey, {
        modelName: name,
        label: name,
        enabled: true,
        isDefault: !hadModels && index === 0,
        sortOrder: 10,
      })
    }
    const needDefault = !global.value.defaultProviderKey
    selectedUpstream[provider.providerKey] = []
    upstream[provider.providerKey] = null
    await load()
    if (needDefault) {
      const target = providers.value.find((item) => item.providerKey === provider.providerKey)
      const model = target ? modelsOf(target).find((item) => item.modelName === names[0]) : undefined
      if (target && model) {
        global.value = await updateGlobalSetting({
          defaultProviderKey: target.providerKey,
          defaultModel: model.modelName,
        })
        notice.value = `已添加 ${names.length} 个模型，并将 ${names[0]} 设为全局默认`
        return
      }
    }
    notice.value = `已添加 ${names.length} 个模型，可在模型行点「设为默认」`
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

async function addModel(provider: LlmProvider): Promise<void> {
  const draft = draftFor(provider.providerKey)
  const modelName = draft.modelName.trim()
  if (!modelName) {
    return
  }
  busy.value = true
  error.value = ''
  try {
    const needDefault = !global.value.defaultProviderKey
    await createModel(provider.providerKey, {
      modelName,
      label: (draft.label || modelName).trim(),
      enabled: true,
      isDefault: modelsOf(provider).length === 0,
      sortOrder: 10,
    })
    draft.modelName = ''
    draft.label = ''
    await load()
    if (needDefault) {
      const target = providers.value.find((item) => item.providerKey === provider.providerKey)
      const model = target ? modelsOf(target).find((item) => item.modelName === modelName) : undefined
      if (target && model) {
        global.value = await updateGlobalSetting({
          defaultProviderKey: target.providerKey,
          defaultModel: model.modelName,
        })
        notice.value = '模型已添加，并已设为全局默认'
        return
      }
    }
    notice.value = '模型已添加'
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

/** 未设置全局默认时的一键入口：用第一个可用模型。 */
async function setFirstGlobal(): Promise<void> {
  if (!firstAvailable.value) {
    return
  }
  await setGlobal(firstAvailable.value.provider, firstAvailable.value.model)
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
      <template v-else>
        <span class="global-empty">未设置 · 简历分析前必须设置</span>
        <button v-if="firstAvailable" type="button" class="link" :disabled="busy" @click="setFirstGlobal">
          用 {{ firstAvailable.model.label }} 设为默认
        </button>
      </template>
    </section>
    <p class="global-hint">全局默认用于简历分析，以及对话未单独指定模型的场景。</p>

    <div class="toolbar">
      <button type="button" class="btn primary" @click="openCreate">新增服务商</button>
      <button type="button" class="btn ghost" @click="load">刷新</button>
      <button type="button" class="btn ghost" @click="emit('navigate')">打开模型对话</button>
    </div>

    <p v-if="error" class="banner danger">{{ error }}</p>
    <p v-else-if="notice" class="banner success">{{ notice }}</p>

    <!-- 新增 / 编辑服务商 -->
    <form v-if="showForm" class="card form" @submit.prevent="saveProvider">
      <h3 class="card-title">{{ editingKey ? '编辑服务商' : '新增服务商' }}</h3>
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
          <button type="button" class="link" :disabled="busy" @click="syncModels(provider)">
            {{ fetchingUpstream[provider.providerKey] ? '拉取中…' : '拉取上游模型' }}
          </button>
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

      <p v-if="tests[provider.providerKey]" class="status" :class="tests[provider.providerKey]!.success ? 'success' : 'danger'">
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
              <button v-if="!isGlobal(provider, model)" type="button" class="link" :disabled="busy" @click="setGlobal(provider, model)">
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

      <div v-if="!modelsOf(provider).length" class="model-empty">
        <span class="muted small">还没有模型：从上游拉取后勾选启用，或手动填写模型名。</span>
        <button
          type="button"
          class="link"
          :disabled="busy"
          @click="syncModels(provider)"
        >{{ fetchingUpstream[provider.providerKey] ? '拉取中…' : '从上游拉取模型' }}</button>
      </div>

      <form class="model-form" @submit.prevent="addModel(provider)">
        <input v-model="draftFor(provider.providerKey).modelName" placeholder="模型名，如 Qwen3.8-27B" />
        <input v-model="draftFor(provider.providerKey).label" placeholder="展示名（可空）" />
        <button type="submit" class="btn ghost" :disabled="busy">手动添加</button>
      </form>

      <section v-if="upstream[provider.providerKey]" class="upstream-panel">
        <p class="upstream-head">
          <template v-if="upstream[provider.providerKey]!.source === 'UPSTREAM'">
            上游发现 {{ upstream[provider.providerKey]!.models.length }} 个模型，请勾选要启用的：
          </template>
          <template v-else>
            上游模型列举不可用（请检查 Base URL / API Key），本地已登记 {{ upstream[provider.providerKey]!.models.length }} 个模型。
          </template>
        </p>
        <template v-if="addableUpstream(provider).length">
          <div class="upstream-options">
            <label v-for="name in addableUpstream(provider)" :key="name" class="upstream-option">
              <input v-model="selectedUpstream[provider.providerKey]" type="checkbox" :value="name" />
              <code>{{ name }}</code>
            </label>
          </div>
          <div class="upstream-actions">
            <button
              type="button"
              class="btn primary"
              :disabled="busy || !selectedOf(provider).length"
              @click="addSelectedUpstream(provider)"
            >添加所选模型（{{ selectedOf(provider).length }}）</button>
            <button type="button" class="btn ghost" :disabled="busy" @click="selectAllUpstream(provider)">全选</button>
            <button type="button" class="btn ghost" :disabled="busy" @click="clearSelected(provider)">清空</button>
          </div>
        </template>
        <p v-else class="muted small">上游模型都已添加，可直接做连通测试。</p>
      </section>
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

.global-hint {
  margin: calc(var(--space-2) * -1) 0 0;
  font-size: 11.5px;
  color: var(--text-3);
}

.toolbar {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.form label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: var(--fs-xs);
  color: var(--text-3);
}

input {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-size: var(--fs-sm);
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
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-2) var(--space-3);
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

.models {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.model {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-2) var(--space-3);
  padding: var(--space-2) 0;
  border-top: 1px solid var(--border);
}

.model-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: var(--fs-sm);
  flex-wrap: wrap;
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

.model-empty {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
  margin-top: var(--space-2);
}

.upstream-panel {
  margin-top: var(--space-3);
  padding: var(--space-3);
  border-radius: var(--radius-btn);
  background: var(--surface-muted);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.upstream-head {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-3);
}

.upstream-options {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
}

.upstream-option {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--fs-sm);
  cursor: pointer;
}

.upstream-actions {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.inline-input {
  width: 140px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-size: var(--fs-sm);
}

.small {
  font-size: var(--fs-xs);
  margin-top: var(--space-2);
}
</style>
