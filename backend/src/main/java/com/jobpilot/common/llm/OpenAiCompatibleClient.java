package com.jobpilot.common.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelOption;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

/**
 * 手写的 OpenAI 兼容客户端（FRX.2）。
 *
 * <p>只依赖标准协议：{@code POST /chat/completions}、{@code GET /models}；
 * 不写任何厂商 if-else。Model 只在请求体中传，因此同一 Provider 下新增模型无需重建客户端。
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final int ERROR_BODY_LIMIT = 300;
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient web;
    private final int maxTokens;

    public OpenAiCompatibleClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, DEFAULT_MAX_TOKENS);
    }

    public OpenAiCompatibleClient(String baseUrl, String apiKey, int maxTokens) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(TIMEOUT);
        this.web = WebClient.builder()
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
        this.maxTokens = maxTokens;
    }

    @Override
    public ChatResult chat(String model, List<ChatMessage> messages) {
        return chat(model, messages, false);
    }

    @Override
    public ChatResult chatJson(String model, List<ChatMessage> messages) {
        return chat(model, messages, true);
    }

    @Override
    public Flux<StreamChunk> stream(String model, List<ChatMessage> messages) {
        Map<String, Object> body = payload(model, messages, true);
        body.put("stream_options", Map.of("include_usage", true));
        return web.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toError)
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() { })
                .takeUntil(event -> "[DONE]".equals(event.data()))
                .map(ServerSentEvent::data)
                .filter(data -> data != null && !data.isBlank() && !"[DONE]".equals(data))
                .map(this::toChunk)
                .filter(Objects::nonNull);
    }

    @Override
    public List<String> listModels() {
        JsonNode node = web.get()
                .uri("/models")
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toError)
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);
        List<String> models = new ArrayList<>();
        if (node != null) {
            node.path("data").forEach(item -> {
                if (item.hasNonNull("id")) {
                    models.add(item.get("id").asText());
                }
            });
        }
        return models;
    }

    private ChatResult chat(String model, List<ChatMessage> messages, boolean jsonMode) {
        Map<String, Object> body = payload(model, messages, false);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
            // 结构化分析固定低温度，保证同一份输入两次分析的重点与结论稳定
            body.put("temperature", 0.2);
        }
        JsonNode node = web.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toError)
                .bodyToMono(JsonNode.class)
                .block(TIMEOUT);
        if (node == null || !node.path("choices").isArray() || node.path("choices").isEmpty()) {
            throw new LlmException(502, "上游响应缺少 choices");
        }
        JsonNode choice = node.path("choices").path(0);
        JsonNode usage = node.path("usage");
        return new ChatResult(
                choice.path("message").path("content").asText(""),
                node.path("model").asText(model),
                intOrNull(usage.path("prompt_tokens")),
                intOrNull(usage.path("completion_tokens")),
                intOrNull(usage.path("total_tokens")));
    }

    private StreamChunk toChunk(String data) {
        JsonNode node;
        try {
            node = MAPPER.readTree(data);
        } catch (Exception ex) {
            // 坏帧不能静默丢弃，否则上层会把残缺回答当作成功落库
            throw new LlmException(502, "上游流式响应不是合法 JSON: " + truncate(data));
        }
        if (node.has("error")) {
            throw new LlmException(502, "上游流式响应错误: " + truncate(node.get("error").toString()));
        }
        JsonNode choices = node.path("choices");
        String content = choices.isArray() && !choices.isEmpty()
                ? choices.path(0).path("delta").path("content").asText(null) : null;
        String model = node.hasNonNull("model") ? node.get("model").asText() : null;
        Integer total = intOrNull(node.path("usage").path("total_tokens"));
        return content == null && model == null && total == null ? null : new StreamChunk(content, model, total);
    }

    private reactor.core.publisher.Mono<? extends Throwable> toError(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new LlmException(response.statusCode().value(),
                        "HTTP " + response.statusCode().value() + " " + truncate(body)));
    }

    private Map<String, Object> payload(String model, List<ChatMessage> messages, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", stream);
        // NFR7：单次任务 token 硬上限，超限由上游中断并返回明确错误
        body.put("max_tokens", maxTokens);
        return body;
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private static String truncate(String text) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return value.length() <= ERROR_BODY_LIMIT ? value : value.substring(0, ERROR_BODY_LIMIT);
    }
}
