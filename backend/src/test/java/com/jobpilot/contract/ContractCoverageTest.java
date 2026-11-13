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

import com.jobpilot.modules.chat.controller.ChatController;
import com.jobpilot.modules.llm.controller.LlmController;
import com.jobpilot.modules.resume.controller.ResumeController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约覆盖率检查：M1 全部 26 个端点必须与 {@code contracts/openapi.yaml} 完全一致。
 */
class ContractCoverageTest {

    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Test
    void allContractEndpointsAreMapped() throws Exception {
        Set<String> expected = contractEndpoints();
        Set<String> actual = controllerEndpoints();

        assertThat(expected).hasSize(26);
        // 双向一致：既不允许契约端点未实现，也不允许实现多出契约外端点
        assertThat(actual).isEqualTo(expected);
    }

    @SuppressWarnings("unchecked")
    private Set<String> contractEndpoints() throws Exception {
        Path contract = Path.of("..", "contracts", "openapi.yaml");
        try (InputStream in = Files.newInputStream(contract)) {
            Map<String, Object> doc = new Yaml().load(in);
            Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
            Set<String> endpoints = new HashSet<>();
            paths.forEach((path, value) -> ((Map<String, Object>) value).keySet().stream()
                    .filter(HTTP_METHODS::contains)
                    .forEach(method -> endpoints.add(method.toUpperCase() + " " + path)));
            return endpoints;
        }
    }

    private Set<String> controllerEndpoints() {
        Set<String> endpoints = new HashSet<>();
        for (Class<?> controller : List.of(LlmController.class, ChatController.class, ResumeController.class)) {
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
