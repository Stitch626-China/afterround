<script setup lang="ts">
/**
 * 模型与绑定：全局默认 + 7 个场景绑定的内联编辑。
 * 所有修改先落本地，点「保存修改」才调用后端；全局默认保存前二次确认。
 * Provider / Model 的增删仍在设置抽屉，避免两处入口语义重叠。
 */
import { computed, onMounted, ref } from 'vue'
import {
  getGlobalSetting,
  listProviders,
  updateGlobalSetting,
  type LlmProvider,
} from '@/api/llm'
import {
  bindSceneModel,
  listSceneBindings,
  unbindSceneModel,
  type LlmScene,
  type SceneBinding,
} from '@/api/jobs'
import { openSettings } from '@/composables/useSettings'

interface SceneDraft {
  providerKey: string
  model: string
}

const providers = ref<LlmProvider[]>([])
const scenes = ref<SceneBinding[]>([])
const globalProviderKey = ref('')
const globalModel = ref('')
const sceneDrafts = ref<Record<string, SceneDraft>>({})
const savedSnapshot = ref('')
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const notice = ref('')

const hasProviders = computed(() => providers.value.length > 0)

function snapshot(): string {
  return JSON.stringify({
    global: [globalProviderKey.value, globalModel.value],
    scenes: sceneDrafts.value,
  })
}

const dirty = computed(() => savedSnapshot.value !== '' && snapshot() !== savedSnapshot.value)

function modelsFor(providerKey: string) {
  const provider = providers.value.find((item) => item.providerKey === providerKey)
  return (provider?.models ?? []).filter((model) => model.enabled)
}

function firstModel(providerKey: string): string {
  return modelsFor(providerKey)[0]?.modelName ?? ''
}

function onGlobalProviderChange(): void {
  globalModel.value = firstModel(globalProviderKey.value)
}

function onSceneProviderChange(scene: string): void {
  const draft = sceneDrafts.value[scene]
  if (!draft) {
    return
  }
  draft.model = draft.providerKey ? firstModel(draft.providerKey) : ''
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [setting, providerList, sceneList] = await Promise.all([
      getGlobalSetting(),
      listProviders(),
      listSceneBindings(),
    ])
    providers.value = providerList
    scenes.value = sceneList
    globalProviderKey.value = setting.defaultProviderKey ?? ''
    globalModel.value = setting.defaultModel ?? ''
    sceneDrafts.value = Object.fromEntries(sceneList.map((scene) => [
      scene.scene,
      { providerKey: scene.providerKey ?? '', model: scene.model ?? '' },
    ]))
    savedSnapshot.value = snapshot()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '模型配置加载失败'
  } finally {
    loading.value = false
  }
}

function effectiveText(scene: LlmScene | null): string {
  if (scene) {
    const draft = sceneDrafts.value[scene]
    if (draft?.providerKey) {
      return draft.model ? `${draft.providerKey} · ${draft.model}` : '未选择模型'
    }
  }
  return globalProviderKey.value
    ? `${globalProviderKey.value} · ${globalModel.value}`
    : '未配置全局默认'
}

const evalScenes = computed(() => [
  { key: 'resume-analysis', label: '简历分析', text: effectiveText('RESUME_ANALYZE') },
  { key: 'jd-parse', label: 'JD 解析', text: effectiveText(null) },
  { key: 'review-analyze', label: '复盘分析', text: effectiveText('REVIEW_ANALYZE') },
])

