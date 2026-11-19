package com.jobpilot.modules.interview.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * 面试方向加载（需求 §6.6）：读取 classpath 下 {@code skills/<key>/skill.meta.yml} 与 SKILL.md，
 * 新增方向只加目录、不改 Java 代码与契约枚举。
 */
@Service
public class InterviewDirectionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewDirectionService.class);

    private final Map<String, Direction> directions;

    public InterviewDirectionService() {
        this.directions = load();
        if (directions.isEmpty()) {
            log.warn("未加载到任何面试方向（classpath:skills/*/skill.meta.yml）");
        }
    }

    public List<Direction> list() {
        return List.copyOf(directions.values());
    }

    public Direction require(String key) {
        Direction direction = key == null ? null : directions.get(key);
        if (direction == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "面试方向不存在: " + key);
        }
        return direction;
    }

    /** 仅关联 JD 时，用方向关键词匹配 JD 文本；无命中回落第一个方向。 */
    public Direction match(String text) {
        if (directions.isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND, "未配置任何面试方向");
        }
        String content = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Direction best = null;
        int bestScore = 0;
        for (Direction direction : directions.values()) {
            int score = 0;
            for (String keyword : direction.keywords()) {
                if (!keyword.isBlank() && content.contains(keyword.toLowerCase(Locale.ROOT))) {
                    score++;
                }
            }
            if (score > bestScore) {
                best = direction;
                bestScore = score;
            }
        }
        return best != null ? best : directions.values().iterator().next();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Direction> load() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Direction> loaded = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources("classpath*:skills/*/skill.meta.yml");
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    Map<String, Object> meta = new Yaml().load(in);
                    if (meta == null || meta.get("key") == null) {
                        continue;
                    }
                    String key = String.valueOf(meta.get("key"));
                    String persona = read(new PathMatchingResourcePatternResolver()
                            .getResource("classpath:skills/" + key + "/SKILL.md"));
                    List<Category> categories = new ArrayList<>();
                    Object rawCategories = meta.get("categories");
                    if (rawCategories instanceof List<?> list) {
                        for (Object item : list) {
                            Map<String, Object> node = (Map<String, Object>) item;
                            categories.add(new Category(String.valueOf(node.get("key")),
                                    String.valueOf(node.get("label")),
                                    ((Number) node.getOrDefault("weight", 0)).intValue()));
                        }
                    }
                    if (categories.isEmpty()) {
                        categories.add(new Category("general", "综合", 100));
                    }
                    List<String> keywords = new ArrayList<>();
                    Object rawKeywords = meta.get("keywords");
                    if (rawKeywords instanceof List<?> list) {
                        list.forEach(item -> keywords.add(String.valueOf(item)));
                    }
                    loaded.add(new Direction(key, String.valueOf(meta.get("label")),
                            meta.get("description") == null ? null : String.valueOf(meta.get("description")),
                            persona, List.copyOf(categories), List.copyOf(keywords),
                            ((Number) meta.getOrDefault("sortOrder", 0)).intValue()));
                } catch (Exception ex) {
                    log.warn("加载面试方向失败 resource={}", resource.getFilename(), ex);
                }
            }
        } catch (Exception ex) {
            log.warn("扫描面试方向失败", ex);
        }
        loaded.sort(Comparator.comparingInt(Direction::sortOrder).thenComparing(Direction::key));
        Map<String, Direction> result = new LinkedHashMap<>();
        loaded.forEach(direction -> result.put(direction.key(), direction));
        return result;
    }

    private String read(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return "";
        }
    }

    public record Direction(String key, String label, String description, String persona,
                            List<Category> categories, List<String> keywords, int sortOrder) {
    }

    public record Category(String key, String label, int weight) {
    }
}
