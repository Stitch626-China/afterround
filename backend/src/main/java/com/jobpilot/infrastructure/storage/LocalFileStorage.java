package com.jobpilot.infrastructure.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 本地磁盘实现：key 相对于 {@code app.storage.root}，并做路径穿越防护。 */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(@Value("${app.storage.root:./data/files}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public void save(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException ex) {
            throw new IllegalStateException("文件写入失败: " + key, ex);
        }
    }

    @Override
    public byte[] load(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException ex) {
            throw new IllegalStateException("文件读取失败: " + key, ex);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new IllegalStateException("文件删除失败: " + key, ex);
        }
    }

    /** 规范化为 root 下的路径，拒绝 ../ 越界。 */
    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法存储路径: " + key);
        }
        return target;
    }
}
