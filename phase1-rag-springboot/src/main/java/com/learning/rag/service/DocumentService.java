package com.learning.rag.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文档管理服务
 *
 * 职责：
 * 1. 加载文档
 * 2. 文档分块（Chunking）
 * 3. 向量化并存储
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Value("${documents.chunk-size}")
    private Integer chunkSize;

    @Value("${documents.chunk-overlap}")
    private Integer chunkOverlap;

    /**
     * 应用启动时自动摄入知识库文档
     *
     * 从 classpath 读取 resources/documents/ 下的所有文档
     * jar 包内无法使用通配符，需要显式列出文件名
     */
    @PostConstruct
    public void autoIngestOnStartup() {
        try {
            log.info("应用启动：开始自动摄入知识库文档");

            // jar 包内无法使用通配符，显式列出所有文档文件名
            // 文件名使用英文避免 Linux 容器编码问题
            String[] documentFiles = {
                "documents/learning-log.md",
                "documents/phase2-agent-summary.md",
                "documents/rag-explained.md",
                "documents/project-readme.md"
            };

            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            int successCount = 0;

            for (String filePath : documentFiles) {
                try {
                    Resource resource = resolver.getResource("classpath:" + filePath);

                    if (!resource.exists()) {
                        log.warn("文档不存在: {}", filePath);
                        continue;
                    }

                    String filename = resource.getFilename();
                    log.info("摄入文档: {}", filename);

                    // 从 InputStream 读取文档内容
                    try (InputStream inputStream = resource.getInputStream()) {
                        String content = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        Document document = Document.from(content);

                        // 配置分块器
                        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);

                        // 配置摄入器
                        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                                .documentSplitter(splitter)
                                .embeddingModel(embeddingModel)
                                .embeddingStore(embeddingStore)
                                .build();

                        ingestor.ingest(document);
                        successCount++;
                        log.info("文档摄入成功: {}", filename);
                    }
                } catch (Exception e) {
                    log.error("摄入文档失败: {}", filePath, e);
                }
            }

            if (successCount > 0) {
                log.info("自动摄入完成，成功处理 {} 个文档", successCount);
            } else {
                log.error("自动摄入失败：没有成功摄入任何文档");
            }

        } catch (Exception e) {
            log.error("自动摄入异常（应用会继续启动，但知识库为空）", e);
        }
    }

    /**
     * 摄入文档到向量存储
     *
     * RAG 第一步：文档预处理
     *
     * 流程：
     * 1. 读取文档内容
     * 2. 分块（避免单个文档过长）
     * 3. 向量化每个分块
     * 4. 存储到向量数据库
     *
     * 学习要点：
     * - 为什么要分块？
     *   → LLM 上下文有长度限制
     *   → 更精确的检索（小块更容易匹配用户问题）
     *
     * - chunkOverlap 的作用？
     *   → 避免关键信息被切断
     *   → 保持上下文连贯性
     */
    public void ingestDocument(String filePath) {
        try {
            log.info("开始摄入文档: {}", filePath);

            // 1. 加载文档
            Path path = Paths.get(filePath);
            Document document = loadDocument(path);

            // 2. 配置文档分块器
            DocumentSplitter splitter = DocumentSplitters.recursive(
                    chunkSize,
                    chunkOverlap
            );

            // 3. 配置摄入器（负责分块 + 向量化 + 存储）
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .documentSplitter(splitter)
                    .embeddingModel(embeddingModel)
                    .embeddingStore(embeddingStore)
                    .build();

            // 4. 执行摄入
            ingestor.ingest(document);

            log.info("文档摄入完成: {}", filePath);

        } catch (Exception e) {
            log.error("文档摄入失败: {}", filePath, e);
            throw new RuntimeException("文档摄入失败", e);
        }
    }

    /**
     * 批量摄入目录下的所有文档
     */
    public void ingestDirectory(String directoryPath) {
        File directory = new File(directoryPath);
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IllegalArgumentException("目录不存在: " + directoryPath);
        }

        File[] files = directory.listFiles((dir, name) ->
                name.endsWith(".txt") || name.endsWith(".md"));

        if (files != null) {
            for (File file : files) {
                ingestDocument(file.getAbsolutePath());
            }
        }
    }

    /**
     * 加载文档
     *
     * 当前使用简单的文本解析器
     * 后续可扩展支持：PDF、Word、HTML 等格式
     */
    private Document loadDocument(Path path) {
        // FileSystemDocumentLoader 负责从文件系统读取文件，
        // TextDocumentParser 负责将文件内容解析为 Document 对象
        return FileSystemDocumentLoader.loadDocument(path, new TextDocumentParser());
    }
}
