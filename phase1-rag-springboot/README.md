# RAG 文档智能问答助手

## 项目简介
这是一个基于 **RAG (Retrieval-Augmented Generation)** 架构的技术文档问答系统，使用 Spring Boot + LangChain4j 构建。

## 核心技术栈
- **Spring Boot 3.2.5**: Web 框架
- **LangChain4j 0.31.0**: Java AI 应用开发框架
- **Anthropic Claude API**: 大语言模型（默认 claude-haiku-4-5）
- **all-MiniLM-L6-v2**: 本地向量化模型（免费、离线可用）

## RAG 架构图
```
用户提问
  ↓
【问题向量化】
  ↓
【向量检索】→ 从文档库中找到最相关的片段
  ↓
【Prompt 增强】→ 将检索结果 + 用户问题组装
  ↓
【LLM 生成】→ 调用大模型生成回答
  ↓
【返回结果】
```

## 快速开始

### 1. 环境要求
- Java 17+
- Maven 3.6+
- Anthropic API Key（[console.anthropic.com](https://console.anthropic.com) 申请）

### 2. 配置 API Key（推荐方式：本地配置文件）

```bash
cd src/main/resources
cp application-local.yml.example application-local.yml
# 然后编辑 application-local.yml，填入你的 API Key
```

`application-local.yml` 已加入 `.gitignore`，不会被提交到代码库。

```yaml
ai:
  anthropic:
    api-key: sk-ant-api03-xxxxxx          # 你的 Anthropic API Key
    base-url: https://api.anthropic.com   # 默认官方地址，如用代理则修改
```

**或者用环境变量**（适合 CI/CD 场景）：
```bash
export ANTHROPIC_API_KEY="sk-ant-api03-xxxxxx"
export ANTHROPIC_BASE_URL="https://api.anthropic.com"
```

### 3. 启动项目

使用本地配置文件启动（对应方式 2 中的 `application-local.yml`）：
```bash
cd phase1-rag-springboot
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

使用环境变量启动（无需 profile）：
```bash
mvn spring-boot:run
```

### 4. 摄入测试文档
```bash
# 摄入单个文档
curl -X POST http://localhost:8080/api/rag/documents/ingest \
  -H "Content-Type: application/json" \
  -d '{"filePath": "./data/documents/spring-boot-intro.txt"}'

# 批量摄入目录
curl -X POST http://localhost:8080/api/rag/documents/ingest-directory \
  -H "Content-Type: application/json" \
  -d '{"directoryPath": "./data/documents"}'
```

### 5. 测试问答
```bash
curl -X POST http://localhost:8080/api/rag/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is Spring Boot Auto-Configuration?"}'
```

## 项目结构
```
phase1-rag-springboot/
├── src/main/java/com/learning/rag/
│   ├── RagDocAssistantApplication.java   # 主应用入口
│   ├── config/
│   │   └── AiConfig.java                 # AI 模型配置
│   ├── controller/
│   │   └── RagController.java            # REST API
│   ├── service/
│   │   ├── DocumentService.java          # 文档管理服务
│   │   └── RagService.java               # RAG 核心服务
├── src/main/resources/
│   ├── application.yml                   # 主配置（可提交）
│   ├── application-local.yml             # 本地私密配置（已 gitignore）
│   └── application-local.yml.example     # 配置模板（可提交）
├── data/documents/                       # 文档存储目录
└── pom.xml
```

## 配置优先级

Spring Boot 加载配置的优先级（高 → 低）：
```
命令行参数  >  环境变量  >  application-local.yml  >  application.yml
```

所以本地开发用文件、CI/CD 用环境变量、临时调试用命令行，三种方式可以叠加使用。

## 核心概念解析

### 1. Embedding（向量化）
将文本转换为固定维度的数字向量，语义相近的文本在向量空间中距离更近。
- **模型**: all-MiniLM-L6-v2（384 维向量）
- **优势**: 免费、快速、离线可用
- **局限**: 不支持中文，生产环境可替换为支持中文的模型

### 2. Document Chunking（文档分块）
将长文档切分成小块，每块独立向量化和检索。
- **chunk-size: 500**: 每块约 500 字符
- **chunk-overlap: 50**: 块之间重叠 50 字符，避免关键信息被切断

### 3. Similarity Search（相似度检索）
通过余弦相似度找到与问题最相关的文档片段。
- **max-results: 3**: 返回前 3 个最相关片段
- **min-score: 0.7**: 相似度阈值（0-1 之间）

### 4. Prompt Augmentation（提示词增强）
将检索到的文档片段作为上下文注入到 Prompt 中，让 LLM 基于这些信息生成回答。

## 配置参数说明

### application.yml 关键参数
```yaml
# 模型参数
ai.anthropic.model: claude-haiku-4-5-20251001  # 模型 ID
ai.anthropic.max-tokens: 1000                  # 最大生成 token 数

# 分块参数
documents.chunk-size: 500     # 分块大小
documents.chunk-overlap: 50   # 重叠大小

# 检索参数
rag.max-results: 3   # 检索返回的片段数
rag.min-score: 0.7   # 最低相似度阈值
```

### 可选模型（按能力 / 成本排序）
| 模型 ID | 适用场景 | 速度 | 成本 |
|---------|---------|------|------|
| `claude-haiku-4-5-20251001` | 学习、轻量任务（**默认**） | 快 | 低 |
| `claude-sonnet-4-6` | 平衡场景、Agent 推理 | 中 | 中 |
| `claude-opus-4-8` | 复杂推理、高质量生成 | 慢 | 高 |

## 常见问题

### Q1: 为什么检索不到相关文档？
- 检查 `min-score` 是否设置过高（建议 0.6-0.7）
- 确认文档已成功摄入（查看日志）
- 尝试调整 `chunk-size`（太大或太小都会影响检索效果）

### Q2: 如何支持中文文档？
all-MiniLM-L6-v2 不支持中文，需要替换为支持中文的 Embedding 模型：
- 本地模型：bge-large-zh、m3e-base
- API 模型：智谱 embedding、阿里云通义 embedding

### Q3: 如何提升回答质量？
- 增加 `max-results`，提供更多上下文
- 优化文档质量（结构化、去噪）
- 调整 Prompt 模板（`RagService.buildAugmentedPrompt`）
- 使用更强的模型（如 `claude-sonnet-4-6` 或 `claude-opus-4-8`）

### Q4: 启动后 401/403 错误？
- 检查 `application-local.yml` 中 API Key 是否填写正确
- 确认启动命令带了 `-Dspring-boot.run.profiles=local`，否则不会加载本地配置
- 如果用代理，确认 `base-url` 末尾**没有** `/`

## 学习路线

### Week 1 任务清单
- [x] 搭建基础 RAG 项目
- [ ] 实验不同的 chunk-size 和 overlap 参数
- [ ] 测试不同类型的问题（事实性、分析性、代码示例）
- [ ] 理解向量检索的原理

### Week 2 任务清单
- [ ] 实现混合检索（关键词 + 语义）
- [ ] 添加 Rerank 机制
- [ ] 实现流式响应
- [ ] 添加对话历史管理

## 参考资源
- [LangChain4j 官方文档](https://docs.langchain4j.dev/)
- [Anthropic API 文档](https://docs.anthropic.com/)
- [Prompt Engineering 指南](https://www.promptingguide.ai/)
