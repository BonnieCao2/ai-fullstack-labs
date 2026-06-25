# 学习日志

## 第1阶段：AI 应用基石（Week 1-2）

### 2024-06-15 - 项目启动

#### 今日完成
- [x] 创建 RAG 文档问答助手项目
- [x] 搭建基础架构：Spring Boot + LangChain4j
- [x] 实现核心 RAG 流程（检索 → 增强 → 生成）
- [x] 准备示例文档（Spring Boot 技术文档）

#### 核心概念理解
✅ **RAG 架构**
- Retrieval（检索）：向量相似度搜索
- Augmentation（增强）：将检索结果注入 Prompt
- Generation（生成）：LLM 基于上下文生成回答

✅ **Embedding（向量化）**
- 文本 → 数字向量（384 维）
- 语义相近 = 向量距离近
- 使用本地模型 all-MiniLM-L6-v2

✅ **Document Chunking（文档分块）**
- 为什么分块：LLM 上下文长度限制 + 精确检索
- chunk-size: 500 字符
- chunk-overlap: 50 字符（避免信息被切断）

#### 待深入理解
- [ ] 向量检索的底层算法（余弦相似度、ANN 算法）
- [ ] Prompt Engineering 的高级技巧
- [ ] 不同分块策略的效果对比

#### 环境排错记录（真实踩坑）
🔧 **坑 1：Maven 无法下载依赖（SSL 握手失败）**
- 现象：`Remote host terminated the handshake`
- 原因：中央仓库连接不稳定
- 解决：配置阿里云镜像 `~/.m2/settings.xml`
- 收获：国内 Java 开发必备技能，镜像源能极大加速依赖下载

🔧 **坑 2：import 包路径错误**
- 现象：`程序包 dev.langchain4j.model.embedding.onnx.allminilml6v2 不存在`
- 原因：AI 生成的代码用了新版本的包路径，与 0.31.0 不符
- 解决：改为 `dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel`
- 收获：**版本差异是 AI 编程的常见坑**，要学会看编译错误定位问题

🔧 **坑 3：API 用法错误（类型不兼容）**
- 现象：`Path 无法转换为 InputStream`
- 原因：`TextDocumentParser.parse()` 需要 InputStream，不能直接传 Path
- 解决：用 `FileSystemDocumentLoader.loadDocument(path, parser)`
- 收获：框架通常提供专门的 Loader 类处理文件加载

🔧 **观察到的警告：deprecated API**
- `embeddingStore.findRelevant()` 在新版本已废弃
- 当前能用，但后续应迁移到 `EmbeddingSearchRequest` 新 API
- 收获：关注废弃警告，这是框架演进的信号

🔧 **坑 4（最有价值）：API 认证方式不匹配 —— x-api-key vs Bearer**
- 现象链：调用代理返回 500 → 日志报 `JsonParseException: Unexpected character ('<')` → curl 探测发现是 403 HTML 拦截页（讯飞 WAF）
- 误判过程：先怀疑 URL 路径错、再怀疑协议不匹配，都不对
- 真正根因：LangChain4j 内置 `AnthropicChatModel` 用 `x-api-key` 头认证（Anthropic 官方方式），但讯飞网关只认 `Authorization: Bearer`（第三方代理常用）。认证头不对 → WAF 拦截 → 返回 HTML → Jackson 解析崩溃
- 定位关键证据：Claude Code 的配置用的是 `ANTHROPIC_AUTH_TOKEN`（= Bearer），不是 `ANTHROPIC_API_KEY`（= x-api-key）
- 解决：自己实现 `IflytekClaudeChatModel`（用 Java HttpClient + Bearer 认证），绕过内置模型
- 收获：
  1. **响应开头是 `<` = 收到 HTML 而非 JSON**，第一反应该是"请求被拦截/路径错"，而不是去 debug JSON
  2. **`API_KEY` 和 `AUTH_TOKEN` 是两种不同认证头**，对接第三方网关时要分清
  3. **ChatLanguageModel 接口的本质**就是一次 HTTP 调用：组装 JSON → POST → 解析响应。自己实现一遍就彻底懂了
  4. **排查 500 错误：永远先看完整堆栈的 `Caused by`，再用 curl 隔离验证**，不要盲猜

#### 下一步任务
1. ✅ 启动项目并测试基础功能（已跑通完整 RAG 链路）
2. 实验不同的配置参数（chunk-size、max-results）
3. 测试不同类型的问题（事实性、分析性、代码示例）
4. 记录问题和改进点

