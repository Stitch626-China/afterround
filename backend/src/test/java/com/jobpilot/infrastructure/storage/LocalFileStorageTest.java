package com.jobpilot.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void savesLoadsAndDeletes() {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());
        byte[] content = "简历内容".getBytes(StandardCharsets.UTF_8);

        storage.save("resumes/abc.txt", content);
        assertThat(storage.load("resumes/abc.txt")).isEqualTo(content);

        storage.delete("resumes/abc.txt");
        assertThatThrownBy(() -> storage.load("resumes/abc.txt"))
                .isInstanceOf(IllegalStateException.class);
        storage.delete("resumes/abc.txt");
    }

    @Test
    void rejectsPathTraversal() {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());

        assertThatThrownBy(() -> storage.save("../evil.txt", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
