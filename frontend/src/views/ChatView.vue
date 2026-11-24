<script setup lang="ts">
/**
 * 模型对话（M1 能力验证）：会话列表、Provider + Model 两级切换、SSE 流式回答。
 * 切换守卫以服务端为准：PROCESSING / LOCKED 时按钮置灰，409 时展示原因。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  createChatSession,
  deleteChatSession,
  getChatSession,
  listChatMessages,
  listChatSessions,
  streamChatMessage,
  switchChatModel,
  type ChatMessage,
  type ChatSession,
} from '@/api/chat'
import { listProviders, type LlmModel, type LlmProvider } from '@/api/llm'
import EmptyState from '@/components/EmptyState.vue'

const sessions = ref<ChatSession[]>([])
const session = ref<ChatSession | null>(null)
const messages = ref<ChatMessage[]>([])
const providers = ref<LlmProvider[]>([])
const total = ref(0)
const page = ref(1)
const size = 20

const selectedProvider = ref('')
const selectedModel = ref('')
const input = ref('')
const draft = ref('')
const streaming = ref(false)
const loading = ref(true)
const busy = ref(false)
const confirming = ref(false)
const error = ref('')
const notice = ref('')
const messageBox = ref<HTMLElement | null>(null)
let controller: AbortController | null = null
/** 会话世代：切换 / 删除 / 翻页 / 离页后让旧请求的迟到响应失效。 */
let epoch = 0
/** 列表请求序号：避免较慢的旧列表响应覆盖新列表。 */
let listSeq = 0

const pages = computed(() => Math.max(1, Math.ceil(total.value / size)))
const models = computed<LlmModel[]>(() => {
  const provider = providers.value.find((item) => item.providerKey === selectedProvider.value)
  return (provider?.models ?? []).filter((model) => model.enabled !== false)
})
const editable = computed(() => !!session.value && session.value.status === 'IDLE' && !streaming.value)
const canSwitch = computed(() =>
  editable.value
  && !!selectedProvider.value
  && !!selectedModel.value
  && (selectedProvider.value !== session.value?.currentProviderKey
    || selectedModel.value !== session.value?.currentModel),
)
const canSend = computed(() =>
  editable.value && input.value.trim().length > 0,
)

const STATUS_TEXT: Record<string, string> = {
  IDLE: '空闲',
  PROCESSING: '生成中',
  LOCKED: '已锁定',
}
const STATUS_TONE: Record<string, string> = {
  IDLE: 'success',
  PROCESSING: 'primary',
  LOCKED: 'muted',
}

function fail(ex: unknown): void {
  error.value = ex instanceof Error ? ex.message : '操作失败'
}

function syncSelectors(value: ChatSession): void {
  selectedProvider.value = value.currentProviderKey ?? ''
  selectedModel.value = value.currentModel ?? ''
}

async function loadSessions(preferredId?: number): Promise<void> {
  const seq = ++listSeq
  try {
    const result = await listChatSessions(page.value, size)
    if (seq !== listSeq) {
      return
    }
    sessions.value = result.items
    total.value = result.total
    error.value = ''
    const target = preferredId ?? session.value?.id ?? result.items[0]?.id
    if (target && result.items.some((item) => item.id === target)) {
      await selectSession(target)
    } else if (!result.items.length) {
      session.value = null
      messages.value = []
    }
  } catch (ex) {
    if (seq === listSeq) {
      fail(ex)
    }
  } finally {
    if (seq === listSeq) {
      loading.value = false
    }
  }
}

/** 仅刷新左侧列表，不改变当前选中会话（流式结束收尾用，避免切回旧会话）。 */
async function refreshSessionList(): Promise<void> {
  const current = epoch
  try {
    const result = await listChatSessions(page.value, size)
    if (current !== epoch) {
      return
    }
    sessions.value = result.items
    total.value = result.total
  } catch (ex) {
    if (current === epoch) {
      fail(ex)
    }
  }
}