#### 实验 1：检索过程可视化（已完成）
- 改动：新增 `RagResult` DTO + `RagService.queryWithDetails()` + `/query/details` 接口，把检索片段、相似度分数、完整 Prompt 全部返回
- 关键代码差异：原 `retrieveRelevantSegments` 用 `.map(match -> match.embedded())` 丢掉了 `score`；新方法保留 `match.score()`
- 实测发现（问 "How to configure Redis connection pool?"）：
  1. **分数高 ≠ 最相关**：top-1（0.7565）是 Redis 集群配置，真正讲连接池的片段排 top-2（0.7479）。向量相似度衡量词汇/语义接近度，不是逻辑精确匹配
  2. **RAG 是两段式协作**：检索召回 top-N（max-results=3）+ LLM 精筛。即使排序错位，只要正确片段进了 top-N，Claude 能自己挑出来答对。所以 max-results 取 3~5 而非 1，靠多召回兜底
  3. **分数只有 0.7 出头**：文档是中英混合，但 Embedding 模型 all-MiniLM-L6-v2 不支持中文，只能靠英文代码块匹配，一半中文内容成了噪音 → 留给实验 2 深挖

#### 待深入理解（实验 1 引出）
- [x] 把 max-results 改成 1，验证"会答错集群配置"的猜想
- [ ] Rerank（重排序）机制：如何在检索后、送 LLM 前修正排序，让真正相关的排前面
- [ ] 中文 Embedding 模型替换（实验 2）

#### 实验 1-A：max-results=1 验证（已完成）
- 操作：max-results 从 3 改为 1，重启 + 重新摄入，问同一个连接池问题
- 踩坑：改 yml 必须重启；重启后 InMemoryEmbeddingStore 清空，第一次忘了重新摄入，得到 `retrievedSegments: []`（亲身撞上"内存存储重启丢数据"）
- 结果：只召回 top-1（0.7565 的集群配置，正确的连接池片段 top-2 被切掉）
- **关键发现**：答案没有跑偏去讲集群，而是诚实回答"我不知道，上下文里只有集群/哨兵配置，没有连接池信息"
- 两个验证点：
  1. **max-results 太小会漏答案**：正确片段进不了 top-N，RAG 就失效一半 → 印证 max-results 取 3~5 的必要性
  2. **Prompt 约束在对抗幻觉**：`buildAugmentedPrompt` 里"没有就说不知道"这条约束，让 Claude 检索失败时诚实认错，而非用集群配置硬编一个似是而非的错误答案
- **认知闭环**：RAG 质量 = 检索召回率 × Prompt 约束力 × LLM 理解力，三者缺一不可

#### 实验 2：Embedding 模型语言能力对检索质量的影响（已完成）
- 目标：验证"Embedding 模型的语言匹配直接决定检索质量"
- 第一步（不改代码，纯对比测试）：同一问题分别用中/英文提问，都用英文模型 all-MiniLM-L6-v2
  - 英文提问：0.7565 / 0.7479 / 0.7062（平均 ~0.74）
  - 中文提问：0.6878 / 0.6757 / 0.6663（平均 ~0.68）→ 中文低约 0.06
  - 之所以没崩盘，是因为文档是中英混合，中文提问靠蹭文档里的英文代码词（redis/pool/lettuce）勉强匹配
- 第二步：换中文模型 bge-small-zh-v15，重新摄入，同样中文提问
  - 中文提问：0.8597 / 0.8346 / 0.8087（平均 ~0.83）→ 比英文模型处理中文高约 0.15，甚至超过英文模型处理英文
- **核心结论**：Embedding 模型语言能力直接决定检索质量。中文场景配中文模型是刚需，不是优化。模型选对了，min-score 阈值才好使（0.68 会被 0.7 阈值过滤，0.83 轻松过线）
- 踩坑（框架 bug）：bge-small-zh 基础版在 0.31.0 有打包缺陷，摄入时报 `idx=24665 out of bounds [-21128,21127]`（分词器词表和模型词表不对齐）。换 bge-small-zh-v15 变体解决。教训：库自己的 bug，换版本/换变体往往比改自己代码更快
- 注意：实验中临时把 min-score 从 0.7 降到 0.3 便于观察真实分数；experiment 配置 max-results=3

#### 配置当前状态（实验 2 后）
- Embedding 模型：bge-small-zh-v15（中文，512维）
- min-score: 0.3（实验临时值，正式用建议调回 0.7 左右）
- max-results: 3

