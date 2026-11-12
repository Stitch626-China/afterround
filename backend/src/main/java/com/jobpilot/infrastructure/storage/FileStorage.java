package com.jobpilot.infrastructure.storage;

/**
 * 文件存储抽象（FR1.4）：M1 用本地磁盘，产品化阶段可平滑替换为 MinIO。
 */
public interface FileStorage {

    /** 保存文件；同一 key 覆盖写。 */
    void save(String key, byte[] content);

    /** 读取文件；不存在时抛出 {@link IllegalStateException}。 */
    byte[] load(String key);

    /** 删除文件；不存在时静默忽略。 */
    void delete(String key);
}
