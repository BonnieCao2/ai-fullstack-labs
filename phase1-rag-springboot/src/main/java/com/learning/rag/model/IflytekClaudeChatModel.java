package com.learning.rag.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 自定义 Claude 模型实现（适配讯飞 LLM 网关）
 *
 * 为什么需要自定义？
 * ------------------
 * LangChain4j 内置的 AnthropicChatModel 把认证信息放在 `x-api-key` 请求头里
 * （这是 Anthropic 官方 API 的认证方式）。
 *
 * 但讯飞这类第三方网关用的是 OAuth 风格的 `Authorization: Bearer <token>` 认证。
 * 内置模型不支持自定义这个头，所以我们直接用 Java 原生 HttpClient 调用。
 *
 * 核心知识点：
 * ChatLanguageModel 接口的本质，就是「把对话消息发给一个 HTTP 端点，再把响应解析回来」。
 * 这个类把这层封装彻底揭开给你看。
 */
public class IflytekClaudeChatModel implements ChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(IflytekClaudeChatModel.class);

    private final String messagesUrl;
    private final String authToken;
    private final String modelName;
    private final int maxTokens;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IflytekClaudeChatModel(String baseUrl, String authToken, String modelName, int maxTokens) {
        // baseUrl 形如 https://one.iflytek.com/api/llm/console/chat
        // Anthropic 协议的端点是 {baseUrl}/v1/messages，这里规范化拼接
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.messagesUrl = normalized + "/v1/messages";
        this.authToken = authToken;
        this.modelName = modelName;
        this.maxTokens = maxTokens;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * ChatLanguageModel 的核心方法：发送对话消息，返回 AI 回复
     *
     * 流程：
     * 1. 把 LangChain4j 的消息对象转成 Anthropic Messages API 的 JSON 格式
     * 2. 用 Bearer token 认证发起 HTTP POST
     * 3. 解析响应，提取回复文本
     */
    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            String requestJson = buildRequestBody(messages);
            log.debug("请求体: {}", requestJson);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl))
                    .header("Authorization", "Bearer " + authToken)  // ← 关键：Bearer 认证
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // 把网关返回的原始内容打出来，方便排查（HTML 拦截页、JSON 错误等）
                String body = response.body();
                String preview = body.length() > 500 ? body.substring(0, 500) : body;
                throw new RuntimeException("Claude 网关返回非 200, status="
                        + response.statusCode() + ", body=" + preview);
            }

            return parseResponse(response.body());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("调用 Claude 网关失败", e);
        }
    }

    /**
     * 构造 Anthropic Messages API 请求体
     *
     * 格式示例：
     * {
     *   "model": "claude-haiku-4-5-20251001",
     *   "max_tokens": 1000,
     *   "system": "系统提示词（可选）",
     *   "messages": [{"role": "user", "content": "用户消息"}]
     * }
     *
     * 注意：Anthropic 协议把 system 消息单独放在顶层 system 字段，
     * 而不是放进 messages 数组（这点和 OpenAI 协议不同）。
     */
    private String buildRequestBody(List<ChatMessage> messages) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("max_tokens", maxTokens);

        StringBuilder systemPrompt = new StringBuilder();
        ArrayNode messagesArray = objectMapper.createArrayNode();

        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                systemPrompt.append(sm.text());
            } else if (msg instanceof UserMessage um) {
                ObjectNode m = objectMapper.createObjectNode();
                m.put("role", "user");
                m.put("content", um.singleText());
                messagesArray.add(m);
            } else if (msg instanceof AiMessage am) {
                ObjectNode m = objectMapper.createObjectNode();
                m.put("role", "assistant");
                m.put("content", am.text());
                messagesArray.add(m);
            }
        }

        if (!systemPrompt.isEmpty()) {
            root.put("system", systemPrompt.toString());
        }
        root.set("messages", messagesArray);

        return objectMapper.writeValueAsString(root);
    }

    /**
     * 解析 Anthropic Messages API 响应
     *
     * 响应格式示例：
     * {
     *   "content": [{"type": "text", "text": "这是回复内容"}],
     *   "stop_reason": "end_turn",
     *   "usage": {"input_tokens": 10, "output_tokens": 25}
     * }
     */
    private Response<AiMessage> parseResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode contentArray = root.path("content");

        if (!contentArray.isArray() || contentArray.isEmpty()) {
            throw new RuntimeException("响应格式异常，缺少 content: " + responseBody);
        }

        // 拼接所有 text 类型的内容块
        StringBuilder text = new StringBuilder();
        for (JsonNode block : contentArray) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }

        return Response.from(AiMessage.from(text.toString()));
    }

    /**
     * 流式生成回答（SSE）
     *
     * 和 generate() 的区别：
     * - generate()：等 Claude 生成完整个答案，一次性返回
     * - generateStream()：Claude 每生成一小段，立刻通过回调推出去（打字机效果）
     *
     * 技术要点：
     * 1. 请求体加 "stream": true，告诉网关用流式返回
     * 2. 响应是 SSE 格式（一行行的 "data: {...}"），用 ofLines() 按行读取
     * 3. 每行解析出增量文本，通过 onPartial 回调推给上层
     *
     * Anthropic SSE 事件格式示例：
     *   data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Redis"}}
     *   data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"连接池"}}
     *   data: {"type":"message_stop"}
     *
     * @param messages   对话消息
     * @param onPartial  每收到一段文本时的回调（把这段文本推给前端）
     * @param onComplete 全部生成完成时的回调
     * @param onError    出错时的回调
     */
    public void generateStream(List<ChatMessage> messages,
                               Consumer<String> onPartial,
                               Consumer<String> onComplete,
                               Consumer<Throwable> onError) {
        try {
            // 1. 构造请求体，加上 stream: true
            ObjectNode root = objectMapper.readValue(buildRequestBody(messages), ObjectNode.class);
            root.put("stream", true);
            String requestJson = objectMapper.writeValueAsString(root);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(messagesUrl))
                    .header("Authorization", "Bearer " + authToken)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            // 2. 用 ofLines() 接收响应——按行读取 SSE 流
            HttpResponse<Stream<String>> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Claude 网关流式返回非 200, status=" + response.statusCode());
            }

            // 3. 累积完整答案（用于 onComplete），同时逐段推送
            StringBuilder fullAnswer = new StringBuilder();
            response.body().forEach(line -> {
                // SSE 每个数据行以 "data: " 开头
                if (line.startsWith("data: ")) {
                    String json = line.substring(6).trim();
                    String delta = extractDeltaText(json);
                    if (delta != null && !delta.isEmpty()) {
                        fullAnswer.append(delta);
                        onPartial.accept(delta);  // 推送这一小段给前端
                    }
                }
            });

            onComplete.accept(fullAnswer.toString());

        } catch (Exception e) {
            log.error("流式调用失败", e);
            onError.accept(e);
        }
    }

    /**
     * 从一行 SSE data 的 JSON 里提取增量文本
     *
     * 只关心 content_block_delta 事件里的 text_delta，
     * 其他事件（message_start、message_stop 等）忽略，返回 null。
     */
    private String extractDeltaText(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            // 只处理 content_block_delta 类型的事件
            if ("content_block_delta".equals(node.path("type").asText())) {
                JsonNode delta = node.path("delta");
                if ("text_delta".equals(delta.path("type").asText())) {
                    return delta.path("text").asText();
                }
            }
            return null;
        } catch (Exception e) {
            // 某些行可能不是合法 JSON（如空行、注释），忽略
            return null;
        }
    }
}
