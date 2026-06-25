package com.learning.rag.agent;

import com.learning.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具
 *
 * 复用现有的 RAG 检索能力，作为 Agent 的一个工具。
 * 当用户问题涉及 Spring Boot / Redis / 数据库等技术文档内容时，
 * Agent 会决定调用这个工具去查知识库。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool implements Tool {

    private final RagService ragService;

    @Override
    public String name() {
        return "knowledge_search";
    }

    @Override
    public String description() {
        return "知识库检索工具，用于查询 Spring Boot、Redis、数据库配置等技术文档内容。" +
                "当问题涉及这些技术的配置、用法、原理时使用。" +
                "输入是检索关键词，例如：Redis 连接池配置";
    }

    @Override
    public String execute(String input) {
        return ragService.searchKnowledge(input);
    }
}