#### 实验 3：chunk-size 对检索质量的影响（已完成）
- 操作：保持中文模型不变，只把 chunk-size 从 500 改为 200（单变量对比）
- 结果对比（中文提问"如何配置 Redis 连接池？"）：
  - chunk=500：0.86/0.83/0.81，召回的是【完整】的 Redis 连接池 yaml
  - chunk=200：0.87/0.83/0.83，分数反而略升，但内容【残缺】
- **陷阱（重要）**：chunk=200 把一段完整的 Redis 连接池配置从中间劈成两块（片段2前半 + 片段3后半），还混进一个沾边不对题的"数据库 HikariCP 连接池"（含"连接池"字样，分数最高 0.87）
- **核心结论**：chunk-size 不是越小越好。它要匹配"知识的自然颗粒度"——一段完整配置/一个完整概念不该被切断。本例连接池配置约 200-300 字，chunk=200 正好劈开它（最差情况），chunk=500 能装下整段反而更好
- **再次印证 LLM 兜底**：片段被切成两半，但都进了 top-3，Claude 自己拼回完整配置，答案仍准确。但这是侥幸——后半截没进 top-3 就残缺了
- 启发：分数高 ≠ 质量好（实验1是"排序错位"，实验3是"分数虚高但内容残缺"），评估检索要看内容，不能只看分数

#### 实验 4：Prompt Engineering 对答案质量的影响（已完成）
- 测试问题："Spring Boot 的自动配置和 Redis 连接池配置，有什么关系？"（故意刁钻：文档没直接说关系，需要综合推理）
- 三种 Prompt 对比：

| Prompt 风格 | 关键设计 | 答案表现 |
|------------|---------|---------|
| **基础版**（原版）| 简单指令 + "没有就说不知道" | "我不知道...文档没说关系" ❌ |
| **CoT 思维链** | "回答步骤 1、2、3、4" + "可以综合片段推理" | 完整分析（3段），准确推理出互补关系 ✅ |
| **Few-shot 示例** | 给一个类似问题的示例答案 | 简洁精准，完美复刻示例框架（机制/应用/总结）✅✅ |

- **核心发现**：
  1. **基础 Prompt 的"诚实约束"是双刃剑**：它防止幻觉，但也阻止了合理推理。"没有就说不知道"被模型理解成"文档没直接说 = 没信息"
  2. **CoT（思维链）鼓励主动推理**：加上"分析片段之间的关系" + "可以综合推理"，模型从"被动查找"升级到"主动综合"
  3. **Few-shot 提供答案模板**：示例让模型"照着学"，答案结构更清晰、更符合预期格式。适合需要特定风格的场景

- **三大 Prompt 原则**（实验验证）：
  1. 明确任务拆解（CoT："步骤 1、2、3"）
  2. 鼓励推理（"可以综合多个片段"）
  3. 提供示例（Few-shot：给模板）

- **什么时候用哪种**：
  - 基础版：简单查询，文档直接包含答案
  - CoT：复杂问题，需要跨片段综合分析
  - Few-shot：需要特定格式/风格（代码示例、表格对比、结构化输出）

#### 配置当前状态（实验 4 后，Few-shot 版本）
- Embedding 模型：bge-small-zh-v15（中文，512维）
- chunk-size: 200（实验值，建议调回 500）
- min-score: 0.3（实验值，建议调回 0.7）
- max-results: 3

---

## 第 2 阶段进展（Week 3-4）

#### 持久化向量库尝试（Qdrant）—— 受阻，已回退
- 装了 Docker（Homebrew），跑通 Qdrant 容器（端口 6333/6334，数据卷持久化）
- 踩坑 1：Qdrant 不自动创建 collection → 用原生客户端建（指定 512 维 + Cosine）
- 踩坑 2（致命）：langchain4j 0.31.0 的 QdrantEmbeddingStore 有 bug —— findRelevant 重算 score 时拿到空向量，报 `Length of vector a (0) must be equal to b (512)`。换 search() API 也没用（内部还是调 findRelevant）
- 决策：回退到 InMemoryEmbeddingStore。Qdrant 留待后续用更新版本或换 Chroma/PGVector
- 收获：Docker 实操（run/ps/stop/-p/-v）、向量库 collection 概念、库版本兼容性坑

