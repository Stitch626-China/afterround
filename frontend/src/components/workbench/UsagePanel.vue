<script setup lang="ts">
/** 调用统计：复用设置抽屉中的只读 UsageSettings，入口统一到 LLM 工作台。 */
import UsageSettings from '@/components/settings/UsageSettings.vue'
</script>

<template>
  <section class="usage-panel">
    <p class="lead">
      观测数据：全部场景的调用次数、成功率、token 与耗时，来自 `llm_call_logs`，只读。
      与「提示词评测」不同，这里不产生任何模型调用。
    </p>
    <UsageSettings />
  </section>
</template>

<style scoped>
.usage-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
</style>
