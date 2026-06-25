package com.learning.rag.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 服务：手写 ReAct 循环
 *
 * ReAct = Reasoning（推理）+ Acting（行动）
 *
 * 核心思想（和固定流程的 RAG 完全不同）：
 * - RAG：流程写死（检索→生成）
 * - Agent：LLM 自己决定每一步做什么（要不要调工具、调哪个、调几次）
 *
 * 工作循环：
 *   Thought（思考）：我现在该做什么？
 *   Action（行动）：调用某个工具
 *   Observation（观察）：看工具返回结果
 *   → 回到 Thought，继续判断
 *   直到：信息够了 → 给最终答案
 *
 * 实现关键：
 * 因为不依赖模型的 Function Calling 能力，我们用 Prompt 约定一个
 * 固定的 JSON 输出格式，让 LLM 的输出能被程序解析。
 */
@Slf4j
@Service
public class AgentService {

    private final ChatLanguageModel chatModel;
    private final Map<String, Tool> tools;  // 工具名 → 工具实例
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 最大循环次数，防止 LLM 陷入死循环 */
    private static final int MAX_STEPS = 5;

    /**
     * 构造时，Spring 自动注入所有 Tool 实现，转成 Map 方便按名查找
     */
    public AgentService(ChatLanguageModel chatModel, List<Tool> toolList) {
        this.chatModel = chatModel;
        this.tools = toolList.stream()
                .collect(Collectors.toMap(Tool::name, t -> t));
        log.info("Agent 初始化完成，可用工具: {}", tools.keySet());
    }

    /**
     * Agent 主流程：处理用户问题
     */
    public String run(String question) {
        log.info("Agent 收到问题: {}", question);

        // scratchpad：记录每一步的"思考-行动-观察"，作为 LLM 的"工作草稿纸"
        StringBuilder scratchpad = new StringBuilder();

        // ReAct 循环
        for (int step = 1; step <= MAX_STEPS; step++) {
            log.info("--- Agent 第 {} 步 ---", step);

            // 1. 构造 Prompt（问题 + 工具说明 + 之前的步骤记录）
            String prompt = buildPrompt(question, scratchpad.toString());

            // 2. 问 LLM "下一步做什么"
            Response<AiMessage> response = chatModel.generate(UserMessage.from(prompt));
            String llmOutput = response.content().text().trim();
            log.info("LLM 输出: {}", llmOutput);

            // 3. 解析 LLM 的决定
            JsonNode decision = parseDecision(llmOutput);
            if (decision == null) {
                // 解析失败，兜底直接返回 LLM 的原始输出
                return llmOutput;
            }

            String type = decision.path("type").asText();

            if ("final_answer".equals(type)) {
                // LLM 认为信息够了，给出最终答案 → 跳出循环
                String answer = decision.path("content").asText();
                log.info("Agent 得出最终答案");
                return answer;

            } else if ("tool_call".equals(type)) {
                // LLM 决定调用工具
                String toolName = decision.path("tool").asText();
                String toolInput = decision.path("input").asText();

                Tool tool = tools.get(toolName);
                String observation;
                if (tool == null) {
                    observation = "错误：没有名为 " + toolName + " 的工具";
                } else {
                    log.info("调用工具: {} | 输入: {}", toolName, toolInput);
                    observation = tool.execute(toolInput);
                    log.info("工具返回: {}", observation.substring(0,
                            Math.min(100, observation.length())));
                }

                // 把这一步记入草稿纸，供下一轮 LLM 参考
                scratchpad.append("思考与行动：调用了工具 ").append(toolName)
                        .append("，输入「").append(toolInput).append("」\n");
                scratchpad.append("观察结果：").append(observation).append("\n\n");
            }
        }

        // 超过最大步数仍没结论
        return "抱歉，我思考了多步仍无法得出答案，请尝试换个问法。";
    }

    /**
     * 构造发给 LLM 的 Prompt
     *
     * 这是 ReAct 的核心——通过 Prompt 教 LLM：
     * 1. 你有哪些工具
     * 2. 用固定的 JSON 格式回复（方便程序解析）
     * 3. 看之前的步骤，决定下一步
     */
    private String buildPrompt(String question, String scratchpad) {
        // 列出所有工具的说明
        String toolDescriptions = tools.values().stream()
                .map(t -> "- " + t.name() + "：" + t.description())
                .collect(Collectors.joining("\n"));

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个智能助手，可以使用工具来回答问题。\n\n");

        prompt.append("可用工具：\n").append(toolDescriptions).append("\n\n");

        prompt.append("请严格按以下 JSON 格式回复（只输出 JSON，不要其他内容）：\n");
        prompt.append("如果需要调用工具：\n");
        prompt.append("{\"type\":\"tool_call\",\"tool\":\"工具名\",\"input\":\"工具输入\"}\n");
        prompt.append("如果信息已足够，给出最终答案：\n");
        prompt.append("{\"type\":\"final_answer\",\"content\":\"你的回答\"}\n\n");

        prompt.append("规则：\n");
        prompt.append("1. 一次只做一个决定（调一个工具，或给最终答案）\n");
        prompt.append("2. 需要查技术文档就用 knowledge_search，需要算数就用 calculator\n");
        prompt.append("3. 拿到工具结果后，判断信息是否足够，不够就继续调工具\n");
        prompt.append("4. 信息足够时，务必给出 final_answer\n\n");

        prompt.append("用户问题：").append(question).append("\n\n");

        if (!scratchpad.isEmpty()) {
            prompt.append("已执行的步骤：\n").append(scratchpad).append("\n");
            prompt.append("基于以上步骤，决定下一步：\n");
        } else {
            prompt.append("请决定第一步：\n");
        }

        return prompt.toString();
    }

    /**
     * 解析 LLM 输出的 JSON 决定
     *
     * LLM 有时会在 JSON 前后加多余文字，这里做容错——
     * 提取第一个 { 到最后一个 } 之间的内容。
     */
    private JsonNode parseDecision(String llmOutput) {
        try {
            int start = llmOutput.indexOf('{');
            int end = llmOutput.lastIndexOf('}');
            if (start == -1 || end == -1 || start > end) {
                return null;
            }
            String json = llmOutput.substring(start, end + 1);
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("解析 LLM 决定失败: {}", llmOutput, e);
            return null;
        }
    }
}
