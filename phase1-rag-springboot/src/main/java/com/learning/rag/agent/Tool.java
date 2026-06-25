package com.learning.rag.agent;

/**
 * Agent 工具接口
 *
 * 每个工具就是一个"能力"，Agent（LLM）可以决定调用哪个。
 *
 * 设计要点：
 * - name()：工具名，LLM 用它来"点名"调用哪个工具
 * - description()：工具说明，告诉 LLM "这个工具能干什么、什么时候用"
 * - execute()：真正执行工具逻辑，返回结果字符串
 *
 * 核心思想：
 * LLM 负责"决定调用哪个工具、传什么参数"，
 * 工具负责"真正执行"。两者分工明确。
 */
public interface Tool {

    /**
     * 工具名称（唯一标识，LLM 用它来调用）
     * 例如："knowledge_search"、"calculator"
     */
    String name();

    /**
     * 工具描述（给 LLM 看的说明书）
     * 告诉 LLM：这个工具是干什么的、什么场景该用、参数是什么
     */
    String description();

    /**
     * 执行工具
     *
     * @param input LLM 传入的参数（字符串）
     * @return 执行结果（字符串，会回传给 LLM）
     */
    String execute(String input);
}
