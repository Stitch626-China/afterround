<script setup lang="ts">
import EmptyState from '@/components/EmptyState.vue'
</script>

<template>
  <div class="page">
    <p class="lead">
      会话内可随时切换服务商与模型；任务执行中为保证一致性会暂时禁用切换。
    </p>
    <EmptyState
      title="还没有会话"
      hint="新建会话后，可在此验证流式回答、模型切换与执行中禁切。"
      action-label="新建会话"
    />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: var(--space-5);
}

.lead {
  margin: 0;
  max-width: 68ch;
  color: var(--text-2);
  font-size: 13.5px;
}
</style>
