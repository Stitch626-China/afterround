<script setup lang="ts">
import EmptyState from '@/components/EmptyState.vue'
</script>

<template>
  <div class="page">
    <p class="lead">
      服务商与模型保存在数据库，新增或修改后立即生效，无需重启、无需改配置文件。切换 GPT / DeepSeek
      只需填入地址与密钥。
    </p>
    <EmptyState
      title="尚未配置模型服务商"
      hint="一个服务商可挂载多个模型；API Key 会加密保存，界面只显示尾号。"
      action-label="新增服务商"
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
