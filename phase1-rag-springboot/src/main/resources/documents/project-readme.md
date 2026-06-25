# RAG Document Assistant

一个基于 Spring Boot + LangChain4j 的 RAG（检索增强生成）学习项目。

## 项目概述

这是 Chloe 的 AI 全栈学习计划第 1 阶段的实战项目，目标是深入理解 RAG 的核心机制。项目通过三个实验验证了检索召回、Embedding 语言匹配、chunk-size 颗粒度对 RAG 质量的影响。

**当前状态**：✅ 核心功能已跑通，Week 1 实验已完成，配置已调优，可正常使用。

## 技术栈

- **Java 21** + **Spring Boot 3.2.5**
- **LangChain4j 0.31.0**（AI 编排框架）
- **Claude Haiku 4.5**（通过讯飞内部网关，Bearer 认证）
- **bge-small-zh-v15**（中文 Embedding 模型，512 维）
- **InMemoryEmbeddingStore**（向量存储，重启丢失，待第 2 阶段换 Qdrant）

## 目录结构

```
phase1-rag-springboot/
├── src/main/java/com/learning/rag/
│   ├── config/
│   │   └── AiConfig.java                    # AI 组件装配（模型、向量库）
│   ├── model/
│   │   └── IflytekClaudeChatModel.java      # 自定义 Claude 调用（讯飞网关 Bearer 认证）
│   ├── service/
│   │   ├── DocumentService.java             # 文档摄入
│   │   └── RagService.java                  # RAG 核心逻辑（检索 + 增强 + 生成）
│   ├── controller/
│   │   └── RagController.java               # HTTP 接口
│   └── dto/
│       └── RagResult.java                   # 实验用：暴露检索中间过程
├── src/main/resources/
│   ├── application.yml                      # 主配置（可提交）
│   └── application-local.yml                # 你的私密配置（gitignore）
├── data/documents/                          # 知识库文档
├── LEARNING_LOG.md                          # 学习日志（记录踩坑和发现）
└── pom.xml
```

## 快速启动

### 1. 配置

复制 `application-local.yml.example` 为 `application-local.yml`，填入你的配置：

```yaml
ai:
  anthropic:
    auth-token: sk-xxxxxx   # 讯飞网关的 token（从 Claude Code 配置里取）
    base-url: https://one.iflytek.com/api/llm/console/chat
```

### 2. 启动

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 3. 摄入文档（首次或重启后必须）

```bash
curl -X POST http://localhost:8080/api/rag/documents/ingest-directory \
  -H "Content-Type: application/json" \
  -d '{"directoryPath": "./data/documents"}'
```

### 4. 问答

```bash
# 简单问答
curl -X POST http://localhost:8080/api/rag/query \
  -H "Content-Type: application/json" \
  -d '{"question": "如何配置 Redis 连接池？"}'

# 详细模式（查看检索过程）
curl -X POST http://localhost:8080/api/rag/query/details \
  -H "Content-Type: application/json" \
  -d '{"question": "如何配置 Redis 连接池？"}' | python3 -m json.tool
```

## 关键配置说明

### Embedding 模型

当前使用 **bge-small-zh-v15**（中文模型），在 `AiConfig.java` 中配置：

```java
@Bean
public EmbeddingModel embeddingModel() {
    return new BgeSmallZhV15EmbeddingModel();  // 中文模型（512维）
    // return new AllMiniLmL6V2EmbeddingModel(); // 英文模型（384维）
}
```

**⚠️ 重要**：切换模型后，向量维度会变（384 vs 512），**必须重启 + 重新摄入所有文档**。

### Claude 调用

项目用的是讯飞内部网关（不是 Anthropic 官方），认证方式是 `Authorization: Bearer`，所以写了自定义的 `IflytekClaudeChatModel`。

如果你要换成官方 Anthropic API：
1. 把 `AiConfig.java` 里的 `chatLanguageModel()` 改回 `AnthropicChatModel.builder()`
2. 把 `application.yml` 里的 `auth-token` 改成 `api-key`
3. `base-url` 改为 `https://api.anthropic.com`

### 检索参数

在 `application.yml` 中调整：

```yaml
documents:
  chunk-size: 500      # 文档分块大小（字符数）
  chunk-overlap: 50    # 分块重叠

rag:
  max-results: 3       # 检索返回的最大片段数
  min-score: 0.7       # 最低相似度阈值
```

**调参建议**（基于实验验证）：
- `chunk-size`：500 是平衡值，能装下一段完整配置。太小（如 200）会把完整知识劈开。
- `max-results`：3 给 LLM 足够兜底空间。设为 1 容易漏掉正确片段。
- `min-score`：中文模型分数普遍 0.8+，0.7 能过滤噪音又不太严。

## 已知问题

1. **向量存储用的是内存**：重启后数据丢失，每次要重新摄入。第 2 阶段会换成 Qdrant 持久化。
2. **`findRelevant()` 方法已废弃**：LangChain4j 新版本建议用 `EmbeddingSearchRequest`，但 0.31.0 还能用，暂不影响。

## Week 1 实验总结

完成了三个核心实验，建立了 RAG 质量的完整认知框架：

| 实验 | 核心发现 |
|------|---------|
| **实验 1**：检索过程可视化 | 分数高 ≠ 最相关；max-results 兜底；Prompt 防幻觉 |
| **实验 2**：Embedding 语言能力 | 中文模型比英文模型处理中文高 0.15 分（0.68 → 0.83） |
| **实验 3**：chunk-size 颗粒度 | 切块要匹配知识自然颗粒度，太小会把完整配置劈开 |

详见 `LEARNING_LOG.md` 和 `~/.claude/projects/.../memory/phase1-week1-experiments.md`。

## 下一步计划

- [ ] 换持久化向量库（Qdrant Docker）
- [ ] 深化 Prompt Engineering
- [ ] 实验 Rerank（重排序）
- [ ] 流式响应
- [ ] 对话历史管理

## 排错提示

### 摄入失败："indices out of bounds"

**现象**：摄入文档时报 `idx=24665 must be within [-21128, 21127]`

**原因**：`bge-small-zh` 基础版在 0.31.0 有打包 bug（分词器和模型词表不对齐）

**解决**：换用 `bge-small-zh-v15` 变体（已在当前配置）

### Claude 调用 403

**现象**：curl 返回 HTML 拦截页，日志报 `JsonParseException`

**原因**：认证头不对，被讯飞 WAF 拦截

**确认**：
1. `application-local.yml` 里填的是 `auth-token`（不是 `api-key`）
2. token 值和 Claude Code 配置里的 `ANTHROPIC_AUTH_TOKEN` 一致
3. `base-url` 是讯飞网关地址

### 答案不准确

**诊断**：用 `/query/details` 接口查看检索到的片段和分数
- 分数太低（< 0.7）→ 调低 `min-score` 或换更匹配的 Embedding 模型
- 片段不对题 → 调整 `chunk-size` 或检查文档质量
- 片段对但答案偏 → 优化 `buildAugmentedPrompt` 里的 Prompt

## 参考资料

- LangChain4j 文档：https://docs.langchain4j.dev/
- BGE 模型：https://github.com/FlagOpen/FlagEmbedding
- Anthropic API：https://docs.anthropic.com/

---

**作者**：Chloe  
**最后更新**：2026-06-23  
**项目阶段**：Phase 1 - Week 1 完成
