package com.jobpilot.modules.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 行情洞察：技能合并去重、薪资分桶、原文分组，均为本地实时聚合。 */
@ExtendWith(MockitoExtension.class)
class JobMarketServiceTest {

    @Mock
    private JobPositionMapper jobMapper;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(JobPosition.class);
    }

    private static JobPosition job(String title, String mustHave, String niceToHave,
                                   Integer min, Integer max, String experience) {
        JobPosition job = new JobPosition();
        job.setTitle(title);
        job.setMustHaveSkills(mustHave);
        job.setNiceToHaveSkills(niceToHave);
        job.setSalaryMin(min);
        job.setSalaryMax(max);
        job.setExperience(experience);
        return job;
    }

    @Test
    void aggregatesSkillsSalaryExperienceAndDirections() {
        when(jobMapper.selectList(any())).thenReturn(List.of(
                job("Java 后端", "[\"Java\",\"Redis\"]", "[\"Kubernetes\"]", 20000, 30000, "3-5年"),
                job("Java 后端", "[\"java\"]", "[\"Redis\",\"Docker\"]", 40000, 60000, "3-5年"),
                job("Go 后端", "[\"Go\"]", "[]", null, null, null)));

        var insights = new JobMarketService(jobMapper).insights();

        assertThat(insights.sampleSize()).isEqualTo(3);
        assertThat(insights.skillTop()).first().satisfies(item -> {
            assertThat(item.skill()).isEqualTo("java");
            assertThat(item.count()).isEqualTo(2);
        });
        assertThat(insights.skillTop()).extracting("skill")
                .containsExactly("java", "redis", "docker", "go", "kubernetes");
        assertThat(insights.salaryDistribution()).extracting("count")
                .containsExactly(0, 0, 1, 0, 1);
        assertThat(insights.experienceDistribution()).singleElement().satisfies(item -> {
            assertThat(item.label()).isEqualTo("3-5年");
            assertThat(item.count()).isEqualTo(2);
        });
        assertThat(insights.directionDistribution()).extracting("label")
                .containsExactly("Java 后端", "Go 后端");
    }

    @Test
    void emptyDatasetReturnsZeroSampleAndBuckets() {
        when(jobMapper.selectList(any())).thenReturn(List.of());

        var insights = new JobMarketService(jobMapper).insights();

        assertThat(insights.sampleSize()).isZero();
        assertThat(insights.skillTop()).isEmpty();
        assertThat(insights.salaryDistribution()).hasSize(5).allSatisfy(item -> assertThat(item.count()).isZero());
        assertThat(insights.experienceDistribution()).isEmpty();
        assertThat(insights.directionDistribution()).isEmpty();
    }

    @Test
    void singleSidedSalaryUsesAvailableValue() {
        when(jobMapper.selectList(any())).thenReturn(List.of(
                job("数据", "[\"SQL\"]", "[]", null, 15000, "1-3年"),
                job("数据", "[\"SQL\"]", "[]", 8000, null, "1-3年")));

        var distribution = new JobMarketService(jobMapper).insights().salaryDistribution();

        assertThat(distribution).extracting("count").containsExactly(1, 1, 0, 0, 0);
    }
}
