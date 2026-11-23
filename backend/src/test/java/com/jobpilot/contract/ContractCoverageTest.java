package com.jobpilot.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jobpilot.modules.application.controller.JobApplicationController;
import com.jobpilot.modules.chat.controller.ChatController;
import com.jobpilot.modules.company.controller.CompanyController;
import com.jobpilot.modules.interview.controller.InterviewController;
import com.jobpilot.modules.interview.controller.InterviewSessionController;
import com.jobpilot.modules.job.controller.JobMarketController;
import com.jobpilot.modules.job.controller.JobMatchController;
import com.jobpilot.modules.job.controller.JobPositionController;
import com.jobpilot.modules.llm.controller.LlmController;
import com.jobpilot.modules.llm.controller.LlmSceneController;
import com.jobpilot.modules.offer.controller.OfferController;
import com.jobpilot.modules.resume.controller.ResumeComparisonController;
import com.jobpilot.modules.resume.controller.ResumeController;
import com.jobpilot.modules.review.controller.ImprovementActionController;
import com.jobpilot.modules.review.controller.InterviewEventController;
import com.jobpilot.modules.review.controller.ReviewSessionController;
import com.jobpilot.modules.review.controller.ReviewTaxonomyController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约覆盖率检查：已实现的端点必须与 {@code contracts/openapi.yaml} 完全一致。
 *
 * <p>契约先行：M2 新增路径在契约中标记 {@code x-implemented: false}，表示「已定义、待实现」。
 * 测试据此区分两类端点——契约总量、必须已实现的部分——实现完成时删除标记即可自动收紧，
 * 不在测试里维护例外清单（见契约 info.description「实现状态标记」）。
 */
class ContractCoverageTest {

    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Test
    void allContractEndpointsAreMapped() throws Exception {
        Set<String> all = contractEndpoints(false);
        Set<String> pending = contractEndpoints(true);
        Set<String> actual = controllerEndpoints();

        Set<String> expectedImplemented = new HashSet<>(all);
        expectedImplemented.removeAll(pending);

        // 契约端点总数：契约扩缩时提醒同步本断言
        // 1.9.0 新增 22 个 M3 采集/档案端点；1.9.5 新增 1 个 ASR 重试端点，总数 86 -> 87
        assertThat(all).hasSize(87);
        // 实现不得出现契约外端点
        assertThat(all).containsAll(actual);
        // 已实现端点必须与契约中「未标记待实现」的部分双向一致；
        // 该双向断言同时防止把已实现端点误标为 x-implemented: false，故无需再断言 pending 数量
        assertThat(actual).isEqualTo(expectedImplemented);
    }

    @SuppressWarnings("unchecked")
    private Set<String> contractEndpoints(boolean pendingOnly) throws Exception {
        Path contract = Path.of("..", "contracts", "openapi.yaml");
        try (InputStream in = Files.newInputStream(contract)) {
            Map<String, Object> doc = new Yaml().load(in);
            Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
            Set<String> endpoints = new HashSet<>();
            paths.forEach((path, value) -> {
                Map<String, Object> pathItem = (Map<String, Object>) value;
                boolean pending = Boolean.FALSE.equals(pathItem.get("x-implemented"));
                if (!pendingOnly || pending) {
                    pathItem.keySet().stream()
                            .filter(HTTP_METHODS::contains)
                            .forEach(method -> endpoints.add(method.toUpperCase() + " " + path));
                }
            });
            return endpoints;
        }
    }

    private Set<String> controllerEndpoints() {
        Set<String> endpoints = new HashSet<>();
        for (Class<?> controller : List.of(LlmController.class, LlmSceneController.class,
                CompanyController.class, JobPositionController.class, JobMatchController.class,
                JobMarketController.class, ChatController.class, ResumeController.class,
                ResumeComparisonController.class, InterviewController.class, InterviewSessionController.class,
                OfferController.class, JobApplicationController.class, InterviewEventController.class,
                ReviewSessionController.class, ImprovementActionController.class,
                ReviewTaxonomyController.class)) {
            String base = controller.getAnnotation(RequestMapping.class).value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    String httpMethod = httpMethod(annotation);
                    if (httpMethod == null) {
                        continue;
                    }
                    String path = mappingPath(annotation);
                    endpoints.add(httpMethod + " " + (path.isEmpty() ? base : base + path));
                }
            }
        }
        return endpoints;
    }

    private String httpMethod(Annotation annotation) {
        if (annotation instanceof GetMapping) {
            return "GET";
        }
        if (annotation instanceof PostMapping) {
            return "POST";
        }
        if (annotation instanceof PutMapping) {
            return "PUT";
        }
        if (annotation instanceof PatchMapping) {
            return "PATCH";
        }
        return annotation instanceof DeleteMapping ? "DELETE" : null;
    }

    private String mappingPath(Annotation annotation) {
        try {
            Method value = annotation.annotationType().getMethod("value");
            String[] paths = (String[]) value.invoke(annotation);
            return paths.length == 0 ? "" : paths[0];
        } catch (Exception ex) {
            return "";
        }
    }
}
