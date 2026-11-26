package com.jobpilot.modules.job.service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.modules.job.dto.CountItemResponse;
import com.jobpilot.modules.job.dto.JobMarketInsightsResponse;
import com.jobpilot.modules.job.dto.SkillCountItemResponse;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import org.springframework.stereotype.Service;

/**
 * 行情洞察（FR2.4）：实时聚合已解析完成的 JD，不落快照；JD 增删后立即反映。
 *
 * <p>技能合并必备与加分并小写归一；薪资取 min/max 中位数分桶；
 * 经验与方向保留原文（不做归一化，契约明确接受同义分列）。
 */
@Service
public class JobMarketService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TOP = 20;

    /** 薪资分桶下界（元/月），最后一档为 50K 以上。 */
    private static final int[] SALARY_BUCKETS = {10_000, 20_000, 30_000, 50_000};
    private static final String[] SALARY_LABELS = {"<10K", "10-20K", "20-30K", "30-50K", "50K+"};

    private final JobPositionMapper jobMapper;

    public JobMarketService(JobPositionMapper jobMapper) {
        this.jobMapper = jobMapper;
    }

    public JobMarketInsightsResponse insights() {
        List<JobPosition> jobs = jobMapper.selectList(Wrappers.<JobPosition>lambdaQuery()
                .eq(JobPosition::getParseStatus, "COMPLETED")
                // 复盘快速录入的手动岗位没有 JD，不参与行情统计
                .ne(JobPosition::getSourceType, "MANUAL"));
        return new JobMarketInsightsResponse(jobs.size(), OffsetDateTime.now(),
                skillTop(jobs), salaryDistribution(jobs),
                counts(jobs, JobPosition::getExperience), counts(jobs, JobPosition::getTitle));
    }

    /** 必备 + 加分合并，小写去重（同一 JD 内同技能只计一次），按词频取 Top 20。 */
    private List<SkillCountItemResponse> skillTop(List<JobPosition> jobs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JobPosition job : jobs) {
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String skill : concat(skills(job.getMustHaveSkills()), skills(job.getNiceToHaveSkills()))) {
                if (skill != null && !skill.isBlank()) {
                    unique.add(skill.trim().toLowerCase());
                }
            }
            unique.forEach(skill -> counts.merge(skill, 1, Integer::sum));
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP)
                .map(entry -> new SkillCountItemResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** 缺失薪资的 JD 不参与；按中位数落桶，固定分桶顺序便于前端稳定展示。 */
    private List<CountItemResponse> salaryDistribution(List<JobPosition> jobs) {
        int[] buckets = new int[SALARY_LABELS.length];
        for (JobPosition job : jobs) {
            Integer min = job.getSalaryMin();
            Integer max = job.getSalaryMax();
            if (min == null && max == null) {
                continue;
            }
            int mid = min == null ? max : max == null ? min : (min + max) / 2;
            int index = 0;
            while (index < SALARY_BUCKETS.length && mid >= SALARY_BUCKETS[index]) {
                index++;
            }
            buckets[index]++;
        }
        return java.util.stream.IntStream.range(0, SALARY_LABELS.length)
                .mapToObj(index -> new CountItemResponse(SALARY_LABELS[index], buckets[index]))
                .toList();
    }

    /** 原文分组计数，空值跳过，按数量倒序取 Top 20。 */
    private List<CountItemResponse> counts(List<JobPosition> jobs,
                                           Function<JobPosition, String> field) {
        Map<String, Integer> counts = jobs.stream()
                .map(field).filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toMap(String::trim, value -> 1, Integer::sum));
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP)
                .map(entry -> new CountItemResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<String> skills(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static List<String> concat(List<String> left, List<String> right) {
        return java.util.stream.Stream.concat(left.stream(), right.stream()).toList();
    }
}
