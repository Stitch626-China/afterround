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
    <p class="empty-title">{{ title }}</p>
    <p v-if="hint" class="empty-hint">{{ hint }}</p>
    <button v-if="actionLabel" type="button" class="empty-action" @click="$emit('action')">
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
  padding: var(--space-6);
  background: var(--surface);
  border: 1px dashed var(--border-strong);
  border-radius: var(--radius-card);
}

.empty-title {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--text);
}

.empty-hint {
  margin: 0;
  font-size: 13px;
  color: var(--text-3);
}

.empty-action {
  margin-top: var(--space-2);
  padding: var(--space-2) var(--space-4);
  border: none;
  border-radius: var(--radius-btn);
  background: var(--primary);
  color: #fff;
  font-size: 13px;
  transition: background 0.12s ease;
}

.empty-action:hover {
  background: var(--primary-hover);
}
</style>
