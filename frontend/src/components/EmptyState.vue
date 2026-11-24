<script setup lang="ts">
/**
 * 空状态：按 §6.10 约定「给行动指引，不给装饰插画」。
 */
defineProps<{
  title: string
  hint?: string
  actionLabel?: string
}>()

defineEmits<{ action: [] }>()
</script>

<template>
  <div class="empty">
    <span class="empty-mark" aria-hidden="true" />
    <p class="empty-title">{{ title }}</p>
    <p v-if="hint" class="empty-hint">{{ hint }}</p>
    <button v-if="actionLabel" type="button" class="btn primary" @click="$emit('action')">
      {{ actionLabel }}
    </button>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
  padding: 40px var(--space-5);
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
}

/* 一段墨线代替插画：只做视觉锚点，不占用注意力 */
.empty-mark {
  width: 24px;
  height: 2px;
  margin-bottom: var(--space-2);
  border-radius: 2px;
  background: var(--primary);
}

.empty-title {
  margin: 0;
  font-size: var(--fs-h2);
  font-weight: 600;
  color: var(--text);
}

.empty-hint {
  margin: 0;
  max-width: 560px;
  font-size: 13px;
  color: var(--text-3);
}

.empty .btn {
  margin-top: var(--space-2);
}
</style>