#### 流式响应（SSE）—— 已完成 ✅
- IflytekClaudeChatModel 加 generateStream()：请求体加 stream:true，用 ofLines() 按行读 SSE，解析 content_block_delta 的 text_delta 增量
- RagService 加 queryStream()，RagController 加 /query/stream（SseEmitter + 独立线程池）
- 前端 static/index.html：因接口是 POST，用 fetch+ReadableStream 接收（EventSource 只支持 GET），打字机效果 + 闪烁光标
- 知识点：SSE vs WebSocket（单向广播 vs 双向电话）；Haiku 太快需加 40ms 延迟才看得见流式效果（演示用，已去掉）

#### 多轮对话 —— 已完成，但暴露"检索失焦"问题 ⚠️
- ConversationService（内存版）：Map<会话ID, 历史>，滑动窗口保留最近 5 轮
- RagService.queryWithHistory()：检索照旧，但把历史一起发给 LLM；历史存原始问题（非带检索片段的长 Prompt）
- RagController：/chat（带 conversationId）+ /chat/clear
- 核心知识点：LLM 无状态，"记忆"=每次把历史一起发给它
- **关键发现「检索失焦」**：实测"它有什么优点？"（历史里"它"=HikariCP）
  - 生成层有记忆：LLM 知道"它"指 HikariCP ✅
  - 检索层无记忆：只用"它有什么优点"检索 → 匹配到【Spring Boot 自动配置优点】那段 ❌
  - 结果：检索片段把 LLM 带偏，答成"自动配置的优点"而非"HikariCP 优点"
  - 本质：**生成有记忆，检索没记忆**。简单塞历史不够
- **解决方案**：问题重写（Query Rewriting）—— 检索前先让 LLM 结合历史把指代还原成完整问题，再去检索

#### 问题重写（Query Rewriting）—— 已实现，并意外揭示 RAG 天花板 ⭐
- RagService 加 rewriteQuestion()：检索前先让 LLM 结合历史把指代还原成完整问题
  - 第一轮无历史则跳过（省一次 LLM 调用）
  - 重写只服务检索，拼 Prompt/存历史仍用原始问题（保持对话自然）
  - 失败兜底：退回原问题
  - 代价：每轮多一次 LLM 调用（延迟+成本），生产可用小模型专门做重写
- 实测：重写【完美成功】，日志显示「它有什么优点？」->「HikariCP 有什么优点？」
- **但答案仍答偏成 Spring Boot 优点** —— 排查发现根因不在技术，而在知识库：
  - grep 确认：文档里 HikariCP 只有 2 句话（"默认连接池""监控工具"），没有任何"HikariCP 优点"内容
  - "特性/优点"的详细内容只在 spring-boot-intro 讲 Spring Boot 本身
  - 检索"HikariCP 优点"→ 库里没有 → 只能匹配最接近的"Spring Boot 特性"→ 答偏
- **最深刻的一课「RAG 的天花板是知识库本身」**：
  - 文档里没有的内容，再完美的检索/重写/Prompt 都变不出来
  - RAG 不"创造"知识，只"找到并组织"已有知识
  - 之前所有优化（Embedding/chunk/Prompt/重写）都是优化"如何找到已有知识"；知识不存在时全部失效
- 对照验证：问知识库里确实有的内容（Redis max-wait），重写对+检索准+库里有 → 答对，证明重写技术本身没问题

#### 流式多轮整合（前端）—— 已完成 ✅
- 后端新增 RagService.queryStreamWithHistory()：多轮+流式+重写三合一，wrappedComplete 自动存历史
- 后端新增 RagController.chatStream()：/chat/stream 接口（SseEmitter + 独立线程池）
- 前端完全重写 index.html：
  - 会话管理：页面加载时生成 conversationId（刷新=新会话，不刷新=继续对话）
  - 流式接收：fetch + ReadableStream 按行读 SSE（因接口是 POST，不能用 EventSource）
  - buffer.split('\n\n') + pop() 处理事件跨读取边界
  - 清空对话按钮：调用 /chat/clear + 清空前端气泡
- 完整数据流：用户点击 → 问题重写 → 检索 → 流式生成 → 逐段推送前端 → 存历史
- 详见 PHASE2_SUMMARY.md（完整总结文档，含流程图和代码解析）

#### AI Agent 入门（手写 ReAct）—— 已完成 ✅⭐
- 选择手写 ReAct 而非 LangChain4j 内置 Agent（原理透明 + 不依赖 Function Calling）
- 架构：
  - Tool 接口（name/description/execute）
  - CalculatorTool（自写四则运算递归下降解析器，Java 15+ 无内置 JS 引擎）
  - KnowledgeSearchTool（复用 RagService.searchKnowledge，只检索不生成）
  - AgentService：ReAct 循环（Thought-Action-Observation），MAX_STEPS=5 防死循环