function cancelStream(): void {
  controller?.abort()
  controller = null
  streaming.value = false
  draft.value = ''
}

async function selectSession(id: number): Promise<void> {
  const current = ++epoch
  cancelStream()
  try {
    const [detail, history] = await Promise.all([getChatSession(id), listChatMessages(id)])
    if (current !== epoch) {
      return
    }
    session.value = detail
    messages.value = history
    syncSelectors(detail)
    confirming.value = false
    error.value = ''
    await scrollToBottom()
  } catch (ex) {
    if (current === epoch) {
      fail(ex)
    }
  }
}

async function refreshActive(id?: number): Promise<void> {
  const target = id ?? session.value?.id
  if (!target) {
    return
  }
  const current = epoch
  try {
    const [detail, history] = await Promise.all([
      getChatSession(target),
      listChatMessages(target),
    ])
    if (current !== epoch || session.value?.id !== target) {
      return
    }
    session.value = detail
    messages.value = history
    syncSelectors(detail)
  } catch (ex) {
    if (current === epoch) {
      fail(ex)
    }
  }
}

async function createSession(): Promise<void> {
  epoch += 1
  cancelStream()
  busy.value = true
  error.value = ''
  try {
    const created = await createChatSession({})
    page.value = 1
    await loadSessions(created.id)
    notice.value = '已新建会话'
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

async function removeSession(): Promise<void> {
  if (!session.value) {
    return
  }
  const id = session.value.id
  const lastOnPage = sessions.value.length === 1
  epoch += 1
  cancelStream()
  busy.value = true
  error.value = ''
  try {
    await deleteChatSession(id)
    confirming.value = false
    session.value = null
    messages.value = []
    // 删除末页唯一会话后回到上一页，避免停在空页无法分页
    if (lastOnPage && page.value > 1) {
      page.value -= 1
    }
    await loadSessions()
  } catch (ex) {
    fail(ex)
  } finally {
    busy.value = false
  }
}

function onProviderChange(): void {
  selectedModel.value = models.value[0]?.modelName ?? ''
}

async function applySwitch(): Promise<void> {
  if (!session.value || !canSwitch.value) {
    return
  }
  const current = epoch
  const id = session.value.id
  busy.value = true
  error.value = ''
  try {
    const updated = await switchChatModel(id, {
      providerKey: selectedProvider.value,
      model: selectedModel.value,
    })
    if (current !== epoch || session.value?.id !== id) {
      return
    }
    session.value = updated
    syncSelectors(updated)
    notice.value = `已切换到 ${selectedProvider.value} · ${selectedModel.value}`
  } catch (ex) {
    if (current === epoch) {
      fail(ex)
      if (session.value) {
        syncSelectors(session.value)
      }
    }
  } finally {
    busy.value = false
  }
}

async function send(): Promise<void> {
  if (!session.value || !canSend.value) {
    return
  }
  const content = input.value.trim()
  const id = session.value.id
  const sendEpoch = epoch
  input.value = ''
  draft.value = ''
  streaming.value = true
  session.value.status = 'PROCESSING'
  error.value = ''
  messages.value.push({
    id: -Date.now(),
    role: 'USER',
    content,
    createdAt: new Date().toISOString(),
  })
  void scrollToBottom()
  controller = new AbortController()
  const signal = controller.signal

  try {
    await streamChatMessage(id, content, (event) => {
      // 慢流期间切换/删除会话时丢弃旧会话的增量，避免串写到当前会话
      if (session.value?.id !== id) {
        return
      }
      if (event.type === 'delta') {
        draft.value += event.content ?? ''
        void scrollToBottom()
      } else if (event.type === 'done') {
        messages.value.push({
          id: event.messageId ?? -Date.now(),
          role: 'ASSISTANT',
          content: draft.value,
          llmProvider: event.providerKey ?? null,
          llmModel: event.model ?? null,
          createdAt: new Date().toISOString(),
        })
        draft.value = ''
      } else if (event.type === 'error') {
        error.value = event.message ?? '生成失败'
      }
    }, signal)
  } catch (ex) {
    if (!signal.aborted) {
      fail(ex)
    }
  } finally {
    controller = null
    streaming.value = false
    draft.value = ''
    // 只有仍停留在本次发送的会话与世代时才回读，避免旧流把新会话顶掉
    if (session.value?.id === id && epoch === sendEpoch) {
      const failure = error.value
      await refreshActive(id)
      if (epoch === sendEpoch) {
        await refreshSessionList()
        // 回读可能覆盖本次流式错误（如 409），这里恢复它
        if (failure && session.value?.id === id) {
          error.value = failure
        }
      }
    }
  }
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  if (messageBox.value) {
    messageBox.value.scrollTop = messageBox.value.scrollHeight
  }
}

async function go(delta: number): Promise<void> {
  const next = page.value + delta
  if (next < 1 || next > pages.value) {
    return
  }
  cancelStream()
  epoch += 1
  listSeq += 1
  page.value = next
  session.value = null
  messages.value = []
  loading.value = true
  await loadSessions()
}

watch([() => messages.value.length, draft], () => void scrollToBottom())

onMounted(async () => {
  try {
    providers.value = await listProviders()
  } catch (ex) {
    fail(ex)
  }
  await loadSessions()
})

onBeforeUnmount(cancelStream)
</script>

<template>
  <div class="chat">
    <!-- 会话列表 -->
    <aside class="sessions">
      <header class="sessions-head">
        <span>会话</span>
        <button type="button" class="btn ghost" :disabled="busy" @click="createSession">新建</button>
      </header>

      <p v-if="loading" class="muted small">加载中…</p>
      <p v-else-if="!sessions.length" class="muted small">还没有会话，点击「新建」开始。</p>

      <ul class="session-list">
        <li v-for="item in sessions" :key="item.id">
          <button
            type="button"
            class="session-item"
            :class="{ active: item.id === session?.id }"
            @click="selectSession(item.id)"
          >
            <span class="session-title">{{ item.title || `会话 ${item.id}` }}</span>
            <span class="session-meta">{{ item.currentModel || '未绑定模型' }}</span>
          </button>
        </li>
      </ul>

      <footer v-if="total > size" class="pager">
        <button type="button" class="btn ghost" :disabled="page <= 1" @click="go(-1)">上一页</button>
        <span>{{ page }} / {{ pages }}</span>
        <button type="button" class="btn ghost" :disabled="page >= pages" @click="go(1)">下一页</button>
      </footer>
    </aside>

    <!-- 对话主区 -->
    <section class="main">
      <template v-if="session">
        <header class="chat-head">
          <div class="chat-title">
            <strong>{{ session.title || `会话 ${session.id}` }}</strong>
            <span class="status" :class="STATUS_TONE[session.status]">
              {{ STATUS_TEXT[session.status] ?? session.status }}
            </span>
          </div>

          <div class="selectors">
            <select v-model="selectedProvider" :disabled="!editable" @change="onProviderChange">
              <option value="">选择服务商</option>
              <option v-for="provider in providers" :key="provider.providerKey" :value="provider.providerKey">
                {{ provider.label }}
              </option>
            </select>
            <select v-model="selectedModel" :disabled="!editable">
              <option value="">选择模型</option>
              <option v-for="model in models" :key="model.modelName" :value="model.modelName">
                {{ model.label }}（{{ model.modelName }}）
              </option>
            </select>
            <button type="button" class="btn ghost" :disabled="!canSwitch || busy" @click="applySwitch">切换</button>
            <template v-if="confirming">
              <button type="button" class="btn danger" :disabled="busy" @click="removeSession">确认删除</button>
              <button type="button" class="btn ghost" @click="confirming = false">取消</button>
            </template>
            <button v-else type="button" class="btn ghost" @click="confirming = true">删除</button>
          </div>
        </header>

        <p v-if="error" class="banner danger">{{ error }}</p>
        <p v-else-if="notice" class="banner success">{{ notice }}</p>
        <p v-if="!providers.length" class="banner warning">
          还没有配置模型服务商，请先在左下角「设置 → 模型服务」中新增并设置全局默认。
        </p>

        <div ref="messageBox" class="messages">
          <p v-if="!messages.length && !streaming" class="muted">发一条消息，验证流式输出与模型切换。</p>
          <article v-for="message in messages" :key="message.id" class="message" :class="message.role.toLowerCase()">
            <p class="content">{{ message.content }}</p>
            <p v-if="message.llmModel" class="meta">{{ message.llmProvider }} · {{ message.llmModel }}</p>
          </article>
          <article v-if="streaming" class="message assistant">
            <p class="content">{{ draft || '…' }}</p>
            <p class="meta">生成中</p>
          </article>
        </div>

        <form class="composer" @submit.prevent="send">
          <textarea
            v-model="input"
            rows="2"
            :disabled="!editable"
            placeholder="输入问题，Enter 发送，Shift + Enter 换行"
            @keydown.enter.exact.prevent="send"
          />
          <button type="submit" class="btn primary" :disabled="!canSend">
            {{ streaming ? '生成中…' : '发送' }}
          </button>
        </form>
      </template>

      <EmptyState
        v-else
        title="还没有选中会话"
        hint="新建或选择一个会话，即可验证流式回答、两级切换与执行中禁切。"
        action-label="新建会话"
        @action="createSession"
      />
    </section>
  </div>
</template>

<style scoped>
.chat {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: var(--space-4);
  height: calc(100vh - 48px - var(--space-6));
  min-height: 460px;
}

/* 会话列表 */
.sessions {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-3);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  overflow: hidden;
}

.sessions-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: var(--text-2);
}

