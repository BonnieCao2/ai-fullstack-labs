package com.learning.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * RAG 文档助手 - 主应用入口
 *
 * 学习目标：
 * 1. 理解 RAG (Retrieval-Augmented Generation) 的完整流程
 * 2. 掌握向量检索的基本原理
 * 3. 学会工程化 AI 应用的基础架构
 */
@SpringBootApplication
@EnableAsync  // 启用异步支持（用于后台摄入知识库）
public class RagDocAssistantApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagDocAssistantApplication.class, args);
    }
}
