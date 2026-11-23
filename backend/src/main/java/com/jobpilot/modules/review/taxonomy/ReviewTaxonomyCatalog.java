package com.jobpilot.modules.review.taxonomy;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 复盘受控词表（FR5.3 / FR5.4）：知识点、失分类型与评分维度固定 key，保证跨场可比。
 *
 * <p>LLM 输出与用户修正都必须落在词表内；版本变化时同步升级 {@link #TAXONOMY_VERSION}。
 */
public final class ReviewTaxonomyCatalog {

    public static final String TAXONOMY_VERSION = "taxonomy-v1";
    public static final String RUBRIC_VERSION = "interview-rubric-v1";
    public static final OffsetDateTime UPDATED_AT = OffsetDateTime.parse("2026-11-23T00:00:00+08:00");

    /** 词表条目：key 稳定，label 用于展示，description 帮助模型与用户理解边界。 */
    public record Item(String key, String label, String description) {
    }

    /** 评分维度：maxScore 固定 100，便于跨场对比。 */
    public record Dimension(String key, String label, String description, int maxScore) {
    }

    public static final List<Item> TOPICS = List.of(
            new Item("java", "Java 基础", "语法、集合、泛型、异常、IO 等"),
            new Item("jvm", "JVM 与 GC", "内存模型、类加载、垃圾回收、调优"),
            new Item("concurrency", "并发编程", "线程、锁、线程池、并发容器、异步"),
            new Item("mysql", "MySQL", "SQL、索引、事务、锁、执行计划"),
            new Item("redis", "Redis", "数据结构、缓存策略、持久化、集群"),
            new Item("kafka", "消息队列", "Kafka / RocketMQ / RabbitMQ 等"),
            new Item("spring", "Spring 生态", "Spring、Spring Boot、Spring Cloud"),
            new Item("distributed", "分布式系统", "一致性、幂等、限流、熔断、分布式事务"),
            new Item("system-design", "系统设计", "容量估算、架构拆分、高可用设计"),
            new Item("algorithm", "算法与数据结构", "手撕代码与复杂度分析"),
            new Item("project", "项目经验", "项目背景、职责、难点、取舍与结果"),
            new Item("communication", "沟通表达", "表达结构、倾听与追问响应"),
            new Item("career", "职业规划", "动机、稳定性、职业方向"),
            new Item("other", "其他", "未归入以上类别的知识点"));

    public static final List<Item> LOSS_TAGS = List.of(
            new Item("knowledge-gap", "知识盲区", "完全不了解或答错关键概念"),
            new Item("shallow-depth", "深度不足", "停留在使用层面，缺少原理与取舍"),
            new Item("unclear-expression", "表达不清", "结构混乱、重点不明、逻辑跳跃"),
            new Item("no-evidence", "缺少证据", "没有数据、案例或细节支撑"),
            new Item("off-topic", "答非所问", "理解偏差或偏离问题核心"),
            new Item("time-management", "时间分配", "超时、卡壳或前松后紧"),
            new Item("pressure", "临场压力", "紧张导致发挥失常"),
            new Item("other", "其他", "未归入以上类别的失分原因"));

    public static final List<Dimension> DIMENSIONS = List.of(
            new Dimension("depth", "技术深度", "原理、边界与取舍", 100),
            new Dimension("accuracy", "准确性", "概念与结论是否正确", 100),
            new Dimension("structure", "结构化表达", "层次清晰、先结论后论据", 100),
            new Dimension("evidence", "证据与数据", "用案例、指标与细节支撑", 100),
            new Dimension("adaptability", "应变能力", "面对追问与陌生问题的反应", 100),
            new Dimension("overall", "综合表现", "整体印象与岗位匹配", 100));

    private static final Map<String, String> TOPIC_LABELS = labels(TOPICS);
    private static final Map<String, String> LOSS_LABELS = labels(LOSS_TAGS);
    private static final Map<String, String> DIMENSION_LABELS = DIMENSIONS.stream()
            .collect(Collectors.toMap(Dimension::key, Dimension::label, (a, b) -> a, LinkedHashMap::new));

    private ReviewTaxonomyCatalog() {
    }

    public static boolean isTopic(String key) {
        return key != null && TOPIC_LABELS.containsKey(key);
    }

    public static boolean isLossTag(String key) {
        return key != null && LOSS_LABELS.containsKey(key);
    }

    public static boolean isDimension(String key) {
        return key != null && DIMENSION_LABELS.containsKey(key);
    }

    public static String topicLabel(String key) {
        return TOPIC_LABELS.get(key);
    }

    public static String lossLabel(String key) {
        return LOSS_LABELS.get(key);
    }

    public static String dimensionLabel(String key) {
        return DIMENSION_LABELS.get(key);
    }

    private static Map<String, String> labels(List<Item> items) {
        return items.stream().collect(Collectors.toMap(Item::key, Item::label, (a, b) -> a, LinkedHashMap::new));
    }
}
