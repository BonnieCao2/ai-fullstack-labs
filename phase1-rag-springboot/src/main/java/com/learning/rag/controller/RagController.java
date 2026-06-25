package com.learning.rag.controller;

import com.learning.rag.dto.RagResult;
import com.learning.rag.agent.AgentService;
import com.learning.rag.service.ConversationService;
import com.learning.rag.service.DocumentService;
import com.learning.rag.service.RagService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RAG API 控制器
 */
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;
    private final DocumentService documentService;
    private final ConversationService conversationService;
    private final AgentService agentService;

    // 流式请求用的线程池（每个流式请求在独立线程跑，避免阻塞 Web 线程）
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    /**
     * 问答接口
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest request) {
        String answer = ragService.query(request.getQuestion());
        return ResponseEntity.ok(new QueryResponse(answer));
    }

    /**
     * Agent 接口：LLM 自己决定调用哪些工具、分几步完成
     *
     * 和 /query 的区别：
     * - /query：固定流程（检索→生成）
     * - /agent：LLM 自主决策（可能调知识库、可能算数、可能多步）
     */
    @PostMapping("/agent")
    public ResponseEntity<QueryResponse> agent(@RequestBody QueryRequest request) {
        String answer = agentService.run(request.getQuestion());
        return ResponseEntity.ok(new QueryResponse(answer));
    }

    /**
     * 多轮对话接口：带历史，AI 能理解上下文（"它""刚才"等指代）
     *
     * 请求需带 conversationId 区分不同对话。
     */
    @PostMapping("/chat")
    public ResponseEntity<QueryResponse> chat(@RequestBody ChatRequest request) {
        String answer = ragService.queryWithHistory(
                request.getConversationId(), request.getQuestion());
        return ResponseEntity.ok(new QueryResponse(answer));
    }

    /**
     * 清空某个会话的历史（开始新对话）
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<String> clearChat(@RequestBody ClearRequest request) {
        conversationService.clear(request.getConversationId());
        return ResponseEntity.ok("会话已清空");
    }

    /**
     * 流式多轮对话接口（SSE）：既支持多轮上下文，又逐字推送
     *
     * 集大成版：问题重写 + 历史记忆 + 流式输出。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);

        streamExecutor.execute(() -> {
            ragService.queryStreamWithHistory(
                    request.getConversationId(),
                    request.getQuestion(),
                    // onPartial：每收到一段，推给客户端
                    partial -> {
                        try {
                            emitter.send(SseEmitter.event().data(partial));
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    },
                    // onComplete：全部完成，关闭连接
                    full -> emitter.complete(),
                    // onError：出错，带错误关闭
                    emitter::completeWithError
            );
        });

        return emitter;
    }

    /**
     * 流式问答接口（SSE）：答案逐字推送，打字机效果
     *
     * 返回 text/event-stream，客户端会持续收到一段段文本。
     *
     * 实现要点：
     * - SseEmitter：Spring 的 SSE 工具，保持一个长连接持续推数据
     * - 在独立线程执行：流式调用是阻塞的（要等 LLM 一段段吐），
     *   不能占用 Web 请求线程，否则并发能力会很差
     */
    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter queryStream(@RequestBody QueryRequest request) {
        // timeout 设长一点（120秒），避免长答案被中途掐断
        SseEmitter emitter = new SseEmitter(120_000L);

        streamExecutor.execute(() -> {
            ragService.queryStream(
                    request.getQuestion(),
                    // onPartial：每收到一段，推给客户端
                    partial -> {
                        try {
                            emitter.send(SseEmitter.event().data(partial));
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    },
                    // onComplete：全部完成，关闭连接
                    full -> emitter.complete(),
                    // onError：出错，带错误关闭
                    emitter::completeWithError
            );
        });

        return emitter;
    }

    /**
     * 问答接口（详细版）：返回检索到的片段、相似度分数、完整 Prompt
     *
     * 实验用：让你直观看到 RAG 检索阶段的中间过程
     */
    @PostMapping("/query/details")
    public ResponseEntity<RagResult> queryWithDetails(@RequestBody QueryRequest request) {
        RagResult result = ragService.queryWithDetails(request.getQuestion());
        return ResponseEntity.ok(result);
    }

    /**
     * 摄入文档接口
     */
    @PostMapping("/documents/ingest")
    public ResponseEntity<String> ingestDocument(@RequestBody IngestRequest request) {
        documentService.ingestDocument(request.getFilePath());
        return ResponseEntity.ok("文档摄入成功");
    }

    /**
     * 批量摄入目录下的文档
     */
    @PostMapping("/documents/ingest-directory")
    public ResponseEntity<String> ingestDirectory(@RequestBody IngestDirectoryRequest request) {
        documentService.ingestDirectory(request.getDirectoryPath());
        return ResponseEntity.ok("目录摄入成功");
    }

    @Data
    public static class QueryRequest {
        private String question;
    }

    @Data
    public static class ChatRequest {
        private String conversationId;
        private String question;
    }

    @Data
    public static class ClearRequest {
        private String conversationId;
    }

    @Data
    public static class QueryResponse {
        private final String answer;
    }

    @Data
    public static class IngestRequest {
        private String filePath;
    }

    @Data
    public static class IngestDirectoryRequest {
        private String directoryPath;
    }
}
