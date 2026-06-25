package com.learning.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.bge.small.zh.v15.BgeSmallZhV15EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import com.learning.rag.model.IflytekClaudeChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 模型配置类
 *
 * 核心概念：
 * 1. ChatLanguageModel: 大语言模型，负责生成回答
 * 2. EmbeddingModel: 向量化模型，负责将文本转换为向量
 * 3. EmbeddingStore: 向量存储，负责保存和检索文档向量
 */
@Configuration
public class AiConfig {

    @Value("${ai.anthropic.auth-token}")
    private String authToken;

    @Value("${ai.anthropic.base-url}")
    private String baseUrl;

    @Value("${ai.anthropic.model}")
    private String modelName;

    @Value("${ai.anthropic.max-tokens}")
    private Integer maxTokens;

    /**
     * 配置 Claude 大语言模型
     *
     * 学习要点：
     * - maxTokens: 限制生成的长度，影响成本和响应时间
     *
     * 实现细节：
     * - 使用自定义的 IflytekClaudeChatModel，用 Authorization: Bearer 认证
     *   （讯飞网关的认证方式，LangChain4j 内置的 AnthropicChatModel 用的是
     *   x-api-key，无法适配，详见 IflytekClaudeChatModel 类注释）
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return new IflytekClaudeChatModel(baseUrl, authToken, modelName, maxTokens);
    }

    /**
     * 配置向量化模型（Embedding Model）
     *
     * 学习要点：
     * - 使用本地轻量级模型 all-MiniLM-L6-v2（无需 API 调用）
     * - 这个模型会将文本转换为 384 维的向量
     * - 向量可以用来计算文本之间的语义相似度
     *
     * 优势：
     * - 免费、快速、离线可用
     * - 适合原型和学习阶段
     *
     * 劣势：
     * - 准确度不如 OpenAI 的 text-embedding-ada-002
     * - 不支持中文（后续可替换为中文模型）
     *
     * 实验 2：从英文模型切换到中文模型
     * - all-MiniLM-L6-v2: 英文模型，384 维，中文几乎是噪音
     * - bge-small-zh:    中文模型，512 维，专为中文优化
     * 切换 Embedding 模型后，向量维度会变，所以必须重新摄入所有文档
     * （旧向量是 384 维，新模型生成 512 维，无法混用）
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        // 实验 2：换成中文模型。想对比英文模型时，把下面两行互换注释即可
        // return new AllMiniLmL6V2EmbeddingModel();  // 英文模型（384维）
        return new BgeSmallZhV15EmbeddingModel();     // 中文模型 v1.5（512维）
    }

    /**
     * 配置向量存储
     *
     * 学习要点：
     * - 内存存储（InMemory）：重启丢失，适合快速实验 ← 当前使用
     * - Qdrant：持久化向量库，但 langchain4j 0.31.0 的 Qdrant 适配有 bug
     *   （findRelevant 重算 score 时拿到空向量，报"向量长度不匹配"），暂时放弃
     * - 待后续阶段用更成熟的版本或换 Chroma/PGVector 再战持久化
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }
}
