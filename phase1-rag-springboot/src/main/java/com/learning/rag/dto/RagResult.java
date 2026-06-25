package com.learning.rag.dto;

import java.util.List;

/**
 * RAG 查询的完整结果（实验用：把检索过程的中间数据全部暴露出来）
 *
 * 为什么要这个类？
 * ----------------
 * 默认情况下，query 只返回最终答案（一个 String），整个检索过程是黑盒。
 * 这个类把 RAG 的中间产物都"接"出来，方便你直观理解：
 * - 系统到底检索到了哪些片段？
 * - 每个片段和问题的相似度分数是多少？
 * - 最终拼给 LLM 的 Prompt 长什么样？
 *
 * Java record：一种简洁的不可变数据载体（Java 14+）。
 * 自动生成构造器、getter、equals、toString，适合做 DTO。
 */
public record RagResult(
        String question,                       // 原始问题
        String answer,                         // LLM 最终回答
        List<RetrievedSegment> retrievedSegments,  // 检索到的片段（带分数）
        String augmentedPrompt                 // 拼接后发给 LLM 的完整 Prompt
) {
    /**
     * 单个检索片段：分数 + 文本
     *
     * @param index 第几个片段（从 1 开始）
     * @param score 相似度分数（0~1，越高越相关）
     * @param text  片段的文本内容
     */
    public record RetrievedSegment(int index, double score, String text) {}
}