- 核心机制：
  - 不用 Function Calling，用 Prompt 约定 JSON 输出格式（tool_call / final_answer）
  - scratchpad 草稿纸记录每步，每轮发给 LLM 让它"记得"做过什么
  - parseDecision 容错：提取第一个 { 到最后一个 }，跳过 LLM 输出的 ```json 干扰
- 实测组合问题"max-active 是多少？乘以 10？"：
  - 第1步：LLM 自己决定调 knowledge_search → 得到 max-active=8
  - 第2步：LLM 自己决定调 calculator("8*10") → 得到 80
  - 第3步：LLM 判断信息够了 → final_answer "8，乘以10等于80"
  - **三步规划全是 LLM 自己想的，没有一行写死**
- **RAG vs Agent 本质区别**：
  - RAG = 固定流程（检索→生成），只会一招
  - Agent = LLM 自主决策（调哪个工具/几步/何时停），看菜下饭
  - 类比：自动售货机 vs 助理

---

## 学习进度追踪

### 第1阶段目标（Week 1-2）
- [x] 理解 RAG 完整工作流程
- [x] 搭建基于 Java Spring Boot 的 RAG 原型系统
- [x] 打通完整链路（含讯飞网关 Bearer 认证适配）
- [x] 实验 1：检索过程可视化，理解"召回兜底 + Prompt 防幻觉"
- [x] 实验 2：验证 Embedding 语言能力对检索质量的决定性影响
- [x] 实验 3：chunk-size 颗粒度对知识完整性的影响
- [x] 实验 4：Prompt Engineering 三种风格对比（基础/CoT/Few-shot）
- [ ] 实验优化检索质量（Rerank）—— 可选，第 2 阶段遇到实际问题再补

**进度**: 100% ✅ - 核心机制全部吃透，Prompt 工程已掌握，可进第 2 阶段

### 第2阶段目标（Week 3-4）
- [x] 深入 LangChain4j 高级特性（自定义模型、流式 API）
- [x] 掌握流式响应（SSE）+ 前端打字机效果
- [x] 掌握对话管理（ConversationService + 滑动窗口）
- [x] 问题重写（Query Rewriting）解决检索失焦
- [x] Docker 实操（虽 Qdrant 受阻，但学会容器化思维）
- [x] ⭐ 核心洞察：RAG 天花板是知识库本身
- [x] 学习 AI Agent 架构设计（手写 ReAct，工具调用）

**进度**: 100% ✅ - 第 2 阶段全部完成（流式+多轮+重写+Agent）

**成果**：
- 完整的多轮流式对话 RAG 系统（像 ChatGPT）
- 手写 ReAct Agent（自主决策 + 多工具）
- 理解了 RAG vs Agent 的本质区别
- 第一个真正可用的 AI 对话网页
- 详见 `PHASE2_SUMMARY.md`

### 第3阶段目标（Week 5-6）
- [ ] Python 基础和 FastAPI 框架
- [ ] Python AI 生态：LangChain、LlamaIndex
- [ ] 对比 Java 和 Python 在 AI 开发中的优势

**进度**: 0%

### 第4阶段目标（Week 7-10）
- [ ] 构建混合架构 AI 应用
- [ ] 沉淀可复用的开发方法论

**进度**: 0%

---

## 知识点汇总

### 已掌握
- Spring Boot 基础项目搭建
- LangChain4j 依赖配置
- RAG 基础流程实现
- 文档加载和向量化

### 需要加强
- 向量检索算法原理
- Prompt Engineering 技巧
- 检索质量优化策略
- 工程化最佳实践

---

## 疑问和思考

### 技术疑问
1. 为什么选择 chunk-size=500？更大或更小会有什么影响？
2. min-score=0.7 的阈值合理吗？如何确定最佳值？
3. all-MiniLM-L6-v2 不支持中文，如何替换为中文模型？

### 待实验
- [ ] 对比不同 chunk-size 的检索效果
- [ ] 测试 min-score 从 0.5 到 0.9 的差异
- [ ] 尝试不同的 Prompt 模板

---

## 反思和改进

### 做得好的地方
- 代码注释详细，便于理解
- 架构清晰，职责分离

### 可以改进的地方
- 缺少错误处理和日志记录
- 没有单元测试
- 配置参数缺少验证

### 下次注意
- 先思考后编码，避免返工
- 及时记录实验结果和心得
- 多问"为什么"，不只满足于"怎么做"
