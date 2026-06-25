package com.learning.rag.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.learning.rag.dto.RagResult;
import com.learning.rag.model.IflytekClaudeChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 核心服务
 *
 * 实现完整的 RAG 流程：
 * 1. 向量检索（Retrieval）
 * 2. 上下文增强（Augmentation）
 * 3. 生成回答（Generation）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final ChatLanguageModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ConversationService conversationService;

    @Value("${rag.max-results}")
    private Integer maxResults;

    @Value("${rag.min-score}")
    private Double minScore;

    /**
     * RAG 主流程：根据问题检索相关文档并生成回答
     *
     * 这是整个系统的核心方法！
     */
    public String query(String question) {
        log.info("收到问题: {}", question);

        // Step 1: 检索相关文档片段
        List<TextSegment> relevantSegments = retrieveRelevantSegments(question);

        if (relevantSegments.isEmpty()) {
            return "抱歉，我在知识库中没有找到相关信息。请尝试换个问题或添加更多文档。";
        }

        // Step 2: 构建增强的 Prompt
        String augmentedPrompt = buildAugmentedPrompt(question, relevantSegments);
        log.debug("增强后的 Prompt:\n{}", augmentedPrompt);

        // Step 3: 调用 LLM 生成回答
        Response<AiMessage> response = chatModel.generate(UserMessage.from(augmentedPrompt));
        String answer = response.content().text();

        log.info("生成回答完成");
        return answer;
    }

    /**
     * RAG 多轮对话：带历史的查询（含问题重写）
     *
     * 和 query() 的核心区别：
     * - query()：每次独立，AI 不记得上一句
     * - queryWithHistory()：把会话历史一起发给 LLM，AI 能理解"它""刚才"等指代
     *
     * 流程（含问题重写，解决"检索失焦"）：
     * 0. 【问题重写】结合历史，把"它有什么优点？"还原成"HikariCP 有什么优点？"
     * 1. 用重写后的完整问题检索（检索层也"有记忆"了）
     * 2. 取出该会话的历史消息
     * 3. 组装消息列表：[历史对话..., 当前问题(含检索片段)]
     * 4. 调用 LLM —— 它能看到完整上下文
     * 5. 把这轮 Q&A 存回历史
     *
     * @param conversationId 会话 ID（区分不同对话）
     * @param question       当前问题（可能含指代，如"它"）
     */
    public String queryWithHistory(String conversationId, String question) {
        log.info("收到多轮问题 [会话={}]: {}", conversationId, question);

        // 取出历史（重写和生成都要用）
        List<ChatMessage> history = conversationService.getHistory(conversationId);

        // Step 0: 问题重写 —— 结合历史把指代还原成完整问题（仅用于检索）
        String searchQuery = rewriteQuestion(history, question);
        log.info("问题重写: 「{}」-> 「{}」", question, searchQuery);

        // Step 1: 用重写后的问题检索（检索层也获得了上下文）
        List<TextSegment> relevantSegments = retrieveRelevantSegments(searchQuery);
        if (relevantSegments.isEmpty()) {
            return "抱歉，我在知识库中没有找到相关信息。";
        }

        // Step 2: 拼当前轮的 Prompt（检索片段 + 原始问题）
        //   注意：拼 Prompt 用原始问题，让对话更自然（重写只为检索服务）
        String augmentedPrompt = buildAugmentedPrompt(question, relevantSegments);

        // Step 3: 组装消息列表 = 历史 + 当前问题
        List<ChatMessage> messages = new ArrayList<>(history);
        messages.add(UserMessage.from(augmentedPrompt));

        // Step 4: 调用 LLM（传入完整消息列表，LLM 就"看得到"历史）
        Response<AiMessage> response = chatModel.generate(messages);
        String answer = response.content().text();

        // Step 5: 把这轮对话存回历史
        //   注意：历史里存的是「原始问题」，不是带检索片段的长 Prompt
        //   （否则历史会越滚越大，塞满检索内容）
        conversationService.addExchange(conversationId, question, answer);

        log.info("多轮回答完成 [会话={}]", conversationId);
        return answer;
    }

    /**
     * 问题重写（Query Rewriting）：结合历史，把含指代的问题还原成完整问题
     *
     * 解决"检索失焦"：
     * - 原始问题"它有什么优点？"单独检索 → 匹配不到正确片段
     * - 重写成"HikariCP 有什么优点？" → 检索准确
     *
     * 实现：让 LLM 看历史 + 当前问题，输出一个"自包含"的完整问题。
     *
     * 注意：
     * - 没有历史时（第一轮），直接返回原问题，不浪费一次 LLM 调用
     * - 重写只为检索服务，最终拼 Prompt 仍用原始问题（保持对话自然）
     */
    private String rewriteQuestion(List<ChatMessage> history, String question) {
        // 第一轮没有历史，无需重写
        if (history.isEmpty()) {
            return question;
        }

        // 把历史拼成可读文本，给重写用
        StringBuilder historyText = new StringBuilder();
        for (ChatMessage msg : history) {
            if (msg instanceof UserMessage um) {
                historyText.append("用户：").append(um.singleText()).append("\n");
            } else if (msg instanceof AiMessage am) {
                historyText.append("助手：").append(am.text()).append("\n");
            }
        }

        // 构造重写 Prompt：要求 LLM 只输出改写后的问题，不要多余内容
        String rewritePrompt = "下面是一段对话历史和用户的最新问题。\n" +
                "请结合历史，把最新问题改写成一个【不依赖上下文也能看懂】的完整问题，" +
                "把其中的指代词（它、这个、那个等）替换成具体内容。\n" +
                "只输出改写后的问题本身，不要加任何解释、前缀或标点说明。\n\n" +
                "对话历史：\n" + historyText + "\n" +
                "最新问题：" + question + "\n\n" +
                "改写后的问题：";

        try {
            Response<AiMessage> response = chatModel.generate(
                    UserMessage.from(rewritePrompt));
            String rewritten = response.content().text().trim();
            // 兜底：如果重写结果为空，退回原问题
            return rewritten.isEmpty() ? question : rewritten;
        } catch (Exception e) {
            // 重写失败不影响主流程，退回原问题
            log.warn("问题重写失败，使用原问题", e);
            return question;
        }
    }

    /**
     * RAG 流式查询：边生成边推送（打字机效果）
     *
     * 和 query() 的区别：
     * - query()：等 LLM 生成完整个答案才返回
     * - queryStream()：检索照旧，但生成阶段逐段推送给前端
     *
     * 检索阶段（找片段、拼 Prompt）和 query() 完全一样，
     * 只是最后调用 LLM 时用流式方法。
     *
     * @param question   用户问题
     * @param onPartial  每收到一段答案文本的回调
     * @param onComplete 全部完成的回调
     * @param onError    出错的回调
     */
    public void queryStream(String question,
                            java.util.function.Consumer<String> onPartial,
                            java.util.function.Consumer<String> onComplete,
                            java.util.function.Consumer<Throwable> onError) {
        log.info("收到流式问题: {}", question);

        // Step 1 & 2: 检索 + 拼 Prompt（和非流式完全一样）
        List<TextSegment> relevantSegments = retrieveRelevantSegments(question);
        if (relevantSegments.isEmpty()) {
            onPartial.accept("抱歉，我在知识库中没有找到相关信息。");
            onComplete.accept("抱歉，我在知识库中没有找到相关信息。");
            return;
        }
        String augmentedPrompt = buildAugmentedPrompt(question, relevantSegments);

        // Step 3: 流式调用 LLM
        // chatModel 字段是 ChatLanguageModel 接口类型，
        // 流式方法在具体类 IflytekClaudeChatModel 上，所以这里转型
        IflytekClaudeChatModel streamModel = (IflytekClaudeChatModel) chatModel;
        streamModel.generateStream(
                List.of(UserMessage.from(augmentedPrompt)),
                onPartial,
                onComplete,
                onError
        );
    }

    /**
     * RAG 流式多轮对话：带历史 + 逐字推送（集大成版）
     *
     * 结合了 queryWithHistory（多轮+问题重写）和 queryStream（流式）。
     *
     * 流程：
     * 0. 问题重写（结合历史）
     * 1. 用重写后的问题检索
     * 2. 流式调用 LLM（带历史）
     * 3. 把这轮对话存回历史
     *
     * @param conversationId 会话 ID
     * @param question       当前问题
     * @param onPartial      每收到一段答案的回调
     * @param onComplete     全部完成的回调（完整答案）
     * @param onError        出错的回调
     */
    public void queryStreamWithHistory(String conversationId,
                                       String question,
                                       java.util.function.Consumer<String> onPartial,
                                       java.util.function.Consumer<String> onComplete,
                                       java.util.function.Consumer<Throwable> onError) {
        log.info("收到流式多轮问题 [会话={}]: {}", conversationId, question);

        // 取出历史
        List<ChatMessage> history = conversationService.getHistory(conversationId);

        // Step 0: 问题重写
        String searchQuery = rewriteQuestion(history, question);
        if (!question.equals(searchQuery)) {
            log.info("问题重写: 「{}」-> 「{}」", question, searchQuery);
        }

        // Step 1: 检索
        List<TextSegment> relevantSegments = retrieveRelevantSegments(searchQuery);
        if (relevantSegments.isEmpty()) {
            onPartial.accept("抱歉，我在知识库中没有找到相关信息。");
            onComplete.accept("抱歉，我在知识库中没有找到相关信息。");
            return;
        }

        // Step 2: 拼 Prompt（用原始问题）
        String augmentedPrompt = buildAugmentedPrompt(question, relevantSegments);

        // Step 3: 组装消息列表（历史 + 当前问题）
        List<ChatMessage> messages = new ArrayList<>(history);
        messages.add(UserMessage.from(augmentedPrompt));

        // Step 4: 流式调用 LLM
        IflytekClaudeChatModel streamModel = (IflytekClaudeChatModel) chatModel;

        // 包装 onComplete：完成时把这轮对话存回历史
        java.util.function.Consumer<String> wrappedComplete = fullAnswer -> {
            conversationService.addExchange(conversationId, question, fullAnswer);
            log.info("流式多轮回答完成 [会话={}]", conversationId);
            onComplete.accept(fullAnswer);
        };

        streamModel.generateStream(messages, onPartial, wrappedComplete, onError);
    }

    /**
     * RAG 主流程（详细版）：返回检索过程的全部中间数据
     *
     * 和 query() 的区别：query() 只返回最终答案字符串，
     * 这个方法把"检索到哪些片段、分数多少、Prompt 长啥样"都返回出来，
     * 方便你直观理解 RAG 的检索阶段在做什么。
     *
     * 注意这里和 query() 的一个关键差异：
     * 我们保留了 EmbeddingMatch 对象（它带着 score 分数），
     * 而不是像 retrieveRelevantSegments() 那样只取出 TextSegment 把分数丢掉。
     */
    public RagResult queryWithDetails(String question) {
        log.info("收到问题(详细模式): {}", question);

        // Step 1: 检索 —— 这次保留完整的 match 对象（含分数）
        //    使用新的 search() API 替代已废弃的 findRelevant()
        Embedding questionEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
        List<EmbeddingMatch<TextSegment>> matches =
                embeddingStore.search(request).matches();

        // 把检索结果转成可读的结构（序号 + 分数 + 文本）
        List<RagResult.RetrievedSegment> retrieved = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            retrieved.add(new RagResult.RetrievedSegment(
                    i + 1, match.score(), match.embedded().text()));
            segments.add(match.embedded());
            log.info("片段 {} | 相似度={} | 内容前50字: {}",
                    i + 1, match.score(),
                    match.embedded().text().substring(0,
                            Math.min(50, match.embedded().text().length())));
        }

        // 没检索到任何片段
        if (segments.isEmpty()) {
            return new RagResult(question,
                    "抱歉，我在知识库中没有找到相关信息。",
                    retrieved, "(未检索到片段，未调用 LLM)");
        }

        // Step 2: 构建增强 Prompt
        String augmentedPrompt = buildAugmentedPrompt(question, segments);

        // Step 3: 调用 LLM
        Response<AiMessage> response = chatModel.generate(UserMessage.from(augmentedPrompt));
        String answer = response.content().text();

        log.info("生成回答完成(详细模式)");
        return new RagResult(question, answer, retrieved, augmentedPrompt);
    }

    /**
     * Step 1: 向量检索
     *
     * 核心原理：
     * 1. 将用户问题转换为向量
     * 2. 在向量存储中查找最相似的文档向量
     * 3. 返回相似度最高的 N 个文档片段
     *
     * 学习要点：
     * - 相似度计算：通常使用余弦相似度（Cosine Similarity）
     * - 为什么向量能表示语义？
     *   → Embedding 模型训练时学习到了词汇、句子的语义关系
     *   → 语义相近的文本在向量空间中距离更近
     */
    private List<TextSegment> retrieveRelevantSegments(String question) {
        // 1. 将问题转换为向量
        Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 2. 在向量存储中查找最相似的文档
        //    使用新的 search() API（EmbeddingSearchRequest），
        //    替代已废弃的 findRelevant()。
        //    新 API 和 Qdrant 兼容性更好（findRelevant 在 Qdrant 上会因
        //    返回向量为空而触发"向量长度不匹配"错误）
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
        List<EmbeddingMatch<TextSegment>> matches =
                embeddingStore.search(request).matches();

        // 3. 提取文档片段
        List<TextSegment> segments = matches.stream()
                .map(match -> {
                    log.debug("检索到相关片段，相似度: {}", match.score());
                    return match.embedded();
                })
                .collect(Collectors.toList());

        log.info("检索到 {} 个相关文档片段", segments.size());
        return segments;
    }

    /**
     * Step 2: 构建增强的 Prompt
     *
     * RAG 的核心技巧：如何组织上下文
     *
     * Prompt Engineering 要点：
     * 1. 明确角色定位（你是一个技术文档助手）
     * 2. 提供相关上下文（检索到的文档片段）
     * 3. 明确任务要求（基于上下文回答问题）
     * 4. 设定约束条件（如果上下文没有答案，诚实告知）
     *
     * 为什么这样设计？
     * - 减少幻觉（Hallucination）：明确要求基于上下文
     * - 提高准确性：提供相关信息作为依据
     * - 改善用户体验：承认不知道比乱答好
     */
    private String buildAugmentedPrompt(String question, List<TextSegment> segments) {
        StringBuilder prompt = new StringBuilder();

        // 角色和任务定义
        prompt.append("你是一个技术文档助手。请根据以下上下文信息回答用户的问题。\n\n");

        // Few-shot 示例（Prompt Engineering 实验：Few-shot 版本）
        prompt.append("## 示例\n\n");
        prompt.append("问题：Spring Boot 的依赖注入和 @Autowired 注解有什么关系？\n");
        prompt.append("回答：依赖注入是 Spring Boot 的核心机制，它负责管理对象的创建和装配。");
        prompt.append("@Autowired 注解是使用这个机制的一种方式，开发者用它标记需要注入的依赖。");
        prompt.append("简单说，依赖注入提供了\"自动装配的能力\"，@Autowired 告诉框架\"在这里用这个能力\"。");
        prompt.append("它们是机制与应用的关系。\n\n");

        prompt.append("---\n\n");

        prompt.append("## 现在回答下面的问题\n\n");

        prompt.append("注意：\n");
        prompt.append("- 只基于提供的上下文信息回答\n");
        prompt.append("- 参考上面示例的分析思路（识别机制和应用）\n");
        prompt.append("- 回答要准确、简洁、易懂\n\n");

        // 上下文信息
        prompt.append("上下文信息：\n");
        prompt.append("---\n");
        for (int i = 0; i < segments.size(); i++) {
            prompt.append("文档片段 ").append(i + 1).append(":\n");
            prompt.append(segments.get(i).text()).append("\n\n");
        }
        prompt.append("---\n\n");

        // 用户问题
        prompt.append("用户问题：").append(question).append("\n\n");
        prompt.append("回答：");

        return prompt.toString();
    }

    /**
     * 知识库检索（供 Agent 工具调用）
     *
     * 和 query() 的区别：
     * - query()：检索 + 调 LLM 生成答案
     * - searchKnowledge()：只检索，返回原始片段文本（不调 LLM）
     *
     * 为什么 Agent 用这个？
     * Agent 的主 LLM 会自己综合检索到的片段，所以工具只需返回"原材料"，
     * 不需要在工具里再调一次 LLM 生成（那样就重复了）。
     *
     * @param question 检索关键词
     * @return 拼接好的相关片段文本
     */
    public String searchKnowledge(String question) {
        List<TextSegment> segments = retrieveRelevantSegments(question);
        if (segments.isEmpty()) {
            return "知识库中没有找到相关信息。";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.size(); i++) {
            sb.append("【片段").append(i + 1).append("】")
              .append(segments.get(i).text()).append("\n\n");
        }
        return sb.toString();
    }
}
