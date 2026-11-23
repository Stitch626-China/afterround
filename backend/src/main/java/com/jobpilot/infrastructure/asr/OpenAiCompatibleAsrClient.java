package com.jobpilot.infrastructure.asr;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.llm.LlmException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * OpenAI 兼容 ASR 客户端：{@code POST {baseUrl}/audio/transcriptions}，multipart 上传文件，
 * 返回 JSON {@code {"text":"..."}}。baseUrl 需与 LLM 一样包含 {@code /v1}。
 */
@Component
public class OpenAiCompatibleAsrClient implements AsrClient {

    private static final int ERROR_BODY_LIMIT = 300;

    private final AsrProperties properties;
    private final WebClient web;

    public OpenAiCompatibleAsrClient(AsrProperties properties) {
        this.properties = properties;
        this.web = properties.configured()
                ? WebClient.builder()
                        .baseUrl(properties.baseUrl())
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                        .build()
                : null;
    }

    @Override
    public boolean configured() {
        return web != null;
    }

    @Override
    public String transcribe(String fileName, String contentType, byte[] audio) {
        if (web == null) {
            throw new LlmException(400, "ASR 未配置");
        }
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }).contentType(MediaType.parseMediaType(contentType));
        builder.part("model", properties.model());
        if (!properties.language().isBlank()) {
            builder.part("language", properties.language());
        }
        builder.part("response_format", "json");
        JsonNode node = web.post()
                .uri("/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new LlmException(response.statusCode().value(),
                                "ASR 调用失败: " + truncate(body)))))
                .bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(properties.timeoutSeconds()));
        String text = node == null ? null : node.path("text").asText(null);
        if (text == null || text.isBlank()) {
            throw new LlmException(502, "ASR 返回内容为空");
        }
        return text.trim();
    }

    private static String truncate(String value) {
        return value == null || value.isBlank() ? "未知错误"
                : value.substring(0, Math.min(value.length(), ERROR_BODY_LIMIT));
    }
}
