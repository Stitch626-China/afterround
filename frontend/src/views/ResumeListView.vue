<script setup lang="ts">
import EmptyState from '@/components/EmptyState.vue'
</script>

<template>
  <div class="page">
    <p class="lead">上传简历，获得五维评分、短板分析与岗位方向推荐。</p>
    <EmptyState
      title="还没有简历"
      hint="支持 PDF / DOCX / DOC / TXT，单文件不超过 5MB；也可以直接粘贴纯文本。"
      action-label="上传简历"
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
  color: var(--text-2);
  font-size: 13.5px;
}
</style>