/** 保存：全局改动需确认；场景留空表示恢复继承全局。 */
async function save(): Promise<void> {
  if (!dirty.value) {
    notice.value = '没有需要保存的修改'
    return
  }
  for (const [scene, draft] of Object.entries(sceneDrafts.value)) {
    if (draft.providerKey && !draft.model) {
      error.value = `场景 ${scene} 已选择 Provider，但未选择模型`
      return
    }
  }

  const original = JSON.parse(savedSnapshot.value) as {
    global: [string, string]
    scenes: Record<string, SceneDraft>
  }
  const globalChanged = original.global[0] !== globalProviderKey.value
    || original.global[1] !== globalModel.value
  if (globalChanged && (!globalProviderKey.value || !globalModel.value)) {
    error.value = '全局默认 Provider 与模型不能为空'
    return
  }
  if (globalChanged
      && !window.confirm('修改全局默认会影响所有未绑定场景，确定保存？')) {
    return
  }

  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    if (globalChanged) {
      await updateGlobalSetting({
        defaultProviderKey: globalProviderKey.value,
        defaultModel: globalModel.value,
      })
    }
    for (const scene of scenes.value) {
      const draft = sceneDrafts.value[scene.scene]
      const before = original.scenes[scene.scene]
      if (!draft || !before) {
        continue
      }
      if (draft.providerKey === before.providerKey && draft.model === before.model) {
        continue
      }
      if (!draft.providerKey) {
        await unbindSceneModel(scene.scene)
      } else {
        await bindSceneModel(scene.scene, draft.providerKey, draft.model)
      }
    }
    notice.value = '模型配置已保存，下一次调用立即生效'
    await load()
  } catch (ex) {
    error.value = ex instanceof Error ? ex.message : '模型配置保存失败'
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="binding">
    <div class="head">
      <div>
        <p class="lead">全局默认供未绑定场景使用；场景绑定优先于全局。所有修改点「保存修改」后才生效。</p>
        <p class="meta">Provider / Model 的增删仍在设置抽屉，与设置同步。</p>
      </div>
      <button type="button" class="link" @click="openSettings('model')">去设置管理服务商</button>
    </div>

    <div v-if="error" class="banner danger">{{ error }}</div>
    <div v-else-if="notice" class="banner success">{{ notice }}</div>
    <div v-if="!loading && !hasProviders" class="banner warning">
      还没有可用的 Provider，请先在设置中添加服务商与模型。
    </div>

    <div v-if="loading" class="skeleton">
      <div class="skeleton-row" />
      <div class="skeleton-row" />
    </div>

    <template v-else>
      <section class="block">
        <div class="block-head">
          <h3>全局默认</h3>
          <span class="status primary">未绑定场景使用</span>
        </div>
        <div class="inline">
          <select v-model="globalProviderKey" aria-label="全局 Provider" @change="onGlobalProviderChange">
            <option value="" disabled>选择 Provider</option>
            <option v-for="provider in providers" :key="provider.providerKey" :value="provider.providerKey">
              {{ provider.label }}（{{ provider.providerKey }}）
            </option>
          </select>
          <select v-model="globalModel" aria-label="全局模型">
            <option value="" disabled>选择模型</option>
            <option v-for="model in modelsFor(globalProviderKey)" :key="model.modelName" :value="model.modelName">
              {{ model.label || model.modelName }}
            </option>
          </select>
        </div>
      </section>

      <section class="block">
        <div class="block-head">
          <h3>场景绑定</h3>
          <span class="meta">留空表示继承全局</span>
        </div>
        <ul class="rows">
          <li v-for="scene in scenes" :key="scene.scene" class="row">
            <div class="row-title">
              <strong>{{ scene.label }}</strong>
              <span class="meta">{{ scene.scene }}</span>
            </div>
            <div class="inline">
              <select
                :value="sceneDrafts[scene.scene]?.providerKey ?? ''"
                :aria-label="`${scene.label} Provider`"
                @change="(event) => {
                  const target = sceneDrafts[scene.scene]
                  if (target) target.providerKey = (event.target as HTMLSelectElement).value
                  onSceneProviderChange(scene.scene)
                }"
              >
                <option value="">继承全局</option>
                <option v-for="provider in providers" :key="provider.providerKey" :value="provider.providerKey">
                  {{ provider.label }}
                </option>
              </select>
              <select
                :value="sceneDrafts[scene.scene]?.model ?? ''"
                :disabled="!sceneDrafts[scene.scene]?.providerKey"
                :aria-label="`${scene.label} 模型`"
                @change="(event) => {
                  const target = sceneDrafts[scene.scene]
                  if (target) target.model = (event.target as HTMLSelectElement).value
                }"
              >
                <option value="" disabled>选择模型</option>
                <option
                  v-for="model in modelsFor(sceneDrafts[scene.scene]?.providerKey ?? '')"
                  :key="model.modelName"
                  :value="model.modelName"
                >
                  {{ model.label || model.modelName }}
                </option>
              </select>
            </div>
            <p class="effective">生效：{{ effectiveText(scene.scene) }}</p>
          </li>
        </ul>
      </section>

      <section class="block">
        <div class="block-head">
          <h3>评测场景生效模型</h3>
          <span class="meta">评测跟随场景绑定；单独绑定将在评测运行上线后提供</span>
        </div>
        <table class="table">
          <thead>
            <tr>
              <th>评测场景</th>
              <th>生效模型</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in evalScenes" :key="item.key">
              <td>{{ item.label }}<span class="key">{{ item.key }}</span></td>
              <td>{{ item.text }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <div class="save-bar">
        <span v-if="dirty" class="status warning">有未保存修改</span>
        <span v-else class="status success">已与服务端一致</span>
        <button type="button" class="btn primary" :disabled="busy || !hasProviders" @click="save">
          保存修改
        </button>
      </div>
    </template>
  </section>
</template>

<style scoped>
.binding {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.head,
.block-head,
.save-bar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-3);
}

.head .meta {
  margin: 3px 0 0;
}

.block {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.block-head {
  align-items: center;
}

.block-head h3 {
  margin: 0;
  font-size: var(--fs-h2);
  font-weight: 500;
}

.rows {
  list-style: none;
  margin: 0;
  padding: 0;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  overflow: hidden;
}

.row {
  padding: var(--space-3) var(--space-4);
  border-top: 1px solid var(--border);
}

.row:first-child {
  border-top: none;
}

.row-title {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
  margin-bottom: var(--space-2);
}

.row-title strong {
  font-size: var(--fs-body);
  font-weight: 500;
}

.inline {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2);
}

select {
  width: 100%;
  height: 34px;
  padding: 0 var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: var(--fs-sm);
}

.effective {
  margin: 4px 0 0;
  color: var(--text-3);
  font-size: var(--fs-xs);
}

.key {
  margin-left: 6px;
  color: var(--text-3);
  font-size: var(--fs-overline);
}

.save-bar {
  align-items: center;
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

@media (max-width: 900px) {
  .inline {
    grid-template-columns: 1fr;
  }
}
</style>
