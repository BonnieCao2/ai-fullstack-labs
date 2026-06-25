package com.learning.rag.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话历史管理服务（内存版）
 *
 * 职责：
 * 1. 按会话 ID 存储每个对话的历史消息
 * 2. 自动保留最近 N 轮，防止历史无限增长（超出 LLM 上下文窗口）
 *
 * 核心知识点：
 * - LLM 本身是「无状态」的，每次调用都是独立的，自己不记任何东西
 * - 所谓「记忆」= 每次调用时把历史对话一起发给它
 * - 这个类就是那个「记事本」，帮 LLM 记住聊过什么
 *
 * 为什么用 ConcurrentHashMap？
 * - 多个用户可能同时对话，需要线程安全的 Map
 */
@Service
public class ConversationService {

    /**
     * 每轮对话保留的最大消息数。
     * 一轮 = 1 个用户消息 + 1 个 AI 回复 = 2 条。
     * 保留 10 条 = 最近 5 轮对话。
     *
     * 为什么要限制？
     * - LLM 上下文窗口有限，历史无限增长会超限、变慢、变贵
     * - 太早的对话通常和当前问题关系不大
     */
    private static final int MAX_MESSAGES = 10;

    /**
     * 会话存储：key = 会话 ID，value = 该会话的消息历史
     */
    private final Map<String, List<ChatMessage>> conversations = new ConcurrentHashMap<>();

    /**
     * 获取某个会话的历史消息（只读副本）
     */
    public List<ChatMessage> getHistory(String conversationId) {
        return new ArrayList<>(
                conversations.getOrDefault(conversationId, new ArrayList<>()));
    }

    /**
     * 添加一轮对话（用户问题 + AI 回复）到历史
     *
     * 添加后自动裁剪，只保留最近 MAX_MESSAGES 条。
     */
    public void addExchange(String conversationId, String userQuestion, String aiAnswer) {
        List<ChatMessage> history = conversations.computeIfAbsent(
                conversationId, k -> new ArrayList<>());

        history.add(UserMessage.from(userQuestion));
        history.add(AiMessage.from(aiAnswer));

        // 裁剪：只保留最近 MAX_MESSAGES 条（滑动窗口）
        while (history.size() > MAX_MESSAGES) {
            history.remove(0);  // 移除最旧的
        }
    }

    /**
     * 清空某个会话的历史（用户点"新对话"时调用）
     */
    public void clear(String conversationId) {
        conversations.remove(conversationId);
    }
}