.session-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
  flex: 1;
}

.session-item {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--space-2) var(--space-3);
  border: none;
  border-radius: var(--radius-btn);
  background: none;
  text-align: left;
}

.session-item:hover {
  background: var(--surface-muted);
}

.session-item.active {
  background: var(--primary-soft);
}

.session-title {
  font-size: 13px;
  color: var(--text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  font-size: 11.5px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--fs-xs);
  color: var(--text-3);
}

/* 主区 */
.main {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  min-width: 0;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  padding: var(--space-4);
  overflow: hidden;
}

.chat-head {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding-bottom: var(--space-3);
  border-bottom: 1px solid var(--border);
}

.chat-title {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  font-size: 14px;
}

.selectors {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
  align-items: center;
}

.selectors select {
  height: 32px;
  max-width: 220px;
  padding: 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-size: var(--fs-sm);
}

.selectors select:focus-visible,
.composer textarea:focus-visible {
  outline: 1px solid var(--primary);
  outline-offset: 0;
  box-shadow: 0 0 0 3px var(--primary-soft);
}

.messages {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
  padding: var(--space-1);
}

.message {
  max-width: 76%;
  padding: var(--space-3);
  border-radius: var(--radius-card);
  background: var(--surface-muted);
}

.message.user {
  align-self: flex-end;
  background: var(--primary-soft);
  border-color: transparent;
}

.message .content {
  margin: 0;
  font-size: 13px;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
}

.message .meta {
  margin: var(--space-1) 0 0;
  font-size: 11px;
  color: var(--text-3);
}

.composer {
  display: flex;
  gap: var(--space-2);
  align-items: flex-end;
  padding-top: var(--space-3);
  border-top: 1px solid var(--border);
}

.composer textarea {
  flex: 1;
  resize: none;
  padding: var(--space-3);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-btn);
  background: var(--surface);
  color: var(--text);
  font-family: var(--font-sans);
  font-size: 13px;
}

.muted {
  margin: 0;
  color: var(--text-3);
  font-size: 12.5px;
}

.small {
  font-size: 11.5px;
}

@media (max-width: 860px) {
  .chat {
    grid-template-columns: 1fr;
    height: auto;
  }

  .sessions {
    max-height: 220px;
  }

  .main {
    min-height: 520px;
  }
}
</style>
